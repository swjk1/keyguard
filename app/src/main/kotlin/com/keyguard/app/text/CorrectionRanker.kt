package com.keyguard.app.text

/**
 * Orders correction candidates and decides which are confident enough to apply automatically.
 *
 * The platform spell checker supplies candidates but ranks them by its own dictionary, with no
 * knowledge of where the keys are. Re-ranking by typing geometry is what turns a list of
 * plausible words into a correction the user actually wanted.
 */
object CorrectionRanker {

    data class Candidate(
        val word: String,
        /** Weighted edit distance from what was typed; lower is better. */
        val distance: Float,
        /** Position in the source's own ranking, used as a tiebreak. */
        val sourceRank: Int,
    )

    /**
     * Ranks [rawCandidates] against [typed].
     *
     * @return candidates ordered best-first, capped at [MAX_SUGGESTIONS], with the typed word
     *   itself removed.
     */
    fun rank(typed: String, rawCandidates: List<String>): List<Candidate> {
        if (typed.isEmpty()) return emptyList()

        return rawCandidates.asSequence()
            .filter { it.isNotBlank() && !it.equals(typed, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .mapIndexed { index, candidate ->
                Candidate(
                    word = candidate,
                    distance = KeyProximity.normalizedDistance(typed, candidate),
                    sourceRank = index,
                )
            }
            .filter { it.distance <= MAX_ACCEPTABLE_DISTANCE }
            // Distance dominates; the source's own ordering only breaks ties.
            .sortedWith(compareBy({ it.distance }, { it.sourceRank }))
            .take(MAX_SUGGESTIONS)
            .toList()
    }

    /**
     * Whether the best candidate should replace the typed word without being asked.
     *
     * Deliberately conservative. A wrong auto-replace is far more annoying than a missed one —
     * it silently changes what the user wrote — so this requires the top candidate to be both
     * close to what was typed and clearly better than the runner-up.
     */
    fun shouldAutoApply(typed: String, ranked: List<Candidate>): Boolean {
        val best = ranked.firstOrNull() ?: return false
        if (!WordScanner.isCorrectable(typed)) return false
        if (best.distance > AUTO_APPLY_MAX_DISTANCE) return false

        val runnerUp = ranked.getOrNull(1) ?: return true
        // An ambiguous choice between two near-equal candidates is one to offer, not to make.
        return runnerUp.distance - best.distance >= AUTO_APPLY_MIN_MARGIN
    }

    /** Suggestions shown in the strip. Three fits comfortably at any supported text size. */
    const val MAX_SUGGESTIONS = 3

    /** Beyond this, a candidate is a different word rather than a correction. */
    const val MAX_ACCEPTABLE_DISTANCE = 0.55f

    private const val AUTO_APPLY_MAX_DISTANCE = 0.34f
    private const val AUTO_APPLY_MIN_MARGIN = 0.08f
}
