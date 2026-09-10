package com.keyguard.app.family

import android.content.Context
import org.json.JSONObject

/**
 * What this device knows about its family, and the policy it is enforcing right now.
 *
 * The policy is **cached locally, not fetched on demand**. A supervised keyboard that only
 * knew its rules when the network was up would quietly revert to the child's own settings on
 * a train, which is both the wrong behaviour and an obvious way around it. The cache is
 * authoritative between syncs; a sync replaces it, and only an explicit "you are no longer
 * supervised" from the server clears it.
 *
 * Parent and child are tracked as two independent facts rather than one role enum. They are
 * genuinely independent — a device that created a family is not thereby prevented from being
 * supervised, and one test phone routinely plays both — and an enum would force a false
 * choice at exactly the moment someone is trying to reproduce a bug.
 */
class Supervision(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Set once this device has joined a family as a supervised device. */
    val childFamilyId: String? get() = prefs.getString(KEY_CHILD_FAMILY, null)

    /** Set once this device has created a family it supervises. */
    val parentFamilyId: String? get() = prefs.getString(KEY_PARENT_FAMILY, null)

    val isSupervised: Boolean get() = childFamilyId != null

    val isParent: Boolean get() = parentFamilyId != null

    /** How this device names itself to the parent. The child types it during pairing. */
    var deviceLabel: String
        get() = prefs.getString(KEY_LABEL, null).orEmpty().ifBlank { DEFAULT_LABEL }
        set(value) = prefs.edit().putString(KEY_LABEL, value.trim().take(MAX_LABEL)).apply()

    /**
     * The policy in force, or null when this device is not supervised.
     *
     * Null is the signal every caller keys off to fall back to the child's own settings, which
     * is why an unsupervised device cannot accidentally inherit a floor.
     */
    val policy: FamilyPolicy?
        get() {
            if (!isSupervised) return null
            val stored = prefs.getString(KEY_POLICY, null) ?: return FamilyPolicy.DEFAULT
            val json = runCatching { JSONObject(stored) }.getOrNull() ?: return FamilyPolicy.DEFAULT
            return FamilyPolicy.fromJson(json)
        }

    /** Epoch millis of the last successful exchange with the server, or 0. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    fun becomeChild(familyId: String, policy: FamilyPolicy) {
        prefs.edit()
            .putString(KEY_CHILD_FAMILY, familyId)
            .putString(KEY_POLICY, policy.toJson().toString())
            .apply()
    }

    fun becomeParent(familyId: String) {
        prefs.edit().putString(KEY_PARENT_FAMILY, familyId).apply()
    }

    fun clearParent() {
        prefs.edit().remove(KEY_PARENT_FAMILY).apply()
    }

    fun updatePolicy(policy: FamilyPolicy) {
        if (!isSupervised) return
        prefs.edit().putString(KEY_POLICY, policy.toJson().toString()).apply()
    }

    /**
     * The server has said this device is no longer in a family.
     *
     * Only ever called on that explicit answer, never on a failed request. A network error
     * that dropped supervision would make airplane mode the way out of it.
     */
    fun clearSupervision() {
        prefs.edit()
            .remove(KEY_CHILD_FAMILY)
            .remove(KEY_POLICY)
            .apply()
    }

    private companion object {
        const val PREFS = "keyguard_supervision"
        const val KEY_CHILD_FAMILY = "child_family_id"
        const val KEY_PARENT_FAMILY = "parent_family_id"
        const val KEY_POLICY = "policy"
        const val KEY_LABEL = "device_label"
        const val KEY_LAST_SYNC = "last_sync_at"

        const val DEFAULT_LABEL = "Phone"
        const val MAX_LABEL = 40
    }
}
