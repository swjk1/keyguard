package com.keyguard.detect

/**
 * Layer B — the curated lexicon pass.
 *
 * Two automatons are built: one for the boundary-checked `loose` form (all terms), and one
 * for the separator-stripped `tight` form (only terms opted into evasion checking, since
 * boundaries cannot be validated there and the false-positive risk is higher).
 */
internal class LexiconMatcher(private val pack: RulePack) {

    private val looseTerms: List<TermRule> = pack.terms
    private val looseAutomaton = AhoCorasick.build(looseTerms.map { normalizeTerm(it.term).loose.text })

    private val evasionTerms: List<TermRule> = pack.terms.filter { it.checkEvasion }
    private val evasionAutomaton =
        AhoCorasick.build(evasionTerms.map { normalizeTerm(it.term).tight.text })

    fun match(forms: NormalizedForms): List<Finding> {
        val findings = ArrayList<Finding>()

        for (hit in looseAutomaton.search(forms.loose.text)) {
            val rule = looseTerms[hit.patternIndex]
            if (!rule.allowSubstring && !forms.loose.isWordBounded(hit.start, hit.end)) continue
            findings += toFinding(rule, forms.loose.mapSpan(hit.start, hit.end))
        }

        for (hit in evasionAutomaton.search(forms.tight.text)) {
            val rule = evasionTerms[hit.patternIndex]
            findings += toFinding(rule, forms.tight.mapSpan(hit.start, hit.end))
        }

        return findings
    }

    private fun toFinding(rule: TermRule, span: IntRange) = Finding(
        start = span.first,
        end = span.last + 1,
        category = rule.category,
        severity = rule.severityEnum,
        ruleId = rule.id,
        message = rule.message,
        contextDependent = rule.contextDependent,
    )

    private companion object {
        /** Terms are stored human-readably in the pack, so they need the same folding as input. */
        fun normalizeTerm(term: String): NormalizedForms = Normalizer.normalize(term)
    }
}
