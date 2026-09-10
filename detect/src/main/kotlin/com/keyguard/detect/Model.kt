package com.keyguard.detect

/**
 * Harm categories the engine can report.
 *
 * [SELF_HARM] is deliberately not a "you are leaking data" category. It routes to a
 * crisis-resource response, never to a privacy warning and never to a reporting threat.
 * See [ScanResult.requiresCrisisResponse].
 */
enum class Category {
    PII_DISCLOSURE,
    HARASSMENT,
    SEXUAL_SOLICITATION,
    SELF_HARM,
    VIOLENCE_THREAT,
    IN_PERSON_MEETUP,
    SUBSTANCE,
}

enum class Severity(val level: Int) {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    ;

    companion object {
        fun of(level: Int): Severity = entries.firstOrNull { it.level == level }
            ?: throw IllegalArgumentException("severity level out of range 0..3: $level")
    }
}

/**
 * A single detection, located by offsets into the *original* buffer.
 *
 * Note there is deliberately no `matchedText` field. The engine never carries raw user
 * text in its output, so no caller can accidentally log, persist, or transmit it. The UI
 * already holds the buffer and slices [start]..[end] itself when it needs to render.
 */
data class Finding(
    val start: Int,
    val end: Int,
    val category: Category,
    val severity: Severity,
    val ruleId: String,
    val message: String,
    /**
     * True when this hit cannot be trusted on lexical evidence alone and wants a
     * second opinion — the primary trigger for AI verification.
     */
    val contextDependent: Boolean = false,
) {
    init {
        require(start in 0..end) { "invalid span $start..$end" }
    }

    val length: Int get() = end - start

    fun overlaps(other: Finding): Boolean = start < other.end && other.start < end
}

/** Why the engine believes an AI verification pass would add value. */
enum class VerifyReason {
    /** A context-dependent lexicon hit with no structured corroboration. */
    AMBIGUOUS,

    /** A confident finding worth double-checking to suppress false positives. */
    CONFIRM_FINDING,

    /** High severity — escalate for a careful read of the surrounding context. */
    ESCALATE,
}

data class ScanResult(
    val findings: List<Finding>,
    val maxSeverity: Severity,
    /**
     * Whether this scan is *eligible* for an AI verification call. The engine only reports
     * eligibility; the caller still applies debounce, result caching, tier budget, and the
     * password-field guard before actually making a request.
     */
    val eligibleForVerification: Boolean,
    val verifyReason: VerifyReason?,
    /**
     * Set when a [Category.SELF_HARM] signal is present. The caller must show crisis
     * resources and supportive copy, and must NOT show a privacy warning or any
     * "this will be reported" messaging for this scan.
     */
    val requiresCrisisResponse: Boolean,
) {
    companion object {
        val EMPTY = ScanResult(
            findings = emptyList(),
            maxSeverity = Severity.NONE,
            eligibleForVerification = false,
            verifyReason = null,
            requiresCrisisResponse = false,
        )
    }
}
