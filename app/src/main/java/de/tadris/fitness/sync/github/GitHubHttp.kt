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

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GitHubHttpException(val code: Int, message: String) : IOException(message)

class GitHubResponse(val code: Int, val body: ByteArray) {
    val text get() = String(body, Charsets.UTF_8)
    val ok get() = code in 200..299
}

internal object GitHubHttp {

    fun request(
        method: String,
        url: String,
        token: String? = null,
        body: ByteArray? = null,
        contentType: String? = null,
        accept: String = "application/vnd.github+json",
    ): GitHubResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("User-Agent", "FitoTrack-GitHubSync")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                contentType?.let { c.setRequestProperty("Content-Type", it) }
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            return GitHubResponse(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            c.disconnect()
        }
    }
}
