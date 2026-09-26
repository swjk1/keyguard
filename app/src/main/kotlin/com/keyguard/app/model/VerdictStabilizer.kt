package com.keyguard.app.model

import com.keyguard.infer.ModelVerdict

/**
 * Decides which model verdict the overlay should be showing *right now*, so the warning does not
 * flicker between states while someone is still typing.
 *
 * Two separate things make a naive wiring flicker, and they need different answers.
 *
 * ### The verdict was being thrown away on every keystroke
 *
 * The rules run synchronously on each text change; the model arrives a few hundred milliseconds
 * later. If each keystroke resets the displayed result to the rule-only one, every character
 * produces the same cycle: block, then warning, then block again when the model catches up. That
 * is not the model being indecisive — it is deterministic, and it happens on every edit.
 *
 * The fix is that a verdict keeps applying while the message has not got shorter. Adding to a
 * message cannot un-disclose an address, so a verdict for "im home alone" still holds for "im home
 * alone at 24 oak st" — and, just as importantly, for "I'm home alone at 24 oak St" after the
 * keyboard has autocapitalised what was already typed. Deleting is the case that gets believed
 * immediately: deleting is how a warning is meant to be resolved, so responsiveness beats
 * steadiness in the one direction the user is actively trying to clear it. See [covers].
 *
 * ### The model genuinely does oscillate
 *
 * Several thresholds sit very high — `meetup` at 0.975, `alone` at 0.95 — so a probability parked
 * near one flips a signal on a single extra character, and the rule table turns that into a level
 * change. The answer is asymmetric hysteresis: **escalate immediately, de-escalate slowly.** A
 * warning that should be more severe must say so at once; a warning that should be less severe can
 * wait [minHoldMs] to prove it, because the cost of being briefly too careful is far lower than
 * the cost of a block that blinks.
 *
 * Pure and free of Android imports, so the whole table is unit-testable — which matters here
 * because the states that flicker are the ones hardest to reproduce by hand on a device.
 */
class VerdictStabilizer(
    private val minHoldMs: Long = MIN_HOLD_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private data class Shown(val verdict: ModelVerdict, val text: String, val atMillis: Long)
    private data class Latest(val verdict: ModelVerdict, val text: String)

    private var shown: Shown? = null
    private var latest: Latest? = null

    /** Records the newest model output. Does not decide anything on its own. */
    fun accept(text: String, verdict: ModelVerdict) {
        latest = Latest(verdict, text)
    }

    /**
     * The verdict to display for [text], or null to show the rules alone.
     *
     * Mutates: promoting the latest verdict to the displayed one is the act of deciding, and the
     * hold window is measured from the moment a level actually went on screen.
     */
    fun stable(text: String): ModelVerdict? {
        // Anything describing text that is no longer present stops applying at once.
        shown?.let { if (!covers(text, it.text)) shown = null }
        latest?.let { if (!covers(text, it.text)) latest = null }

        val incoming = latest ?: return shown?.verdict
        val current = shown

        if (current == null || incoming.verdict.level >= current.verdict.level) {
            // No warning yet, or a more severe one. Both go up immediately.
            shown = Shown(incoming.verdict, incoming.text, now())
            return incoming.verdict
        }

        if (now() - current.atMillis >= minHoldMs) {
            shown = Shown(incoming.verdict, incoming.text, now())
            return incoming.verdict
        }

        // Less severe, too soon. Keep what is on screen.
        return current.verdict
    }

    /**
     * Milliseconds until a pending de-escalation may be applied, or 0 when nothing is waiting.
     *
     * The caller uses this to schedule one more evaluation. Without it a downgrade that arrives
     * just as the user stops typing would never be applied, because nothing else would ask again
     * — and the overlay would sit at the more severe level until the field was touched.
     */
    fun pendingSettleMs(): Long {
        val current = shown ?: return 0
        val incoming = latest ?: return 0
        if (incoming.verdict.level >= current.verdict.level) return 0
        val waited = now() - current.atMillis
        return if (waited >= minHoldMs) 0 else minHoldMs - waited
    }

    /** Field changed or the composition ended. Nothing carries across. */
    fun reset() {
        shown = null
        latest = null
    }

    /**
     * Whether the message still plausibly contains what [described] warned about.
     *
     * This started as a prefix test — a verdict holds while the text still begins with the text
     * it described — which is correct in principle and useless against a real keyboard. Gboard
     * rewrites characters that have already been typed: "im" becomes "I'm", "st" becomes "St",
     * an autocorrect fixes a word three back. Every one of those breaks a prefix, so the verdict
     * was being dropped on ordinary typing and the overlay fell back to rules-only until the next
     * inference — which is precisely the blink this class exists to stop.
     *
     * So the test is length. Text that has not got shorter still contains a disclosure, whatever
     * the keyboard did to its surface form, and a stale verdict can only survive until the next
     * one lands a debounce later. Text that *has* got shorter is the case that matters: deleting
     * is how a warning is meant to be resolved, and that has to be believed immediately.
     */
    private fun covers(text: String, described: String): Boolean =
        text.length >= described.length

    companion object {
        /**
         * How long a level stays on screen before a less severe one may replace it.
         *
         * Long enough that a level survives a typing burst — the gate fires on a 350 ms pause, so
         * this covers roughly three of them — and short enough that a genuinely resolved warning
         * clears while the user is still looking at it.
         */
        const val MIN_HOLD_MS = 1_200L
    }
}
