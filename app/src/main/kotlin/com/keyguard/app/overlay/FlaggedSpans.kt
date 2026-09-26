package com.keyguard.app.overlay

import com.keyguard.detect.Finding

/**
 * What "Remove it" takes out of a field.
 *
 * One tap has to clear the warning it answers. The detector routinely reports one disclosure as
 * several findings — "my address is 123 Main Street" is a lead-in phrase *and* an address
 * pattern, both at the top severity — and the button used to remove only the earliest of them.
 * On the emulator that deleted "my address is", left "123 Main Street" in the message, and
 * raised the warning again: the harmless half gone and the address still there, one tap from
 * being sent.
 *
 * So every span at the top severity goes, overlapping spans are merged, and the whitespace
 * either side of a hole is collapsed so the remaining text does not read as "see you  there".
 * Lower-severity findings stay; they were not what the warning was about, and removing text
 * nobody was warned about is the behaviour that teaches people to compose elsewhere and paste.
 *
 * Pure, so the edge cases are checked on the JVM rather than on someone's phone.
 */
object FlaggedSpans {

    /** The field's new text, or null when there is nothing valid to remove. */
    fun remove(text: String, findings: List<Finding>): String? {
        val top = findings.maxOfOrNull { it.severity.level } ?: return null
        val spans = findings
            .filter { it.severity.level == top && it.end <= text.length && it.end > it.start }
            .map { it.start until it.end }
            .sortedBy { it.first }
        if (spans.isEmpty()) return null

        val merged = mutableListOf<IntRange>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && span.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, span.last)
            } else {
                merged += span
            }
        }

        val out = StringBuilder(text)
        // From the end, so earlier offsets stay valid while later spans are cut.
        for (span in merged.asReversed()) {
            var start = span.first
            var end = span.last + 1
            val spaceBefore = start > 0 && out[start - 1].isWhitespace()
            val spaceAfter = end < out.length && out[end].isWhitespace()
            // Keep one separator between the surviving words, none at either edge.
            if (spaceBefore && (spaceAfter || end == out.length)) start--
            else if (spaceAfter && start == 0) end++
            out.delete(start, end)
        }
        return out.toString()
    }
}
