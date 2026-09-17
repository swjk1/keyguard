package com.keyguard.infer

/**
 * The outcome of evaluating one message against the rule table.
 *
 * [fired] carries only the rules at the winning level, matching the Python engine, because that is
 * what explains the warning the user sees. Lower-level rules that also matched are not interesting
 * once a higher one has decided the level.
 */
data class RiskResult(
    val level: Int,
    val fired: List<String> = emptyList(),
    val active: List<String> = emptyList(),
    val message: String = "",
) {
    companion object {
        val NONE = RiskResult(0)
    }
}

/**
 * The deterministic half of the system — §21/§22's rule table, run on the phone.
 *
 * The model emits signals; this decides what they mean. Keeping that boundary sharp is what makes
 * the policy auditable and changeable without retraining: adjusting when an address plus a time
 * becomes a Level 3 warning is an edit to `risk_rules.json`, not a GPU run.
 *
 * The table is *data*, loaded from the same JSON the Python evaluator reads. That is the whole
 * point. Two implementations of one policy drift, and when they drift the numbers in the
 * experiment record stop describing the shipped product — the report would be measuring a policy
 * no phone runs.
 *
 * Levels map onto [com.keyguard.detect.Severity] one-to-one (0 NONE, 1 LOW, 2 MEDIUM, 3 HIGH).
 * That alignment was designed in, so no translation layer belongs here.
 */
class RiskEngine(private val table: RiskRuleTable) {

    val ruleCount: Int get() = table.rules.size

    init {
        val known = (table.contextLabels + table.safetyEntities).toSet()
        val unknown = table.rules
            .flatMap { it.allOf + it.anyOf + it.noneOf }
            .filterNot { it in known }
            .distinct()
        check(unknown.isEmpty()) {
            "risk_rules.json references terms that are neither a context label nor an entity " +
                "bucket: $unknown. A rule naming a term nothing produces can never fire, and " +
                "would do so silently."
        }
    }

    /**
     * Combines thresholded signals and detected entity buckets into a level.
     *
     * Order of the rules does not affect the result — the level is the maximum over everything
     * that matched — so the table stays ordered for readability rather than for precedence.
     */
    fun evaluate(activeSignals: Set<String>, entityBuckets: Set<String>): RiskResult {
        val active = activeSignals + entityBuckets
        val matched = table.rules.filter { it.matches(active) }
        if (matched.isEmpty()) return RiskResult(0, emptyList(), active.sorted())

        val level = matched.maxOf { it.level }
        val winners = matched.filter { it.level == level }
        return RiskResult(
            level = level,
            fired = winners.map { it.id },
            active = active.sorted(),
            message = winners.first().message,
        )
    }

    /** Convenience for the common path: raw probabilities plus the thresholds that gate them. */
    fun evaluate(
        contextProbabilities: FloatArray,
        thresholds: DoubleArray,
        contract: LabelContract,
        entityBuckets: Set<String>,
    ): RiskResult {
        require(contextProbabilities.size == contract.contextLabelCount) {
            "expected ${contract.contextLabelCount} probabilities, got ${contextProbabilities.size}"
        }
        val signals = buildSet {
            for (i in contextProbabilities.indices) {
                if (contextProbabilities[i] >= thresholds[i]) add(contract.contextLabels[i])
            }
        }
        return evaluate(signals, entityBuckets)
    }
}
