package com.keyguard.app.text

import android.content.Context

/**
 * Words and word pairs the user actually types.
 *
 * This is most of what makes a stock keyboard feel adaptive: after a few days it knows your
 * friends' names, your slang, and how you usually start a sentence. A fixed bundled list never
 * gets there, however large it is.
 *
 * **Privacy note.** This is the only place the keyboard persists anything derived from typing,
 * and it deliberately stores *words*, never messages — no order, no timestamps, no context
 * beyond adjacent pairs, so nothing here can reconstruct what was said. It is also excluded
 * from cloud backup (see `data_extraction_rules.xml`) and never leaves the device: it is not
 * part of any verification payload. [clear] wipes it.
 */
/**
 * The learned-vocabulary surface [Predictor] depends on.
 *
 * Extracted so the predictor can be tested against a fake instead of a Context-bound store —
 * the merge rules between learned and bundled suggestions are the part most worth pinning, and
 * they should not require a device to exercise.
 */
interface LearnedWords {
    fun following(previous: String, limit: Int): List<String>
    fun completions(prefix: String, limit: Int): List<String>
    fun isKnown(word: String): Boolean

    /** A store that has learned nothing; used before assets load and in tests. */
    object Empty : LearnedWords {
        override fun following(previous: String, limit: Int) = emptyList<String>()
        override fun completions(prefix: String, limit: Int) = emptyList<String>()
        override fun isKnown(word: String) = false
    }
}

class UserVocabulary(context: Context) : LearnedWords {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Words with counts, and pairs keyed "first>second". */
    private val wordCounts = HashMap<String, Int>()
    private val pairCounts = HashMap<String, Int>()
    private var dirty = false

    init {
        for ((key, value) in prefs.all) {
            val count = (value as? Int) ?: continue
            when {
                key.startsWith(WORD_PREFIX) -> wordCounts[key.removePrefix(WORD_PREFIX)] = count
                key.startsWith(PAIR_PREFIX) -> pairCounts[key.removePrefix(PAIR_PREFIX)] = count
            }
        }
    }

    /** Records a committed word and, when known, the pair it followed. */
    fun learn(word: String, previous: String?) {
        val clean = word.lowercase().trim()
        if (!isLearnable(clean)) return

        wordCounts[clean] = (wordCounts[clean] ?: 0) + 1
        if (previous != null) {
            val prev = previous.lowercase().trim()
            if (isLearnable(prev)) {
                val key = "$prev>$clean"
                pairCounts[key] = (pairCounts[key] ?: 0) + 1
            }
        }
        dirty = true
        pruneIfNeeded()
    }

    /**
     * Words the user has typed after [previous], most-used first. These outrank the bundled
     * table, since they reflect how this person actually writes.
     */
    override fun following(previous: String, limit: Int): List<String> {
        val prefix = "${previous.lowercase()}>"
        return pairCounts.asSequence()
            .filter { it.key.startsWith(prefix) && it.value >= MIN_PAIR_USES }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key.removePrefix(prefix) }
            .toList()
    }

    /** Learned words starting with [prefix], most-used first. */
    override fun completions(prefix: String, limit: Int): List<String> {
        val lower = prefix.lowercase()
        return wordCounts.asSequence()
            .filter { it.key.startsWith(lower) && it.key != lower && it.value >= MIN_WORD_USES }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
            .toList()
    }

    /**
     * Whether the user has typed this often enough that it should be treated as a real word
     * rather than a typo to be corrected. Autocorrecting someone's own name is infuriating.
     */
    override fun isKnown(word: String): Boolean =
        (wordCounts[word.lowercase()] ?: 0) >= MIN_WORD_USES

    fun flush() {
        if (!dirty) return
        val editor = prefs.edit()
        for ((word, count) in wordCounts) editor.putInt(WORD_PREFIX + word, count)
        for ((pair, count) in pairCounts) editor.putInt(PAIR_PREFIX + pair, count)
        editor.apply()
        dirty = false
    }

    fun clear() {
        wordCounts.clear()
        pairCounts.clear()
        prefs.edit().clear().apply()
        dirty = false
    }

    val learnedWordCount: Int get() = wordCounts.count { it.value >= MIN_WORD_USES }

    private fun isLearnable(word: String): Boolean =
        word.length in 2..24 && word.all { it.isLetter() || it == '\'' }

    /** Keeps storage bounded by dropping the least-used entries. */
    private fun pruneIfNeeded() {
        if (wordCounts.size > MAX_WORDS) {
            val keep = wordCounts.entries.sortedByDescending { it.value }.take(MAX_WORDS * 3 / 4)
            val kept = HashMap<String, Int>(keep.size)
            for (entry in keep) kept[entry.key] = entry.value
            val removed = wordCounts.keys - kept.keys
            wordCounts.clear()
            wordCounts.putAll(kept)
            prefs.edit().apply { removed.forEach { remove(WORD_PREFIX + it) } }.apply()
        }
        if (pairCounts.size > MAX_PAIRS) {
            val keep = pairCounts.entries.sortedByDescending { it.value }.take(MAX_PAIRS * 3 / 4)
            val kept = HashMap<String, Int>(keep.size)
            for (entry in keep) kept[entry.key] = entry.value
            val removed = pairCounts.keys - kept.keys
            pairCounts.clear()
            pairCounts.putAll(kept)
            prefs.edit().apply { removed.forEach { remove(PAIR_PREFIX + it) } }.apply()
        }
    }

    private companion object {
        const val PREFS = "keyguard_user_vocab"
        const val WORD_PREFIX = "w:"
        const val PAIR_PREFIX = "p:"

        /** Typed at least twice before it counts, so a one-off typo is not learned. */
        const val MIN_WORD_USES = 2
        const val MIN_PAIR_USES = 2
        const val MAX_WORDS = 2_000
        const val MAX_PAIRS = 4_000
    }
}
