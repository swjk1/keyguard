package com.keyguard.infer

import java.text.Normalizer as UnicodeNormalizer

/**
 * The text normalization BERT's tokenizer applies before anything is split.
 *
 * This is a reimplementation of HuggingFace's `BertNormalizer` as configured for the shipped
 * checkpoint, and it has to match it exactly. It is not a place to be approximately right: a
 * normalization that differs by one rule produces different token ids, therefore different
 * probabilities, and every one of them is plausible. Nothing crashes and no metric drops — the
 * model simply behaves worse than it tested, for a reason nothing in the app can surface.
 * [WordPieceTokenizer.FIXTURES_NOTE] is how that is caught.
 *
 * Two settings deserve their own note, because both are traps.
 *
 * **Accents are stripped, even though the config says `strip_accents: null`.** In
 * `BertNormalizer`, `strip_accents` is an `Option<bool>` that defaults to *the value of
 * `lowercase`* when unset. The export sets `lowercase: true`, so accents are stripped. Reading
 * `null` as "off" is the single most likely way to get this wrong, and it would only show up on
 * text carrying diacritics — a name, a place — which is exactly the text this model exists to
 * find.
 *
 * **This is not [com.keyguard.detect.Normalizer].** That one is built for lexicon matching and is
 * aggressive on purpose: leetspeak substitution, run-collapsing, punctuation stripping. Those
 * transforms are right for catching obfuscated terms and catastrophic here, because the model was
 * trained on text that had none of them applied. The two normalizers must stay separate.
 *
 * Order matters and is the library's: clean, then Chinese spacing, then accents, then case.
 */
internal object BertNormalizer {

    fun normalize(text: String): String {
        var out = cleanText(text)
        out = padChineseChars(out)
        out = stripAccents(out)
        return out.lowercase()
    }

    /**
     * Drops NUL, the replacement character and every control character, and flattens the rest of
     * Unicode's whitespace to a plain space.
     *
     * "Control" here is Unicode category C* — not just the ASCII control block — with tab, newline
     * and carriage return excluded because the next step treats them as whitespace instead.
     */
    private fun cleanText(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> Unit
                isWhitespace(cp) -> out.append(' ')
                else -> out.appendCodePoint(cp)
            }
            i += width
        }
        return out.toString()
    }

    /**
     * Surrounds CJK ideographs with spaces so each becomes its own token.
     *
     * Kept despite the product being English-only: it is in the trained tokenizer, so leaving it
     * out would be a divergence, and a divergence that only appears on input nobody tested is
     * worse than one that appears everywhere.
     */
    private fun padChineseChars(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            if (isChineseChar(cp)) {
                out.append(' ').appendCodePoint(cp).append(' ')
            } else {
                out.appendCodePoint(cp)
            }
            i += width
        }
        return out.toString()
    }

    /** NFD, then drop every non-spacing mark — "José" becomes "Jose", not "José". */
    private fun stripAccents(text: String): String {
        val decomposed = UnicodeNormalizer.normalize(text, UnicodeNormalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            val width = Character.charCount(cp)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) {
                out.appendCodePoint(cp)
            }
            i += width
        }
        return out.toString()
    }

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.UNASSIGNED.toInt(),
            -> true

            else -> false
        }
    }

    /**
     * Unicode's White_Space property, which is not what `Character.isWhitespace` reports.
     *
     * The difference that matters is U+00A0 no-break space: Java excludes it, Unicode includes it,
     * and a phone keyboard emits them. Built from `isSpaceChar` (the Z* categories) plus the five
     * ASCII control-range separators and U+0085.
     */
    private fun isWhitespace(cp: Int): Boolean = when (cp) {
        ' '.code, '\t'.code, '\n'.code, 0x0B, 0x0C, '\r'.code, 0x85 -> true
        else -> Character.isSpaceChar(cp)
    }

    private fun isChineseChar(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) ||
            (cp in 0x3400..0x4DBF) ||
            (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) ||
            (cp in 0x2B740..0x2B81F) ||
            (cp in 0x2B820..0x2CEAF) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0x2F800..0x2FA1F)

    /**
     * What `BertPreTokenizer` treats as punctuation: the Unicode P* categories plus the ASCII
     * punctuation that is not in them.
     *
     * That second half is not redundant. `$ + < = > ^ \` | ~` are Symbol characters, not
     * punctuation, and BERT splits on them anyway. Dropping them would silently glue "5:30pm"
     * variants and "u/name" handles into single unknown tokens.
     */
    fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            -> true

            else -> false
        }
    }
}
