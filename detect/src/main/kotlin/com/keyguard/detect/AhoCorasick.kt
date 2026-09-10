package com.keyguard.detect

/**
 * Aho-Corasick multi-pattern matcher.
 *
 * The lexicon has to be scanned on every keystroke, so per-term regex or `contains` calls
 * would be O(terms x length). This walks the buffer once — O(length) regardless of how far
 * the rule pack grows, which is what keeps the engine inside its per-keystroke budget as
 * the term list expands.
 */
internal class AhoCorasick private constructor(
    private val children: List<Map<Char, Int>>,
    private val fail: IntArray,
    private val output: List<IntArray>,
    private val patternLengths: IntArray,
) {
    /** A hit, located by a half-open span in the searched text. */
    data class Match(val patternIndex: Int, val start: Int, val end: Int)

    fun search(text: String): List<Match> {
        if (text.isEmpty() || patternLengths.isEmpty()) return emptyList()
        val results = ArrayList<Match>()
        var state = 0
        for (i in text.indices) {
            state = step(state, text[i])
            val hits = output[state]
            for (patternIndex in hits) {
                val length = patternLengths[patternIndex]
                results.add(Match(patternIndex, i - length + 1, i + 1))
            }
        }
        return results
    }

    private fun step(from: Int, c: Char): Int {
        var state = from
        while (state != 0 && children[state][c] == null) state = fail[state]
        return children[state][c] ?: 0
    }

    companion object {
        /** Builds an automaton. Patterns are matched literally; normalize them first. */
        fun build(patterns: List<String>): AhoCorasick {
            val children = ArrayList<MutableMap<Char, Int>>().apply { add(HashMap()) }
            val outputs = ArrayList<MutableList<Int>>().apply { add(ArrayList()) }
            val lengths = IntArray(patterns.size)

            patterns.forEachIndexed { patternIndex, pattern ->
                lengths[patternIndex] = pattern.length
                if (pattern.isEmpty()) return@forEachIndexed
                var node = 0
                for (c in pattern) {
                    val existing = children[node][c]
                    node = if (existing != null) {
                        existing
                    } else {
                        children.add(HashMap())
                        outputs.add(ArrayList())
                        val created = children.size - 1
                        children[node][c] = created
                        created
                    }
                }
                outputs[node].add(patternIndex)
            }

            // BFS assigns fail links in depth order, which guarantees a node's fail target
            // is fully resolved before we inherit its outputs.
            val fail = IntArray(children.size)
            val queue = ArrayDeque<Int>()
            for (child in children[0].values) {
                fail[child] = 0
                queue.add(child)
            }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                for ((c, next) in children[node]) {
                    var f = fail[node]
                    while (f != 0 && children[f][c] == null) f = fail[f]
                    val candidate = children[f][c] ?: 0
                    fail[next] = if (candidate == next) 0 else candidate
                    outputs[next].addAll(outputs[fail[next]])
                    queue.add(next)
                }
            }

            return AhoCorasick(
                children = children.map { it.toMap() },
                fail = fail,
                output = outputs.map { it.toIntArray() },
                patternLengths = lengths,
            )
        }
    }
}
