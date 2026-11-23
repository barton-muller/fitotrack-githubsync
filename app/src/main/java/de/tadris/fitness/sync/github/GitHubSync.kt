/*
 * Copyright (c) 2026 barton-muller
 *
 * This file is part of FitoTrack
 *
 * FitoTrack is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     FitoTrack is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tadris.fitness.sync.github

import android.content.Context
import de.tadris.fitness.Instance
import de.tadris.fitness.data.ExportTargetConfiguration
import de.tadris.fitness.data.autoexport.source.ExportSource
import de.tadris.fitness.data.autoexport.source.WorkoutGpxExportSource
import de.tadris.fitness.util.export.ImportWorkoutSaver
import de.tadris.fitness.util.export.gpx.GpxImporter
import org.json.JSONObject

class SyncResult(
    val imported: Int,
    val uploaded: Int,
    val deleted: Int,
    val failed: Int,
    val settings: String,
    val firstError: String?,
)

/**
 * Keeps the workouts and settings of this device in step with a GitHub repo.
 *
 * What has been synced is remembered in plain preferences (nothing secret in there):
 * - `files`: repo path -> start time of the workout in that file
 * - `starts`: start times of all workouts known to be in the repo
 * - `handled`: repo paths that were imported or uploaded, so a workout deleted here is not imported again
 */
class GitHubSync(context: Context) {

    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val auth = GitHubAuth(this.context)

    var repo: String?
        get() = prefs.getString(KEY_REPO, null)
        private set(value) = prefs.edit().putString(KEY_REPO, value).apply()

    var branch: String
        get() = prefs.getString(KEY_BRANCH, "main") ?: "main"
        private set(value) = prefs.edit().putString(KEY_BRANCH, value).apply()

    var lastSyncMessage: String?
        get() = prefs.getString(KEY_LAST_MESSAGE, null)
        set(value) = prefs.edit().putString(KEY_LAST_MESSAGE, value).apply()

    /** Remove a GPX from the repo when its workout was deleted on this device. The git history keeps it. */
    var deleteFromRepo: Boolean
        get() = prefs.getBoolean(KEY_DELETE_FROM_REPO, true)
        set(value) = prefs.edit().putBoolean(KEY_DELETE_FROM_REPO, value).apply()

    var syncSettings: Boolean
        get() = prefs.getBoolean(KEY_SYNC_SETTINGS, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_SETTINGS, value).apply()

    fun api() = GitHubApi(auth.token() ?: throw IllegalStateException("Not signed in"))

    /** Uses [choice] for syncing and makes sure that new workouts are uploaded to it. */
    fun chooseRepo(choice: RepoChoice) {
        repo = choice.full
        branch = choice.branch
        removeExportTargets()
        Instance.getInstance(context).db.exportTargetDao().insert(ExportTargetConfiguration().also {
            it.source = ExportSource.EXPORT_SOURCE_WORKOUT_GPX
            it.type = GitHubConfig.TARGET_TYPE
            it.data = JSONObject().put("repo", choice.full).put("branch", choice.branch).toString()
        })
        forgetSyncedState()
    }

    fun signOut() {
        removeExportTargets()
        auth.signOut()
        prefs.edit().remove(KEY_REPO).apply()
        forgetSyncedState()
    }

    /** Writes one exported GPX into the repo and remembers it, so it is neither uploaded twice nor imported again. */
    fun upload(repo: String, branch: String, file: ExportSource.ExportedFile) {
        val name = file.file.name.replace(UNSAFE_FILE_NAME_CHARS, "_")
        val path = "${GitHubConfig.FOLDER}/$name"
        api().putFile(repo, branch, path, file.file.readBytes(), "Add $name")
        addToSet(KEY_HANDLED, path)
        file.meta[META_TIMESTAMP]?.toLongOrNull()?.let { start ->
            addToSet(KEY_STARTS, start.toString())
            updateFiles { put(path, start) }
        }
    }

