package de.tadris.fitness.sync.github

import android.content.Context
import de.tadris.fitness.Instance
import de.tadris.fitness.data.ExportTargetConfiguration
import de.tadris.fitness.data.autoexport.source.ExportSource
import de.tadris.fitness.data.autoexport.source.WorkoutGpxExportSource
import de.tadris.fitness.util.export.ImportWorkoutSaver
import de.tadris.fitness.util.export.gpx.GpxImporter
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.TreeMap

class SyncResult(
    val imported: Int,
    val uploaded: Int,
    val deleted: Int,
    val failed: Int,
    val settings: String,
    val firstError: String?,
)

/** Which repo is used and what has been synced. Plain preferences: nothing secret in here. */
class GitHubSync(context: Context) {

    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("github_sync", Context.MODE_PRIVATE)
    val auth = GitHubAuth(this.context)

    var repo: String?
        get() = prefs.getString("repo", null)
        private set(v) = prefs.edit().putString("repo", v).apply()

    var branch: String
        get() = prefs.getString("branch", "main") ?: "main"
        private set(v) = prefs.edit().putString("branch", v).apply()

    var lastSyncMessage: String?
        get() = prefs.getString("lastMessage", null)
        set(v) = prefs.edit().putString("lastMessage", v).apply()

    /** Delete a GPX from the repo when its workout was deleted on this device. The git history keeps it. */
    var deleteFromRepo: Boolean
        get() = prefs.getBoolean("deleteRemote", true)
        set(v) = prefs.edit().putBoolean("deleteRemote", v).apply()

    var syncSettings: Boolean
        get() = prefs.getBoolean("syncSettings", true)
        set(v) = prefs.edit().putBoolean("syncSettings", v).apply()

    fun api() = GitHubApi(auth.token() ?: throw IllegalStateException("Not signed in"))

    private fun stringSet(key: String): MutableSet<String> = prefs.getStringSet(key, emptySet())!!.toMutableSet()

    private fun addToSet(key: String, value: String) {
        prefs.edit().putStringSet(key, stringSet(key).apply { add(value) }).apply()
    }

