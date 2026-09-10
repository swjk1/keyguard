package com.keyguard.app.family

import android.content.Context
import com.keyguard.app.net.ApiClient
import org.json.JSONArray
import org.json.JSONObject

/** A pairing code as a parent sees it, with the moment it stops working. */
data class PairingInvite(val code: String, val expiresAt: Long)

sealed interface ParentAuthOutcome {
    data class Success(val email: String, val recoveryCode: String? = null) : ParentAuthOutcome
    data object Invalid : ParentAuthOutcome
    data object EmailInUse : ParentAuthOutcome
    data object RateLimited : ParentAuthOutcome
    data object Failed : ParentAuthOutcome
}

/** One reported warning, with the server's receipt time alongside the device's claim. */
data class ReportedEvent(val event: SupervisionEvent, val receivedAt: Long)

data class ChildActivity(
    val installId: String,
    val label: String,
    val pairedAt: Long,
    val lastSeen: Long,
    /** Newest first, as the server stores them. */
    val events: List<ReportedEvent>,
)

/**
 * The whole parent screen in one response.
 *
 * [policy] comes from here rather than from the policy route, which answers as the *child* and
 * is keyed on the caller's own membership — a parent is not a member of their own family, so
 * that route would correctly and uselessly tell them they are unsupervised.
 */
data class FamilyOverview(
    /**
     * Null when this install owns no family yet — the ordinary state of a parent who has not
     * asked for their first code. Kept distinct from a null [FamilyClient.fetchOverview],
     * which means the server could not be reached, because one of those is worth an error
     * banner and the other is just an empty screen.
     */
    val familyId: String?,
    val policy: FamilyPolicy,
    val children: List<ChildActivity>,
)

/**
 * The outcome of asking the server about supervision.
 *
 * Three cases, and keeping [Unsupervised] distinct from [Failed] is the whole point of the
 * type. They arrive on the same code path and mean opposite things: one is a parent having
 * removed this device, the other is a tunnel. Collapsing them would make airplane mode the
 * way out of supervision.
 */
sealed interface PolicyOutcome {
    data class Supervised(val policy: FamilyPolicy) : PolicyOutcome
    data object Unsupervised : PolicyOutcome
    data object Failed : PolicyOutcome
}

/**
 * The outcome of uploading activity samples.
 *
 * A fourth case that [UploadOutcome] does not need: the server can reject samples because the
 * family's review scope no longer permits them, which is not a failure and not an unpairing.
 * It means a parent turned collection down while this device was offline, and the right
 * response is to drop the queue rather than retry a batch nobody is entitled to receive.
 */
sealed interface SampleOutcome {
    data class Accepted(val received: Int) : SampleOutcome
    data object Unsupervised : SampleOutcome
    data object ScopeWithdrawn : SampleOutcome
    data object Failed : SampleOutcome
}

sealed interface UploadOutcome {
    /** The server took the batch. [received] is what may now be dropped from the queue. */
    data class Accepted(val received: Int) : UploadOutcome
    data object Unsupervised : UploadOutcome
    data object Failed : UploadOutcome
}

/**
 * The family half of the backend.
 *
 * Every method blocks and must be called off the main thread; [SupervisionSync] owns the
 * threading. Failure is always a null or a `Failed`, never an exception — supervision going
 * quiet must never be something a user experiences as a crash.
 */
class FamilyClient(context: Context, baseUrl: String) {

    private val api = ApiClient(context, baseUrl)

    /** This device's anonymous install id — the child identifier a parent's list is keyed by. */
    val installId: String get() = api.installId

    val hasParentSession: Boolean get() = api.hasParentSession
    val parentEmail: String? get() = api.parentEmail

    fun registerParent(email: String, password: String): ParentAuthOutcome =
        authenticate("/api/auth/register", email, password)

    fun loginParent(email: String, password: String): ParentAuthOutcome =
        authenticate("/api/auth/login", email, password)

    fun recoverParent(email: String, code: String, password: String): ParentAuthOutcome {
        val body = JSONObject()
            .put("email", email.trim())
            .put("recoveryCode", code.trim())
            .put("newPassword", password)
            .toString()
        return parseAuth(api.publicRequest("POST", "/api/auth/recover", body))
    }

