package com.keyguard.app.verify

import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import com.keyguard.detect.VerifyReason

/**
 * Decides whether to spend a network call — and money — on verifying a scan.
 *
 * The planning meeting's longest argument was per-user inference cost, and this class is the
 * answer. Every condition below exists to avoid a call that would not change what the user
 * sees:
 *
 *  - **Debounce**: mid-word text is incomplete, so a verdict on it is likely wasted.
 *  - **Cache**: identical text produces an identical verdict; asking twice is pure cost.
 *  - **Budget**: a hard local ceiling, so a pathological session cannot run up a bill.
 *  - **Eligibility**: only scans the local engine could not resolve alone.
 *
 * Pure and Android-free, so the whole decision table is unit-testable — which matters because
 * every branch here has a direct cost consequence.
 */
class VerifyPolicy(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val minCharsBetweenCalls: Int = DEFAULT_MIN_CHARS,
    private val dailyBudget: Int = DEFAULT_DAILY_BUDGET,
    private val cacheSize: Int = DEFAULT_CACHE_SIZE,
) {
    /** Why a call was declined. Surfaced for the benchmark, which needs the trigger rate. */
    enum class Decision {
        SEND,
        NOT_ELIGIBLE,
        DEBOUNCING,
        TOO_FEW_CHANGES,
        CACHED,
        BUDGET_EXHAUSTED,
        ALREADY_IN_FLIGHT,
    }

    private val cache = object : LinkedHashMap<Int, CachedVerdict>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedVerdict>) =
            size > cacheSize
    }

    private var lastCallAtMs = 0L
    private var lengthAtLastCall = 0
    private var callsToday = 0
    private var budgetDayIndex = -1L
    private var inFlight = false

    val callsUsedToday: Int get() = callsToday

    /**
     * @param result the local scan result
     * @param textLength current buffer length, for the change threshold
     * @param nowMs current time
     */
    fun decide(result: ScanResult, textLength: Int, nowMs: Long): Decision {
        rolloverBudget(nowMs)

        if (!result.eligibleForVerification) return Decision.NOT_ELIGIBLE
        if (inFlight) return Decision.ALREADY_IN_FLIGHT
        if (callsToday >= dailyBudget) return Decision.BUDGET_EXHAUSTED

        // High severity skips the debounce. A serious warning is worth a call immediately;
        // waiting for a typing pause could mean the message is already gone.
        val urgent = result.maxSeverity == Severity.HIGH ||
            result.verifyReason == VerifyReason.ESCALATE

        if (!urgent) {
            val sinceLastCall = nowMs - lastCallAtMs
            val changed = kotlin.math.abs(textLength - lengthAtLastCall)
            if (lastCallAtMs != 0L && sinceLastCall < debounceMs && changed < minCharsBetweenCalls) {
                return if (sinceLastCall < debounceMs) Decision.DEBOUNCING else Decision.TOO_FEW_CHANGES
            }
        }
        return Decision.SEND
    }

    /** Cached verdict for [request], or null. Keyed by content, not by time. */
    fun cached(request: VerifyRequest): CachedVerdict? = cache[request.cacheKey()]

    /** Records that a call is starting, so a second one cannot pile on behind it. */
    fun onCallStarted(nowMs: Long, textLength: Int) {
        rolloverBudget(nowMs)
        inFlight = true
        lastCallAtMs = nowMs
        lengthAtLastCall = textLength
        callsToday++
    }

    fun onCallFinished(request: VerifyRequest, verdict: CachedVerdict?) {
        inFlight = false
        if (verdict != null) cache[request.cacheKey()] = verdict
    }

    /**
     * A failed call still consumed budget but must not be cached — a transient timeout would
     * otherwise poison the result for that text until eviction.
     */
    fun onCallFailed() {
        inFlight = false
    }

    fun reset() {
        cache.clear()
        lastCallAtMs = 0
        lengthAtLastCall = 0
        inFlight = false
    }

    /** Budget is per calendar day in device-local terms; good enough for a spend ceiling. */
    private fun rolloverBudget(nowMs: Long) {
        val dayIndex = nowMs / MILLIS_PER_DAY
        if (dayIndex != budgetDayIndex) {
            budgetDayIndex = dayIndex
            callsToday = 0
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 350L
        const val DEFAULT_MIN_CHARS = 12
        /** Client-side ceiling. The server enforces its own, which is authoritative. */
        const val DEFAULT_DAILY_BUDGET = 40
        const val DEFAULT_CACHE_SIZE = 64
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