    /** Imports new workouts, removes repo files of deleted workouts, uploads local workouts and syncs the settings. */
    fun sync(): SyncResult {
        val repo = repo ?: throw IllegalStateException("No repo chosen")
        val api = api()
        val errors = Errors()

        val remote = api.listFiles(repo, branch, GitHubConfig.FOLDER, ".gpx").associateBy { it.path }
        val known = files()
        val imported = importNew(api, repo, remote.values, known, errors)
        val deleted = removeDeleted(api, repo, remote, known, errors)
        saveFiles(known)
        val uploaded = uploadMissing(repo, errors)
        val settings = if (syncSettings) syncSettingsFile(api, repo, errors) else SETTINGS_OFF
        return SyncResult(imported, uploaded, deleted, errors.count, settings, errors.first)
    }

    private fun importNew(api: GitHubApi, repo: String, remote: Collection<RemoteFile>, known: MutableMap<String, Long>, errors: Errors): Int {
        val dao = Instance.getInstance(context).db.workoutDao()
        val handled = stringSet(KEY_HANDLED)
        val importer = GpxImporter()
        var imported = 0
        for (file in remote) {
            if (file.path in known) continue
            try {
                val workouts = importer.readWorkouts(api.readBlob(repo, file.sha).inputStream()).workouts
                for (data in workouts) {
                    if (file.path !in handled && dao.getWorkoutByStart(data.workout.start) == null) {
                        ImportWorkoutSaver(context, data).save()
                        imported++
                    }
                    addToSet(KEY_STARTS, data.workout.start.toString())
                }
                workouts.firstOrNull()?.let { known[file.path] = it.workout.start }
                addToSet(KEY_HANDLED, file.path)
            } catch (e: Exception) {
                errors.add("import ${file.path}", e)
            }
        }
        return imported
    }

    private fun removeDeleted(api: GitHubApi, repo: String, remote: Map<String, RemoteFile>, known: MutableMap<String, Long>, errors: Errors): Int {
        val dao = Instance.getInstance(context).db.workoutDao()
        var deleted = 0
        for ((path, start) in known.toMap()) {
            val file = remote[path]
            if (file == null) {
                known.remove(path)
            } else if (deleteFromRepo && dao.getWorkoutByStart(start) == null) {
                try {
                    api.deleteFile(repo, branch, path, file.sha, "Remove ${path.substringAfterLast('/')}")
                    known.remove(path)
                    deleted++
                } catch (e: Exception) {
                    errors.add("delete $path", e)
                }
            }
        }
        return deleted
    }

    private fun uploadMissing(repo: String, errors: Errors): Int {
        val starts = stringSet(KEY_STARTS)
        var uploaded = 0
        for (workout in Instance.getInstance(context).db.workoutDao().getWorkouts()) {
            if (workout.start.toString() in starts || workout.end <= workout.start) continue
            try {
                upload(repo, branch, WorkoutGpxExportSource(workout.id).provideFile(context))
                uploaded++
            } catch (e: Exception) {
                errors.add("upload", e)
            }
        }
        return uploaded
    }