    // path -> start time of the workout in that file, for files this device has seen
    private fun files(): MutableMap<String, Long> =
        stringSet("files").mapNotNull { e -> e.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[1] to it[0].toLongOrNull() } }
            .filter { it.second != null }.associate { it.first to it.second!! }.toMutableMap()

    private fun saveFiles(m: Map<String, Long>) {
        prefs.edit().putStringSet("files", m.map { "${it.value}\t${it.key}" }.toSet()).apply()
    }

    private fun resetState() {
        prefs.edit().remove("handled").remove("starts").remove("files").remove("lastLocal").remove("lastRemote").apply()
    }

    private fun removeTargets() {
        val dao = Instance.getInstance(context).db.exportTargetDao()
        dao.findAllFor(ExportSource.EXPORT_SOURCE_WORKOUT_GPX)
            .filter { it.type == GitHubConfig.TARGET_TYPE }
            .forEach { dao.delete(it) }
    }

    /** Chooses the repo and makes sure new workouts are exported to it. */
    fun chooseRepo(choice: RepoChoice) {
        repo = choice.full
        branch = choice.branch
        removeTargets()
        Instance.getInstance(context).db.exportTargetDao().insert(ExportTargetConfiguration().also {
            it.source = ExportSource.EXPORT_SOURCE_WORKOUT_GPX
            it.type = GitHubConfig.TARGET_TYPE
            it.data = JSONObject().put("repo", choice.full).put("branch", choice.branch).toString()
        })
        resetState()
    }

    fun signOut() {
        removeTargets()
        auth.signOut()
        prefs.edit().remove("repo").apply()
        resetState()
    }

    /** Writes one exported GPX into the repo and remembers it, so it is neither uploaded twice nor re-imported. */
    fun upload(repo: String, branch: String, file: ExportSource.ExportedFile) {
        val name = file.file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val path = "${GitHubConfig.FOLDER}/$name"
        api().putFile(repo, branch, path, file.file.readBytes(), "Add $name")
        addToSet("handled", path)
        file.meta["FitoTrack-Timestamp"]?.toLongOrNull()?.let { start ->
            addToSet("starts", start.toString())
            saveFiles(files().apply { put(path, start) })
        }
    }

    /** Uploads local workouts that are not in the repo yet. */
    private fun push(errors: Errors): Int {
        val r = repo ?: return 0
        val known = stringSet("starts")
        var uploaded = 0
        for (w in Instance.getInstance(context).db.workoutDao().getWorkouts()) {
            if (w.start.toString() in known || w.end <= w.start) continue
            try {
                upload(r, branch, WorkoutGpxExportSource(w.id).provideFile(context))
                uploaded++
            } catch (e: Exception) {
                errors.add("upload", e)
            }
        }
        return uploaded
    }

    /** Pulls new GPX files, uploads local ones and removes repo files for workouts deleted here. */
    fun sync(): SyncResult {
        val r = repo ?: throw IllegalStateException("No repo chosen")
        val api = api()
        val dao = Instance.getInstance(context).db.workoutDao()
        val errors = Errors()

        val remote = api.listFiles(r, branch, GitHubConfig.FOLDER, ".gpx").associateBy { it.path }
        val mapping = files()
        val handled = stringSet("handled")
        val importer = GpxImporter()
        var imported = 0
        for (f in remote.values) {
            if (f.path in mapping) continue
            try {
                val workouts = importer.readWorkouts(api.readBlob(r, f.sha).inputStream()).workouts
                for (data in workouts) {
                    if (f.path !in handled && dao.getWorkoutByStart(data.workout.start) == null) {
                        ImportWorkoutSaver(context, data).save()
                        imported++
                    }
                    addToSet("starts", data.workout.start.toString())
                }
                workouts.firstOrNull()?.let { mapping[f.path] = it.workout.start }
                addToSet("handled", f.path)
            } catch (e: Exception) {
                errors.add("import ${f.path}", e)
            }
        }

        var deleted = 0
        for ((path, start) in mapping.toMap()) {
            val file = remote[path]
            if (file == null) {
                mapping.remove(path)
            } else if (deleteFromRepo && dao.getWorkoutByStart(start) == null) {
                try {
                    api.deleteFile(r, branch, path, file.sha, "Remove ${path.substringAfterLast('/')}")
                    mapping.remove(path)
                    deleted++
                } catch (e: Exception) {
                    errors.add("delete $path", e)
                }
            }
        }
        saveFiles(mapping)

        val uploaded = push(errors)
        val settings = if (syncSettings) try { syncSettingsFile(api, r) } catch (e: Exception) { errors.add("settings", e); "failed" } else "off"
        return SyncResult(imported, uploaded, deleted, errors.count, settings, errors.first)
    }

    private class Errors {
        var count = 0
        var first: String? = null
        fun add(what: String, e: Exception) {
            count++
            if (first == null) first = "$what: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ---- settings and personal measurements (weight, step length and the app preferences) ----

    private val deny = Regex("(?i)token|secret|auth|uri|director|path|file|bluetooth|device|lastversion|showing|firststart|prompt|autostarted|^_")

    private fun appPrefs() = Instance.getInstance(context).userPreferences.preferences

    private fun snapshot(): String {
        val sorted = TreeMap<String, Any?>()
        for ((k, v) in appPrefs().all) if (!deny.containsMatchIn(k) && v != null) sorted[k] = v
        val out = JSONObject()
        for ((k, v) in sorted) {
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "S").put("v", JSONArray(v.map { it.toString() }.sorted()))
                else -> continue
            }
            out.put(k, e)
        }
        return out.toString(2)
    }

    private fun applySnapshot(text: String) {
        val j = JSONObject(text)
        appPrefs().edit().apply {
            for (k in j.keys()) {
                if (deny.containsMatchIn(k)) continue
                val e = j.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> putBoolean(k, e.getBoolean("v"))
                    "i" -> putInt(k, e.getInt("v"))
                    "l" -> putLong(k, e.getLong("v"))
                    "f" -> putFloat(k, e.getDouble("v").toFloat())
                    "s" -> putString(k, e.getString("v"))
                    "S" -> putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                }
            }
        }.apply()
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Keeps settings.json in step. A change made here wins and is pushed; otherwise a change in the repo is applied here.
     * The first sync on a device applies what the repo has, so a new device picks up the settings.
     */
    private fun syncSettingsFile(api: GitHubApi, repo: String): String {
        val path = "${GitHubConfig.FOLDER}/settings.json"
        val local = snapshot()
        val remote = api.readText(repo, branch, path)
        val lastLocal = prefs.getString("lastLocal", null)
        val lastRemote = prefs.getString("lastRemote", null)
        if (remote != null && lastRemote == null) {
            applySnapshot(remote)
            prefs.edit().putString("lastLocal", sha256(snapshot())).putString("lastRemote", sha256(remote)).apply()
            return "applied from repo (restart the app to see all changes)"
        }
        val localChanged = sha256(local) != lastLocal
        val remoteChanged = remote != null && sha256(remote) != lastRemote
        return when {
            (localChanged || remote == null) && remote != local -> {
                api.putFile(repo, branch, path, local.toByteArray(), "Update settings")
                prefs.edit().putString("lastLocal", sha256(local)).putString("lastRemote", sha256(local)).apply()
                "saved to repo"
            }
            remoteChanged -> {
                applySnapshot(remote!!)
                prefs.edit().putString("lastLocal", sha256(snapshot())).putString("lastRemote", sha256(remote)).apply()
                "applied from repo (restart the app to see all changes)"
            }
            else -> {
                prefs.edit().putString("lastLocal", sha256(local)).apply()
                "unchanged"
            }
        }
    }
}
