package com.keyguard.detect

/**
 * A bounded, time-windowed memory of recent activity — the meeting's "we need to look back
 * about a minute" requirement.
 *
 * Two distinct things are retained, for two distinct consumers:
 *
 *  - **Category events** (no text) drive local escalation scoring. Keeping only the
 *    category and severity means the scorer can reason about conversation shape without
 *    holding on to what was actually said.
 *  - **A bounded text ring** supplies the context payload for an AI verification call.
 *
 * This is **in-memory only and never persisted**. Callers must invoke [clear] when the
 * input field changes, so context never leaks across apps or conversations.
 */
class RollingContext(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS,
) {
    private data class CategoryEvent(val category: Category, val severity: Severity, val atMs: Long)

    private val events = ArrayDeque<CategoryEvent>()
    private val recentText = ArrayDeque<String>()
    private var recentTextChars = 0

    /** Records the categories observed in a scan so later scans can escalate on them. */
    fun recordFindings(findings: List<Finding>, nowMs: Long) {
        for (finding in findings) {
            events.addLast(CategoryEvent(finding.category, finding.severity, nowMs))
        }
        evict(nowMs)
    }

    /**
     * Records text the user actually committed (a sent message), for use as AI context.
     * Call this on a confirmed send, not on every keystroke.
     */
    fun recordCommittedText(text: String, nowMs: Long) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        recentText.addLast(trimmed)
        recentTextChars += trimmed.length
        while (recentTextChars > maxContextChars && recentText.size > 1) {
            recentTextChars -= recentText.removeFirst().length
        }
        evict(nowMs)
    }

    /** Categories seen inside the age window. */
    fun activeCategories(nowMs: Long): Set<Category> {
        evict(nowMs)
        return events.mapTo(HashSet()) { it.category }
    }

    /**
     * The bounded prior-conversation context for an AI verify payload. Never the full
     * keystream — only recently committed messages, capped at [maxContextChars].
     */
    fun contextSnapshot(): String {
        val snapshot = recentText.joinToString("\n")
        return if (snapshot.length <= maxContextChars) {
            snapshot
        } else {
            snapshot.substring(snapshot.length - maxContextChars)
        }
    }

    /** Drops everything. Must be called on input-field change. */
    fun clear() {
        events.clear()
        recentText.clear()
        recentTextChars = 0
    }

    private fun evict(nowMs: Long) {
        val cutoff = nowMs - maxAgeMs
        while (events.isNotEmpty() && events.first().atMs < cutoff) {
            events.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 60_000
        const val DEFAULT_MAX_CONTEXT_CHARS: Int = 600
    }
}
