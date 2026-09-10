package com.keyguard.detect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The regression gate for detection quality.
 *
 * Two numbers matter and they pull against each other. Recall keeps the product honest
 * about what it claims to catch. The false-positive rate on ordinary chat is what actually
 * decides whether the keyboard survives on someone's phone — a safety keyboard that
 * interrupts normal conversation gets switched off within a day, and then it protects
 * nobody. Both are asserted so tuning one cannot silently wreck the other.
 */
class GoldenCorpusTest {

    @Serializable
    private data class Corpus(
        val positives: List<Positive> = emptyList(),
        val negatives: List<Negative> = emptyList(),
    )

    @Serializable
    private data class Positive(
        val text: String,
        val category: Category,
        val minSeverity: Int,
        val note: String = "",
    )

    @Serializable
    private data class Negative(val text: String, val note: String = "")

    private val engine = DetectionEngine.withBundledPack()
    private val corpus: Corpus = loadCorpus()

    /** Below this and the product is overclaiming. */
    private val minRecall = 0.90

    /** Above this and users start turning the keyboard off. */
    private val maxFalsePositiveRate = 0.05

    @Test
    fun `recall on labelled positives meets threshold`() {
        val misses = corpus.positives.filterNot { positive ->
            engine.scan(positive.text).findings.any {
                it.category == positive.category && it.severity.level >= positive.minSeverity
            }
        }

        val recall = 1.0 - misses.size.toDouble() / corpus.positives.size
        println("recall = %.3f (%d/%d)".format(recall, corpus.positives.size - misses.size, corpus.positives.size))

        if (recall < minRecall) {
            fail(
                buildString {
                    appendLine("recall %.3f below threshold %.3f. Missed:".format(recall, minRecall))
                    misses.forEach { appendLine("  [${it.category}>=${it.minSeverity}] \"${it.text}\" (${it.note})") }
                },
            )
        }
    }

    @Test
    fun `ordinary chat does not trigger visible warnings`() {
        // Severity 0 and 1 hits are tolerated here: they render no warning and exist to
        // route an uncertain case to AI verification. Only MEDIUM and above interrupts.
        val falsePositives = corpus.negatives.mapNotNull { negative ->
            val noisy = engine.scan(negative.text).findings
                .filter { it.severity.level >= Severity.MEDIUM.level }
            if (noisy.isEmpty()) null else negative to noisy
        }

        val rate = falsePositives.size.toDouble() / corpus.negatives.size
        println("false positive rate = %.3f (%d/%d)".format(rate, falsePositives.size, corpus.negatives.size))

        if (rate > maxFalsePositiveRate) {
            fail(
                buildString {
                    appendLine("false positive rate %.3f exceeds %.3f:".format(rate, maxFalsePositiveRate))
                    falsePositives.forEach { (negative, findings) ->
                        appendLine("  \"${negative.text}\" (${negative.note})")
                        findings.forEach { appendLine("      ${it.ruleId} ${it.severity} @${it.start}..${it.end}") }
                    }
                },
            )
        }
    }

    @Test
    fun `ordinary chat never triggers a crisis response`() {
        // Stricter than the false-positive check above, and deliberately zero-tolerance.
        // requiresCrisisResponse fires at *any* severity, so a severity-1 self-harm hit that
        // the other assertion would tolerate still puts a crisis intervention in front of
        // someone joking about a heatwave. That both trivialises the real thing and teaches
        // the user to ignore the strip.
        val wrong = corpus.negatives.filter { engine.scan(it.text).requiresCrisisResponse }

        if (wrong.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("${wrong.size} ordinary message(s) triggered a crisis response:")
                    wrong.forEach { appendLine("  \"${it.text}\" (${it.note})") }
                },
            )
        }
    }

    @Test
    fun `self harm findings always request a crisis response`() {
        val selfHarmCases = corpus.positives.filter { it.category == Category.SELF_HARM }
        assertTrue(selfHarmCases.isNotEmpty(), "corpus must cover SELF_HARM")

        for (case in selfHarmCases) {
            val result = engine.scan(case.text)
            assertTrue(
                result.requiresCrisisResponse,
                "\"${case.text}\" must set requiresCrisisResponse so the UI shows support, not a privacy warning",
            )
        }
    }

    @Test
    fun `every finding span lies inside its buffer`() {
        // Guards the normalizer's offset mapping. A span drifting out of range would
        // underline the wrong characters, or crash the IME rendering the highlight.
        val all = corpus.positives.map { it.text } + corpus.negatives.map { it.text }
        for (text in all) {
            for (finding in engine.scan(text).findings) {
                assertTrue(
                    finding.start >= 0 && finding.end <= text.length && finding.start < finding.end,
                    "rule ${finding.ruleId} produced span ${finding.start}..${finding.end} for length ${text.length}: \"$text\"",
                )
            }
        }
    }

    private fun loadCorpus(): Corpus {
        val stream = javaClass.getResourceAsStream(CORPUS_PATH)
            ?: error("corpus missing: $CORPUS_PATH")
        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString(Corpus.serializer(), stream.bufferedReader().use { it.readText() })
    }

    private companion object {
        const val CORPUS_PATH = "/corpus/golden-v0.json"
    }
}
