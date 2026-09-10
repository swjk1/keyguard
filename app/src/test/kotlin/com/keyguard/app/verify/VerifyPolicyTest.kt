package com.keyguard.app.verify

import com.keyguard.detect.Category
import com.keyguard.detect.Finding
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import com.keyguard.detect.VerifyReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every branch in the policy is a cost decision, which is why it is asserted this closely —
 * the planning meeting's longest argument was per-user inference spend, and this is where the
 * answer lives.
 */
class VerifyPolicyTest {

    private fun finding(
        severity: Severity = Severity.MEDIUM,
        ruleId: String = "test.rule",
        contextDependent: Boolean = false,
    ) = Finding(
        start = 0,
        end = 5,
        category = Category.PII_DISCLOSURE,
        severity = severity,
        ruleId = ruleId,
        message = "test",
        contextDependent = contextDependent,
    )

    private fun result(
        eligible: Boolean = true,
        severity: Severity = Severity.MEDIUM,
        reason: VerifyReason? = VerifyReason.CONFIRM_FINDING,
    ) = ScanResult(
        findings = listOf(finding(severity)),
        maxSeverity = severity,
        eligibleForVerification = eligible,
        verifyReason = reason,
        requiresCrisisResponse = false,
    )

    private fun request(sentence: String = "my address is 1 Main St") = VerifyRequest(
        sentence = sentence,
        context = "",
        findings = listOf(
            VerifyRequest.FindingPayload("test.rule", Category.PII_DISCLOSURE, 2, "1 Main St"),
        ),
        escalate = false,
    )

    private fun verdict() = CachedVerdict(
        verdicts = listOf(
            Verdict("test.rule", confirmed = true, severity = Severity.HIGH, figurative = false, reason = "r"),
        ),
        suggestedRewrite = null,
    )

    @Test
    fun `ineligible scans are never sent`() {
        val policy = VerifyPolicy()
        assertEquals(
            VerifyPolicy.Decision.NOT_ELIGIBLE,
            policy.decide(result(eligible = false), textLength = 20, nowMs = 1_000),
        )
    }

    @Test
    fun `the first eligible scan is sent`() {
        val policy = VerifyPolicy()
        assertEquals(
            VerifyPolicy.Decision.SEND,
            policy.decide(result(), textLength = 20, nowMs = 1_000),
        )
    }

    @Test
    fun `rapid typing is debounced`() {
        val policy = VerifyPolicy(debounceMs = 350, minCharsBetweenCalls = 12)
        policy.onCallStarted(nowMs = 1_000, textLength = 20)
        policy.onCallFinished(request(), verdict())

        // 100ms later and only two characters changed: not worth another call.
        assertEquals(
            VerifyPolicy.Decision.DEBOUNCING,
            policy.decide(result(), textLength = 22, nowMs = 1_100),
        )
    }

    @Test
    fun `a large edit bypasses the debounce`() {
        val policy = VerifyPolicy(debounceMs = 350, minCharsBetweenCalls = 12)
        policy.onCallStarted(nowMs = 1_000, textLength = 20)
        policy.onCallFinished(request(), verdict())

        // Enough new text that the previous verdict is likely stale.
        assertEquals(
            VerifyPolicy.Decision.SEND,
            policy.decide(result(), textLength = 40, nowMs = 1_100),
        )
    }

    @Test
    fun `high severity skips the debounce entirely`() {
        // Waiting for a typing pause on a serious finding could mean the message is already
        // sent by the time a verdict arrives.
        val policy = VerifyPolicy(debounceMs = 350)
        policy.onCallStarted(nowMs = 1_000, textLength = 20)
        policy.onCallFinished(request(), verdict())

        assertEquals(
            VerifyPolicy.Decision.SEND,
            policy.decide(
                result(severity = Severity.HIGH, reason = VerifyReason.ESCALATE),
                textLength = 21,
                nowMs = 1_050,
            ),
        )
    }

