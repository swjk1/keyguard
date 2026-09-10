package com.keyguard.app.keyboard

import com.keyguard.detect.Category
import com.keyguard.detect.DetectionEngine
import com.keyguard.detect.Finding
import com.keyguard.detect.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlighterTest {

    private fun finding(
        start: Int,
        end: Int,
        severity: Severity = Severity.MEDIUM,
    ) = Finding(
        start = start,
        end = end,
        category = Category.PII_DISCLOSURE,
        severity = severity,
        ruleId = "test",
        message = "test",
    )

    @Test
    fun `highlights the flagged span`() {
        val text = "call me at 555-123-4567"
        val plan = Highlighter.plan(text, listOf(finding(11, 23)))

        assertEquals(text, plan.text)
        val span = plan.spans.single()
        assertEquals("555-123-4567", text.substring(span.start, span.end))
    }

    @Test
    fun `marks high severity spans distinctly`() {
        val plan = Highlighter.plan(
            "my address is 123 Main Street",
            listOf(finding(14, 29, Severity.HIGH)),
        )
        assertTrue(plan.spans.single().high)
    }

    @Test
    fun `severity zero findings are not highlighted`() {
        // Severity 0 exists to route an uncertain case to AI verification, not to mark up
        // the user's text.
        val plan = Highlighter.plan("you are such a loser", listOf(finding(15, 20, Severity.NONE)))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `no findings means nothing to show`() {
        assertTrue(Highlighter.plan("perfectly ordinary message", emptyList()).isEmpty)
        assertTrue(Highlighter.plan("", listOf(finding(0, 1))).isEmpty)
    }

    @Test
    fun `long text is trimmed from the start and offsets follow`() {
        val filler = "a".repeat(300)
        val secret = "555-123-4567"
        val text = "$filler $secret"
        val start = text.indexOf(secret)

        val plan = Highlighter.plan(text, listOf(finding(start, start + secret.length)))

        assertTrue(plan.text.startsWith(Highlighter.ELLIPSIS), "trimmed preview must be marked")
        assertTrue(
            plan.text.length <= Highlighter.MAX_PREVIEW_CHARS + Highlighter.ELLIPSIS.length,
            "preview must stay bounded, was ${plan.text.length}",
        )

        val span = plan.spans.single()
        assertEquals(
            secret,
            plan.text.substring(span.start, span.end),
            "the shifted span must still cover the flagged text after trimming",
        )
    }

    @Test
    fun `trimming never clips a highlight in half`() {
        // A finding near the beginning of a long buffer would fall outside a naive tail
        // window. Showing half a highlight looks like a rendering bug, so the window has to
        // stretch back to include it.
        val secret = "555-123-4567"
        val text = secret + "b".repeat(400)

        val plan = Highlighter.plan(text, listOf(finding(0, secret.length)))
        val span = plan.spans.single()
        assertEquals(secret, plan.text.substring(span.start, span.end))
    }

    @Test
    fun `multiple findings all get spans`() {
        val text = "email a@b.co and call 555-123-4567"
        val plan = Highlighter.plan(
            text,
            listOf(finding(6, 12), finding(22, 34, Severity.HIGH)),
        )
        assertEquals(2, plan.spans.size)
        assertTrue(plan.spans.any { it.high })
    }

    @Test
    fun `spans stay inside the preview text`() {
        // Guards against an out-of-range span reaching the Spannable, which would throw and
        // take down the keyboard while the user is typing.
        val engine = DetectionEngine.withBundledPack()
        val samples = listOf(
            "my address is 123 Main Street",
            "send nudes",
            "call me at 555-123-4567 or email a@b.com",
            "x".repeat(500) + " my password is hunter2",
            "im 14 and i live in a small town",
        )

        for (sample in samples) {
            val plan = Highlighter.plan(sample, engine.scan(sample).findings)
            for (span in plan.spans) {
                assertTrue(
                    span.start in 0..span.end && span.end <= plan.text.length,
                    "span ${span.start}..${span.end} outside preview of length " +
                        "${plan.text.length} for \"$sample\"",
                )
            }
        }
    }
}
