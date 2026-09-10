package com.keyguard.detect

/**
 * The topic and mood pass.
 *
 * Structurally the same trick as [LexiconMatcher] — one Aho-Corasick walk over the normalized
 * buffer — but answering a different question and, critically, **not on the keystroke path**.
 * [DetectionEngine.scan] runs per character and has a 5ms budget; this runs once when a message
 * is finished, so it can afford a much larger word list than the harm lexicon without competing
 * for that budget.
 *
 * Three deliberate differences from the harm pass:
 *
 * - **No spans, no severities, no messages.** Nothing here is ever shown to the person typing.
 *   A keyboard that told a teenager "this message reads as anxious" would be insufferable, and
 *   would teach them to write around it, which destroys the signal for the report as well.
 * - **Order is by first appearance, then deduplicated.** A parent reading "school, friendship"
 *   is reading the order the message actually went in, which is a small thing that makes the
 *   list read like an observation rather than a sorted enum.
 * - **Results are capped.** A long message can touch a dozen themes, and a report that lists
 *   all of them says nothing. The cap keeps the dominant few.
 */
internal class ThemeScanner(pack: RulePack) {

    private val themeRules: List<ThemeRule> = pack.themes
    private val themeAutomaton =
        AhoCorasick.build(themeRules.map { Normalizer.normalize(it.term).loose.text })

    private val emotionRules: List<EmotionRule> = pack.emotions
    private val emotionAutomaton =
        AhoCorasick.build(emotionRules.map { Normalizer.normalize(it.term).loose.text })

    fun summarize(buffer: String): MessageSummary {
        if (buffer.isBlank()) return MessageSummary.EMPTY
        val forms = Normalizer.normalize(buffer)

        val themes = LinkedHashSet<Theme>()
        for (hit in themeAutomaton.search(forms.loose.text)) {
            val rule = themeRules[hit.patternIndex]
            if (!rule.allowSubstring && !forms.loose.isWordBounded(hit.start, hit.end)) continue
            themes += rule.theme
        }

        val emotions = LinkedHashSet<Emotion>()
        for (hit in emotionAutomaton.search(forms.loose.text)) {
            val rule = emotionRules[hit.patternIndex]
            if (!rule.allowSubstring && !forms.loose.isWordBounded(hit.start, hit.end)) continue
            emotions += rule.emotion
        }

        return MessageSummary(
            themes = themes.take(MAX_THEMES),
            emotions = emotions.take(MAX_EMOTIONS),
            chars = buffer.length,
        )
    }

    private companion object {
        /**
         * Chosen to be readable in a sentence rather than by measurement. "They talked about
         * school, a friend, and football" is a report; six themes is a tag cloud.
         */
        const val MAX_THEMES = 3
        const val MAX_EMOTIONS = 2
    }
}
