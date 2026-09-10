package com.keyguard.app.net

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

/**
 * One HTTP surface and one install identity for everything that talks to the backend.
 *
 * Extracted from `VerifyClient` when supervision arrived and needed the same three things:
 * the anonymous install id, the token minted from it, and the re-register-once-on-401 dance
 * that a rotated server secret requires. Two copies of that would also mean two install ids
 * per device, which would key the server's rate limits twice and make one phone look like two
 * to the family routes.
 *
 * Still `HttpURLConnection` rather than a client library, for the reason the verify path
 * already gave: the payloads are tiny and APK size is scarce inside an IME.
 *
 * Every method is blocking and every one returns null rather than throwing. Callers are
 * expected to be on a background thread and to treat null as "nothing changed".
 */
class ApiClient(context: Context, private val baseUrl: String) {

    /**
     * Deliberately the same preferences file the verification client has always used, so an
     * upgrade keeps the install id it already registered instead of silently becoming a new
     * device to the server.
     */
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * A random per-install id, generated locally and never derived from device identifiers, so
     * it cannot be tied back to a person or correlated across reinstalls.
     */
    val installId: String
        get() = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }

    private var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var parentSessionToken: String?
        get() = prefs.getString(KEY_PARENT_SESSION, null)
        private set(value) = prefs.edit().putString(KEY_PARENT_SESSION, value).apply()

    var parentEmail: String?
        get() = prefs.getString(KEY_PARENT_EMAIL, null)
        private set(value) = prefs.edit().putString(KEY_PARENT_EMAIL, value).apply()

    val hasParentSession: Boolean get() = !parentSessionToken.isNullOrBlank()

    class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
        fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
    }

    /**
     * Sends [body] with an install token, registering first if there is none.
     *
     * A 401 means the server's secret was rotated and every issued token died with it, so the
     * request is retried once against a fresh token. Any second failure is just a failure.
     */
    fun authorized(
        method: String,
        path: String,
        body: String? = null,
        timeoutMs: Int = TIMEOUT_MS,
    ): Response? {
        val active = token ?: register() ?: return null

        val response = send(method, path, body, installToken = active, parentToken = null, timeoutMs = timeoutMs)
        if (response?.code != HttpURLConnection.HTTP_UNAUTHORIZED) return response

        token = null
        val fresh = register() ?: return null
        return send(method, path, body, installToken = fresh, parentToken = null, timeoutMs = timeoutMs)
    }

    fun publicRequest(
        method: String,
        path: String,
        body: String? = null,
        timeoutMs: Int = TIMEOUT_MS,
    ): Response? = send(method, path, body, installToken = null, parentToken = null, timeoutMs = timeoutMs)

    fun parentAuthorized(
        method: String,
        path: String,
        body: String? = null,
        timeoutMs: Int = TIMEOUT_MS,
    ): Response? {
        val active = parentSessionToken ?: return null
        val response = send(method, path, body, installToken = null, parentToken = active, timeoutMs = timeoutMs)
        if (response?.code == HttpURLConnection.HTTP_UNAUTHORIZED) clearParentSession()
        return response
    }

    fun saveParentSession(token: String, email: String) {
        parentSessionToken = token
        parentEmail = email.trim()
    }

    fun clearParentSession() {
        parentSessionToken = null
        parentEmail = null
    }

    /** Exchanges the install id for a token. Returns null if the server cannot be reached. */
    fun register(): String? {
        val body = JSONObject().put("installId", installId).toString()
        val response = send(
            "POST",
            "/api/register",
            body,
            installToken = null,
            parentToken = null,
            timeoutMs = TIMEOUT_MS,
        )
        if (response == null || !response.ok) return null

        val issued = response.json()?.optString("token")?.takeIf { it.isNotBlank() }
        return issued?.also { token = it }
    }

    private fun send(
        method: String,
        path: String,
        body: String?,
        installToken: String?,
        parentToken: String?,
        timeoutMs: Int,
    ): Response? {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            installToken?.let { connection.setRequestProperty("x-install-token", it) }
            parentToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            Response(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (_: IOException) {
            // Includes the timeout case, which is the expected one on a slow network.
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 7000

        private const val PREFS = "keyguard_verify"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_TOKEN = "install_token"
        private const val KEY_PARENT_SESSION = "parent_session"
        private const val KEY_PARENT_EMAIL = "parent_email"
    }
}
