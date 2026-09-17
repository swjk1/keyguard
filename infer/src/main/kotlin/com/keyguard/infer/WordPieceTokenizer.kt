package com.keyguard.infer

/**
 * One encoded message, in the shape the ONNX graph wants.
 *
 * [offsets] is per non-special token and indexes the *original* string, so a predicted entity span
 * can be underlined in the text the user actually typed rather than in the normalized form. The
 * `[CLS]` and `[SEP]` positions carry `null`, because they correspond to nothing the user wrote.
 */
data class Encoding(
    val ids: IntArray,
    val attentionMask: IntArray,
    val tokens: List<String>,
    val offsets: List<IntRange?>,
) {
    /** Number of real (non-padding) positions. */
    val length: Int get() = attentionMask.sum()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Encoding && ids.contentEquals(other.ids))

    override fun hashCode(): Int = ids.contentHashCode()
}

/**
 * WordPiece, as the shipped checkpoint was trained with it.
 *
 * Greedy longest-match-first over a fixed vocabulary, with continuation pieces carrying the `##`
 * prefix. A word longer than [MAX_CHARS_PER_WORD] characters, or one where the greedy match fails
 * partway, becomes a single `[UNK]` rather than a partial decomposition.
 *
 * ### Why this class is the riskiest thing in the module
 *
 * Every other failure in the inference path announces itself. A missing native library throws, a
 * shape mismatch throws, a corrupt model throws. This one does not: a tokenizer that disagrees with
 * training by one normalization rule produces ids that are all valid, probabilities that are all in
 * range, and answers that are all plausible — just worse than the model tested at, for a reason no
 * runtime check can see.
 *
 * That is what the fixtures are for. [FIXTURES_NOTE].
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val unkToken: String = "[UNK]",
    private val clsToken: String = "[CLS]",
    private val sepToken: String = "[SEP]",
    private val padToken: String = "[PAD]",
    private val continuingPrefix: String = "##",
) {

    private val unkId = requireId(unkToken)
    private val clsId = requireId(clsToken)
    private val sepId = requireId(sepToken)
    val padId = requireId(padToken)

    val vocabSize: Int get() = vocab.size

    private fun requireId(token: String): Int =
        vocab[token] ?: error("vocabulary is missing the special token $token")

    /**
     * Encodes for the model: `[CLS] … [SEP]`, truncated and padded to [maxLength].
     *
     * Truncation drops the tail of the message rather than failing. A message longer than the
     * window is not an error condition — it is Tuesday — and the window was chosen to cover the
     * message lengths the phone actually sees.
     */
    fun encode(text: String, maxLength: Int = MAX_LENGTH): Encoding {
        val pieces = tokenize(text)
        val budget = maxLength - 2 // [CLS] and [SEP]
        val kept = if (pieces.size > budget) pieces.subList(0, budget) else pieces

        val ids = IntArray(maxLength) { padId }
        val mask = IntArray(maxLength)
        val tokens = ArrayList<String>(maxLength)
        val offsets = ArrayList<IntRange?>(maxLength)

        var position = 0
        ids[position] = clsId
        mask[position] = 1
        tokens.add(clsToken)
        offsets.add(null)
        position++

        for (piece in kept) {
            ids[position] = vocab[piece.token] ?: unkId
            mask[position] = 1
            tokens.add(piece.token)
            offsets.add(piece.source)
            position++
        }

        ids[position] = sepId
        mask[position] = 1
        tokens.add(sepToken)
        offsets.add(null)

        return Encoding(ids, mask, tokens, offsets)
    }

    /** The ids a fixture states — `[CLS] … [SEP]`, no padding. */
    fun encodeToIds(text: String): IntArray {
        val encoding = encode(text)
        return encoding.ids.copyOf(encoding.length)
    }

    // -- internals -----------------------------------------------------------------------

    private data class Piece(val token: String, val source: IntRange)

    /**
     * Normalize, split on whitespace and punctuation, then WordPiece each word.
     *
     * Character offsets are tracked through the split so a span can be reported against the
     * original text. They are approximate in one direction only: normalization can change a
     * word's length (accent stripping composes two code points into one), so an offset points at
     * the word that produced the token rather than at the exact sub-range within it. For
     * underlining an address that is the right granularity anyway.
     */
    private fun tokenize(text: String): List<Piece> {
        val out = mutableListOf<Piece>()
        for (word in splitWords(text)) {
            wordPiece(word.text, word.source, out)
        }
        return out
    }

    private data class Word(val text: String, val source: IntRange)

    private fun splitWords(text: String): List<Word> {
        val words = mutableListOf<Word>()
        // Normalization runs per original-character so offsets survive it. A character that
        // normalizes away contributes nothing; one that expands keeps its origin.
        val builder = StringBuilder()
        val origin = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            val normalized = BertNormalizer.normalize(String(Character.toChars(cp)))
            for (ch in normalized) {
                builder.append(ch)
                origin.add(i)
            }
            i += width
        }

        val normalizedText = builder.toString()
        var start = -1
        var j = 0
        while (j < normalizedText.length) {
            val ch = normalizedText[j]
            val cp = ch.code
            when {
                ch == ' ' -> {
                    if (start >= 0) {
                        words += Word(normalizedText.substring(start, j), origin[start]..origin[j - 1])
                        start = -1
                    }
                }

                BertNormalizer.isPunctuation(cp) -> {
                    if (start >= 0) {
                        words += Word(normalizedText.substring(start, j), origin[start]..origin[j - 1])
                        start = -1
                    }
                    words += Word(ch.toString(), origin[j]..origin[j])
                }

                else -> if (start < 0) start = j
            }
            j++
        }
        if (start >= 0) {
            words += Word(
                normalizedText.substring(start, normalizedText.length),
                origin[start]..origin[normalizedText.length - 1],
            )
        }
        return words
    }

    private fun wordPiece(word: String, source: IntRange, out: MutableList<Piece>) {
        if (word.isEmpty()) return
        if (word.codePointCount(0, word.length) > MAX_CHARS_PER_WORD) {
            out += Piece(unkToken, source)
            return
        }

        val pieces = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var match: String? = null
            while (start < end) {
                val candidate = if (start == 0) {
                    word.substring(start, end)
                } else {
                    continuingPrefix + word.substring(start, end)
                }
                if (vocab.containsKey(candidate)) {
                    match = candidate
                    break
                }
                end--
            }
            if (match == null) {
                // Greedy matching failed partway through. The whole word becomes [UNK] — a
                // partial decomposition would be a token sequence the model never saw in training.
                out += Piece(unkToken, source)
                return
            }
            pieces += match
            start = end
        }
        pieces.forEach { out += Piece(it, source) }
    }

    companion object {
        /** The window the checkpoint was exported with. Not a tuning knob — the graph is fixed. */
        const val MAX_LENGTH = 128

        /** WordPiece's own cutoff, from the exported tokenizer config. */
        const val MAX_CHARS_PER_WORD = 100

        const val FIXTURES_NOTE =
            "tokenizer_fixtures.json ships 50 texts with the exact ids the training tokenizer " +
                "produced. WordPieceTokenizerTest asserts every one of them, and it is the gate " +
                "on believing any number this module produces."

        /** Parses a `vocab.txt` — one token per line, id is the line number. */
        fun vocabularyFrom(lines: Sequence<String>): Map<String, Int> {
            val vocab = HashMap<String, Int>(40_000)
            var id = 0
            for (line in lines) {
                // Only the trailing newline is stripped. A vocabulary entry can legitimately be
                // a space-bearing token, and trimming would silently rewrite it.
                vocab[line.removeSuffix("\r")] = id
                id++
            }
            return vocab
        }
    }
}