    /**
     * Keeps settings.json in step. A change made on this device wins and is pushed, otherwise a change in the repo is applied.
     * The first sync on a device applies what the repo has, so that a new device picks up the settings.
     */
    private fun syncSettingsFile(api: GitHubApi, repo: String, errors: Errors): String = try {
        val path = "${GitHubConfig.FOLDER}/settings.json"
        val preferences = Instance.getInstance(context).userPreferences.preferences
        val local = PreferencesSnapshot.take(preferences)
        val remote = api.readText(repo, branch, path)
        val lastLocal = prefs.getString(KEY_LAST_LOCAL, null)
        val lastRemote = prefs.getString(KEY_LAST_REMOTE, null)
        fun remember(local: String, remote: String) =
            prefs.edit().putString(KEY_LAST_LOCAL, PreferencesSnapshot.hash(local)).putString(KEY_LAST_REMOTE, PreferencesSnapshot.hash(remote)).apply()

        val localChanged = PreferencesSnapshot.hash(local) != lastLocal
        val remoteChanged = remote != null && PreferencesSnapshot.hash(remote) != lastRemote
        when {
            remote != null && lastRemote == null -> {
                PreferencesSnapshot.apply(preferences, remote)
                remember(PreferencesSnapshot.take(preferences), remote)
                SETTINGS_APPLIED
            }
            (localChanged || remote == null) && remote != local -> {
                api.putFile(repo, branch, path, local.toByteArray(), "Update settings")
                remember(local, local)
                SETTINGS_SAVED
            }
            remoteChanged -> {
                PreferencesSnapshot.apply(preferences, remote!!)
                remember(PreferencesSnapshot.take(preferences), remote)
                SETTINGS_APPLIED
            }
            else -> {
                prefs.edit().putString(KEY_LAST_LOCAL, PreferencesSnapshot.hash(local)).apply()
                SETTINGS_UNCHANGED
            }
        }
    } catch (e: Exception) {
        errors.add("settings", e)
        SETTINGS_FAILED
    }

    private class Errors {
        var count = 0
        var first: String? = null

        fun add(what: String, e: Exception) {
            count++
            if (first == null) first = "$what: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun removeExportTargets() {
        val dao = Instance.getInstance(context).db.exportTargetDao()
        dao.findAllFor(ExportSource.EXPORT_SOURCE_WORKOUT_GPX)
            .filter { it.type == GitHubConfig.TARGET_TYPE }
            .forEach { dao.delete(it) }
    }

    private fun forgetSyncedState() {
        prefs.edit().remove(KEY_HANDLED).remove(KEY_STARTS).remove(KEY_FILES).remove(KEY_LAST_LOCAL).remove(KEY_LAST_REMOTE).apply()
    }

    // The sets are read, changed and written back, and the upload worker and the screen can both do that.
    private fun stringSet(key: String): MutableSet<String> = synchronized(LOCK) {
        prefs.getStringSet(key, emptySet())!!.toMutableSet()
    }

    private fun addToSet(key: String, value: String) = synchronized(LOCK) {
        prefs.edit().putStringSet(key, stringSet(key).apply { add(value) }).apply()
    }

    private fun files(): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        for (entry in stringSet(KEY_FILES)) {
            val (start, path) = entry.split('\t', limit = 2).takeIf { it.size == 2 } ?: continue
            start.toLongOrNull()?.let { out[path] = it }
        }
        return out
    }

    private fun saveFiles(files: Map<String, Long>) = synchronized(LOCK) {
        prefs.edit().putStringSet(KEY_FILES, files.map { "${it.value}\t${it.key}" }.toSet()).apply()
    }

    private fun updateFiles(change: MutableMap<String, Long>.() -> Unit) = synchronized(LOCK) {
        saveFiles(files().apply(change))
    }

    private companion object {
        const val PREFS = "github_sync"
        const val KEY_REPO = "repo"
        const val KEY_BRANCH = "branch"
        const val KEY_LAST_MESSAGE = "lastMessage"
        const val KEY_DELETE_FROM_REPO = "deleteRemote"
        const val KEY_SYNC_SETTINGS = "syncSettings"
        const val KEY_HANDLED = "handled"
        const val KEY_STARTS = "starts"
        const val KEY_FILES = "files"
        const val KEY_LAST_LOCAL = "lastLocal"
        const val KEY_LAST_REMOTE = "lastRemote"
        const val META_TIMESTAMP = "FitoTrack-Timestamp"
        const val SETTINGS_OFF = "off"
        const val SETTINGS_FAILED = "failed"
        const val SETTINGS_UNCHANGED = "unchanged"
        const val SETTINGS_SAVED = "saved to repo"
        const val SETTINGS_APPLIED = "applied from repo, restart the app to see all changes"
        val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
        val LOCK = Any()
    }
}
