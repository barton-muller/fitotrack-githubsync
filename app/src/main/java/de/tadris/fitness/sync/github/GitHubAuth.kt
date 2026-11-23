package de.tadris.fitness.sync.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

/** GitHub App sign-in with the device flow: no client secret, no redirect. The token is kept encrypted with a Keystore key. */
class GitHubAuth(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun login(): String? = prefs.getString(KEY_LOGIN, null)

    fun signedIn() = token() != null

    fun signOut() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply()
    }

    fun save(token: String, login: String?) {
        prefs.edit().putString(KEY_TOKEN, encrypt(token)).putString(KEY_LOGIN, login).apply()
    }

    fun startDeviceFlow(): DeviceCode {
        val r = GitHubHttp.request(
            "POST", "https://github.com/login/device/code",
            body = "client_id=${GitHubConfig.CLIENT_ID}".toByteArray(),
            contentType = "application/x-www-form-urlencoded", accept = "application/json",
        )
        if (!r.ok) throw IOException("GitHub ${r.code}")
        val j = JSONObject(r.text)
        if (j.has("error")) throw IOException(j.optString("error_description", j.getString("error")))
        return DeviceCode(
            j.getString("device_code"), j.getString("user_code"), j.getString("verification_uri"),
            j.getInt("expires_in"), j.optInt("interval", 5),
        )
    }

    /** Polls until the person approves in the browser. Throws on denial or expiry. Cancel through the coroutine. */
    suspend fun awaitToken(code: DeviceCode): String {
        var interval = code.intervalSeconds
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val r = withContext(Dispatchers.IO) {
                GitHubHttp.request(
                    "POST", "https://github.com/login/oauth/access_token",
                    body = ("client_id=${GitHubConfig.CLIENT_ID}&device_code=${code.deviceCode}" +
                            "&grant_type=urn:ietf:params:oauth:grant-type:device_code").toByteArray(),
                    contentType = "application/x-www-form-urlencoded", accept = "application/json",
                )
            }
            val j = JSONObject(r.text)
            when (val err = j.optString("error")) {
                "" -> return j.getString("access_token")
                "authorization_pending" -> Unit
                "slow_down" -> interval = j.optInt("interval", interval + 5)
                "access_denied" -> throw IOException("Sign-in was denied")
                "expired_token" -> throw IOException("The code expired, try again")
                else -> throw IOException(j.optString("error_description", err))
            }
        }
        throw IOException("The code expired, try again")
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    private companion object {
        const val PREFS = "github_sync_auth"
        const val KEY_TOKEN = "token"
        const val KEY_LOGIN = "login"
        const val ALIAS = "github_sync_token"
    }
}