    fun signOutParent(): Boolean {
        val response = api.parentAuthorized("POST", "/api/auth/logout")
        api.clearParentSession()
        return response?.ok == true
    }

    fun deleteParentAccount(): Boolean {
        val response = api.parentAuthorized("DELETE", "/api/auth/account")
        if (response?.ok == true) api.clearParentSession()
        return response?.ok == true
    }

    private fun authenticate(path: String, email: String, password: String): ParentAuthOutcome {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        return parseAuth(api.publicRequest("POST", path, body))
    }

    private fun parseAuth(response: ApiClient.Response?): ParentAuthOutcome {
        if (response == null) return ParentAuthOutcome.Failed
        val json = response.json()
        if (!response.ok) {
            return when (json?.optString("error")) {
                "invalid_credentials", "invalid_recovery" -> ParentAuthOutcome.Invalid
                "email_in_use" -> ParentAuthOutcome.EmailInUse
                "rate_limited" -> ParentAuthOutcome.RateLimited
                else -> ParentAuthOutcome.Failed
            }
        }
        val token = json?.optString("token")?.takeIf { it.isNotBlank() }
            ?: return ParentAuthOutcome.Failed
        val account = json.optJSONObject("account") ?: return ParentAuthOutcome.Failed
        val email = account.optString("email").takeIf { it.isNotBlank() }
            ?: return ParentAuthOutcome.Failed
        api.saveParentSession(token, email)
        return ParentAuthOutcome.Success(
            email = email,
            recoveryCode = json.optString("recoveryCode").takeIf { it.isNotBlank() },
        )
    }

    /** Creates or returns the family the signed-in parent account owns. */
    fun createFamily(): String? {
        val response = api.parentAuthorized("POST", "/api/family/create") ?: return null
        if (!response.ok) return null
        return response.json()?.optString("familyId")?.takeIf { it.isNotBlank() }
    }

    fun issuePairingCode(): PairingInvite? {
        val response = api.parentAuthorized("POST", "/api/family/code") ?: return null
        if (!response.ok) return null

        val json = response.json() ?: return null
        val code = json.optString("code").takeIf { it.isNotBlank() } ?: return null
        return PairingInvite(code, json.optLong("expiresAt"))
    }

    fun issueParentInvite(): PairingInvite? {
        val response = api.parentAuthorized("POST", "/api/family/parent-code") ?: return null
        if (!response.ok) return null
        val json = response.json() ?: return null
        val code = json.optString("code").takeIf { it.isNotBlank() } ?: return null
        return PairingInvite(code, json.optLong("expiresAt"))
    }

    fun joinAsParent(code: String): Boolean {
        val body = JSONObject().put("code", code).toString()
        return api.parentAuthorized("POST", "/api/family/parent-join", body)?.ok == true
    }

    /** Redeems a code. Returns the policy to start enforcing, or null if the code is no good. */
    fun join(code: String, label: String): Pair<String, FamilyPolicy>? {
        val body = JSONObject().put("code", code).put("label", label).toString()
        val response = api.authorized("POST", "/api/family/join", body) ?: return null
        if (!response.ok) return null

        val json = response.json() ?: return null
        val familyId = json.optString("familyId").takeIf { it.isNotBlank() } ?: return null
        val policy = json.optJSONObject("policy")?.let(FamilyPolicy::fromJson)
            ?: FamilyPolicy.DEFAULT
        return familyId to policy
    }

    fun fetchPolicy(): PolicyOutcome {
        val response = api.authorized("GET", "/api/family/policy") ?: return PolicyOutcome.Failed
        if (!response.ok) return PolicyOutcome.Failed

        val json = response.json() ?: return PolicyOutcome.Failed
        if (!json.optBoolean("supervised", false)) return PolicyOutcome.Unsupervised

        val policy = json.optJSONObject("policy") ?: return PolicyOutcome.Failed
        return PolicyOutcome.Supervised(FamilyPolicy.fromJson(policy))
    }

