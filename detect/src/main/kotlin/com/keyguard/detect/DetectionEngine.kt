package com.keyguard.detect

/**
 * The local detection engine — layers A, B, and C behind one call.
 *
 * Apple's guideline 4.4.1 requires a keyboard extension to "remain functional without full
 * network access and without requiring full access," so this engine is not a first pass in
 * front of a cloud service: it is the product's floor, and it must be good enough to stand
 * alone. The AI layer is a second opinion on top, never a dependency.
 *
 * Construct once and reuse — building the automatons is the expensive part, and [scan] is
 * called on a keystroke cadence.
 *
 * This class is not thread-safe with respect to a shared [RollingContext]; call it from the
 * IME's single input thread.
 */
class DetectionEngine(private val pack: RulePack) {

    private val lexicon = LexiconMatcher(pack)
    private val scorer = ContextScorer(pack)
    private val themes = ThemeScanner(pack)

    val packVersion: Int get() = pack.version
    val packRevision: String get() = pack.revision

    /**
     * Number of lexicon terms loaded. Surfaced in the app as a cheap way to confirm the rule
     * pack actually deserialized — a release build whose shrinking broke serialization would
     * otherwise look identical to a working one while silently detecting nothing.
     */
    val termCount: Int get() = pack.terms.size

    /**
     * Scans [buffer] and returns findings located against it.
     *
     * @param context rolling conversation memory; pass the same instance across keystrokes
     *   for a given input field and [RollingContext.clear] it when the field changes.
     * @param nowMs monotonic-ish timestamp used only for context windowing. Injected rather
     *   than read from the clock so tests are deterministic.
     */
    fun scan(
        buffer: String,
        context: RollingContext = RollingContext(),
        nowMs: Long = 0L,
    ): ScanResult {
        if (buffer.isBlank()) return ScanResult.EMPTY

        val structured = PiiMatchers.match(buffer)
        val lexical = lexicon.match(Normalizer.normalize(buffer))
        val result = scorer.score(structured + lexical, buffer, context, nowMs)

        context.recordFindings(result.findings, nowMs)
        return result
    }

    /**
     * Reduces a *finished* message to the themes and mood a parent report may quote.
     *
     * Deliberately not part of [scan], and the separation is load-bearing in two directions.
     *
     * On cost: [scan] runs per keystroke against a 5ms budget, and the topic vocabulary is
     * several times the size of the harm lexicon. Folding it in would spend that budget on a
     * signal nothing displays.
     *
     * On privacy: this is the only method whose output is designed to describe an *unflagged*
     * message, so it is the one place where a caller could start reporting ordinary
     * conversation. Making it a separate call means every such caller is visible in a search
     * for this method name, and the review-scope check that gates them is somewhere a reader
     * can find. [scan] stays what it always was — the thing that decides whether to interrupt.
     *
     * Takes no [RollingContext]: a summary describes one message, and mixing in the previous
     * one would attribute a theme to a message that never mentioned it.
     */
    fun summarize(buffer: String): MessageSummary = themes.summarize(buffer)

    /**
     * Size of the topic vocabulary, alongside [termCount], for the same reason: a release build
     * whose shrinking broke deserialization reports zero here instead of looking healthy.
     */
    val themeTermCount: Int get() = pack.themes.size + pack.emotions.size

    companion object {
        /** Engine backed by the rule pack bundled in this module. */
        fun withBundledPack(): DetectionEngine = DetectionEngine(RulePack.bundled())
    }
}
