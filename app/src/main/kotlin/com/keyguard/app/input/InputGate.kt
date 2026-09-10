package com.keyguard.app.input

import com.keyguard.app.family.OverrideLevel
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity

/**
 * Decides whether the keys are live.
 *
 * A high-severity finding stops the keyboard accepting new text: the user has to take the
 * flagged content back out, or press Ignore, before they can carry on typing. Everything
 * softer than that stays advisory — the warning strip says its piece and the keys keep
 * working.
 *
 * Two exits, and at least one must always be reachable, because a keyboard the user cannot
 * type on and cannot get out of is worse than no keyboard at all:
 *
 * - **Resolve.** Backspace and the strip's *Remove it* button are never gated, so deleting
 *   the flagged text lifts the block on its own. [update] clears the acknowledgement at the
 *   same moment, so text that goes high again later blocks afresh rather than sailing
 *   through on a stale press of Ignore. **This exit exists at every override level** — it is
 *   what makes [OverrideLevel.NONE] a constraint rather than a trap.
 * - **Acknowledge.** [acknowledge] is the explicit override. It survives further typing —
 *   unlike the strip's own `dismissed` flag, which the next keystroke re-arms. If it did
 *   not survive, Ignore would buy exactly one character before locking again, which is not
 *   an override at all. On a supervised device a parent can take this exit away; see
 *   [OverrideLevel].
 *
 * Two cases are never blocked, and the ordering in [qualifies] is what enforces it:
 *
 * - **Protected fields.** Nothing is scanned there, so there is nothing to block on.
 * - **Crisis.** Locking someone out of their keyboard mid-sentence because they typed about
 *   hurting themselves is the exact opposite of the response that path owes them. The
 *   crisis strip offers a helpline; it must not also confiscate the keys. No override level
 *   changes this, including [OverrideLevel.NONE] — a parent tightening the screws must not
 *   be able to gag a child who is trying to say they are in trouble.
 *
 * Pure and Android-free so the state machine is testable on the JVM, in the same spirit as
 * [SendInference].
 */
class InputGate {

    /** True while the keys should refuse new text. */
    var isBlocked: Boolean = false
        private set

    /**
     * True when the block is in force and Ignore is not available, so the only way out is
     * removing the flagged content.
     *
     * Surfaced separately from [isBlocked] because the strip has to render differently: an
     * Ignore button that is drawn and refuses to work reads as a bug, and the child taps it
     * repeatedly instead of finding the exit that actually works.
     */
    var mustResolve: Boolean = false
        private set

    /** Whether the user has pressed Ignore on the finding currently blocking. */
    private var acknowledged = false

    /** Severity of the finding currently blocking, so [acknowledge] can judge the request. */
    private var blockingSeverity: Severity = Severity.NONE

    /** What the current policy permits. Held so [acknowledge] needs no arguments. */
    private var overrideLevel: OverrideLevel = OverrideLevel.DEFAULT

    /**
     * Recomputes from a fresh scan. Must be called everywhere the scan result changes —
     * including when AI verification downgrades a finding, since that is a legitimate way
     * for a block to end.
     */
    fun update(
        result: ScanResult,
        fieldProtected: Boolean,
        blockingEnabled: Boolean = true,
        overrideLevel: OverrideLevel = OverrideLevel.DEFAULT,
    ) {
        this.overrideLevel = overrideLevel

        if (!qualifies(result, fieldProtected, blockingEnabled)) {
            // The blocking content is gone. Forget the override with it, so the next
            // high-severity finding is judged on its own terms.
            acknowledged = false
            blockingSeverity = Severity.NONE
            isBlocked = false
            mustResolve = false
            return
        }

        blockingSeverity = result.maxSeverity
        // A stale acknowledgement cannot outlive a policy that withdrew the right to make it.
        // Without this, a child who pressed Ignore before a sync and whose parent then moved
        // them to NONE would keep typing on the strength of a permission they no longer have.
        if (acknowledged && !overrideLevel.mayDismiss(blockingSeverity)) acknowledged = false

        isBlocked = !acknowledged
        mustResolve = isBlocked && !overrideLevel.mayDismiss(blockingSeverity)
    }

    /**
     * The user pressed Ignore. Lifts the block until the flagged content is resolved.
     *
     * @return whether the override was permitted. False means the policy withheld it and the
     *   caller must not treat the warning as dismissed — the strip stays up and the keys stay
     *   refused. Returning a value rather than silently doing nothing is what lets the one
     *   caller that also clears its own `dismissed` flag stay in step with this one.
     */
    fun acknowledge(): Boolean {
        if (!overrideLevel.mayDismiss(blockingSeverity)) return false
        acknowledged = true
        isBlocked = false
        mustResolve = false
        return true
    }

    /** New field, or a sent message. Nothing carries over. */
    fun reset() {
        acknowledged = false
        blockingSeverity = Severity.NONE
        isBlocked = false
        mustResolve = false
    }

    companion object {
        /**
         * Whether this scan is the kind that stops input at all, override aside.
         *
         * [blockingEnabled] defaults to true because that is what an unsupervised keyboard has
         * always done. On a supervised device it carries the parent's decision, which is the
         * first coherent answer the product has had to *Subtle* promising never to gate and
         * the block firing anyway — there, someone chose.
         */
        fun qualifies(
            result: ScanResult,
            fieldProtected: Boolean,
            blockingEnabled: Boolean = true,
        ): Boolean = when {
            fieldProtected -> false
            result.requiresCrisisResponse -> false
            !blockingEnabled -> false
            else -> result.maxSeverity == Severity.HIGH
        }
    }
}
