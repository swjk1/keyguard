package com.keyguard.app.text

/**
 * Produces the three suggestions shown above the keys.
 *
 * A stock keyboard's strip does three different jobs depending on what the user is doing, and
 * conflating them is what makes a home-made keyboard feel wrong:
 *
 *  - **Prediction** — nothing typed yet: what word usually comes next.
 *  - **Completion** — a prefix typed that is a valid word start: finish it.
 *  - **Correction** — a prefix typed that is not a word: fix it.
 *
 * Only correction may ever auto-apply. Silently replacing a word the user was still typing
 * would be indefensible.
 */
class Predictor(
    private val vocabulary: Vocabulary,
    private val userVocabulary: LearnedWords = LearnedWords.Empty,
) {
    enum class Kind { PREDICTION, COMPLETION, CORRECTION, NONE }

    data class Suggestions(
        val items: List<String>,
        val kind: Kind,
        /** Index that will be applied on the next terminator, or -1. */
        val autoApplyIndex: Int = -1,
        /**
         * Index holding the user's literal typed word. Shown so a pending autocorrect can be
         * rejected before it happens rather than undone afterwards.
         */
        val literalIndex: Int = -1,
    ) {
        val isEmpty: Boolean get() = items.isEmpty()

        companion object {
            val EMPTY = Suggestions(emptyList(), Kind.NONE)
        }
    }

    /**
     * @param previousWord the completed word before the cursor, or null at the start
     * @param prefix the partially typed word, possibly empty
     * @param spellCheckerCandidates corrections from the platform spell checker, if any
     */
    fun suggest(
        previousWord: String?,
        prefix: String,
        spellCheckerCandidates: List<String> = emptyList(),
    ): Suggestions {
        if (prefix.isEmpty()) return predict(previousWord)
        return completeOrCorrect(prefix, spellCheckerCandidates)
    }

    /** Next-word prediction. Learned pairs first, then the bundled table. */
    private fun predict(previousWord: String?): Suggestions {
        val slots = CorrectionRanker.MAX_SUGGESTIONS

        val items = if (previousWord.isNullOrEmpty()) {
            // Sentence start: the most common openers are better than nothing, and match what
            // a stock keyboard shows on an empty field.
            vocabulary.mostCommon(slots)
        } else {
            val learned = userVocabulary.following(previousWord, slots)
            val bundled = vocabulary.following(previousWord)
            (learned + bundled).distinct().take(slots)
        }

        return if (items.isEmpty()) {
            Suggestions.EMPTY
        } else {
            Suggestions(items, Kind.PREDICTION)
        }
    }

    private fun completeOrCorrect(prefix: String, spellCheckerCandidates: List<String>): Suggestions {
        val slots = CorrectionRanker.MAX_SUGGESTIONS
        val lower = prefix.lowercase()

        // A word the user has typed repeatedly counts as real, even if the dictionary disagrees.
        // Autocorrecting somebody's own name or their friend's is infuriating.
        val isRealWord = vocabulary.isKnown(lower) || userVocabulary.isKnown(lower)

        val completions = (
            userVocabulary.completions(lower, slots) + vocabulary.completions(lower, slots)
            )
            .distinct()
            .take(slots)
            .map { WordScanner.matchCase(prefix, it) }

        // A prefix that real words start with means the user is mid-word, not mistaken. "hel"
        // should offer hello/help/held, not try to correct it — treating every unfinished word
        // as a typo is what makes a home-made keyboard fight the person using it. Only when
        // nothing in the vocabulary begins with the prefix is a correction the right reading.
        if (isRealWord || completions.isNotEmpty() || !WordScanner.isCorrectable(prefix)) {
            return if (completions.isEmpty()) {
                Suggestions.EMPTY
            } else {
                Suggestions(completions, Kind.COMPLETION)
            }
        }

        // Nothing starts with it, so it is very likely a typo. The spell checker is preferred;
        // the vocabulary scan is a fallback for when it is unavailable or unhelpful.
        val raw = spellCheckerCandidates.ifEmpty { vocabulary.nearby(lower, slots * 2) }
        val ranked = CorrectionRanker.rank(lower, raw)
        if (ranked.isEmpty()) return Suggestions.EMPTY

        val corrections = ranked.map { WordScanner.matchCase(prefix, it.word) }
        val autoApplies = CorrectionRanker.shouldAutoApply(lower, ranked)

        return if (autoApplies) {
            // Stock-keyboard convention: the literal sits on the left and the correction in the
            // centre. Keeping the literal visible is what lets the user decline the correction
            // before it lands instead of undoing it after.
            Suggestions(
                items = listOf(prefix, corrections[0]) + corrections.drop(1).take(slots - 2),
                kind = Kind.CORRECTION,
                autoApplyIndex = 1,
                literalIndex = 0,
            )
        } else {
            Suggestions(corrections.take(slots), Kind.CORRECTION)
        }
    }
}
