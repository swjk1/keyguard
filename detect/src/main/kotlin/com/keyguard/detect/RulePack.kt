package com.keyguard.detect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A versioned, data-only description of what the engine detects.
 *
 * Severity tuning is the single highest-churn part of this product, so it lives in data
 * rather than code: the same pack file feeds Android, the eventual iOS port, and the OTA
 * update endpoint, and retuning ships without an app release.
 */
@Serializable
data class RulePack(
    val version: Int,
    val revision: String,
    /** Free-text provenance/tuning notes. Carried for maintainers; unused at runtime. */
    val notes: String? = null,
    val terms: List<TermRule> = emptyList(),
    val suppressors: List<SuppressorRule> = emptyList(),
    @SerialName("co_occurrence") val coOccurrence: List<CoOccurrenceRule> = emptyList(),
    /** Categories that, if seen in the rolling window, escalate any PII finding. */
    @SerialName("grooming_context_categories")
    val groomingContextCategories: List<Category> = emptyList(),
    /**
     * The topic vocabulary, which is a separate list from [terms] rather than a category on it.
     *
     * They answer different questions and are read by different code at different times —
     * [terms] on every keystroke to decide whether to interrupt, these once per finished
     * message to decide what to tell a parent later. Merging them would put a word list that
     * exists purely for reporting inside the automaton that has a 5ms keystroke budget, and
     * would make "does this term warn the user" a property nobody could read off the entry.
     */
    val themes: List<ThemeRule> = emptyList(),
    val emotions: List<EmotionRule> = emptyList(),
) {
    init {
        require(version > 0) { "rule pack version must be positive" }
        val dupes = terms.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "duplicate term rule ids: $dupes" }
        val themeDupes = themes.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(themeDupes.isEmpty()) { "duplicate theme rule ids: $themeDupes" }
        val emotionDupes = emotions.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(emotionDupes.isEmpty()) { "duplicate emotion rule ids: $emotionDupes" }
    }

    companion object {
        private val json = Json {
            // Tolerate fields a newer pack adds, so an OTA pack can roll out ahead of the
            // app version that understands every key in it.
            ignoreUnknownKeys = true
        }

        fun parse(text: String): RulePack = json.decodeFromString(serializer(), text)

        /** Loads the pack bundled in this module's resources. */
        fun bundled(): RulePack {
            val stream = RulePack::class.java.getResourceAsStream(BUNDLED_PATH)
                ?: error("bundled rule pack missing from resources: $BUNDLED_PATH")
            return parse(stream.bufferedReader().use { it.readText() })
        }

        const val BUNDLED_PATH: String = "/rules/pack-v0.json"
    }
}

/**
 * A lexicon entry. Matching is word-boundary validated by default, which is what keeps
 * "class" from tripping a term like "ass" (the Scunthorpe problem).
 */
@Serializable
data class TermRule(
    val id: String,
    val term: String,
    val category: Category,
    val severity: Int,
    /** Wants AI corroboration before being surfaced with confidence. */
    @SerialName("context_dependent") val contextDependent: Boolean = false,
    /** Message shown to the user. Keep it plain, specific, and non-judgmental. */
    val message: String,
    /**
     * Also match against the separator-stripped form of the text, catching evasions like
     * `f.u.c.k` or `f u c k`. Off by default because it cannot be boundary-validated and
     * so is more false-positive prone; enable only for terms worth the trade.
     */
    @SerialName("check_evasion") val checkEvasion: Boolean = false,
    /** Allow matches mid-word. Off by default. */
    @SerialName("allow_substring") val allowSubstring: Boolean = false,
) {
    init {
        require(term.isNotBlank()) { "term rule $id has a blank term" }
        require(severity in 0..3) { "term rule $id severity out of range: $severity" }
    }

    val severityEnum: Severity get() = Severity.of(severity)
}

/**
 * Cancels a finding when a nearby phrase reveals it as figurative.
 *
 * Ordinary speech, and teenage speech especially, is full of phrases that are lexically
 * identical to serious ones: "I want to die of embarrassment", "I keep cutting myself
 * shaving", "this heat makes me want to die". Firing a crisis intervention at those is
 * actively harmful — it trivialises the real thing and teaches the user to ignore the strip,
 * which is how the genuine signal gets lost.
 *
 * This is a blunt instrument and it will never be complete. Reliably telling hyperbole from
 * intent is a contextual judgement, which is what the AI verification layer is for; these
 * rules only cover the common, checkable cases the local engine can handle alone.
 */
@Serializable
data class SuppressorRule(
    val id: String,
    /** Only findings in this category can be suppressed. */
    val category: Category,
    /** Phrase whose presence nearby marks the finding as figurative. */
    val phrase: String,
    /** Search window on either side of the finding, in characters. */
    @SerialName("within_chars") val withinChars: Int = 40,
    /** Restricts this suppressor to one term rule. Null applies it to the whole category. */
    @SerialName("only_rule_id") val onlyRuleId: String? = null,
) {
    init {
        require(phrase.isNotBlank()) { "suppressor $id has a blank phrase" }
        require(withinChars > 0) { "suppressor $id withinChars must be positive" }
    }
}

/**
 * Escalation when two categories appear near each other. This is where the meeting's
 * "you need the surrounding context" requirement is expressed declaratively — e.g. a home
 * address alone is medium, but a home address next to a meet-up solicitation is high.
 */
@Serializable
data class CoOccurrenceRule(
    val id: String,
    @SerialName("when_category") val whenCategory: Category,
    @SerialName("with_category") val withCategory: Category,
    /** Character distance within the current buffer for the pair to count as related. */
    @SerialName("within_chars") val withinChars: Int = 240,
    @SerialName("result_severity") val resultSeverity: Int,
    val message: String,
) {
    init {
        require(resultSeverity in 0..3) { "co-occurrence $id severity out of range" }
        require(withinChars > 0) { "co-occurrence $id withinChars must be positive" }
    }

    val resultSeverityEnum: Severity get() = Severity.of(resultSeverity)
}

/**
 * A topic word. No severity, no message, no span — nothing here reaches the person typing.
 *
 * Deliberately a much lower bar than [TermRule]. A harm term that fires wrongly interrupts
 * someone mid-sentence, so the lexicon is conservative and anything uncertain is routed to AI
 * verification. A theme term that fires wrongly adds one word to a weekly summary, so breadth
 * is worth more than precision here and the list can be far longer.
 */
@Serializable
data class ThemeRule(
    val id: String,
    val term: String,
    val theme: Theme,
    /** Allow matches mid-word. Useful for stems like "homework" inside "homeworks". */
    @SerialName("allow_substring") val allowSubstring: Boolean = false,
) {
    init {
        require(term.isNotBlank()) { "theme rule $id has a blank term" }
    }
}

/**
 * A mood word.
 *
 * Read [Emotion]'s own note before extending this list: single-message readings from a word
 * list are close to worthless, and the whole value is in the aggregate over a week. Adding a
 * term here should be judged on whether it moves that aggregate honestly, not on whether the
 * word "sounds sad".
 */
@Serializable
data class EmotionRule(
    val id: String,
    val term: String,
    val emotion: Emotion,
    @SerialName("allow_substring") val allowSubstring: Boolean = false,
) {
    init {
        require(term.isNotBlank()) { "emotion rule $id has a blank term" }
    }
}
