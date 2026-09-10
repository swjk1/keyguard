package com.keyguard.app.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The decision table the planning meeting concluded was impossible.
 *
 * The claim there was that a keyboard cannot tell a sent message from a deleted one, so the
 * only option was to assume "sent" and accept the false positives. These tests demonstrate
 * otherwise: the keyboard sees its own delete key, so an empty buffer it emptied is
 * distinguishable from one the host app emptied. Including the select-all-then-delete case
 * that was specifically argued about.
 */
class SendInferenceTest {

    private fun typing(inference: SendInference, text: String) {
        text.forEachIndexed { index, _ ->
            inference.record(Mutation.INSERT, index + 1)
        }
    }

    @Test
    fun `host clearing the field without our delete key means sent`() {
        val inference = SendInference()
        typing(inference, "hello there")

        // An in-app send button clears the input. We never saw a delete key.
        val outcome = inference.record(Mutation.EXTERNAL, 0)
        assertEquals(ComposeOutcome.SENT_INFERRED, outcome)
    }

    @Test
    fun `our action key means sent with high confidence`() {
        val inference = SendInference()
        typing(inference, "hello")

        val outcome = inference.record(Mutation.ACTION_KEY, 5)
        assertEquals(ComposeOutcome.SENT, outcome)
    }

    @Test
    fun `the host clear following our action key is not double counted`() {
        val inference = SendInference()
        typing(inference, "hello")
        assertEquals(ComposeOutcome.SENT, inference.record(Mutation.ACTION_KEY, 5))

        // The host clears its field right after; that is the echo of the send we already
        // reported, not a second composition ending.
        assertNull(inference.record(Mutation.EXTERNAL, 0))
    }

    @Test
    fun `held backspace to empty means deleted`() {
        val inference = SendInference()
        typing(inference, "oops")

        var outcome: ComposeOutcome? = null
        for (remaining in 3 downTo 0) {
            outcome = inference.record(Mutation.OUR_DELETE, remaining)
        }
        assertEquals(ComposeOutcome.ABANDONED_DELETED, outcome)
    }

    @Test
    fun `select all then delete means deleted not sent`() {
        // This is the exact case the meeting argued was indistinguishable from sending, and
        // concluded should be treated as sent. It is distinguishable: wiping a selection
        // still arrives through our delete key.
        val inference = SendInference()
        typing(inference, "a long message the user thought better of")

        val outcome = inference.record(Mutation.OUR_DELETE, 0)
        assertEquals(ComposeOutcome.ABANDONED_DELETED, outcome)
    }

    @Test
    fun `backspacing partway then sending still counts as sent`() {
        // A naive "did we ever see a delete" rule gets this wrong and reports the message as
        // abandoned. Only the mutation that actually emptied the buffer matters.
        val inference = SendInference()
        typing(inference, "helllo")
        inference.record(Mutation.OUR_DELETE, 5)
        typing(inference, "!")

        val outcome = inference.record(Mutation.EXTERNAL, 0)
        assertEquals(ComposeOutcome.SENT_INFERRED, outcome)
    }

    @Test
    fun `leaving the field with text pending is abandonment not sending`() {
        val inference = SendInference()
        typing(inference, "half a thought")

        assertEquals(ComposeOutcome.ABANDONED_SWITCHED, inference.onFinishInput())
    }

    @Test
    fun `leaving an empty field reports nothing`() {
        val inference = SendInference()
        assertNull(inference.onFinishInput())
    }

    @Test
    fun `typing does not end a composition`() {
        val inference = SendInference()
        repeat(5) { assertNull(inference.record(Mutation.INSERT, it + 1)) }
    }

    @Test
    fun `edits in an already empty field report nothing`() {
        val inference = SendInference()
        assertNull(inference.record(Mutation.EXTERNAL, 0))
        assertNull(inference.record(Mutation.OUR_DELETE, 0))
    }

    @Test
    fun `consecutive compositions are tracked independently`() {
        val inference = SendInference()

        typing(inference, "first")
        assertEquals(ComposeOutcome.SENT_INFERRED, inference.record(Mutation.EXTERNAL, 0))

        typing(inference, "second")
        assertEquals(ComposeOutcome.ABANDONED_DELETED, inference.record(Mutation.OUR_DELETE, 0))

        typing(inference, "third")
        assertEquals(ComposeOutcome.SENT_INFERRED, inference.record(Mutation.EXTERNAL, 0))
    }
}
