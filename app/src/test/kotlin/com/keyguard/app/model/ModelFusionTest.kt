package com.keyguard.app.model

import com.keyguard.detect.Category
import com.keyguard.detect.Finding
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import com.keyguard.detect.VerifyReason
import com.keyguard.infer.EntitySpan
import com.keyguard.infer.ModelVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The model may add to what the rules decided. It may never subtract from it.
 *
 * Every test here is about that asymmetry, because it is the property the whole layering rests
 * on: the rule engine is the product's floor and has to stand alone, so a model that is wrong —
 * and the current one is wrong often, with 0.349 Level 3 recall on the human gold set — must not
 * be able to make the local layer worse than it was without it.
 */
class ModelFusionTest {

    private fun verdict(
        level: Int,
        spans: List<EntitySpan> = emptyList(),
        fired: List<String> = listOf("risk.unsupervised_window"),
    ) = ModelVerdict(
        level = level,
        fired = fired,
        message = "You're saying where you are and that you're on your own.",
        activeSignals = setOf("child_ownership", "alone"),
        probabilities = FloatArray(8),
        spans = spans,
        tokenCount = 11,
        elapsedMillis = 7,
    )

    private fun rules(
        severity: Severity,
        crisis: Boolean = false,
        findings: List<Finding> = emptyList(),
    ) = ScanResult(
        findings = findings.ifEmpty {
            if (severity == Severity.NONE) emptyList()
            else listOf(Finding(0, 4, Category.PII_DISCLOSURE, severity, "rule.x", "m"))
        },
        maxSeverity = severity,
        eligibleForVerification = true,
        verifyReason = VerifyReason.AMBIGUOUS,
        requiresCrisisResponse = crisis,
    )

    @Test
    fun `a higher model level raises the result`() {
        val merged = ModelFusion.merge(rules(Severity.LOW), verdict(3), textLength = 20)
        assertEquals(Severity.HIGH, merged.maxSeverity)
        assertEquals(2, merged.findings.size, "the rule finding must survive alongside the model's")
    }

    @Test
    fun `an equal model level changes nothing`() {
        val before = rules(Severity.MEDIUM)
        assertSame(before, ModelFusion.merge(before, verdict(2), textLength = 20))
    }

    @Test
    fun `a lower model level changes nothing`() {
        val before = rules(Severity.HIGH)
        assertSame(before, ModelFusion.merge(before, verdict(1), textLength = 20))
    }

    @Test
    fun `a level zero verdict changes nothing`() {
        val before = rules(Severity.NONE)
        assertSame(before, ModelFusion.merge(before, verdict(0), textLength = 20))
    }

    @Test
    fun `the crisis path is never joined by a privacy warning`() {
        // Someone writing about hurting themselves is offered help. A model that is confident
        // they also disclosed an address must not turn that into a data-safety warning, and
        // must not be able to shade their keyboard.
        val before = rules(Severity.LOW, crisis = true)
        assertSame(before, ModelFusion.merge(before, verdict(3), textLength = 20))
    }

    @Test
    fun `verification eligibility is left exactly as the rules set it`() {
        val before = rules(Severity.LOW)
        val merged = ModelFusion.merge(before, verdict(3), textLength = 20)
        assertEquals(before.eligibleForVerification, merged.eligibleForVerification)
        assertEquals(before.verifyReason, merged.verifyReason)
        assertEquals(before.requiresCrisisResponse, merged.requiresCrisisResponse)
    }

    @Test
    fun `the finding points at the entities the model found`() {
        val text = "i live at 24 oak street"
        val merged = ModelFusion.merge(
            rules(Severity.NONE),
            verdict(2, spans = listOf(EntitySpan("STREET", 10..22, "exact_location"))),
            textLength = text.length,
        )
        val finding = merged.findings.single()
        assertEquals("24 oak street", text.substring(finding.start, finding.end))
    }

    @Test
    fun `with no entity to point at, the finding is the whole message`() {
        // "nobody's home till 9" has no substring that carries the risk on its own, so Remove it
        // clears the message. That is the honest span rather than a convenient one.
        val text = "nobody is home right now"
        val merged = ModelFusion.merge(rules(Severity.NONE), verdict(3), textLength = text.length)
        val finding = merged.findings.single()
        assertEquals(0, finding.start)
        assertEquals(text.length, finding.end)
    }

    @Test
    fun `the finding names the rule that actually fired`() {
        val merged = ModelFusion.merge(
            rules(Severity.NONE),
            verdict(3, fired = listOf("risk.address_alone", "risk.unsupervised_window")),
            textLength = 20,
        )
        assertEquals("risk.address_alone", merged.findings.single().ruleId)
    }

    @Test
    fun `spans outside the text are ignored rather than crashing`() {
        // The model ran on text that has since changed. The service guards against this, but a
        // Finding constructor that throws would take the accessibility service down with it.
        val merged = ModelFusion.merge(
            rules(Severity.NONE),
            verdict(3, spans = listOf(EntitySpan("STREET", 90..140, "exact_location"))),
            textLength = 10,
        )
        val finding = merged.findings.single()
        assertTrue(finding.end <= 10, "finding ran past the text: $finding")
    }

    @Test
    fun `an empty field is never flagged`() {
        val before = rules(Severity.NONE)
        assertSame(before, ModelFusion.merge(before, verdict(3), textLength = 0))
    }
}
