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
