package com.keyguard.app.overlay

import com.keyguard.app.family.OverrideLevel
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity

/**
 * What the floating warning should be doing right now.
 *
 * The IME's equivalent of this is spread across `resolveStripState`, `InputGate`, and a couple
 * of booleans on the service, which was tolerable when all three lived in one class that also
 * owned the views. The overlay cannot work that way: it has no keys of its own to dim, its
 * "block" is a second window placed over somebody else's keyboard, and getting the two windows
 * out of step means either a shade with no warning explaining it or a warning insisting typing
 * is paused while it plainly is not.
 *
 * So the whole decision is one pure function returning one value, and the service's only job is
 * to make the windows match it.
 */
sealed interface OverlayState {

    /** Nothing to say. No windows at all — not an empty one, which would still cost a frame. */
    data object Hidden : OverlayState

    /**
     * A warning, with or without the keyboard shade.
     *
     * [shaded] and [dismissible] are the two independent axes, and they are independent on
     * purpose. A high-severity finding under [OverrideLevel.FULL] is shaded *and* dismissible —
     * typing is paused, and one tap un-pauses it. Under [OverrideLevel.NONE] it is shaded and
     * not dismissible, and the only button is *Remove it*.
     */
    data class Warning(
        val summary: String,
        val detail: String,
        val severity: Severity,
        /** Cover the keyboard so typing genuinely stops, rather than only being discouraged. */
        val shaded: Boolean,
        /** Draw an Ignore button. False means the child's policy withheld the override. */
        val dismissible: Boolean,
        /** Offer *Remove it*. The exit that exists at every override level. */
        val removable: Boolean,
    ) : OverlayState

    /**
     * The self-harm path. Support and a helpline, never a warning and never a shade.
     *
     * A separate state rather than a [Warning] with flags, for the same reason
     * `StripState.Crisis` is: the difference is not one of degree, and a future edit that added
     * a shade to "serious" warnings must not be able to reach this by accident.
     */
    data class Crisis(val message: String, val resourceLabel: String) : OverlayState
}

/**
 * Turns a scan plus a policy into an [OverlayState].
 *
 * Pure and Android-free, so the table below is testable in full — which is the point, because
 * the combination that matters most (crisis under the strictest policy) is also the one hardest
 * to reproduce by hand on a device.
 */
object OverlayDecision {

    /**
     * @param blockingEnabled the parent's `blockAtHigh`, or true on an unsupervised device.
     * @param acknowledged whether the user has already waved this finding away.
     */
    fun decide(
        result: ScanResult,
        fieldProtected: Boolean,
        overrideLevel: OverrideLevel,
        blockingEnabled: Boolean,
        acknowledged: Boolean,
        summaryFor: (ScanResult) -> String,
        detailFor: (ScanResult) -> String,
        crisisMessage: String,
        crisisResourceLabel: String,
    ): OverlayState {
        if (fieldProtected) return OverlayState.Hidden

        // Checked before `acknowledged` and before severity, exactly as the IME checks it
        // before its own severity test. Someone writing about hurting themselves is offered
        // help; they are not shaded, not gated, and not told to remove what they wrote.
        if (result.requiresCrisisResponse) {
            return OverlayState.Crisis(crisisMessage, crisisResourceLabel)
        }

        val severity = result.maxSeverity
        if (result.findings.isEmpty() || severity == Severity.NONE) return OverlayState.Hidden

        val dismissible = overrideLevel.mayDismiss(severity)

        // An acknowledgement the policy did not permit counts for nothing, anywhere. Resolved
        // once, here, rather than being re-derived per field: an earlier version applied the
        // policy check when deciding whether to hide the overlay but not when deciding whether
        // to shade, so a child at OverrideLevel.NONE who pressed Ignore kept the warning and
        // lost the shade — the one combination that leaves a visible block nobody is enforcing.
        //
        // Checking the policy rather than trusting the flag is also what makes a tightening
        // sync take effect on the very next keystroke, instead of the child riding out the rest
        // of the message on a permission that has been withdrawn.
        val waived = acknowledged && dismissible

        if (waived) return OverlayState.Hidden

        return OverlayState.Warning(
            summary = summaryFor(result),
            detail = detailFor(result),
            severity = severity,
            // The shade is the overlay's answer to the IME's dead keys, and it fires under the
            // same conditions: high severity, blocking permitted by policy, not legitimately
            // waved away.
            shaded = blockingEnabled && severity == Severity.HIGH && !waived,
            dismissible = dismissible,
            // Always. This is the exit that must exist at every level, and it is the only exit
            // at OverrideLevel.NONE.
            removable = true,
        )
    }
}
