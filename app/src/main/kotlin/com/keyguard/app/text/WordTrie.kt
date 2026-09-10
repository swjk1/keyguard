package com.keyguard.app.text

/**
 * Prefix trie over the vocabulary, for word completion.
 *
 * Completion has to run on every keystroke, so scanning the word list looking for prefix
 * matches would be O(vocabulary) per key. A trie makes it O(prefix length) plus the size of
 * the matched subtree, which stays flat as the vocabulary grows.
 *
 * Each terminal node stores the word's frequency rank, so results come back best-first without
 * a separate sort over the whole subtree.
 */
class WordTrie {

    private class Node {
        val children = HashMap<Char, Node>(4)
        /** Frequency rank when a word ends here; -1 otherwise. Lower is more common. */
        var rank: Int = -1
        /** Best (lowest) rank anywhere in this subtree, for pruning. */
        var bestRank: Int = Int.MAX_VALUE
    }

    private val root = Node()
    private var wordCount = 0

    val size: Int get() = wordCount

    /** Inserts [word] with frequency [rank]. Later inserts of the same word keep the best rank. */
    fun insert(word: String, rank: Int) {
        if (word.isEmpty()) return
        var node = root
        node.bestRank = minOf(node.bestRank, rank)
        for (c in word) {
            node = node.children.getOrPut(c) { Node() }
            node.bestRank = minOf(node.bestRank, rank)
        }
        if (node.rank == -1) {
            wordCount++
            node.rank = rank
        } else {
            node.rank = minOf(node.rank, rank)
        }
    }

    fun contains(word: String): Boolean = nodeFor(word)?.rank?.let { it >= 0 } == true

    /** Frequency rank of [word], or null when absent. */
    fun rankOf(word: String): Int? = nodeFor(word)?.rank?.takeIf { it >= 0 }

    /**
     * Up to [limit] words starting with [prefix], best-first.
     *
     * The prefix itself is excluded — offering the user the word they have already finished
     * typing wastes a slot.
     */
    fun completions(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val start = nodeFor(prefix) ?: return emptyList()

        val found = ArrayList<Pair<String, Int>>()
        collect(start, StringBuilder(prefix), found, limit)

        return found.asSequence()
            .filter { !it.first.equals(prefix, ignoreCase = true) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * Depth-first collection, visiting the most promising subtree first so [limit] is usually
     * reached before the whole subtree is walked.
     */
    private fun collect(
        node: Node,
        path: StringBuilder,
        out: MutableList<Pair<String, Int>>,
        limit: Int,
    ) {
        if (out.size >= limit * SUBTREE_OVERSCAN) return
        if (node.rank >= 0) out += path.toString() to node.rank

        val ordered = node.children.entries.sortedBy { it.value.bestRank }
        for ((c, child) in ordered) {
            path.append(c)
            collect(child, path, out, limit)
            path.setLength(path.length - 1)
        }
    }

    private fun nodeFor(word: String): Node? {
        var node = root
        for (c in word) node = node.children[c] ?: return null
        return node
    }

    private companion object {
        /**
         * Gather more than requested before sorting. Depth-first order is not exactly
         * frequency order, so a little slack produces a correct top-N without walking
         * everything.
         */
        const val SUBTREE_OVERSCAN = 4
    }
}
