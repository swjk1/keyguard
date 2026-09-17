package com.keyguard.infer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * The half of the verification that only a handset can do.
 *
 * The JVM tests cover everything downstream of the graph by replaying recorded probabilities.
 * They cannot cover the graph itself: ONNX Runtime ships ARM kernels that no desktop JVM test
 * exercises, and the whole question of whether this deploys is what those kernels do with a
 * dynamically quantized 28.8M-parameter encoder on a phone.
 *
 * So this does three things a device is required for:
 *
 * 1. **Loads the model** — proving the asset copy, the native library and the session build all
 *    work under the real packaging, not just in a build log.
 * 2. **Checks parity** — the probabilities ARM produces against the ones recorded from the
 *    desktop export. This is the check that would catch a tokenizer or runtime divergence, and
 *    none of those fail loudly on their own.
 * 3. **Measures latency** — cold start separately from steady state, at the real sequence
 *    lengths, which is §31 and the number that actually decides deployment.
 */
@RunWith(AndroidJUnit4::class)
class KeyguardModelDeviceTest {

    @Serializable
    private data class Case(
        val text: String,
        @SerialName("n_tokens") val nTokens: Int,
        @SerialName("context_probs") val contextProbs: List<Double>,
        @SerialName("entity_buckets") val entityBuckets: List<String>,
        val level: Int,
        val fired: List<String>,
    )

    @Serializable
    private data class Fixtures(
        @SerialName("context_labels") val contextLabels: List<String>,
        val checkpoint: String = "",
        val cases: List<Case>,
    )

    companion object {
        private const val TAG = "KeyguardModelDevice"

        /** Enough warmup to get past first-run allocation; enough samples for a stable p95. */
        private const val WARMUP = 3
        private const val ITERATIONS = 20

        /** Matches the gate's quiet period, so the core is as cold as it is in use. */
        private const val IDLE_MS = 400L
        private const val IDLE_ROUNDS = 3

        private lateinit var model: KeyguardModel
        private lateinit var fixtures: Fixtures
        private var coldStartMillis: Long = 0

        @BeforeClass
        @JvmStatic
        fun loadOnce() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            fixtures = ModelJson.decodeFromString(
                Fixtures.serializer(),
                ModelAssets.readText(context, "inference_fixtures.json"),
            )
            // Cold start is what the user feels on the first thing they type, and it is
            // dominated by the asset copy plus session construction rather than by inference.
            // Averaging it into a steady-state median would hide it completely.
            coldStartMillis = System.currentTimeMillis()
            model = KeyguardModel.load(context)
            coldStartMillis = System.currentTimeMillis() - coldStartMillis
        }

