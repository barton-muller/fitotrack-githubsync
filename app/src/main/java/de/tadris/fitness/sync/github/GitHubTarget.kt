package de.tadris.fitness.sync.github

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import de.tadris.fitness.R
import de.tadris.fitness.data.autoexport.source.ExportSource
import de.tadris.fitness.data.autoexport.target.ExportTarget
import org.json.JSONObject

class GitHubTarget(private val repo: String, private val branch: String) : ExportTarget {

    override val id get() = GitHubConfig.TARGET_TYPE

    override val titleRes get() = R.string.exportTargetGitHub

    override val summary get() = repo

    override val constraints
        get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun exportFile(context: Context, file: ExportSource.ExportedFile) {
        GitHubSync(context).upload(repo, branch, file)
    }

    companion object {
        @JvmStatic
        fun fromData(data: String): GitHubTarget {
            val j = JSONObject(data)
            return GitHubTarget(j.optString("repo"), j.optString("branch", "main"))
        }
    }
}
