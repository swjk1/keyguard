package com.keyguard.app.model

import com.keyguard.detect.Category
import com.keyguard.detect.Finding
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import com.keyguard.infer.ModelVerdict

/**
 * Folds a model verdict into the rule engine's result.
 *
 * The model is a second opinion layered on top of the rules, never a replacement and never a
 * dependency. Three properties hold, and each is enforced here rather than trusted:
 *
 * **The model can only raise the level.** If it agrees with the rules, or sees less than they do,
 * its verdict changes nothing. That is what keeps the local rule layer standing alone — the thing
 * Apple's 4.4.1 requires independently of anything we want, and the thing that makes the free tier
 * genuinely good rather than a funnel. It also bounds the blast radius of a weak model: the worst
 * a bad probability can do is add a warning, never remove one the rules were right about.
 *
 * **The crisis path is untouchable.** A self-harm scan routes to support and a helpline, never to
 * a privacy warning. [OverlayDecision][com.keyguard.app.overlay.OverlayDecision] already checks
 * crisis before severity, so this is belt and braces — but the invariant is worth enforcing where
 * it could actually be broken, which is here, at the one place something new gets added to a
 * result.
 *
 * **Verification eligibility is not the model's business.** `eligibleForVerification` and
 * `verifyReason` pass through untouched. Whether to spend a cloud call is a budget decision the
 * rules own; letting an on-device signal quietly change it would make the spend depend on a model
 * version.
 */
object ModelFusion {

    /**
     * @param textLength length of the message the verdict describes, used when the model found no
     *   entity to point at — "im home alone till 9" has nothing to excise but itself.
     */
    fun merge(rules: ScanResult, verdict: ModelVerdict, textLength: Int): ScanResult {
        if (verdict.level <= Severity.NONE.level) return rules
        if (rules.requiresCrisisResponse) return rules

        val severity = Severity.of(verdict.level)
        if (severity.level <= rules.maxSeverity.level) return rules
        if (textLength <= 0) return rules

        val finding = finding(verdict, textLength) ?: return rules

        return rules.copy(
            findings = rules.findings + finding,
            maxSeverity = severity,
        )
    }

    /**
     * The span to underline, and the span *Remove it* would delete.
     *
     * When the model located entities, their extent is what the warning is about and what should
     * come out — the address, not the sentence around it. When it located none, the disclosure is
     * the message: there is no substring of "nobody's home till 9" that carries the risk on its
     * own, so the finding covers the whole field and removing it clears the message. That is the
     * honest span rather than a convenient one.
     */
    private fun finding(verdict: ModelVerdict, textLength: Int): Finding? {
        val spans = verdict.spans.filter { it.range.first >= 0 && it.range.last < textLength }
        val start = spans.minOfOrNull { it.range.first } ?: 0
        val end = spans.maxOfOrNull { it.range.last + 1 } ?: textLength
        if (start >= end) return null

        return Finding(
            start = start,
            end = end.coerceAtMost(textLength),
            category = Category.PII_DISCLOSURE,
            severity = Severity.of(verdict.level),
            // The rule the model's signals actually tripped, so a warning on a device can be
            // traced back to a line of risk_rules.json rather than to "the model said so".
            ruleId = verdict.fired.firstOrNull() ?: "risk.model",
            message = verdict.message,
            // The model *is* the contextual judgement. Asking for a second opinion on it would
            // spend a cloud call re-deciding what the local layer was added to decide.
            contextDependent = false,
        )
    }
}
