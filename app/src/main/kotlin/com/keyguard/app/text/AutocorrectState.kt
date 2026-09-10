package com.keyguard.app.text

/**
 * Tracks a correction that was just applied automatically, so it can be undone.
 *
 * Revert-on-backspace is not a nicety. An autocorrect that cannot be taken back is the single
 * most infuriating behaviour a keyboard has, and it is the reason people disable the feature
 * outright — at which point the corrections stop helping anyone.
 *
 * Pure state, no Android types, so the ordering rules are directly testable.
 */
class AutocorrectState {

    /** A correction that has been applied and is still revertible. */
    data class Pending(
        /** What the user actually typed. */
        val original: String,
        /** What it was replaced with. */
        val replacement: String,
        /** Offset in the buffer where the replacement begins. */
        val start: Int,
    ) {
        val end: Int get() = start + replacement.length
    }

    private var pending: Pending? = null

    val revertible: Pending? get() = pending

    fun recordApplied(original: String, replacement: String, start: Int) {
        pending = Pending(original, replacement, start)
    }

    /**
     * Consumes the pending correction for a revert.
     *
     * @return the correction to undo, or null when there is nothing revertible.
     */
    fun consumeForRevert(): Pending? {
        val current = pending
        pending = null
        return current
    }

    /**
     * Called on any input that is not the immediate backspace following a correction. Once the
     * user has typed on, the correction is accepted and is no longer undoable — reverting it
     * later would silently rewrite text they have moved past.
     */
    fun invalidate() {
        pending = null
    }

    fun clear() = invalidate()
}