    fun uploadEvents(events: List<SupervisionEvent>): UploadOutcome {
        if (events.isEmpty()) return UploadOutcome.Accepted(0)

        val array = JSONArray()
        for (event in events) array.put(event.toJson())
        val body = JSONObject().put("events", array).toString()

        val response = api.authorized("POST", "/api/family/events", body)
            ?: return UploadOutcome.Failed
        if (!response.ok) return UploadOutcome.Failed

        val json = response.json() ?: return UploadOutcome.Failed
        if (!json.optBoolean("supervised", false)) return UploadOutcome.Unsupervised
        return UploadOutcome.Accepted(json.optInt("received", 0))
    }

    /**
     * Delivers activity samples.
     *
     * Deliberately a separate route from [uploadEvents] rather than one payload carrying both.
     * They are gated on different things — an event on being supervised, a sample on the review
     * scope — and the server has to be able to refuse one while accepting the other. Sharing a
     * request would have meant a partial rejection nobody could express.
     */
    fun uploadSamples(samples: List<ActivitySample>): SampleOutcome {
        if (samples.isEmpty()) return SampleOutcome.Accepted(0)

        val array = JSONArray()
        for (sample in samples) array.put(sample.toJson())
        val body = JSONObject().put("samples", array).toString()

        val response = api.authorized("POST", "/api/family/samples", body)
            ?: return SampleOutcome.Failed
        val json = response.json()

        if (!response.ok) {
            // A scope that no longer permits collection is a 403 with a named error, not a
            // transport failure, so it is distinguished here rather than collapsed into Failed.
            return if (json?.optString("error") == "scope_withdrawn") {
                SampleOutcome.ScopeWithdrawn
            } else {
                SampleOutcome.Failed
            }
        }
        if (json == null) return SampleOutcome.Failed
        if (!json.optBoolean("supervised", false)) return SampleOutcome.Unsupervised
        return SampleOutcome.Accepted(json.optInt("received", 0))
    }

    /** The parent's daily or weekly summary for one child. */
    fun fetchReport(childInstallId: String, period: ReportPeriod): FamilyReport? {
        val path = "/api/family/report?installId=$childInstallId&period=${period.name}"
        val response = api.parentAuthorized("GET", path) ?: return null
        if (!response.ok) return null
        return response.json()?.optJSONObject("report")?.let(FamilyReport::fromJson)
    }

    fun writePolicy(policy: FamilyPolicy): FamilyPolicy? {
        // The version is the server's to assign, so it is not sent.
        val body = policy.toJson().apply { remove(FamilyPolicy.FIELD_VERSION) }.toString()
        val response = api.parentAuthorized("PUT", "/api/family/policy", body) ?: return null
        if (!response.ok) return null
        return response.json()?.optJSONObject("policy")?.let(FamilyPolicy::fromJson)
    }

    fun fetchOverview(limit: Int = DEFAULT_ACTIVITY_LIMIT): FamilyOverview? {
        val response = api.parentAuthorized("GET", "/api/family/activity?limit=$limit") ?: return null
        if (!response.ok) return null

        val json = response.json() ?: return null
        val children = json.optJSONArray("children")

        return FamilyOverview(
            familyId = json.optString("familyId").takeIf { it.isNotBlank() },
            policy = json.optJSONObject("policy")?.let(FamilyPolicy::fromJson)
                ?: FamilyPolicy.DEFAULT,
            children = (0 until (children?.length() ?: 0)).mapNotNull { index ->
                children?.optJSONObject(index)?.let(::parseChild)
            },
        )
    }

    fun removeChild(childInstallId: String): Boolean {
        val body = JSONObject().put("installId", childInstallId).toString()
        return api.parentAuthorized("DELETE", "/api/family/child", body)?.ok == true
    }

    private fun parseChild(json: JSONObject): ChildActivity? {
        val installId = json.optString("installId").takeIf { it.isNotBlank() } ?: return null
        val events = json.optJSONArray("events")
        return ChildActivity(
            installId = installId,
            label = json.optString("label").ifBlank { "Phone" },
            pairedAt = json.optLong("pairedAt"),
            lastSeen = json.optLong("lastSeen"),
            events = (0 until (events?.length() ?: 0)).mapNotNull { index ->
                val item = events?.optJSONObject(index) ?: return@mapNotNull null
                SupervisionEvent.fromJson(item)?.let {
                    ReportedEvent(it, item.optLong("receivedAt", it.at))
                }
            },
        )
    }

    private companion object {
        const val DEFAULT_ACTIVITY_LIMIT = 100
    }
}