    @Test
    fun `only one call is in flight at a time`() {
        val policy = VerifyPolicy()
        policy.onCallStarted(nowMs = 1_000, textLength = 20)

        assertEquals(
            VerifyPolicy.Decision.ALREADY_IN_FLIGHT,
            policy.decide(result(), textLength = 60, nowMs = 2_000),
        )
    }

    @Test
    fun `the daily budget is a hard ceiling`() {
        val policy = VerifyPolicy(debounceMs = 0, dailyBudget = 3)
        repeat(3) { index ->
            val at = 10_000L + index * 5_000
            assertEquals(VerifyPolicy.Decision.SEND, policy.decide(result(), 20, at))
            policy.onCallStarted(at, 20)
            policy.onCallFinished(request("call $index"), verdict())
        }

        assertEquals(
            VerifyPolicy.Decision.BUDGET_EXHAUSTED,
            policy.decide(result(), textLength = 20, nowMs = 30_000),
        )
        assertEquals(3, policy.callsUsedToday)
    }

    @Test
    fun `the budget resets the next day`() {
        val policy = VerifyPolicy(debounceMs = 0, dailyBudget = 1)
        policy.onCallStarted(nowMs = 1_000, textLength = 20)
        policy.onCallFinished(request(), verdict())
        assertEquals(
            VerifyPolicy.Decision.BUDGET_EXHAUSTED,
            policy.decide(result(), 20, nowMs = 2_000),
        )

        val nextDay = 25L * 60 * 60 * 1000
        assertEquals(VerifyPolicy.Decision.SEND, policy.decide(result(), 20, nextDay))
    }

    @Test
    fun `identical text reuses the cached verdict`() {
        // Retyping the same message must cost nothing; this is the cheapest saving available.
        val policy = VerifyPolicy()
        val req = request()
        assertNull(policy.cached(req))

        policy.onCallStarted(nowMs = 1_000, textLength = 20)
        policy.onCallFinished(req, verdict())

        assertNotNull(policy.cached(req))
        assertNull(policy.cached(request("completely different text")), "cache is content-keyed")
    }

    @Test
    fun `a failed call is not cached`() {
        // Caching a timeout would poison that text until eviction, leaving the user permanently
        // unverified on a message that merely hit a slow network once.
        val policy = VerifyPolicy()
        val req = request()
        policy.onCallStarted(nowMs = 1_000, textLength = 20)
        policy.onCallFailed()

        assertNull(policy.cached(req))
    }

    @Test
    fun `a failed call releases the in flight lock`() {
        val policy = VerifyPolicy(debounceMs = 0)
        policy.onCallStarted(nowMs = 1_000, textLength = 20)
        policy.onCallFailed()

        assertEquals(VerifyPolicy.Decision.SEND, policy.decide(result(), 20, nowMs = 5_000))
    }

    @Test
    fun `the cache evicts rather than growing without bound`() {
        val policy = VerifyPolicy(debounceMs = 0, dailyBudget = 1_000, cacheSize = 4)
        val requests = (0 until 10).map { request("message number $it") }

        requests.forEachIndexed { index, req ->
            policy.onCallStarted(nowMs = 1_000L + index * 1_000, textLength = 20)
            policy.onCallFinished(req, verdict())
        }

        assertNotNull(policy.cached(requests.last()), "the newest entry must survive")
        assertNull(policy.cached(requests.first()), "the oldest entry must have been evicted")
    }
}

class VerifyRequestTest {

    private fun finding(ruleId: String, start: Int, end: Int) = Finding(
        start = start,
        end = end,
        category = Category.PII_DISCLOSURE,
        severity = Severity.MEDIUM,
        ruleId = ruleId,
        message = "m",
    )

    @Test
    fun `only flagged spans are carried, never the whole buffer`() {
        val buffer = "hi there my number is 555-123-4567 ok"
        val request = VerifyRequest.from(
            buffer = buffer,
            findings = listOf(finding("pii.phone", 22, 34)),
            context = "",
            escalate = false,
        )

        assertNotNull(request)
        assertEquals("555-123-4567", request.findings.single().text)
    }

