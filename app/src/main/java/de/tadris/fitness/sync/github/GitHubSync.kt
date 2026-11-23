package de.tadris.fitness.sync.github

import android.content.Context
import de.tadris.fitness.Instance
import de.tadris.fitness.data.ExportTargetConfiguration
import de.tadris.fitness.data.autoexport.source.ExportSource
import de.tadris.fitness.util.export.ImportWorkoutSaver
import de.tadris.fitness.util.export.gpx.GpxImporter
import org.json.JSONObject

class PullResult(val imported: Int, val skipped: Int, val failed: Int)

/** Which repo is used, and the GPX files already handled. Plain preferences: nothing secret in here. */
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

    fun api() = GitHubApi(auth.token() ?: throw IllegalStateException("Not signed in"))

    private fun handled(): MutableSet<String> = prefs.getStringSet("handled", emptySet())!!.toMutableSet()

    private fun markHandled(shaOrPath: String) {
        prefs.edit().putStringSet("handled", handled().apply { add(shaOrPath) }).apply()
    }

    /** Chooses the repo and makes sure new workouts are exported to it. */
    fun chooseRepo(choice: RepoChoice) {
        repo = choice.full
        branch = choice.branch
        val dao = Instance.getInstance(context).db.exportTargetDao()
        dao.findAllFor(ExportSource.EXPORT_SOURCE_WORKOUT_GPX)
            .filter { it.type == GitHubConfig.TARGET_TYPE }
            .forEach { dao.delete(it) }
        dao.insert(ExportTargetConfiguration().also {
            it.source = ExportSource.EXPORT_SOURCE_WORKOUT_GPX
            it.type = GitHubConfig.TARGET_TYPE
            it.data = JSONObject().put("repo", choice.full).put("branch", choice.branch).toString()
        })
        prefs.edit().remove("handled").apply()
    }

    fun signOut() {
        val dao = Instance.getInstance(context).db.exportTargetDao()
        dao.findAllFor(ExportSource.EXPORT_SOURCE_WORKOUT_GPX)
            .filter { it.type == GitHubConfig.TARGET_TYPE }
            .forEach { dao.delete(it) }
        auth.signOut()
        prefs.edit().remove("repo").remove("handled").apply()
    }

    fun recordUploaded(path: String, sha: String?) = markHandled(path)

    /** Imports GPX files from the repo that are not on this device yet. */
    fun pull(): PullResult {
        val r = repo ?: throw IllegalStateException("No repo chosen")
        val api = api()
        val dao = Instance.getInstance(context).db.workoutDao()
        val importer = GpxImporter()
        val done = handled()
        var imported = 0
        var skipped = 0
        var failed = 0
        for (f in api.listFiles(r, branch, GitHubConfig.FOLDER, ".gpx")) {
            if (f.path in done) { skipped++; continue }
            try {
                val bytes = api.readBlob(r, f.sha)
                for (data in importer.readWorkouts(bytes.inputStream()).workouts) {
                    if (dao.getWorkoutByStart(data.workout.start) != null) continue
                    ImportWorkoutSaver(context, data).save()
                    imported++
                }
                markHandled(f.path)
            } catch (e: Exception) {
                failed++
            }
        }
        return PullResult(imported, skipped, failed)
    }
}
