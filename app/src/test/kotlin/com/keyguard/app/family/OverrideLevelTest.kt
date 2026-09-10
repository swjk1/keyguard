package com.keyguard.app.family

import com.keyguard.app.input.InputGate
import com.keyguard.detect.Category
import com.keyguard.detect.Finding
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import com.keyguard.detect.VerifyReason
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The permission levels, and the gate that enforces them.
 *
 * The cases worth writing here are the ones where a level and an existing invariant pull against
 * each other — crisis under the strictest level, an acknowledgement that outlives the permission
 * behind it — rather than the straight-line "NONE cannot dismiss" reading, which is a one-line
 * `when` and could hardly be wrong.
 */
class OverrideLevelTest {

    private fun result(severity: Severity, crisis: Boolean = false) = ScanResult(
        findings = listOf(
            Finding(
                start = 0,
                end = 4,
                category = if (crisis) Category.SELF_HARM else Category.PII_DISCLOSURE,
                severity = severity,
                ruleId = "test.rule",
                message = "test",
            ),
        ),
        maxSeverity = severity,
        eligibleForVerification = false,
        verifyReason = null as VerifyReason?,
        requiresCrisisResponse = crisis,
    )

    @Test
    fun `full permission may dismiss anything`() {
        for (severity in Severity.entries) {
            assertTrue(OverrideLevel.FULL.mayDismiss(severity), "FULL refused $severity")
        }
    }

    @Test
    fun `limited permission draws the line at high severity`() {
        assertTrue(OverrideLevel.LIMITED.mayDismiss(Severity.LOW))
        assertTrue(OverrideLevel.LIMITED.mayDismiss(Severity.MEDIUM))
        assertFalse(OverrideLevel.LIMITED.mayDismiss(Severity.HIGH))
    }

    @Test
    fun `no permission may dismiss nothing`() {
        for (severity in Severity.entries) {
            assertFalse(OverrideLevel.NONE.mayDismiss(severity), "NONE allowed $severity")
        }
    }

    @Test
    fun `the default is what an unsupervised keyboard has always done`() {
        // If this ever changes, pairing starts altering behaviour on its own — the exact thing
        // FamilyPolicy.DEFAULT is written to prevent.
        assertTrue(OverrideLevel.DEFAULT == OverrideLevel.FULL)
        assertTrue(FamilyPolicy.DEFAULT.overrideLevel == OverrideLevel.FULL)
    }

    @Test
    fun `an unrecognised level falls back to the permissive reading`() {
        // In the safe direction, matching every other field in FamilyPolicy: a parse bug that
        // quietly maximised restrictions would be discovered by a child locked out of their own
        // keyboard, which is the failure nobody reports and everybody uninstalls over.
        assertTrue(OverrideLevel.fromName("STRICTEST") == OverrideLevel.FULL)
        assertTrue(OverrideLevel.fromName(null) == OverrideLevel.FULL)
    }

    // region InputGate

    @Test
    fun `ignore is refused at the strictest level and the block holds`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false, overrideLevel = OverrideLevel.NONE)

        assertTrue(gate.isBlocked)
        assertTrue(gate.mustResolve, "the strip needs to know not to draw Ignore")
        assertFalse(gate.acknowledge(), "acknowledge must report that it was refused")
        assertTrue(gate.isBlocked, "a refused acknowledgement must leave the block in force")
    }

    @Test
    fun `ignore is accepted at full permission`() {
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false, overrideLevel = OverrideLevel.FULL)

        assertTrue(gate.isBlocked)
        assertFalse(gate.mustResolve)
        assertTrue(gate.acknowledge())
        assertFalse(gate.isBlocked)
    }

    @Test
    fun `removing the flagged content lifts the block at every level`() {
        // The exit that has to exist everywhere. Without it, OverrideLevel.NONE would be a
        // keyboard someone cannot type on and cannot get out of.
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false, overrideLevel = OverrideLevel.NONE)
        assertTrue(gate.isBlocked)

        gate.update(ScanResult.EMPTY, fieldProtected = false, overrideLevel = OverrideLevel.NONE)
        assertFalse(gate.isBlocked)
        assertFalse(gate.mustResolve)
    }

    @Test
    fun `a policy that tightens revokes an acknowledgement already made`() {
        // The timing attack on the override level: press Ignore, then have the parent tighten
        // the policy. If the flag survived, the child would ride out the rest of the message on
        // a permission that has been withdrawn.
        val gate = InputGate()
        gate.update(result(Severity.HIGH), fieldProtected = false, overrideLevel = OverrideLevel.FULL)
        assertTrue(gate.acknowledge())
        assertFalse(gate.isBlocked)

        gate.update(result(Severity.HIGH), fieldProtected = false, overrideLevel = OverrideLevel.NONE)
        assertTrue(gate.isBlocked, "a withdrawn permission must re-block")
        assertTrue(gate.mustResolve)
    }

    @Test
    fun `the crisis path is never blocked, at any level`() {
        // The invariant that outranks every parental control in the product. A parent tightening
        // the screws must not be able to take the keyboard away from a child who is trying to
        // say they are in trouble.
        for (level in OverrideLevel.entries) {
            val gate = InputGate()
            gate.update(
                result(Severity.HIGH, crisis = true),
                fieldProtected = false,
                overrideLevel = level,
            )
            assertFalse(gate.isBlocked, "crisis blocked at $level")
            assertFalse(gate.mustResolve, "crisis demanded resolution at $level")
        }
    }

    @Test
    fun `a protected field is never blocked, at any level`() {
        for (level in OverrideLevel.entries) {
            val gate = InputGate()
            gate.update(result(Severity.HIGH), fieldProtected = true, overrideLevel = level)
            assertFalse(gate.isBlocked, "password field blocked at $level")
        }
    }

    @Test
    fun `a parent who disabled blocking is not overruled by a strict override level`() {
        // Two settings that could plausibly be read as contradicting each other. blockAtHigh
        // decides whether the keys stop; overrideLevel decides who may un-stop them. A parent
        // who turned blocking off gets no block, however strict the level.
        val gate = InputGate()
        gate.update(
            result(Severity.HIGH),
            fieldProtected = false,
            blockingEnabled = false,
            overrideLevel = OverrideLevel.NONE,
        )
        assertFalse(gate.isBlocked)
    }

    // endregion
}
