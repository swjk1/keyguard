package com.keyguard.detect

/**
 * Layer C — turns raw Layer A/B hits into a scored result.
 *
 * This is where "a phone number is medium, but a phone number next to a request to meet up
 * is high" gets decided. Individually harmless signals combining into a serious one is the
 * whole reason the product needs a context layer at all.
 */
internal class ContextScorer(private val pack: RulePack) {

    fun score(
        raw: List<Finding>,
        buffer: String,
        context: RollingContext,
        nowMs: Long,
    ): ScanResult {
        if (raw.isEmpty()) return ScanResult.EMPTY

        // Suppression runs first: a figurative hit should never reach escalation, and must
        // never reach the crisis path.
        val surviving = applySuppressors(raw, buffer)
        if (surviving.isEmpty()) return ScanResult.EMPTY

        val escalated = applyGroomingContext(applyCoOccurrence(surviving), context, nowMs)
        val merged = mergeOverlapping(escalated)

        val maxSeverity = merged.maxOf { it.severity }
        val requiresCrisis = merged.any { it.category == Category.SELF_HARM }

        return ScanResult(
            findings = merged.sortedWith(compareBy({ it.start }, { it.ruleId })),
            maxSeverity = maxSeverity,
            eligibleForVerification = verifyReason(merged, maxSeverity) != null,
            verifyReason = verifyReason(merged, maxSeverity),
            requiresCrisisResponse = requiresCrisis,
        )
    }

    /**
     * Drops findings that a nearby phrase reveals as figurative.
     *
     * Matched against a lowercased view of the buffer rather than the full normalizer: these
     * are ordinary words, and leetspeak-folding them would create matches of its own.
     */
    private fun applySuppressors(findings: List<Finding>, buffer: String): List<Finding> {
        if (pack.suppressors.isEmpty()) return findings
        val haystack = buffer.lowercase()

        return findings.filterNot { finding ->
            pack.suppressors.any { rule ->
                if (rule.category != finding.category) return@any false
                if (rule.onlyRuleId != null && rule.onlyRuleId != finding.ruleId) return@any false

                val from = (finding.start - rule.withinChars).coerceAtLeast(0)
                val to = (finding.end + rule.withinChars).coerceAtMost(haystack.length)
                if (from >= to) return@any false

                haystack.substring(from, to).contains(rule.phrase.lowercase())
            }
        }
    }

    /** Bumps severity when two categories co-occur closely inside the current buffer. */
    private fun applyCoOccurrence(findings: List<Finding>): List<Finding> {
        if (pack.coOccurrence.isEmpty()) return findings
        return findings.map { finding ->
            var current = finding
            for (rule in pack.coOccurrence) {
                if (finding.category != rule.whenCategory) continue
                val partner = findings.firstOrNull { other ->
                    other.category == rule.withCategory &&
                        other !== finding &&
                        gapBetween(finding, other) <= rule.withinChars
                } ?: continue
                if (rule.resultSeverityEnum.level > current.severity.level) {
                    current = current.copy(
                        severity = rule.resultSeverityEnum,
                        ruleId = rule.id,
                        message = rule.message,
                        // Corroborated by a second signal, so no longer speculative.
                        contextDependent = false,
                    )
                }
                if (partner.severity.level >= Severity.HIGH.level) break
            }
            current
        }
    }

    /**
     * Escalates PII disclosure when the recent conversation already showed grooming-shaped
     * signals. Sharing an address is one thing; sharing it minutes after someone asked to
     * meet in person is the pattern this product exists to interrupt.
     */
    private fun applyGroomingContext(
        findings: List<Finding>,
        context: RollingContext,
        nowMs: Long,
    ): List<Finding> {
        if (pack.groomingContextCategories.isEmpty()) return findings
        val active = context.activeCategories(nowMs)
        if (active.none { it in pack.groomingContextCategories }) return findings

        return findings.map { finding ->
            if (finding.category == Category.PII_DISCLOSURE &&
                finding.severity.level < Severity.HIGH.level
            ) {
                finding.copy(
                    severity = Severity.HIGH,
                    message = finding.message +
                        " Be extra careful — this conversation already raised concerns.",
                    contextDependent = false,
                )
            } else {
                finding
            }
        }
    }

    /**
     * Collapses overlapping findings so the user sees one warning per span rather than a
     * stack of near-duplicates. Highest severity wins; ties break on span length then rule
     * id, purely so the result is deterministic and testable.
     */
    private fun mergeOverlapping(findings: List<Finding>): List<Finding> {
        val ordered = findings.sortedWith(
            compareByDescending<Finding> { it.severity.level }
                .thenByDescending { it.length }
                .thenBy { it.ruleId },
        )
        val kept = ArrayList<Finding>()
        for (candidate in ordered) {
            if (kept.none { it.overlaps(candidate) && it.category == candidate.category }) {
                kept += candidate
            }
        }
        return kept
    }

    private fun verifyReason(findings: List<Finding>, maxSeverity: Severity): VerifyReason? {
        if (findings.isEmpty()) return null

        if (maxSeverity.level >= Severity.HIGH.level) return VerifyReason.ESCALATE

        // A speculative hit with nothing structural backing it up is exactly the case the
        // AI layer earns its cost on — the local rules genuinely cannot resolve it.
        val hasUncorroboratedGuess = findings.any { finding ->
            finding.contextDependent && findings.none { other ->
                other !== finding && !other.contextDependent && other.category == finding.category
            }
        }
        if (hasUncorroboratedGuess) return VerifyReason.AMBIGUOUS

        if (maxSeverity.level >= Severity.LOW.level) return VerifyReason.CONFIRM_FINDING
        return null
    }

    private fun gapBetween(a: Finding, b: Finding): Int =
        if (a.overlaps(b)) 0 else maxOf(a.start, b.start) - minOf(a.end, b.end)
}
