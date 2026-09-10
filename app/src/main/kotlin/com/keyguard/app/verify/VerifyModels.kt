package com.keyguard.app.verify

import com.keyguard.detect.Category
import com.keyguard.detect.Finding
import com.keyguard.detect.Severity

/**
 * What gets sent for verification.
 *
 * Note what is *absent*: the full buffer, the keystream, any identifier, any history. Only the
 * sentence containing the findings and a bounded slice of prior conversation leave the device,
 * and only when the local engine could not resolve the case alone.
 */
data class VerifyRequest(
    val sentence: String,
    val context: String,
    val findings: List<FindingPayload>,
    val escalate: Boolean,
) {
    data class FindingPayload(
        val ruleId: String,
        val category: Category,
        val severity: Int,
        /** The flagged substring only. */
        val text: String,
    )

    /**
     * Content-addressed cache key. Identical text yields an identical verdict, so hashing the
     * payload means retyping the same message costs nothing.
     */
    fun cacheKey(): Int {
        var hash = sentence.hashCode()
        hash = 31 * hash + context.hashCode()
        hash = 31 * hash + escalate.hashCode()
        for (finding in findings) {
            hash = 31 * hash + finding.ruleId.hashCode()
            hash = 31 * hash + finding.text.hashCode()
        }
        return hash
    }

    companion object {
        /** Matches the server's cap; anything longer is truncated from the front. */
        const val MAX_SENTENCE_CHARS = 1000
        const val MAX_CONTEXT_CHARS = 600

        /**
         * Builds a request from a local scan, carrying only the flagged spans.
         *
         * @return null when there is nothing worth asking about.
         */
        fun from(
            buffer: String,
            findings: List<Finding>,
            context: String,
            escalate: Boolean,
        ): VerifyRequest? {
            val usable = findings.filter { it.start < buffer.length && it.end <= buffer.length }
            if (usable.isEmpty()) return null

            return VerifyRequest(
                sentence = buffer.takeLast(MAX_SENTENCE_CHARS),
                context = context.takeLast(MAX_CONTEXT_CHARS),
                findings = usable.map {
                    FindingPayload(
                        ruleId = it.ruleId,
                        category = it.category,
                        severity = it.severity.level,
                        text = buffer.substring(it.start, it.end),
                    )
                },
                escalate = escalate,
            )
        }
    }
}

/**
 * Who a flagged message is about.
 *
 * The local engine cannot answer this and does not try: "I can't do this anymore" is the same
 * string whether it is despair, a quotation, or a complaint about homework. The distinction
 * that matters most is [REPORTING_SOMEONE_ELSE] — a child telling a friend that another friend
 * said they wanted to die is doing exactly the right thing, and showing them a crisis card
 * aimed at *them* both misreads it and discourages the behaviour we want.
 */
enum class Direction {
    ABOUT_SELF,
    ABOUT_OTHER,
    REPORTING_SOMEONE_ELSE,
    NOT_APPLICABLE,
    ;

    companion object {
        fun fromName(name: String?): Direction =
            entries.firstOrNull { it.name == name } ?: NOT_APPLICABLE
    }
}

/** A per-finding verdict from the model. */
data class Verdict(
    val ruleId: String,
    val confirmed: Boolean,
    val severity: Severity,
    val figurative: Boolean,
    val reason: String,
    val direction: Direction = Direction.NOT_APPLICABLE,
    /** 0..1. Applied as a floor before acting on a downgrade — see [VerdictApplier]. */
    val confidence: Double = 1.0,
)

/** A complete cached response. */
data class CachedVerdict(
    val verdicts: List<Verdict>,
    val suggestedRewrite: String?,
) {
    fun forRule(ruleId: String): Verdict? = verdicts.firstOrNull { it.ruleId == ruleId }
}

/**
 * Applies verdicts to local findings.
 *
 * Unverified findings are left exactly as the local engine reported them. That is the whole
 * fail-open contract: the AI can only refine a result, never gate it, so a missing or partial
 * response degrades to local-only behaviour rather than to nothing.
 */
object VerdictApplier {

    /**
     * Below this, a downgrade is ignored and the local verdict stands.
     *
     * Asymmetric on purpose, and the asymmetry is the whole safety argument for letting a model
     * touch a verdict at all. An *upgrade* is accepted at any confidence, because being wrong
     * costs an unnecessary warning. A *downgrade* removes a warning the local rules raised, so
     * being wrong costs protection — and a model that is unsure is exactly the case where the
     * conservative local answer should win.
     */
    const val MIN_DOWNGRADE_CONFIDENCE = 0.6

    fun apply(findings: List<Finding>, verdict: CachedVerdict?): List<Finding> {
        if (verdict == null) return findings

        return findings.mapNotNull { finding ->
            val decision = verdict.forRule(finding.ruleId) ?: return@mapNotNull finding

            val suppresses = !decision.confirmed ||
                decision.figurative ||
                decision.severity == Severity.NONE
            val downgrades = decision.severity.level < finding.severity.level

            // An unsure downgrade is discarded and the local finding kept exactly as it was.
            // Note this covers suppression too: dropping a finding entirely is the largest
            // downgrade there is, and it is the one a low-confidence model most wants to make.
            if ((suppresses || downgrades) && decision.confidence < MIN_DOWNGRADE_CONFIDENCE) {
                return@mapNotNull finding
            }

            // Figurative or unconfirmed: drop it. This is the case local rules genuinely
            // cannot handle, and the main reason this layer earns its cost.
            if (suppresses) return@mapNotNull null

            finding.copy(
                severity = decision.severity,
                message = decision.reason.ifBlank { finding.message },
                contextDependent = false,
            )
        }
    }

    /**
     * Whether a scan still warrants the crisis path, given what the model said about direction.
     *
     * The local engine sets `requiresCrisisResponse` for any SELF_HARM finding at any severity,
     * which is correct as a default — it must never miss someone — and wrong in one specific
     * case it cannot detect: a message *about* another person's distress. Confirming that with
     * the model and standing down the crisis card is the only way to tell a worried friend
     * something useful instead of handing them a helpline they did not ask for.
     *
     * Fails toward showing the card. No verdict, an unsure verdict, or any finding still
     * pointing at the writer all keep it.
     */
    fun requiresCrisis(findings: List<Finding>, verdict: CachedVerdict?): Boolean {
        val selfHarm = findings.filter { it.category == Category.SELF_HARM }
        if (selfHarm.isEmpty()) return false
        if (verdict == null) return true

        return selfHarm.any { finding ->
            val decision = verdict.forRule(finding.ruleId) ?: return@any true
            if (decision.confidence < MIN_DOWNGRADE_CONFIDENCE) return@any true
            decision.direction != Direction.REPORTING_SOMEONE_ELSE
        }
    }
}
