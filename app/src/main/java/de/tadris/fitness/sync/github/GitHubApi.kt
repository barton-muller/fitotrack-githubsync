package de.tadris.fitness.sync.github

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

class RepoChoice(val full: String, val branch: String)

class RemoteFile(val path: String, val sha: String)

class GitHubApi(private val token: String) {

    private fun call(method: String, path: String, body: JSONObject? = null, accept: String = "application/vnd.github+json") =
        GitHubHttp.request(
            method, "https://api.github.com$path", token,
            body?.toString()?.toByteArray(), "application/json", accept,
        )

    private fun check(r: GitHubResponse): GitHubResponse {
        if (!r.ok) throw GitHubHttpException(r.code, "GitHub ${r.code}: ${runCatching { JSONObject(r.text).optString("message") }.getOrDefault("")}")
        return r
    }

    fun login(): String = JSONObject(check(call("GET", "/user")).text).getString("login")

    /** Repos the GitHub App is installed on and the person can reach. */
    fun repos(): List<RepoChoice> {
        val out = mutableListOf<RepoChoice>()
        val installs = JSONObject(check(call("GET", "/user/installations?per_page=100")).text).getJSONArray("installations")
        for (i in 0 until installs.length()) {
            val id = installs.getJSONObject(i).getLong("id")
            val repos = JSONObject(check(call("GET", "/user/installations/$id/repositories?per_page=100")).text).getJSONArray("repositories")
            for (k in 0 until repos.length()) {
                val r = repos.getJSONObject(k)
                out += RepoChoice(r.getString("full_name"), r.getString("default_branch"))
            }
        }
        return out
    }

    /** Creates a private repo for the signed-in person. Needs the app's repository-creation permission. */
    fun createRepo(name: String, description: String): RepoChoice {
        val r = call("POST", "/user/repos", JSONObject().put("name", name).put("description", description).put("private", true).put("auto_init", true))
        if (r.code == 422) throw GitHubHttpException(422, "A repo called $name already exists")
        if (r.code == 403 || r.code == 404) throw GitHubHttpException(r.code, "This app may not create repos. Create one on github.com and add it with the install page")
        val j = JSONObject(check(r).text)
        return RepoChoice(j.getString("full_name"), j.getString("default_branch"))
    }

    fun listFiles(repo: String, branch: String, folder: String, suffix: String): List<RemoteFile> {
        val r = call("GET", "/repos/$repo/git/trees/${enc(branch)}?recursive=1")
        if (r.code == 404 || r.code == 409) return emptyList() // missing branch or empty repo
        val tree = JSONObject(check(r).text).getJSONArray("tree")
        val out = mutableListOf<RemoteFile>()
        for (i in 0 until tree.length()) {
            val e = tree.getJSONObject(i)
            val p = e.getString("path")
            if (e.getString("type") == "blob" && p.startsWith("$folder/") && p.endsWith(suffix)) out += RemoteFile(p, e.getString("sha"))
        }
        return out
    }

    /** Text of a file, or null when it does not exist. */
    fun readText(repo: String, branch: String, path: String): String? {
        val r = call("GET", "/repos/$repo/contents/${encPath(path)}?ref=${enc(branch)}", accept = "application/vnd.github.raw+json")
        if (r.code == 404) return null
        return check(r).text
    }

    fun deleteFile(repo: String, branch: String, path: String, sha: String, message: String) {
        check(call("DELETE", "/repos/$repo/contents/${encPath(path)}", JSONObject().put("message", message).put("sha", sha).put("branch", branch)))
    }

    fun readBlob(repo: String, sha: String): ByteArray =
        check(call("GET", "/repos/$repo/git/blobs/$sha", accept = "application/vnd.github.raw+json")).body

    /** Creates or replaces a file in one commit. */
    fun putFile(repo: String, branch: String, path: String, bytes: ByteArray, message: String) {
        val existing = call("GET", "/repos/$repo/contents/${encPath(path)}?ref=${enc(branch)}")
        val sha = if (existing.ok) JSONObject(existing.text).optString("sha").ifBlank { null } else null
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        check(call("PUT", "/repos/$repo/contents/${encPath(path)}", body))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun encPath(p: String) = p.split('/').joinToString("/") { enc(it).replace("+", "%20") }
}
