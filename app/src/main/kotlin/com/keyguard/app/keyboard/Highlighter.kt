package com.keyguard.app.keyboard

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.keyguard.detect.Finding
import com.keyguard.detect.Severity

/** One span to highlight, already resolved against the trimmed preview text. */
data class HighlightSpan(val start: Int, val end: Int, val high: Boolean)

/** What to render: the possibly-trimmed text plus spans located against it. */
data class HighlightPlan(val text: String, val spans: List<HighlightSpan>) {
    val isEmpty: Boolean get() = spans.isEmpty()

    companion object {
        val EMPTY = HighlightPlan("", emptyList())
    }
}

/**
 * Renders the user's text with flagged spans highlighted.
 *
 * The highlight lives in the keyboard's own strip rather than in the host app's input field,
 * because an IME cannot reliably style committed text in another app — hosts drop spans, and
 * composing-region styling covers only a transient region and risks corrupting input. Echoing
 * the text back with the problem parts marked gets the same message across using a surface we
 * actually control.
 *
 * [plan] holds the offset arithmetic and is deliberately free of Android types so it can be
 * unit-tested; [apply] is the thin shim that turns a plan into spans.
 */
object Highlighter {

    /**
     * Longest preview to render. Long enough for a typical message, short enough that the
     * strip cannot grow tall enough to crowd out the keys.
     */
    const val MAX_PREVIEW_CHARS: Int = 140

    const val ELLIPSIS: String = "…"

    /** Pure: works out what to show and where the highlights land after any trimming. */
    fun plan(text: String, findings: List<Finding>): HighlightPlan {
        if (text.isEmpty()) return HighlightPlan.EMPTY

        val visible = findings.filter {
            it.severity != Severity.NONE && it.start < text.length && it.end > it.start
        }
        if (visible.isEmpty()) return HighlightPlan.EMPTY

        val trimStart = trimStartFor(text, visible)
        val prefix = if (trimStart > 0) ELLIPSIS else ""
        val body = prefix + text.substring(trimStart)
        val shift = prefix.length - trimStart

        val spans = visible.mapNotNull { finding ->
            val start = (finding.start + shift).coerceIn(0, body.length)
            val end = (finding.end + shift).coerceIn(start, body.length)
            if (start == end) {
                null
            } else {
                HighlightSpan(start, end, high = finding.severity == Severity.HIGH)
            }
        }
        return if (spans.isEmpty()) HighlightPlan.EMPTY else HighlightPlan(body, spans)
    }

    /**
     * Where the preview starts. Keeps the tail, which is where the user is typing and so
     * where a fresh warning almost always is, but never trims into the earliest highlighted
     * span — a highlight clipped in half looks like a rendering bug.
     */
    private fun trimStartFor(text: String, visible: List<Finding>): Int {
        if (text.length <= MAX_PREVIEW_CHARS) return 0
        val naive = text.length - MAX_PREVIEW_CHARS
        val earliestVisible = visible.minOf { it.start }
        return minOf(naive, earliestVisible).coerceAtLeast(0)
    }

    /**
     * Builds the display text. Returns an empty sequence when there is nothing to highlight,
     * which the strip treats as "hide the preview line".
     */
    fun highlight(
        text: String,
        findings: List<Finding>,
        mediumColor: Int,
        highColor: Int,
        onHighlightColor: Int,
    ): CharSequence = apply(plan(text, findings), mediumColor, highColor, onHighlightColor)

    fun apply(
        plan: HighlightPlan,
        mediumColor: Int,
        highColor: Int,
        onHighlightColor: Int,
    ): CharSequence {
        if (plan.isEmpty) return ""
        val builder = SpannableStringBuilder(plan.text)
        for (span in plan.spans) {
            val color = if (span.high) highColor else mediumColor
            builder.setSpan(
                BackgroundColorSpan(color),
                span.start,
                span.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(
                ForegroundColorSpan(onHighlightColor),
                span.start,
                span.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            // Weight as well as colour, so the highlight is not carried by colour alone.
            // Matters for red-green colour blindness, and red on its own reads as spellcheck.
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                span.start,
                span.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }
}
