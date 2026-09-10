package com.keyguard.detect

/**
 * Normalized text plus the mapping needed to report spans against the *original* buffer.
 *
 * Normalization is lossy in both directions — characters get dropped (zero-width joiners)
 * and runs get collapsed (`fuuuuck` -> `fuck`) — so a per-character span map is the only
 * way to underline the right characters in the user's actual text.
 */
internal class NormalizedText(
    val text: String,
    private val origStart: IntArray,
    private val origEnd: IntArray,
) {
    /** Maps a half-open normalized span to a half-open original span. */
    fun mapSpan(normStart: Int, normEnd: Int): IntRange {
        require(normStart in 0 until normEnd && normEnd <= text.length) {
            "span $normStart..$normEnd outside normalized text of length ${text.length}"
        }
        return origStart[normStart] until origEnd[normEnd - 1]
    }

    /**
     * True when [normStart] until [normEnd] sits on word boundaries. This is what stops a
     * term like "ass" from firing inside "class" or "passage".
     */
    fun isWordBounded(normStart: Int, normEnd: Int): Boolean {
        val beforeOk = normStart == 0 || !isWordChar(text[normStart - 1])
        val afterOk = normEnd >= text.length || !isWordChar(text[normEnd])
        return beforeOk && afterOk
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    companion object {
        internal fun of(chars: CharArray, starts: IntArray, ends: IntArray, size: Int) =
            NormalizedText(
                String(chars, 0, size),
                starts.copyOf(size),
                ends.copyOf(size),
            )
    }
}

/** The two normalized views the lexicon matcher needs. */
internal class NormalizedForms(
    /** Lowercased, de-leetspeaked, run-collapsed. Separators preserved so word boundaries survive. */
    val loose: NormalizedText,
    /** Additionally strips every non-alphanumeric char, catching `f.u.c.k` and `f u c k`. */
    val tight: NormalizedText,
)

internal object Normalizer {

    /**
     * Leetspeak substitutions. Applied only on the path to lexicon matching — the
     * structured PII matchers run against the original text, so mangling digits here
     * cannot corrupt a phone number or credit card match.
     */
    private val LEET = mapOf(
        '4' to 'a', '@' to 'a',
        '3' to 'e',
        '1' to 'i', '!' to 'i', '|' to 'i',
        '0' to 'o',
        '5' to 's', '$' to 's',
        '7' to 't',
        '9' to 'g',
    )

    /** Runs of this length or longer collapse to a single character. */
    private const val COLLAPSE_THRESHOLD = 3

    fun normalize(input: String): NormalizedForms {
        val base = transform(input)
        return NormalizedForms(
            loose = collapseRuns(base),
            tight = collapseRuns(stripNonAlphanumeric(base)),
        )
    }

    /** Pass 1: drop invisible characters, lowercase, and fold leetspeak. */
    private fun transform(input: String): NormalizedText {
        val chars = CharArray(input.length)
        val starts = IntArray(input.length)
        val ends = IntArray(input.length)
        var n = 0
        for (i in input.indices) {
            val c = input[i]
            if (isInvisible(c)) continue
            val lowered = c.lowercaseChar()
            chars[n] = LEET[lowered] ?: lowered
            starts[n] = i
            ends[n] = i + 1
            n++
        }
        return NormalizedText.of(chars, starts, ends, n)
    }

    /**
     * Zero-width and formatting characters, plus combining marks. These are the cheapest
     * possible filter evasion (`f‌u‌c‌k` with zero-width non-joiners between letters) and
     * cost nothing to defeat.
     */
    private fun isInvisible(c: Char): Boolean = when (c.category) {
        CharCategory.FORMAT,
        CharCategory.NON_SPACING_MARK,
        CharCategory.ENCLOSING_MARK,
        CharCategory.COMBINING_SPACING_MARK,
        CharCategory.CONTROL,
        -> true
        else -> c == '﻿' || c == '­'
    }

    private fun stripNonAlphanumeric(src: NormalizedText): NormalizedText {
        val chars = CharArray(src.text.length)
        val starts = IntArray(src.text.length)
        val ends = IntArray(src.text.length)
        var n = 0
        for (i in src.text.indices) {
            val c = src.text[i]
            if (!c.isLetterOrDigit()) continue
            val span = src.mapSpan(i, i + 1)
            chars[n] = c
            starts[n] = span.first
            ends[n] = span.last + 1
            n++
        }
        return NormalizedText.of(chars, starts, ends, n)
    }

    /**
     * Pass 2: collapse runs of >= [COLLAPSE_THRESHOLD] identical characters to one.
     *
     * The threshold matters: collapsing pairs would break ordinary doubled letters
     * ("cool" -> "col"), while leaving 3+ alone would let "fuuuck" through. Collapsing
     * only 3+ keeps real words intact and still defeats the padding evasion.
     */
    private fun collapseRuns(src: NormalizedText): NormalizedText {
        val text = src.text
        val chars = CharArray(text.length)
        val starts = IntArray(text.length)
        val ends = IntArray(text.length)
        var n = 0
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j] == text[i]) j++
            val runLength = j - i
            val keep = if (runLength >= COLLAPSE_THRESHOLD) 1 else runLength
            for (k in 0 until keep) {
                val isLastKept = k == keep - 1
                // The final kept character absorbs the whole remainder of the run, so a
                // match ending on it underlines every original character it stands for.
                val sourceEndIndex = if (isLastKept) j - 1 else i + k
                chars[n] = text[i]
                starts[n] = src.mapSpan(i + k, i + k + 1).first
                ends[n] = src.mapSpan(sourceEndIndex, sourceEndIndex + 1).last + 1
                n++
            }
            i = j
        }
        return NormalizedText.of(chars, starts, ends, n)
    }
}
