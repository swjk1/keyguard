package com.keyguard.app.text

/**
 * Keyboard-geometry-aware typo scoring.
 *
 * Plain edit distance treats every substitution as equally likely, which is wrong on a phone:
 * "hellp" is far more likely to be "hello" than "help", because `p` neighbours `o`. Weighting
 * substitutions by physical key distance is what makes correction ranking feel intentional
 * rather than arbitrary.
 */
object KeyProximity {

    /**
     * Approximate key centres on the standard QWERTY layout, in key-width units. Row 2 is
     * offset by half a key and row 3 by one and a half, matching the real layout.
     */
    private val positions: Map<Char, Pair<Float, Float>> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(c, i.toFloat() to 0f) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, (i + 0.5f) to 1f) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, (i + 1.5f) to 2f) }
    }

    /** Euclidean distance between two keys, or [FAR] when either is not a letter key. */
    fun distance(a: Char, b: Char): Float {
        if (a == b) return 0f
        val pa = positions[a.lowercaseChar()] ?: return FAR
        val pb = positions[b.lowercaseChar()] ?: return FAR
        val dx = pa.first - pb.first
        val dy = pa.second - pb.second
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** True when two keys are close enough that hitting one instead of the other is likely. */
    fun areAdjacent(a: Char, b: Char): Boolean = distance(a, b) <= ADJACENT_THRESHOLD

    /**
     * Substitution cost in the range 0..1. Adjacent keys cost little; distant keys cost nearly
     * a full edit, since a far-off substitution is more likely a different word than a slip.
     */
    fun substitutionCost(a: Char, b: Char): Float {
        if (a.lowercaseChar() == b.lowercaseChar()) return 0f
        val d = distance(a, b)
        if (d >= FAR) return 1f
        return (d / MAX_MEANINGFUL_DISTANCE).coerceIn(MIN_SUBSTITUTION_COST, 1f)
    }

    /**
     * Weighted Levenshtein distance between a typed word and a candidate.
     *
     * Transpositions are charged as a single cheap edit rather than two, because swapped
     * letters ("teh", "adn") are among the most common real typing errors.
     */
    fun editDistance(typed: String, candidate: String): Float {
        val a = typed.lowercase()
        val b = candidate.lowercase()
        if (a == b) return 0f
        if (a.isEmpty()) return b.length.toFloat()
        if (b.isEmpty()) return a.length.toFloat()

        val previous2 = FloatArray(b.length + 1)
        val previous = FloatArray(b.length + 1) { it.toFloat() }
        val current = FloatArray(b.length + 1)

        var prev2 = previous2
        var prev = previous
        var curr = current

        for (i in 1..a.length) {
            curr[0] = i.toFloat()
            for (j in 1..b.length) {
                val substitution = prev[j - 1] + substitutionCost(a[i - 1], b[j - 1])
                val deletion = prev[j] + INSERT_DELETE_COST
                val insertion = curr[j - 1] + INSERT_DELETE_COST
                var best = minOf(substitution, deletion, insertion)

                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, prev2[j - 2] + TRANSPOSITION_COST)
                }
                curr[j] = best
            }
            val rotated = prev2
            prev2 = prev
            prev = curr
            curr = rotated
        }
        return prev[b.length]
    }

    /** Distance normalised by word length, so long and short words compare fairly. */
    fun normalizedDistance(typed: String, candidate: String): Float {
        val longest = maxOf(typed.length, candidate.length)
        if (longest == 0) return 0f
        return editDistance(typed, candidate) / longest
    }

    private const val FAR = 99f
    private const val MAX_MEANINGFUL_DISTANCE = 4f
    private const val MIN_SUBSTITUTION_COST = 0.25f
    private const val ADJACENT_THRESHOLD = 1.45f
    private const val INSERT_DELETE_COST = 1f
    private const val TRANSPOSITION_COST = 0.6f
}
