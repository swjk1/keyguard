package com.keyguard.app.family

import com.keyguard.detect.Severity

/**
 * How much authority the child has over a warning their keyboard raises.
 *
 * The product has always had exactly one answer to "the user disagrees with this warning": a
 * button that makes it go away. That is the right default for an adult using a safety keyboard
 * on their own phone, and it is not obviously the right one for a nine-year-old whose parent
 * installed it — which is the gap this closes.
 *
 * The levels are named for what the *child* can do, not for how strict the parent is being,
 * because that is the question every call site is actually asking. A screen that needs to know
 * whether to draw an Ignore button should not have to reason backwards from "strict".
 *
 * **[FULL] is the default and stays the default.** A policy sets a floor, not a value (see
 * [FamilyPolicy]), and pairing on its own must not change how anyone's keyboard behaves —
 * an unsupervised device and a freshly paired one both resolve to [FULL], which is exactly
 * what the keyboard did before any of this existed.
 */
enum class OverrideLevel {
    /**
     * High permission: any warning can be dismissed and typing continues.
     *
     * What an unsupervised keyboard has always done. The warning has said its piece; what
     * happens next is the person's own call.
     */
    FULL,

    /**
     * Medium: soft warnings can be dismissed, high-severity ones must be dealt with.
     *
     * The rung most families will actually want, and the one that keeps the product usable.
     * Dismissing a nudge about the word "loser" is not the behaviour anybody was worried
     * about; sending a home address to a stranger is.
     */
    LIMITED,

    /**
     * Low permission: nothing can be dismissed. The only exit is removing the flagged content.
     *
     * The keys stay refused until the text is gone, and the warning offers *Remove it* with no
     * Ignore beside it. Two things this deliberately does **not** do:
     *
     * - **It never deletes on its own.** "Force delete" is a button the child presses, not
     *   something that happens to them mid-sentence. Silently eating what someone typed —
     *   including the ninety per cent of the message that was fine — is how a tool teaches
     *   people to compose somewhere else and paste in, which defeats the whole product.
     * - **It never takes the crisis path.** See [FamilyPolicy] and [InputGate]: a child
     *   writing about hurting themselves keeps their keyboard at every level, because the
     *   alternative is confiscating someone's ability to reach out mid-sentence.
     */
    NONE,
    ;

    /**
     * Whether the child may dismiss a warning of this severity and carry on typing.
     *
     * Severity is passed in rather than assumed, which is what makes [LIMITED] expressible at
     * all — it is the only level whose answer depends on the finding.
     */
    fun mayDismiss(severity: Severity): Boolean = when (this) {
        FULL -> true
        LIMITED -> severity.level < Severity.HIGH.level
        NONE -> false
    }

    /**
     * Whether the strip should show an Ignore button at all for this severity.
     *
     * The same question as [mayDismiss], named for the UI that asks it. A button that is drawn
     * and then does nothing is worse than an absent one: it reads as a bug, and the child taps
     * it repeatedly instead of finding the exit that works.
     */
    fun showsDismiss(severity: Severity): Boolean = mayDismiss(severity)

    companion object {
        /** What both an unsupervised device and a freshly paired one use. */
        val DEFAULT = FULL

        fun fromName(name: String?): OverrideLevel =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
