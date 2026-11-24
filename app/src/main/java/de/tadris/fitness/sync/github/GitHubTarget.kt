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
        // The sync merges with the repo and uploads what is new, which includes the workout that was just recorded
        GitHubSync(context).sync()
    }

    companion object {
        @JvmStatic
        fun fromData(data: String): GitHubTarget {
            val j = JSONObject(data)
            return GitHubTarget(j.optString("repo"), j.optString("branch", "main"))
        }
    }
}
