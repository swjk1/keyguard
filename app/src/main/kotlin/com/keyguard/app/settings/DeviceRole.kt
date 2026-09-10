package com.keyguard.app.settings

import android.content.Context

/**
 * Which side of the product this phone is.
 *
 * Until now both sides shipped in one screen: `SetupActivity` carried the child's pairing form
 * and a button through to `ParentActivity`, and a device could be halfway into both. That is
 * fine on a developer's test phone and wrong everywhere else. A child's device offering *Set up
 * parent mode* invites exactly the experiment you would expect, and a parent's device — which
 * may not even have the keyboard enabled and has no reason to — spent its first screen being
 * told to go and enable a keyboard.
 *
 * Two independent booleans already exist for the *server's* view of this — `Supervision.isParent`
 * and `Supervision.isSupervised` — and they stay independent for the reason documented there:
 * one test phone routinely plays both. This is a different question. It is a local UI decision about
 * which product this person is holding, and it is deliberately a single value, because the whole
 * point is that the other side stops being reachable.
 */
enum class DeviceRole {
    /** Nothing chosen yet. The launcher activity asks before showing either side. */
    UNSET,

    /** The monitored device. Keyboard setup, sizing, pairing, and the supervision disclosure. */
    CHILD,

    /** The supervising device. Dashboard, policy, reports. Never nagged about a keyboard. */
    PARENT,
    ;

    companion object {
        fun fromName(name: String?): DeviceRole =
            entries.firstOrNull { it.name == name } ?: UNSET
    }
}

/**
 * Whether the role may be changed right now, and why not.
 *
 * Pure so the rules are testable without a device, and because the interesting case is a
 * security property rather than a preference: **a supervised child must not be able to walk out
 * of supervision by picking a different role.** That is the same argument as "only a parent can
 * unpair", and it has to hold here too or the role chooser becomes the unpair button that was
 * deliberately never built.
 */
object RolePolicy {

    sealed interface Verdict {
        data object Allowed : Verdict

        /** Supervised. Only the parent can end that, from their own device. */
        data object LockedBySupervision : Verdict

        /** Signed in as a parent. Sign out first, which needs the password to undo. */
        data object LockedByParentSession : Verdict
    }

    fun verdict(
        current: DeviceRole,
        isSupervised: Boolean,
        hasParentSession: Boolean,
    ): Verdict = when {
        // Checked first and regardless of the current role. A device that is being supervised
        // is locked to CHILD even if its stored role somehow says otherwise, so a corrupted or
        // hand-edited preference cannot become an escape hatch.
        isSupervised -> Verdict.LockedBySupervision
        current == DeviceRole.PARENT && hasParentSession -> Verdict.LockedByParentSession
        else -> Verdict.Allowed
    }

    fun mayChange(
        current: DeviceRole,
        isSupervised: Boolean,
        hasParentSession: Boolean,
    ): Boolean = verdict(current, isSupervised, hasParentSession) == Verdict.Allowed
}

/**
 * Persistence for the role.
 *
 * Its own preferences file rather than a key in [Settings], because [Settings] is the child's
 * own keyboard preferences and this is not one of those — it is the switch that decides whether
 * that file is even consulted.
 */
class RoleStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var role: DeviceRole
        get() = DeviceRole.fromName(prefs.getString(KEY_ROLE, null))
        private set(value) = prefs.edit().putString(KEY_ROLE, value.name).apply()

    val isChosen: Boolean get() = role != DeviceRole.UNSET

    /**
     * Records a choice, if the current state permits one.
     *
     * @return whether the role changed. A refusal is not an error the caller has to handle
     *   specially — the screen simply stays where it is and says why — but it must not be
     *   silently reported as success, or the chooser would appear to work and change nothing.
     */
    fun choose(
        next: DeviceRole,
        isSupervised: Boolean,
        hasParentSession: Boolean,
    ): Boolean {
        if (next == DeviceRole.UNSET) return false
        if (!RolePolicy.mayChange(role, isSupervised, hasParentSession)) return false
        role = next
        return true
    }

    /**
     * Forces the role back to unset. Only for a device that has genuinely left both
     * arrangements — a child the parent unpaired, or a parent account that was deleted.
     */
    fun reset() {
        prefs.edit().remove(KEY_ROLE).apply()
    }

    private companion object {
        const val PREFS = "keyguard_role"
        const val KEY_ROLE = "device_role"
    }
}
