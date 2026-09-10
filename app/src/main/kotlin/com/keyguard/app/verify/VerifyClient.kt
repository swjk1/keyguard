package com.keyguard.app.verify

import android.content.Context
import android.util.Log
import com.keyguard.app.net.ApiClient
import com.keyguard.detect.Severity
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to the verification endpoint.
 *
 * **Fails open, always.** Every error path — no network, timeout, 429, 503, malformed body —
 * returns null, and null means "keep the local verdict". The keyboard must never block on this
 * and must never show a spinner: a safety keyboard that stalls mid-sentence gets uninstalled,
 * and Apple's 4.4.1 requires it to work without network access anyway.
 *
 * Transport, the install id, and token renewal all live in [ApiClient], which the supervision
 * client shares — one install identity per device, not one per feature.
 */
class VerifyClient(context: Context, baseUrl: String) {

    private val api = ApiClient(context, baseUrl)
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Verifies [request] off the main thread and delivers the result to [onResult], which is
     * invoked on the calling executor's thread — callers must post to the UI thread themselves.
     */
    fun verify(request: VerifyRequest, onResult: (CachedVerdict?) -> Unit) {
        executor.execute {
            val result = runCatching { performVerify(request) }
                .onFailure { Log.d(TAG, "verify failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            onResult(result)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun performVerify(request: VerifyRequest): CachedVerdict? {
        val response = api.authorized(
            method = "POST",
            path = "/api/verify",
            body = requestJson(request),
            timeoutMs = if (request.escalate) ESCALATION_TIMEOUT_MS else TIMEOUT_MS,
        )
        if (response == null || !response.ok) return null
        return parse(response.body)
    }

    private fun requestJson(request: VerifyRequest): String {
        val findings = JSONArray()
        for (finding in request.findings) {
            findings.put(
                JSONObject()
                    .put("ruleId", finding.ruleId)
                    .put("category", finding.category.name)
                    .put("severity", finding.severity)
                    .put("text", finding.text),
            )
        }
        return JSONObject()
            .put("sentence", request.sentence)
            .put("context", request.context)
            .put("findings", findings)
            .put("escalate", request.escalate)
            .toString()
    }

    private fun parse(body: String): CachedVerdict? = runCatching {
        val json = JSONObject(body)
        val array = json.getJSONArray("verdicts")
        val verdicts = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Verdict(
                ruleId = item.getString("ruleId"),
                confirmed = item.optBoolean("confirmed", true),
                severity = Severity.of(item.optInt("severity", 0).coerceIn(0, 3)),
                figurative = item.optBoolean("figurative", false),
                reason = item.optString("reason", ""),
                direction = Direction.fromName(item.optString("direction")),
                // Defaults to full confidence when absent, so a server that predates the field
                // behaves exactly as before rather than having every verdict discarded as
                // unsure — the fail-open contract applies to the schema too, not just the
                // network.
                confidence = item.optDouble("confidence", 1.0),
            )
        }
        CachedVerdict(
            verdicts = verdicts,
            suggestedRewrite = json.optString("suggestedRewrite").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private companion object {
        const val TAG = "KeyguardVerify"

        /**
         * The keyboard gives up here. A late verdict is worthless.
         *
         * TESTING VALUES — not a shipping decision. The original 800ms/2500ms were set before
         * the endpoint had ever been run. First measurement against Haiku 4.5 (2026-08-02, n=13,
         * localhost) put the *fastest* standard response at 1148ms, median ~1.9s, slowest 3.2s;
         * one escalated Sonnet 5 call took 4.3s. At 800ms the client discarded 100% of verdicts,
         * so the layer was dead code that still cost money on every call.
         *
         * These are set above the server's own aborts so the client receives a structured 503
         * rather than a socket timeout. Whether a verdict this late is worth showing is an open
         * product question — see the note in maybeVerify about stale-text discard.
         */
        const val TIMEOUT_MS = 7000
        const val ESCALATION_TIMEOUT_MS = 14000
    }
}
