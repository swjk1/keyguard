package com.keyguard.app.family

import com.keyguard.app.input.ComposeOutcome
import com.keyguard.detect.Emotion
import com.keyguard.detect.MessageSummary
import com.keyguard.detect.Theme
import org.json.JSONArray
import org.json.JSONObject

/**
 * One composed message, as a parent report may see it.
 *
 * Deliberately a **different type from [SupervisionEvent]**, even though the two travel the
 * same road and could have been one class with nullable fields. They are separated because
 * they carry different promises:
 *
 * - [SupervisionEvent] is a warning. It exists at every [ReviewScope], has no text field at
 *   all, and its test asserts that against the serialized bytes.
 * - [ActivitySample] is an ordinary message. It exists only above [ReviewScope.CONCERNING_ONLY]
 *   and it *may* carry text.
 *
 * Merging them would have put a nullable `text` on the type the codebase's strongest privacy
 * assertion is written against, and the assertion would have had to become "text is null here",
 * which is a much weaker thing to promise than "there is nowhere to put text". Keeping them
 * apart means a reader can still tell, from the class alone, which records can contain what
 * someone wrote.
 *
 * [text] is non-null only at [ReviewScope.FULL_TEXT]. Nothing constructs one of these directly
 * — [ContentCapture] is the only gate, and it is the place to look for what a scope permits.
 */
data class ActivitySample(
    /** Device wall clock, epoch millis. Untrusted, like [SupervisionEvent.at]. */
    val at: Long,
    val themes: List<Theme>,
    val emotions: List<Emotion>,
    /** Length of the composed message. A volume signal that survives every scope. */
    val chars: Int,
    /** Whether this message also produced a warning. Lets a report weight its own history. */
    val flagged: Boolean,
    val outcome: ComposeOutcome,
    /**
     * What the child wrote, or null.
     *
     * Non-null only under [ReviewScope.FULL_TEXT]. Truncated to [MAX_TEXT] — a report is not
     * an archive, and an unbounded field here would make one child's chatty afternoon the
     * largest object in the store.
     */
    val text: String?,
) {

    fun toJson(): JSONObject {
        val json = JSONObject()
            .put(FIELD_AT, at)
            .put(FIELD_THEMES, JSONArray().apply { themes.forEach { put(it.name) } })
            .put(FIELD_EMOTIONS, JSONArray().apply { emotions.forEach { put(it.name) } })
            .put(FIELD_CHARS, chars)
            .put(FIELD_FLAGGED, flagged)
            .put(FIELD_OUTCOME, outcome.name)
        // Omitted rather than sent as null, so a CONCERNING_ONLY or THEMES payload has no text
        // key at all. A server or a log reader can then tell the two scopes apart by shape,
        // rather than by trusting that a null meant what it was supposed to mean.
        if (text != null) json.put(FIELD_TEXT, text)
        return json
    }

    companion object {
        const val MAX_TEXT = 500

        private const val FIELD_AT = "at"
        private const val FIELD_THEMES = "themes"
        private const val FIELD_EMOTIONS = "emotions"
        private const val FIELD_CHARS = "chars"
        private const val FIELD_FLAGGED = "flagged"
        private const val FIELD_OUTCOME = "outcome"
        private const val FIELD_TEXT = "text"

        /**
         * Rebuilds a sample, or null if it is unreadable.
         *
         * Unknown theme and emotion names are *dropped* rather than failing the whole sample,
         * which is the opposite of [SupervisionEvent.fromJson]'s rule for categories. The
         * asymmetry is deliberate: a category decides how a warning is described to a parent
         * and getting it wrong miscategorises an incident, whereas a theme is one word in a
         * weekly summary and losing it costs nothing. A queue written by a newer build should
         * degrade here, not disappear.
         */
        fun fromJson(json: JSONObject): ActivitySample? = runCatching {
            ActivitySample(
                at = json.getLong(FIELD_AT),
                themes = json.optJSONArray(FIELD_THEMES).names(Theme::fromName),
                emotions = json.optJSONArray(FIELD_EMOTIONS).names(Emotion::fromName),
                chars = json.getInt(FIELD_CHARS),
                flagged = json.getBoolean(FIELD_FLAGGED),
                outcome = ComposeOutcome.valueOf(json.getString(FIELD_OUTCOME)),
                text = if (json.has(FIELD_TEXT)) json.getString(FIELD_TEXT) else null,
            )
        }.getOrNull()

        private fun <T> JSONArray?.names(parse: (String) -> T?): List<T> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank)?.let(parse) }
        }
    }
}

/**
 * The one place that decides what a review scope permits to be recorded.
 *
 * Pure and Android-free so every branch is testable, in the same spirit as [FamilyPolicy] and
 * [com.keyguard.app.input.InputGate] — and for a stronger reason than either. This is the
 * function that converts a parent's setting into "does what my child wrote leave this phone",
 * and it is the only one. A second copy of this logic at a call site is how a scope ends up
 * being honoured in one code path and not another, which for this particular setting means
 * silently collecting text a family did not agree to.
 */
object ContentCapture {

    /**
     * The sample to queue for this finished message, or null if this scope records nothing.
     *
     * @param protectedField whether the field was one that is never scanned. Passed in and
     *   checked here as well as at the call site, because "no parental setting can reach past
     *   [com.keyguard.detect.FieldPolicy]" is a claim worth enforcing twice.
     */
    fun sample(
        scope: ReviewScope,
        summary: MessageSummary,
        outcome: ComposeOutcome,
        atMs: Long,
        flagged: Boolean,
        text: String,
        protectedField: Boolean,
    ): ActivitySample? {
        if (protectedField) return null
        if (!scope.recordsEveryMessage) return null
        if (text.isBlank()) return null

        return ActivitySample(
            at = atMs,
            themes = summary.themes,
            emotions = summary.emotions,
            chars = text.length,
            flagged = flagged,
            outcome = outcome,
            // The whole scope decision, in one expression. Everything above this line is the
            // same at THEMES and FULL_TEXT; this is the only line where they differ.
            text = if (scope.recordsText) text.take(ActivitySample.MAX_TEXT) else null,
        )
    }
}
