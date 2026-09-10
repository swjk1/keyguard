package com.keyguard.app.input

import com.keyguard.detect.Category
import com.keyguard.detect.Finding
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The block is the only feature that can take the keyboard away from the user, so the exits
 * are asserted as closely as the entry condition. A regression here is not a missed warning —
 * it is a phone that cannot be typed on.
 */
class InputGateTest {

    private fun result(
        severity: Severity = Severity.HIGH,
        crisis: Boolean = false,
    ) = ScanResult(
        findings = if (severity == Severity.NONE) {
            emptyList()
        } else {
            listOf(
                Finding(
                    start = 0,
                    end = 5,
                    category = if (crisis) Category.SELF_HARM else Category.PII_DISCLOSURE,
                    severity = severity,
                    ruleId = "test.rule",
                    message = "test",
                    contextDependent = false,
                ),
            )
        },
        maxSeverity = severity,
        eligibleForVerification = false,
        verifyReason = null,
        requiresCrisisResponse = crisis,
    )

    @Test
    fun `high severity blocks input`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false)
        assertTrue(gate.isBlocked)
    }

    @Test
    fun `starts unblocked`() {
        assertFalse(InputGate().isBlocked)
    }

    @Test
    fun `severities below high stay advisory`() {
        for (severity in listOf(Severity.NONE, Severity.LOW, Severity.MEDIUM)) {
            val gate = InputGate()
            gate.update(result(severity), fieldProtected = false)
            assertFalse(gate.isBlocked, "$severity must not block input")
        }
    }

    @Test
    fun `protected fields never block`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = true)
        assertFalse(gate.isBlocked)
    }

    /**
     * Locking someone out of their keyboard because they typed about hurting themselves is
     * the opposite of what that path owes them.
     */
    @Test
    fun `crisis findings never block`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH, crisis = true), fieldProtected = false)
        assertFalse(gate.isBlocked)
    }

    @Test
    fun `acknowledging lifts the block`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false)
        gate.acknowledge()
        assertFalse(gate.isBlocked)
    }

    /**
     * The whole point of the override. If a rescan re-blocked, Ignore would buy exactly one
     * keystroke — the strip's own `dismissed` flag is the one that typing re-arms, not this.
     */
    @Test
    fun `acknowledgement survives further scans of the same content`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false)
        gate.acknowledge()

        repeat(5) { gate.update(result(Severity.HIGH), fieldProtected = false) }

        assertFalse(gate.isBlocked, "typing on after Ignore must not re-block")
    }

    /** Deleting the flagged text is the other exit, and it needs no button press. */
    @Test
    fun `resolving the content lifts the block`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false)
        gate.update(result(Severity.NONE), fieldProtected = false)
        assertFalse(gate.isBlocked)
    }

    /** A downgrade by AI verification is a legitimate way for a block to end. */
    @Test
    fun `downgrade to medium lifts the block`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false)
        gate.update(result(Severity.MEDIUM), fieldProtected = false)
        assertFalse(gate.isBlocked)
    }

    /**
     * A stale Ignore must not license everything typed afterwards. Once the flagged content
     * is gone the override goes with it, so fresh high-severity content is judged afresh.
     */
    @Test
    fun `override does not carry across to new high severity content`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false)
        gate.acknowledge()

        gate.update(result(Severity.NONE), fieldProtected = false)
        gate.update(result(Severity.HIGH), fieldProtected = false)

        assertTrue(gate.isBlocked, "a new high-severity finding must block on its own terms")
    }

    @Test
    fun `reset clears both the block and the override`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false)
        gate.acknowledge()
        gate.reset()

        assertFalse(gate.isBlocked)
        gate.update(result(Severity.HIGH), fieldProtected = false)
        assertTrue(gate.isBlocked, "a new field must not inherit the previous field's Ignore")
    }

    // region parental policy

    @Test
    fun `a parent can switch blocking off entirely`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false, blockingEnabled = false)
        assertFalse(gate.isBlocked, "the policy said not to stop the keys")
    }

    @Test
    fun `switching blocking off releases a block already in force`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false, blockingEnabled = true)
        assertTrue(gate.isBlocked)

        // A policy landing mid-sentence must give the keys back rather than wait for the
        // flagged text to be resolved.
        gate.update(result(Severity.HIGH), fieldProtected = false, blockingEnabled = false)
        assertFalse(gate.isBlocked)
    }

    @Test
    fun `blocking defaults to on, matching an unsupervised keyboard`() {
        assertTrue(InputGate.qualifies(result(Severity.HIGH), fieldProtected = false))
    }

    @Test
    fun `the protected-field and crisis exemptions still win when blocking is on`() {
        assertFalse(
            InputGate.qualifies(result(Severity.HIGH), fieldProtected = true, blockingEnabled = true),
        )
        assertFalse(
            InputGate.qualifies(
                result(Severity.HIGH, crisis = true),
                fieldProtected = false,
                blockingEnabled = true,
            ),
            "no policy may make the crisis path confiscate the keyboard",
        )
    }

    // endregion
}
