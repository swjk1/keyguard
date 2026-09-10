package com.keyguard.app.family

import com.keyguard.detect.Emotion
import com.keyguard.detect.Theme
import org.json.JSONArray
import org.json.JSONObject

/** Daily or weekly. Two windows, because they answer different questions. */
enum class ReportPeriod {
    /** "Was today alright?" Read most often, and most often uneventful. */
    DAY,

    /** "Is this a pattern or a bad day?" The window where the mood signal starts to mean something. */
    WEEK,
    ;

    companion object {
        fun fromName(name: String?): ReportPeriod =
            entries.firstOrNull { it.name == name } ?: DAY
    }
}

data class ThemeCount(val theme: Theme, val count: Int)

data class EmotionCount(val emotion: Emotion, val count: Int)

/**
 * What a parent reads.
 *
 * The narrative fields ([summary], [concerns], [interests]) are written by the model server-side
 * from whatever the review scope allowed to be collected; the counts are arithmetic done on the
 * same data. Both are carried so the screen can show the numbers underneath the sentence —
 * a summary a parent cannot check against anything is a summary they are asked to take on
 * faith, and this is not a product that has earned that.
 *
 * [basis] is the honesty field and is rendered, not just carried. A report built at
 * [ReviewScope.THEMES] is a genuinely different object from one built at
 * [ReviewScope.FULL_TEXT] — the first is inference from tags, the second is a reading of what
 * was actually written — and a parent who cannot tell which they are holding will over-trust
 * the weaker one. It also means a child who asks "what does my mum actually see" gets an answer
 * that matches the screen their mum is looking at.
 */
data class FamilyReport(
    val period: ReportPeriod,
    /** Window covered, epoch millis, half-open. */
    val from: Long,
    val to: Long,
    val childInstallId: String,
    val childLabel: String,
    /** Messages the window drew on. Zero means the summary is a placeholder, not a finding. */
    val messageCount: Int,
    val flaggedCount: Int,
    val themes: List<ThemeCount>,
    val emotions: List<EmotionCount>,
    /**
     * Things a parent might want to look at, in plain language.
     *
     * Deliberately not "incidents". A concern here can be "three anxious evenings in a row",
     * which is not a warning, was not flagged, and is exactly the sort of thing the warning
     * stream alone could never surface.
     */
    val concerns: List<String>,
    /** What the child seems to be enjoying. The half of the report that is not about risk. */
    val interests: List<String>,
    /** The narrative. One short paragraph; the screen shows it above everything else. */
    val summary: String,
    val generatedAt: Long,
    /** Which scope the underlying data was collected under. See the class note. */
    val basis: ReviewScope,
    /** Model that wrote the narrative, or null when it was composed without one. */
    val model: String?,
) {
    /** True when there was nothing to summarize, so the screen can say so plainly. */
    val isEmpty: Boolean get() = messageCount == 0 && flaggedCount == 0

    companion object {
        fun fromJson(json: JSONObject): FamilyReport? = runCatching {
            FamilyReport(
                period = ReportPeriod.fromName(json.optString("period")),
                from = json.optLong("from"),
                to = json.optLong("to"),
                childInstallId = json.optString("childInstallId"),
                childLabel = json.optString("childLabel").ifBlank { "Phone" },
                messageCount = json.optInt("messageCount"),
                flaggedCount = json.optInt("flaggedCount"),
                themes = json.optJSONArray("themes").mapObjects { item ->
                    Theme.fromName(item.optString("theme"))?.let {
                        ThemeCount(it, item.optInt("count"))
                    }
                },
                emotions = json.optJSONArray("emotions").mapObjects { item ->
                    Emotion.fromName(item.optString("emotion"))?.let {
                        EmotionCount(it, item.optInt("count"))
                    }
                },
                concerns = json.optJSONArray("concerns").strings(),
                interests = json.optJSONArray("interests").strings(),
                summary = json.optString("summary"),
                generatedAt = json.optLong("generatedAt"),
                basis = ReviewScope.fromName(json.optString("basis")),
                model = json.optString("model").takeIf { it.isNotBlank() },
            )
        }.getOrNull()

        private fun <T> JSONArray?.mapObjects(parse: (JSONObject) -> T?): List<T> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optJSONObject(it)?.let(parse) }
        }

        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
        }
    }
}
