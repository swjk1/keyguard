package com.keyguard.app.model

import com.keyguard.infer.ModelVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The overlay must not blink.
 *
 * Each test here is a flicker that was observed or is one edit away from being observed: the
 * warning dropping to nothing on every keystroke, the block turning on and off as a probability
 * wobbles around a threshold, a stale verdict outliving the text it described.
 */
class VerdictStabilizerTest {

    private var clock = 0L
    private val stabilizer = VerdictStabilizer(minHoldMs = 1_000L, now = { clock })

    private fun verdict(level: Int) = ModelVerdict(
        level = level,
        fired = listOf("risk.unsupervised_window"),
        message = "m",
        activeSignals = emptySet(),
        probabilities = FloatArray(8),
        spans = emptyList(),
        tokenCount = 8,
        elapsedMillis = 50,
    )

    private fun levelFor(text: String): Int? = stabilizer.stable(text)?.level

    @Test
    fun `a verdict survives the next keystroke`() {
        // The bug this whole class exists for. The rules re-run synchronously on every character
        // while the model takes a few hundred milliseconds, so if a keystroke cleared the verdict
        // the overlay would cycle block, warning, block on every letter typed.
        stabilizer.accept("im home alone", verdict(3))
        assertEquals(3, levelFor("im home alone"))

        assertEquals(3, levelFor("im home alone "), "a space wiped the verdict")
        assertEquals(3, levelFor("im home alone at 24 oak st"), "appending wiped the verdict")
    }

    @Test
    fun `escalation is immediate`() {
        stabilizer.accept("a", verdict(1))
        assertEquals(1, levelFor("a"))

        clock += 10
        stabilizer.accept("a b", verdict(3))
        assertEquals(3, levelFor("a b"), "a more severe level must show at once")
    }

    @Test
    fun `de-escalation waits for the hold window`() {
        stabilizer.accept("a", verdict(3))
        assertEquals(3, levelFor("a"))

        clock += 100
        stabilizer.accept("a b", verdict(1))
        assertEquals(3, levelFor("a b"), "dropped the block after 100ms")

        clock += 400
        assertEquals(3, levelFor("a b"), "dropped the block after 500ms")

        clock += 600 // now past the 1000ms hold
        assertEquals(1, levelFor("a b"), "never dropped the block")
    }

    @Test
    fun `an oscillating model holds at the higher level rather than blinking`() {
        // meetup ships at 0.975 and alone at 0.95, so a probability parked near one flips on a
        // single extra character. Alternating verdicts must not alternate the UI.
        stabilizer.accept("t", verdict(3))
        assertEquals(3, levelFor("t"))

        repeat(6) { step ->
            clock += 300
            val text = "t" + "x".repeat(step + 1)
            stabilizer.accept(text, verdict(if (step % 2 == 0) 1 else 3))
            assertEquals(3, levelFor(text), "flickered on oscillation step $step")
        }
    }

    @Test
    fun `deleting clears the verdict at once`() {
        // Deleting is how a warning is meant to be resolved. Holding a stale block for a second
        // while someone is actively trying to clear it would make the app feel broken, and is the
        // one direction where responsiveness beats steadiness.
        stabilizer.accept("im home alone at 24 oak st", verdict(3))
        assertEquals(3, levelFor("im home alone at 24 oak st"))

        assertNull(levelFor("im home alone at 24 oak"), "a stale verdict outlived the deletion")
    }

    @Test
    fun `autocorrect rewriting earlier characters does not drop the verdict`() {
        // The failure that made the overlay blink on a real device: Gboard turns "im" into
        // "I'm" and "st" into "St" after the fact, so a prefix test fails on ordinary typing
        // and the warning falls back to rules-only until the next inference.
        stabilizer.accept("im home alone at 24 oak st", verdict(3))
        assertEquals(3, levelFor("I'm home alone at 24 oak St"))
    }

    @Test
    fun `text that grew but changed keeps the verdict only until the next one lands`() {
        // Replacing the message with something longer holds the old verdict briefly. That is
        // accepted: the model re-runs a debounce later, and the alternative is dropping the
        // verdict on every autocorrect.
        stabilizer.accept("im home alone", verdict(3))
        assertEquals(3, levelFor("something else entirely and longer"))

        clock += 2_000
        stabilizer.accept("something else entirely and longer", verdict(0))
        assertEquals(0, levelFor("something else entirely and longer"))
    }

    @Test
    fun `nothing is shown before the first verdict`() {
        assertNull(levelFor("anything"))
    }

    @Test
    fun `a pending de-escalation asks to be re-evaluated`() {
        stabilizer.accept("a", verdict(3))
        levelFor("a")
        assertEquals(0, stabilizer.pendingSettleMs(), "nothing pending yet")

        clock += 200
        stabilizer.accept("a b", verdict(0))
        levelFor("a b")

        val remaining = stabilizer.pendingSettleMs()
        assertTrue(remaining in 1..1_000, "expected a settle delay, got $remaining")

        clock += remaining
        assertEquals(0, levelFor("a b"), "the downgrade never landed")
        assertEquals(0, stabilizer.pendingSettleMs())
    }

    @Test
    fun `dropping to zero is a de-escalation like any other`() {
        // The "no warning to warning and back" variant. Hiding the overlay is the most visible
        // transition there is, so it gets the same hold as any other downgrade.
        stabilizer.accept("a", verdict(1))
        assertEquals(1, levelFor("a"))

        clock += 100
        stabilizer.accept("a b", verdict(0))
        assertEquals(1, levelFor("a b"), "the overlay vanished after 100ms")

        clock += 1_000
        assertEquals(0, levelFor("a b"))
    }

    @Test
    fun `reset forgets everything`() {
        stabilizer.accept("a", verdict(3))
        assertEquals(3, levelFor("a"))
        stabilizer.reset()
        assertNull(levelFor("a"), "a verdict survived a field change")
    }

    @Test
    fun `the hold is measured from when a level reached the screen, not when it arrived`() {
        stabilizer.accept("a", verdict(3))
        clock += 5_000 // it sat unread in `latest` for five seconds
        assertEquals(3, levelFor("a"), "first display")

        clock += 100
        stabilizer.accept("a b", verdict(1))
        assertEquals(3, levelFor("a b"), "hold started when it was displayed, not when accepted")
    }
}
