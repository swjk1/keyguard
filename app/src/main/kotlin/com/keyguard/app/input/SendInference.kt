package com.keyguard.app.input

/**
 * How a composition ended.
 *
 * The meeting concluded this was undecidable and settled on "assume sent, accept the
 * losses". It isn't undecidable. Because the keyboard sees every key it handles, an empty
 * buffer that we emptied looks nothing like an empty buffer the host app emptied.
 */
enum class ComposeOutcome {
    /** The user pressed our own action key (SEND/GO/DONE). Highest confidence. */
    SENT,

    /**
     * The field was cleared in one step with no delete key from us — which is what happens
     * when the host app clears its input on send. High confidence.
     */
    SENT_INFERRED,

    /** The user backspaced the text away, including select-all-then-delete. */
    ABANDONED_DELETED,

    /** Focus left the field while text was still present. */
    ABANDONED_SWITCHED,
}

/** What last changed the buffer. The distinction is the entire basis of the inference. */
enum class Mutation {
    /** We committed text. */
    INSERT,

    /** Our delete key removed text. */
    OUR_DELETE,

    /** Our action key was pressed. */
    ACTION_KEY,

    /** The buffer changed without us doing it — a paste, a host-side clear, cursor moves. */
    EXTERNAL,
}

/**
 * Infers whether composed text was sent or abandoned.
 *
 * Deliberately free of Android imports so the whole decision table is unit-testable without
 * a device. The IME feeds it mutations; it emits an outcome when a composition ends.
 */
class SendInference {

    private var length = 0
    private var lastMutation: Mutation = Mutation.EXTERNAL
    private var everHadContent = false

    val currentLength: Int get() = length
    val hasContent: Boolean get() = length > 0

    /**
     * Records a buffer mutation and returns an outcome if this ended a composition.
     *
     * @param mutation what caused the change
     * @param newLength buffer length after the change
     */
    fun record(mutation: Mutation, newLength: Int): ComposeOutcome? {
        val previousLength = length
        length = newLength

        if (mutation == Mutation.ACTION_KEY) {
            // The action key fires while text is still present; the host clears the field
            // afterwards. Report the send now and swallow the clear that follows.
            lastMutation = Mutation.ACTION_KEY
            return if (previousLength > 0) {
                everHadContent = false
                ComposeOutcome.SENT
            } else {
                null
            }
        }

        if (newLength > 0) {
            everHadContent = true
            lastMutation = mutation
            return null
        }

        // Buffer is now empty. If it previously held something, a composition just ended.
        val endedComposition = previousLength > 0 || everHadContent
        val previousMutation = lastMutation
        lastMutation = mutation
        if (!endedComposition) return null

        everHadContent = false

        return when {
            // We just consumed a send; the host clearing the field is the echo of it.
            previousMutation == Mutation.ACTION_KEY -> null

            // We emptied it ourselves. Covers both held-backspace and select-all-delete:
            // a selection wipe still arrives as our delete key.
            mutation == Mutation.OUR_DELETE -> ComposeOutcome.ABANDONED_DELETED

            // Cleared out from under us with no delete key involved. The host app cleared
            // its input field, which is what sending a message does.
            else -> ComposeOutcome.SENT_INFERRED
        }
    }

    /**
     * Focus left the field. Returns an outcome if text was still pending, since abandoning
     * a half-typed message is a different signal from sending it.
     */
    fun onFinishInput(): ComposeOutcome? {
        val outcome = if (length > 0) ComposeOutcome.ABANDONED_SWITCHED else null
        reset()
        return outcome
    }

    fun reset() {
        length = 0
        lastMutation = Mutation.EXTERNAL
        everHadContent = false
    }
}
