package com.keyguard.infer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.nio.LongBuffer

/**
 * What the model says about one message.
 *
 * Carries no text, on purpose. [com.keyguard.detect.Finding] deliberately has no `matchedText`
 * field so that no caller can log, persist or transmit user text by accident, and the model path
 * holds the same line: offsets and levels go out, the caller already has the buffer and slices it
 * itself when it needs to render.
 */
data class ModelVerdict(
    val level: Int,
    val fired: List<String>,
    val message: String,
    val activeSignals: Set<String>,
    val probabilities: FloatArray,
    val spans: List<EntitySpan>,
    val tokenCount: Int,
    val elapsedMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ModelVerdict && level == other.level && fired == other.fired)

    override fun hashCode(): Int = 31 * level + fired.hashCode()

    companion object {
        val SAFE = ModelVerdict(0, emptyList(), "", emptySet(), FloatArray(0), emptyList(), 0, 0)
    }
}

/**
 * The on-device model: tokenize, run, threshold, decode, and hand the rule table its terms.
 *
 * Construct once and reuse — creating the session is the expensive part and the whole reason the
 * model is copied out of the APK at all. Close it when the owning service dies.
 *
 * **This class is not thread-safe and must not be called from the accessibility callback thread.**
 * Blocking an accessibility callback degrades the whole device, not just this app. The intended
 * shape is one dedicated background thread with cancel-and-replace semantics, running on a typing
 * pause rather than per keystroke: the rule engine in `:detect` is built for a 5 ms per-keystroke
 * budget and this is two orders of magnitude away from that.
 *
 * ### Why the input is not padded to 128
 *
 * The exported graph has dynamic batch and sequence dimensions, and mean pooling divides by the
 * attention mask, so feeding exactly the real tokens with an all-ones mask is mathematically the
 * same computation as feeding 128 positions with most of them masked out. It was checked rather
 * than assumed: across the fixture messages the two paths agree bit for bit. Real messages are
 * 8-13 tokens, so this is most of the arithmetic the model would otherwise do.
 */
class KeyguardModel internal constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: WordPieceTokenizer,
    private val contract: LabelContract,
    private val thresholds: DoubleArray,
    private val engine: RiskEngine,
) : Closeable {

    /** Surfaced so a debug build can confirm the assets deserialized, as `:detect` does. */
    val vocabularySize: Int get() = tokenizer.vocabSize
    val contextLabelCount: Int get() = contract.contextLabelCount
    val ruleCount: Int get() = engine.ruleCount

    fun analyze(text: String): ModelVerdict {
        if (text.isBlank()) return ModelVerdict.SAFE
        val started = System.nanoTime()

        val encoding = tokenizer.encode(text)
        val length = encoding.length
        val shape = longArrayOf(1, length.toLong())

        val ids = LongArray(length) { encoding.ids[it].toLong() }
        val mask = LongArray(length) { encoding.attentionMask[it].toLong() }

        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { idTensor ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape).use { maskTensor ->
                val inputs = mapOf(INPUT_IDS to idTensor, ATTENTION_MASK to maskTensor)
                session.run(inputs).use { results ->
                    val contextProbabilities = results.floatMatrix(CONTEXT_PROBS)[0]
                    val tokenProbabilities = results.floatCube(TOKEN_PROBS)[0]

                    contract.assertMatches(contextProbabilities.size, tokenProbabilities[0].size)

                    val spans = BioDecoder.decode(tokenProbabilities, encoding, contract)
                    val buckets = BioDecoder.bucketsOf(spans)
                    val verdict = engine.evaluate(
                        contextProbabilities, thresholds, contract, buckets,
                    )

                    return ModelVerdict(
                        level = verdict.level,
                        fired = verdict.fired,
                        message = verdict.message,
                        activeSignals = verdict.active.toSet() - buckets,
                        probabilities = contextProbabilities,
                        spans = spans,
                        tokenCount = length,
                        elapsedMillis = (System.nanoTime() - started) / 1_000_000,
                    )
                }
            }
        }
    }

    /**
     * Compares live output against the probabilities recorded when the model was exported.
     *
     * This is the on-device half of the parity story. The JVM tests check everything downstream of
     * the graph by replaying recorded probabilities; only a device can check that the graph itself,
     * running under ONNX Runtime's ARM kernels, produces those probabilities in the first place.
     *
     * A failure here means the tokenizer, the runtime or the model file differs from what was
     * evaluated — and since none of those fail loudly on their own, this is the check that makes
     * the difference visible. Returns the worst absolute difference seen; anything above about
     * 1e-3 deserves investigation before the numbers are trusted.
     */
    fun selfTest(cases: List<Pair<String, FloatArray>>): Double {
        var worst = 0.0
        for ((text, expected) in cases) {
            val actual = analyze(text).probabilities
            check(actual.size == expected.size) {
                "self-test fixture has ${expected.size} probabilities, model emitted ${actual.size}"
            }
            for (i in actual.indices) {
                val difference = kotlin.math.abs(actual[i] - expected[i]).toDouble()
                if (difference > worst) worst = difference
            }
        }
        return worst
    }

    override fun close() {
        session.close()
    }

    private fun OrtSession.Result.floatMatrix(name: String): Array<FloatArray> {
        val value = get(name).orElseThrow { IllegalStateException(missing(name)) }
        @Suppress("UNCHECKED_CAST")
        return value.value as Array<FloatArray>
    }

    private fun OrtSession.Result.floatCube(name: String): Array<Array<FloatArray>> {
        val value = get(name).orElseThrow { IllegalStateException(missing(name)) }
        @Suppress("UNCHECKED_CAST")
        return value.value as Array<Array<FloatArray>>
    }

    private fun missing(name: String) =
        "the ONNX graph has no output named '$name'. The exported model and this runtime " +
            "disagree about the interface; they are from different runs."

    companion object {
        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val TOKEN_PROBS = "token_probs"
        private const val CONTEXT_PROBS = "context_probs"

        /**
         * Loads everything from the APK's assets. Expensive; call once, off the main thread.
         *
         * @param threads intra-op threads. One by default: these are 8-13 token sequences on a
         *   phone, where the coordination cost of spreading that across cores is a real fraction
         *   of the work, and where spare cores belong to whatever app the child is actually using.
         *   §31's benchmark is what should settle this, not a guess.
         */
        fun load(context: Context, threads: Int = 1): KeyguardModel {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = environment.createSession(
                ModelAssets.modelFile(context).absolutePath, options,
            )
            val contract = ModelAssets.contract(context)
            return KeyguardModel(
                environment = environment,
                session = session,
                tokenizer = ModelAssets.tokenizer(context),
                contract = contract,
                thresholds = ModelAssets.thresholds(context).ordered(contract),
                engine = ModelAssets.riskEngine(context),
            )
        }
    }
}