        @AfterClass
        @JvmStatic
        fun closeOnce() {
            if (::model.isInitialized) model.close()
        }
    }

    @Test
    fun modelLoadsAndReportsItsAssets() {
        // `:detect` surfaces termCount for exactly this reason: a build whose shrinking broke
        // deserialization looks identical to a working one while detecting nothing.
        assertEquals("vocabulary size", 30_522, model.vocabularySize)
        assertEquals("context labels", 8, model.contextLabelCount)
        assertEquals("risk rules", 18, model.ruleCount)
        Log.i(TAG, "cold start (copy + session + first load): $coldStartMillis ms")
    }

    @Test
    fun probabilitiesMatchTheDesktopExport() {
        val expected = fixtures.cases.map { case ->
            case.text to FloatArray(case.contextProbs.size) { case.contextProbs[it].toFloat() }
        }
        val worst = model.selfTest(expected)
        Log.i(TAG, "worst |device - desktop| probability difference: $worst")
        assertTrue(
            "ARM output diverges from the recorded export by $worst. Something in the " +
                "tokenizer, the runtime or the model file is not what was evaluated.",
            worst < 1e-3,
        )
    }

    @Test
    fun theWholeChainReproducesTheRecordedVerdict() {
        val failures = mutableListOf<String>()
        for (case in fixtures.cases) {
            val verdict = model.analyze(case.text)
            if (verdict.level != case.level || verdict.fired.sorted() != case.fired) {
                failures += "  \"${case.text}\" expected L${case.level} ${case.fired} " +
                    "but got L${verdict.level} ${verdict.fired.sorted()}"
            }
        }
        assertTrue(
            "${failures.size} of ${fixtures.cases.size} cases differ on device:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun tokenCountsMatchTheTrainingTokenizer() {
        for (case in fixtures.cases) {
            val verdict = model.analyze(case.text)
            assertEquals("token count for \"${case.text}\"", case.nTokens, verdict.tokenCount)
        }
    }

    /**
     * §31's benchmark.
     *
     * Deliberately not an assertion on the targets. A Pixel is not the oldest supported device,
     * so a pass here would prove nothing about the hardware that decides this, and a failure
     * would be worth knowing rather than worth failing CI over. The numbers go to logcat and the
     * judgement stays with a person.
     */
    @Test
    fun latencyAtRealMessageLengths() {
        val texts = fixtures.cases.map { it.text }

        repeat(WARMUP) { texts.forEach { model.analyze(it) } }

        val samples = ArrayList<Double>(ITERATIONS * texts.size)
        repeat(ITERATIONS) {
            for (text in texts) {
                samples += measureNanoTime { model.analyze(text) } / 1_000_000.0
            }
        }
        samples.sort()

        fun percentile(p: Double) = samples[((samples.size - 1) * p).toInt()]
        val p50 = percentile(0.50)
        val p95 = percentile(0.95)
        val tokens = fixtures.cases.map { it.nTokens }

        Log.i(TAG, "=== §31 on-device latency ===")
        Log.i(TAG, "device        ${android.os.Build.MODEL} / ${android.os.Build.HARDWARE}, API ${android.os.Build.VERSION.SDK_INT}")
        Log.i(TAG, "abi           ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        Log.i(TAG, "threads       1 intra-op")
        Log.i(TAG, "sequence      ${tokens.min()}-${tokens.max()} tokens (unpadded)")
        Log.i(TAG, "samples       ${samples.size}")
        Log.i(TAG, "cold start    $coldStartMillis ms")
        Log.i(TAG, "p50           ${"%.2f".format(p50)} ms   (target < 75)")
        Log.i(TAG, "p95           ${"%.2f".format(p95)} ms   (target < 150)")
        Log.i(TAG, "min / max     ${"%.2f".format(samples.first())} / ${"%.2f".format(samples.last())} ms")

        assertTrue("benchmark produced no samples", samples.isNotEmpty())
    }

    /**
     * The number that actually describes this product, as opposed to the one a benchmark loop
     * produces.
     *
     * [latencyAtRealMessageLengths] runs back-to-back, which keeps the CPU boosted and the caches
     * warm — a shape nothing in the app ever has. Real usage is one inference after the user
     * stops typing, on a core that has been idle, and that turned out to be roughly seven times
     * slower when observed in the live app: 73-95 ms against the loop's 10 ms.
     *
     * So this measures the real shape. Sleeping between samples is the entire point of the test
     * and not an oversight.
     */
    @Test
    fun latencyOneShotAfterIdle() {
        val texts = fixtures.cases.map { it.text }
        repeat(WARMUP) { texts.forEach { model.analyze(it) } }

        val samples = ArrayList<Double>(texts.size * IDLE_ROUNDS)
        repeat(IDLE_ROUNDS) {
            for (text in texts) {
                Thread.sleep(IDLE_MS)
                samples += measureNanoTime { model.analyze(text) } / 1_000_000.0
            }
        }
        samples.sort()

        fun percentile(p: Double) = samples[((samples.size - 1) * p).toInt()]
        Log.i(TAG, "=== §31 one-shot after ${IDLE_MS} ms idle ===")
        Log.i(TAG, "samples       ${samples.size}")
        Log.i(TAG, "p50           ${"%.2f".format(percentile(0.50))} ms   (target < 75)")
        Log.i(TAG, "p95           ${"%.2f".format(percentile(0.95))} ms   (target < 150)")
        Log.i(TAG, "min / max     ${"%.2f".format(samples.first())} / ${"%.2f".format(samples.last())} ms")

        assertTrue("benchmark produced no samples", samples.isNotEmpty())
    }

    @Test
    fun blankTextCostsNothing() {
        val verdict = model.analyze("   ")
        assertEquals(0, verdict.level)
        assertEquals(0, verdict.tokenCount)
    }

}
