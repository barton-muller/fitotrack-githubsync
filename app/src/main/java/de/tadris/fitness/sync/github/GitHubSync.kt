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
import de.tadris.fitness.sync.PreferencesBaseStore
import de.tadris.fitness.sync.SyncBase
import de.tadris.fitness.sync.SyncEngine
import de.tadris.fitness.sync.SyncOptions
import de.tadris.fitness.sync.SyncReport
import org.json.JSONObject

/** The repo chosen on GitHub, the options, and the sync of this device with it. */
class GitHubSync(context: Context) {

    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val baseStore = PreferencesBaseStore(prefs, KEY_BASE)
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

    /** Remove a workout's GPX from the repo when the workout was deleted on this device. The git history keeps it. */
    var deleteFromRepo: Boolean
        get() = prefs.getBoolean(KEY_DELETE_FROM_REPO, true)
        set(value) = prefs.edit().putBoolean(KEY_DELETE_FROM_REPO, value).apply()

    /** Delete a workout here when its GPX was deleted from the repo. Otherwise it is uploaded again. */
    var deleteHere: Boolean
        get() = prefs.getBoolean(KEY_DELETE_HERE, false)
        set(value) = prefs.edit().putBoolean(KEY_DELETE_HERE, value).apply()

    var syncSettings: Boolean
        get() = prefs.getBoolean(KEY_SYNC_SETTINGS, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_SETTINGS, value).apply()

    fun api() = GitHubApi(auth.token() ?: throw IllegalStateException("Not signed in"))

    /** Uses [choice] for syncing and makes sure that new workouts are synced after they are recorded. */
    fun chooseRepo(choice: RepoChoice) {
        repo = choice.full
        branch = choice.branch
        removeExportTargets()
        Instance.getInstance(context).db.exportTargetDao().insert(ExportTargetConfiguration().also {
            it.source = ExportSource.EXPORT_SOURCE_WORKOUT_GPX
            it.type = GitHubConfig.TARGET_TYPE
            it.data = JSONObject().put("repo", choice.full).put("branch", choice.branch).toString()
        })
        baseStore.clear()
    }

    fun signOut() {
        removeExportTargets()
        auth.signOut()
        prefs.edit().remove(KEY_REPO).apply()
        baseStore.clear()
    }

    /** Earlier builds remembered which file held which workout; that is what the engine keeps as its base now. */
    private fun migrateLegacyState() {
        if (prefs.contains(KEY_BASE)) return
        val legacy = prefs.getStringSet(LEGACY_FILES, null) ?: return
        val files = legacy.mapNotNull { entry ->
            entry.split('\t', limit = 2).takeIf { it.size == 2 }?.let { (start, path) -> start.toLongOrNull()?.let { path to it } }
        }.toMap()
        baseStore.save(SyncBase(files))
        prefs.edit().remove(LEGACY_FILES).remove("handled").remove("starts").remove("lastLocal").remove("lastRemote").apply()
    }

    /** Brings the workouts and settings of this device and the repo to the same state. */
    fun sync(): SyncReport = synchronized(LOCK) {
        val repo = repo ?: throw IllegalStateException("No repo chosen")
        migrateLegacyState()
        SyncEngine(
            remote = GitHubRemote(api(), repo, branch),
            device = AndroidDeviceSide(context),
            baseStore = baseStore,
            options = SyncOptions(deleteFromRepo, deleteHere, syncSettings),
            folder = GitHubConfig.FOLDER,
        ).run()
    }

    private fun removeExportTargets() {
        val dao = Instance.getInstance(context).db.exportTargetDao()
        dao.findAllFor(ExportSource.EXPORT_SOURCE_WORKOUT_GPX)
            .filter { it.type == GitHubConfig.TARGET_TYPE }
            .forEach { dao.delete(it) }
    }

    private companion object {
        const val PREFS = "github_sync"
        const val KEY_REPO = "repo"
        const val KEY_BRANCH = "branch"
        const val KEY_BASE = "base"
        const val LEGACY_FILES = "files"
        const val KEY_LAST_MESSAGE = "lastMessage"
        const val KEY_DELETE_FROM_REPO = "deleteRemote"
        const val KEY_DELETE_HERE = "deleteHere"
        const val KEY_SYNC_SETTINGS = "syncSettings"

        /** The screen and the upload after a recording can start a sync at the same time. */
        val LOCK = Any()
    }
}
