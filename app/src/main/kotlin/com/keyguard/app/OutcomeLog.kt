package com.keyguard.app

import android.content.Context
import com.keyguard.app.input.ComposeOutcome
import com.keyguard.detect.Severity

/**
 * Counters for whether warnings actually changed behaviour.
 *
 * **Counters only — never text.** The entire telemetry surface is severity plus outcome, so
 * there is no code path by which what the user typed could reach storage, a log, a crash
 * report, or a server.
 *
 * This exists to answer the only question that proves the product does anything: how often
 * did a warning change what got sent? Held in memory it died with the process, which meant a
 * whole round of testing produced an anecdote instead of a number.
 */
class OutcomeLog(context: Context) {

    data class Counters(
        val warningsShown: Int = 0,
        /** A warning was shown and the message was then deleted rather than sent. */
        val heeded: Int = 0,
        val sentAnyway: Int = 0,
        val abandonedOther: Int = 0,
    ) {
        val decided: Int get() = heeded + sentAnyway

        /**
         * Share of decided outcomes where the warning changed the result, or null when there
         * is not yet anything to divide by — an honest "no data" rather than a misleading 0%.
         */
        val heedRate: Double? get() = if (decided == 0) null else heeded.toDouble() / decided
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(severity: Severity, outcome: ComposeOutcome, heeded: Boolean) {
        val field = when {
            heeded -> FIELD_HEEDED
            outcome == ComposeOutcome.SENT || outcome == ComposeOutcome.SENT_INFERRED -> FIELD_SENT
            else -> FIELD_ABANDONED
        }
        prefs.edit()
            .putInt(key(severity, FIELD_SHOWN), prefs.getInt(key(severity, FIELD_SHOWN), 0) + 1)
            .putInt(key(severity, field), prefs.getInt(key(severity, field), 0) + 1)
            .apply()
    }

    fun counters(severity: Severity): Counters = Counters(
        warningsShown = prefs.getInt(key(severity, FIELD_SHOWN), 0),
        heeded = prefs.getInt(key(severity, FIELD_HEEDED), 0),
        sentAnyway = prefs.getInt(key(severity, FIELD_SENT), 0),
        abandonedOther = prefs.getInt(key(severity, FIELD_ABANDONED), 0),
    )

    /** Totals across every severity that renders a warning. */
    fun total(): Counters = reportedSeverities.map(::counters).fold(Counters()) { acc, c ->
        Counters(
            warningsShown = acc.warningsShown + c.warningsShown,
            heeded = acc.heeded + c.heeded,
            sentAnyway = acc.sentAnyway + c.sentAnyway,
            abandonedOther = acc.abandonedOther + c.abandonedOther,
        )
    }

    fun clear() = prefs.edit().clear().apply()

    private fun key(severity: Severity, field: String) = "${severity.name}_$field"

    companion object {
        /** Severity 0 never renders, so it never produces an outcome worth counting. */
        val reportedSeverities = listOf(Severity.LOW, Severity.MEDIUM, Severity.HIGH)

        private const val PREFS_NAME = "keyguard_outcomes"
        private const val FIELD_SHOWN = "shown"
        private const val FIELD_HEEDED = "heeded"
        private const val FIELD_SENT = "sent"
        private const val FIELD_ABANDONED = "abandoned"
    }
}
