package com.keyguard.app.text

import java.io.InputStream

/**
 * The bundled word list and next-word table.
 *
 * Loaded once and reused; parsing is not free and this is consulted on every keystroke.
 */
class Vocabulary private constructor(
    private val trie: WordTrie,
    private val ranked: List<String>,
    private val bigrams: Map<String, List<String>>,
    /** Words bucketed by length, for the correction fallback's candidate filter. */
    private val byLength: Map<Int, List<String>>,
) {
    val wordCount: Int get() = ranked.size
    val bigramCount: Int get() = bigrams.size

    fun isKnown(word: String): Boolean = trie.contains(word.lowercase())

    fun rankOf(word: String): Int? = trie.rankOf(word.lowercase())

    fun completions(prefix: String, limit: Int): List<String> =
        trie.completions(prefix.lowercase(), limit)

    /** Words commonly following [word]. */
    fun following(word: String): List<String> = bigrams[word.lowercase()].orEmpty()

    /** The most common words overall, used at the start of a sentence. */
    fun mostCommon(limit: Int): List<String> = ranked.take(limit)

    /**
     * Correction candidates by edit distance. Only used when the platform spell checker is
     * unavailable or returned nothing.
     *
     * Brute-forcing the whole vocabulary per keystroke would be too slow, so candidates are
     * pre-filtered to a similar length and a plausible first letter before any distance is
     * computed. Typos rarely change a word's first letter, and when they do it is almost
     * always to an adjacent key.
     */
    fun nearby(typed: String, limit: Int): List<String> {
        if (typed.length < 3) return emptyList()
        val lower = typed.lowercase()
        val first = lower.first()

        val pool = ((lower.length - LENGTH_WINDOW)..(lower.length + LENGTH_WINDOW))
            .flatMap { byLength[it].orEmpty() }
            .filter { candidate ->
                val c = candidate.first()
                c == first || KeyProximity.areAdjacent(c, first)
            }

        return pool.asSequence()
            .map { it to KeyProximity.normalizedDistance(lower, it) }
            .filter { it.second in 0.001f..CorrectionRanker.MAX_ACCEPTABLE_DISTANCE }
            .sortedWith(compareBy({ it.second }, { rankOf(it.first) ?: Int.MAX_VALUE }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    companion object {
        private const val LENGTH_WINDOW = 2

        /**
         * Parses the bundled assets. Word order is the frequency rank, so no explicit counts
         * are stored — which keeps the asset small and hand-editable.
         */
        fun load(words: InputStream, bigrams: InputStream): Vocabulary {
            val trie = WordTrie()
            val ranked = ArrayList<String>()
            val seen = HashSet<String>()

            words.bufferedReader().forEachLine { line ->
                if (line.startsWith("#")) return@forEachLine
                for (token in line.split(' ', '\t')) {
                    val word = token.trim().lowercase()
                    if (word.isEmpty() || !word.all { it.isLetter() || it == '\'' || it == '-' }) continue
                    if (!seen.add(word)) continue
                    trie.insert(word, ranked.size)
                    ranked += word
                }
            }

            val table = HashMap<String, List<String>>()
            bigrams.bufferedReader().forEachLine { line ->
                if (line.startsWith("#") || line.isBlank()) return@forEachLine
                val tokens = line.trim().split(' ').filter { it.isNotBlank() }
                if (tokens.size < 2) return@forEachLine
                table[tokens[0].lowercase()] = tokens.drop(1).map { it.lowercase() }
            }

            return Vocabulary(
                trie = trie,
                ranked = ranked,
                bigrams = table,
                byLength = ranked.groupBy { it.length },
            )
        }
    }
}
