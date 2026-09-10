package com.keyguard.detect

/**
 * Layer A — structured PII matchers.
 *
 * These run against the *original* buffer, not the normalized form, because structure is
 * the signal: dots, digits, and separators are what distinguish an email or a card number
 * from prose. Normalization would destroy exactly the evidence these rules depend on.
 *
 * Precision is the priority here. A keyboard that cries wolf gets uninstalled, and these
 * are the rules that fire most often, so anything speculative belongs in the lexicon with
 * `context_dependent` set instead.
 */
internal object PiiMatchers {

    private class Matcher(
        val ruleId: String,
        val regex: Regex,
        val category: Category,
        val severity: Severity,
        val message: String,
        val contextDependent: Boolean = false,
        /** Optional extra check; a match is discarded when this returns false. */
        val validate: ((MatchResult) -> Boolean)? = null,
        /** Which capture group to underline. 0 = the whole match. */
        val spanGroup: Int = 0,
    )

    private val matchers: List<Matcher> = listOf(
        Matcher(
            ruleId = "pii.email",
            regex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}"""),
            category = Category.PII_DISCLOSURE,
            severity = Severity.MEDIUM,
            message = "This looks like an email address.",
        ),
        Matcher(
            ruleId = "pii.phone.nanp",
            regex = Regex("""(?<![\d\-])(?:\+?1[ .\-]?)?\(?[2-9]\d{2}\)?[ .\-]?\d{3}[ .\-]?\d{4}(?![\d\-])"""),
            category = Category.PII_DISCLOSURE,
            severity = Severity.MEDIUM,
            message = "This looks like a phone number.",
        ),
        Matcher(
            ruleId = "pii.phone.intl",
            regex = Regex("""\+(?:\d[ .\-]?){7,14}\d"""),
            category = Category.PII_DISCLOSURE,
            severity = Severity.MEDIUM,
            message = "This looks like a phone number.",
        ),
        Matcher(
            ruleId = "pii.credit_card",
            regex = Regex("""(?<!\d)(?:\d[ \-]?){12,18}\d(?!\d)"""),
            category = Category.PII_DISCLOSURE,
            severity = Severity.HIGH,
            message = "This looks like a payment card number. Never send this in a message.",
            validate = { luhnValid(it.value) },
        ),
        Matcher(
            ruleId = "pii.ssn",
            regex = Regex("""(?<!\d)(?!000|666|9\d\d)\d{3}[ \-](?!00)\d{2}[ \-](?!0000)\d{4}(?!\d)"""),
            category = Category.PII_DISCLOSURE,
            severity = Severity.HIGH,
            message = "This looks like a Social Security number.",
        ),
        Matcher(
            ruleId = "pii.iban",
            regex = Regex("""(?<![A-Za-z0-9])[A-Z]{2}\d{2}[A-Z0-9]{11,30}(?![A-Za-z0-9])"""),
            category = Category.PII_DISCLOSURE,
            severity = Severity.HIGH,
            message = "This looks like a bank account number.",
        ),
        Matcher(
            // A minor's home address is the highest-stakes disclosure this product exists
            // to prevent, so it is HIGH on its own rather than waiting for corroboration.
            ruleId = "pii.street_address",
            regex = Regex(
                """(?<!\d)\d{1,6}\s+(?:[A-Za-z0-9.'\-]+\s+){0,3}""" +
                    """(?:street|st|avenue|ave|road|rd|boulevard|blvd|lane|ln|drive|dr|""" +
                    """court|ct|circle|cir|place|pl|way|terrace|ter|parkway|pkwy|highway|hwy)\b\.?""",
                RegexOption.IGNORE_CASE,
            ),
            category = Category.PII_DISCLOSURE,
            severity = Severity.HIGH,
            message = "This looks like a street address.",
        ),
        Matcher(
            ruleId = "pii.postal.us",
            regex = Regex("""(?<![\d\-])\d{5}(?:-\d{4})?(?![\d\-])"""),
            category = Category.PII_DISCLOSURE,
            severity = Severity.LOW,
            message = "This might be a ZIP code.",
            contextDependent = true,
        ),
        Matcher(
            ruleId = "pii.postal.ca",
            regex = Regex(
                """(?<![A-Za-z0-9])[ABCEGHJ-NPRSTVXY]\d[ABCEGHJ-NPRSTV-Z][ \-]?\d[ABCEGHJ-NPRSTV-Z]\d(?![A-Za-z0-9])""",
                RegexOption.IGNORE_CASE,
            ),
            category = Category.PII_DISCLOSURE,
            severity = Severity.LOW,
            message = "This might be a postal code.",
            contextDependent = true,
        ),
        Matcher(
            ruleId = "pii.social_handle",
            regex = Regex("""(?<![A-Za-z0-9_@])@[A-Za-z0-9._]{3,30}(?![A-Za-z0-9._])"""),
            category = Category.PII_DISCLOSURE,
            severity = Severity.LOW,
            message = "This looks like a social media handle.",
            contextDependent = true,
        ),
        Matcher(
            ruleId = "pii.self_disclose.name",
            regex = Regex(
                """\b(?:my name(?:'s| is)|i'?m called|you can call me)\s+([A-Za-z][A-Za-z'\-]{1,20})""",
                RegexOption.IGNORE_CASE,
            ),
            category = Category.PII_DISCLOSURE,
            severity = Severity.MEDIUM,
            message = "You're sharing your name.",
            spanGroup = 1,
        ),
        Matcher(
            ruleId = "pii.self_disclose.age_minor",
            regex = Regex(
                """\b(?:i'?m|i am|im)\s+(\d{1,2})\s*(?:years?\s*old|yrs?\s*old|yo|y/o)?\b""",
                RegexOption.IGNORE_CASE,
            ),
            category = Category.PII_DISCLOSURE,
            severity = Severity.MEDIUM,
            message = "You're sharing your age.",
            // Only treat a plausible minor age as a disclosure worth flagging; "I'm 200"
            // or "I'm 42" in casual chat is noise for this product's audience.
            validate = { (it.groupValues[1].toIntOrNull() ?: -1) in 5..17 },
            spanGroup = 0,
        ),
        Matcher(
            ruleId = "pii.self_disclose.school",
            regex = Regex(
                """\bi\s+(?:go\s+to|attend|study\s+at)\s+(?:[A-Za-z][\w'.\-]*\s+){0,4}""" +
                    """(?:school|academy|high|middle|elementary|junior\s+high|college|university)\b""",
                RegexOption.IGNORE_CASE,
            ),
            category = Category.PII_DISCLOSURE,
            severity = Severity.MEDIUM,
            message = "You're sharing where you go to school.",
        ),
        Matcher(
            ruleId = "pii.self_disclose.location",
            regex = Regex(
                """\bi\s+live\s+(?:in|at|near|on|by)\s+(?:the\s+)?([A-Za-z0-9][\w'.\-]*(?:\s+[\w'.\-]+){0,3})""",
                RegexOption.IGNORE_CASE,
            ),
            category = Category.PII_DISCLOSURE,
            severity = Severity.MEDIUM,
            message = "You're sharing where you live.",
        ),
    )

    fun match(text: String): List<Finding> {
        if (text.isEmpty()) return emptyList()
        val findings = ArrayList<Finding>()
        for (matcher in matchers) {
            for (result in matcher.regex.findAll(text)) {
                if (matcher.validate?.invoke(result) == false) continue
                val group = result.groups[matcher.spanGroup] ?: result.groups[0]!!
                findings += Finding(
                    start = group.range.first,
                    end = group.range.last + 1,
                    category = matcher.category,
                    severity = matcher.severity,
                    ruleId = matcher.ruleId,
                    message = matcher.message,
                    contextDependent = matcher.contextDependent,
                )
            }
        }
        return findings
    }

    /**
     * Luhn checksum. Without it the card-number pattern matches any long digit run — dates,
     * order numbers, game IDs — which would make the highest-severity rule the noisiest one.
     */
    internal fun luhnValid(candidate: String): Boolean {
        var sum = 0
        var alternate = false
        var digits = 0
        for (i in candidate.indices.reversed()) {
            val c = candidate[i]
            if (!c.isDigit()) continue
            digits++
            var value = c - '0'
            if (alternate) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
            alternate = !alternate
        }
        return digits in 13..19 && sum % 10 == 0
    }
}