    @Test
    fun `payloads are bounded`() {
        val request = VerifyRequest.from(
            buffer = "x".repeat(5_000) + " 555-123-4567",
            findings = listOf(finding("pii.phone", 5_001, 5_013)),
            context = "y".repeat(5_000),
            escalate = false,
        )

        assertNotNull(request)
        assertTrue(request.sentence.length <= VerifyRequest.MAX_SENTENCE_CHARS)
        assertTrue(request.context.length <= VerifyRequest.MAX_CONTEXT_CHARS)
    }

    @Test
    fun `no findings means no request`() {
        assertNull(VerifyRequest.from("hello", emptyList(), "", escalate = false))
    }

    @Test
    fun `out of range findings are dropped`() {
        assertNull(
            VerifyRequest.from("short", listOf(finding("r", 100, 200)), "", escalate = false),
        )
    }

    @Test
    fun `cache keys are content addressed`() {
        val a = VerifyRequest.from("my number is 555-123-4567", listOf(finding("r", 13, 25)), "", false)
        val b = VerifyRequest.from("my number is 555-123-4567", listOf(finding("r", 13, 25)), "", false)
        val c = VerifyRequest.from("my number is 555-999-0000", listOf(finding("r", 13, 25)), "", false)

        assertEquals(a!!.cacheKey(), b!!.cacheKey())
        assertTrue(a.cacheKey() != c!!.cacheKey())
    }
}

class VerdictApplierTest {

    private fun finding(ruleId: String, severity: Severity, category: Category = Category.PII_DISCLOSURE) =
        Finding(0, 5, category, severity, ruleId, "local message", contextDependent = true)

    @Test
    fun `figurative findings are dropped`() {
        // The case local rules cannot handle, and the main reason this layer earns its cost.
        val findings = listOf(finding("selfharm.want_to_die", Severity.HIGH, Category.SELF_HARM))
        val verdict = CachedVerdict(
            listOf(Verdict("selfharm.want_to_die", true, Severity.NONE, figurative = true, reason = "hyperbole")),
            null,
        )
        assertTrue(VerdictApplier.apply(findings, verdict).isEmpty())
    }

    @Test
    fun `unconfirmed findings are dropped`() {
        val findings = listOf(finding("r", Severity.MEDIUM))
        val verdict = CachedVerdict(
            listOf(Verdict("r", confirmed = false, Severity.MEDIUM, figurative = false, reason = "no")),
            null,
        )
        assertTrue(VerdictApplier.apply(findings, verdict).isEmpty())
    }

    @Test
    fun `confirmed findings take the model severity and wording`() {
        val findings = listOf(finding("r", Severity.LOW))
        val verdict = CachedVerdict(
            listOf(Verdict("r", true, Severity.HIGH, figurative = false, reason = "This is your address.")),
            null,
        )

        val applied = VerdictApplier.apply(findings, verdict).single()
        assertEquals(Severity.HIGH, applied.severity)
        assertEquals("This is your address.", applied.message)
        assertTrue(!applied.contextDependent, "a verified finding is no longer speculative")
    }

    @Test
    fun `findings the model did not mention are left untouched`() {
        // The fail-open contract: a partial response degrades to local behaviour, never to
        // silently dropping a warning the model simply did not address.
        val findings = listOf(finding("mentioned", Severity.MEDIUM), finding("ignored", Severity.HIGH))
        val verdict = CachedVerdict(
            listOf(Verdict("mentioned", true, Severity.LOW, figurative = false, reason = "r")),
            null,
        )

        val applied = VerdictApplier.apply(findings, verdict)
        assertEquals(Severity.HIGH, applied.single { it.ruleId == "ignored" }.severity)
    }

    @Test
    fun `a null verdict changes nothing`() {
        val findings = listOf(finding("r", Severity.MEDIUM))
        assertEquals(findings, VerdictApplier.apply(findings, null))
    }

    @Test
    fun `a blank reason keeps the local wording`() {
        val findings = listOf(finding("r", Severity.MEDIUM))
        val verdict = CachedVerdict(
            listOf(Verdict("r", true, Severity.MEDIUM, figurative = false, reason = "")),
            null,
        )
        assertEquals("local message", VerdictApplier.apply(findings, verdict).single().message)
    }
}
