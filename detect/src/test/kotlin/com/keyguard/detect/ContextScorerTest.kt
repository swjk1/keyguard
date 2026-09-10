package com.keyguard.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Layer C is the part that justifies the product's claim to understand context rather than
 * just match words, so its escalation behaviour is asserted directly.
 */
class ContextScorerTest {

    private val engine = DetectionEngine.withBundledPack()

    @Test
    fun `individually mild signals escalate when they co-occur`() {
        val mild = engine.scan("my snap is coolkid99")
        assertTrue(
            mild.maxSeverity.level < Severity.HIGH.level,
            "sharing a handle alone should not be HIGH, was ${mild.maxSeverity}",
        )

        val combined = engine.scan("come over sometime, my snap is coolkid99")
        assertEquals(
            Severity.HIGH,
            combined.maxSeverity,
            "a handle shared alongside a meet-up request is the pattern worth interrupting",
        )
    }

    @Test
    fun `earlier concerning context escalates a later disclosure`() {
        val context = RollingContext()

        // Turn one establishes a solicitation signal in the window.
        engine.scan("dont tell your parents about this", context, nowMs = 0L)

        // Turn two is a disclosure that would otherwise be MEDIUM.
        val later = engine.scan("my name is Priya", context, nowMs = 5_000L)
        assertEquals(
            Severity.HIGH,
            later.maxSeverity,
            "a disclosure moments after a grooming signal must escalate",
        )
    }

    @Test
    fun `context expires so old signals stop escalating`() {
        val context = RollingContext(maxAgeMs = 60_000)
        engine.scan("dont tell your parents about this", context, nowMs = 0L)

        val muchLater = engine.scan("my name is Priya", context, nowMs = 10 * 60_000L)
        assertTrue(
            muchLater.maxSeverity.level < Severity.HIGH.level,
            "a signal from ten minutes ago should no longer escalate, was ${muchLater.maxSeverity}",
        )
    }

    @Test
    fun `clearing context prevents leakage across input fields`() {
        val context = RollingContext()
        engine.scan("dont tell your parents about this", context, nowMs = 0L)
        context.clear()

        val afterClear = engine.scan("my name is Priya", context, nowMs = 1_000L)
        assertTrue(
            afterClear.maxSeverity.level < Severity.HIGH.level,
            "context must not survive a field change",
        )
    }

    @Test
    fun `uncorroborated speculative hits ask for AI verification`() {
        // "loser" is severity 0 and context dependent: it renders nothing, but the local
        // rules genuinely cannot tell banter from bullying, which is what the AI is for.
        val result = engine.scan("you are such a loser")
        assertEquals(Severity.NONE, result.maxSeverity, "must not warn on its own")
        assertTrue(result.eligibleForVerification)
        assertEquals(VerifyReason.AMBIGUOUS, result.verifyReason)
    }

    @Test
    fun `high severity findings request escalation`() {
        val result = engine.scan("my address is 123 Main Street")
        assertEquals(Severity.HIGH, result.maxSeverity)
        assertEquals(VerifyReason.ESCALATE, result.verifyReason)
    }

    @Test
    fun `clean text is not eligible for verification`() {
        val result = engine.scan("what time does practice start")
        assertEquals(ScanResult.EMPTY, result)
        assertFalse(result.eligibleForVerification)
    }

    @Test
    fun `overlapping findings in one category collapse to the most severe`() {
        val result = engine.scan("my address is 123 Main Street")
        val overlapping = result.findings.filter { it.category == Category.PII_DISCLOSURE }
        for (a in overlapping) {
            for (b in overlapping) {
                if (a !== b) {
                    assertFalse(a.overlaps(b), "same-category findings must not overlap: $a vs $b")
                }
            }
        }
    }

    @Test
    fun `hyperbole is suppressed but genuine ideation is not`() {
        // The pairs below differ only by a nearby word. Suppression has to be narrow enough
        // that stripping the hyperbole cue brings the finding straight back.
        val suppressed = listOf(
            "this heat makes me want to die",
            "i want to die of embarrassment",
            "that made me want to die laughing",
            "i keep cutting myself shaving",
            "i could kill myself for forgetting the tickets",
        )
        for (text in suppressed) {
            val result = engine.scan(text)
            assertFalse(
                result.requiresCrisisResponse,
                "\"$text\" is figurative and must not trigger a crisis response",
            )
        }

        val genuine = listOf(
            "i want to die",
            "i have been cutting myself again",
            "i want to kill myself",
        )
        for (text in genuine) {
            val result = engine.scan(text)
            assertTrue(
                result.requiresCrisisResponse,
                "\"$text\" must still reach the crisis path",
            )
        }
    }

    @Test
    fun `suppressors are scoped to their own category`() {
        // "paper" suppresses the cutting rule. It must not silence an unrelated disclosure in
        // a sentence that happens to mention paper.
        val result = engine.scan("i wrote my address on paper: 123 Main Street")
        assertEquals(Severity.HIGH, result.maxSeverity)
        assertTrue(result.findings.any { it.category == Category.PII_DISCLOSURE })
    }

    @Test
    fun `suppressors respect their distance window`() {
        // The cue is far away, so it should no longer be read as qualifying the phrase.
        val far = "i want to die. " + "x".repeat(120) + " anyway that was embarrassing"
        assertTrue(
            engine.scan(far).requiresCrisisResponse,
            "a cue 120 characters away must not suppress a serious finding",
        )
    }

    @Test
    fun `rolling context snapshot stays bounded`() {
        val context = RollingContext(maxContextChars = 100)
        repeat(50) { context.recordCommittedText("message number $it padded out a bit", nowMs = it.toLong()) }
        assertTrue(
            context.contextSnapshot().length <= 100,
            "AI payload context must stay capped, was ${context.contextSnapshot().length}",
        )
    }
}
