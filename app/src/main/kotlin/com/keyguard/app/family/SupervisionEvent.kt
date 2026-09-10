package com.keyguard.app.family

import com.keyguard.app.input.ComposeOutcome
import com.keyguard.detect.Category
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import org.json.JSONObject

/**
 * One warning, as a parent sees it.
 *
 * **There is deliberately no text field**, in the same spirit as [com.keyguard.detect.Finding]
 * and for a stronger reason: this is the one record in the product that is designed to leave
 * the device and be read by someone other than the person who typed it. Category, severity and
 * outcome are enough to tell a parent that something happened and whether the warning worked,
 * and they are the most that survives a store review of a keyboard that reports on its user.
 * If a snippet field ever appears here, the Play Data Safety declaration, the child-facing
 * disclosure, and Apple's 4.4.1 argument all change at once — it is not a small addition.
 *
 * Nor is the host app recorded. Which app a child was typing in is genuinely useful to a
 * parent and is a materially larger surveillance surface than "a self-harm warning fired at
 * 4pm"; it is left out until someone decides to defend it on purpose.
 *
 * The timestamp is the child device's clock, so it can be wrong or deliberately moved. The
 * server stamps its own receipt time as well, and the parent view prefers that for ordering.
 */
data class SupervisionEvent(
    /** Device wall clock, epoch millis. Untrusted; see the class note. */
    val at: Long,
    val category: Category,
    val severity: Severity,
    val outcome: ComposeOutcome,
    /** The warning changed the result: flagged content was deleted rather than sent. */
    val heeded: Boolean,
) {

    fun toJson(): JSONObject = JSONObject()
        .put(FIELD_AT, at)
        .put(FIELD_CATEGORY, category.name)
        .put(FIELD_SEVERITY, severity.level)
        .put(FIELD_OUTCOME, outcome.name)
        .put(FIELD_HEEDED, heeded)

    companion object {
        private const val FIELD_AT = "at"
        private const val FIELD_CATEGORY = "category"
        private const val FIELD_SEVERITY = "severity"
        private const val FIELD_OUTCOME = "outcome"
        private const val FIELD_HEEDED = "heeded"

        /**
         * Rebuilds an event, or null if any field is missing or out of range.
         *
         * Unknown enum names return null rather than a default. A queue written by a newer
         * build should be dropped, not silently miscategorised into someone's activity feed.
         */
        fun fromJson(json: JSONObject): SupervisionEvent? = runCatching {
            SupervisionEvent(
                at = json.getLong(FIELD_AT),
                category = Category.valueOf(json.getString(FIELD_CATEGORY)),
                severity = Severity.of(json.getInt(FIELD_SEVERITY)),
                outcome = ComposeOutcome.valueOf(json.getString(FIELD_OUTCOME)),
                heeded = json.getBoolean(FIELD_HEEDED),
            )
        }.getOrNull()

        /**
         * The category a scan should be reported under: the one carrying the highest severity.
         *
         * A scan can hold several findings and a parent needs one line, not a list. Ties go to
         * the first finding, which is document order — arbitrary but stable, so the same buffer
         * always reports the same way.
         *
         * Null when nothing was found, which is the caller's signal that there is no event.
         */
        fun dominantCategory(result: ScanResult): Category? =
            result.findings.maxByOrNull { it.severity.level }?.category
    }
}
