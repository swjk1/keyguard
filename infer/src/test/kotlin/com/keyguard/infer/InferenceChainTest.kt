package com.keyguard.infer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Everything downstream of the graph, checked against what Python actually computed.
 *
 * `inference_fixtures.json` records, for ten real messages, the probabilities the shipped INT8
 * model emitted and the level the Python risk engine derived from them. This test replays those
 * probabilities through the Kotlin thresholds and rule table and asserts the same answer.
 *
 * That covers the whole chain except the session run itself — thresholding, entity buckets, rule
 * evaluation, level selection — on the JVM, in milliseconds, with no 67 MB model and no device.
 * The session run is the one part that needs hardware, and [KeyguardModel.selfTest] compares its
 * output against the same recorded probabilities once there is a phone to run it on.
 *
 * The fixtures are also an honest record of where the model is. Several of these cases are wrong —
 * "I live at 24 Oak Street" comes out Level 0 because the model does not fire `child_location`,
 * whose recall on the human gold set is 0.110. That is a model problem, not a wiring problem, and
 * pinning the current behaviour is what will make the improvement visible when run 3 lands.
 */
class InferenceChainTest {

    @Serializable
    private data class Case(
        val text: String,
        @SerialName("n_tokens") val nTokens: Int,
        @SerialName("context_probs") val contextProbs: List<Double>,
        @SerialName("entity_buckets") val entityBuckets: List<String>,
        @SerialName("active_signals") val activeSignals: List<String>,
        val level: Int,
        val fired: List<String>,
    )

    @Serializable
    private data class Fixtures(
        @SerialName("context_labels") val contextLabels: List<String>,
        val checkpoint: String = "",
        val cases: List<Case>,
    )

    private val contract = ModelAssetsForTest.contract
    private val engine = ModelAssetsForTest.riskEngine
    private val thresholds = ModelAssetsForTest.thresholds.ordered(contract)

    private val fixtures: Fixtures by lazy {
        ModelJson.decodeFromString(
            Fixtures.serializer(),
            ModelAssetsForTest.text("inference_fixtures.json"),
        )
    }

    @Test
    fun `the fixture labels are in the contract's order`() {
        // The probabilities are positional. If the two files disagree about order, every
        // comparison below would still "pass" on some cases and silently mean nothing.
        assertContentEquals(contract.contextLabels, fixtures.contextLabels)
    }

    @Test
    fun `thresholding reproduces Python's active signal set`() {
        for (case in fixtures.cases) {
            val active = contract.contextLabels.filterIndexed { i, _ ->
                case.contextProbs[i] >= thresholds[i]
            }.sorted()
            assertContentEquals(
                case.activeSignals, active,
                "signals differ for \"${case.text}\"",
            )
        }
    }

    @Test
    fun `the rule table reproduces Python's level and fired rules`() {
        for (case in fixtures.cases) {
            val probabilities = FloatArray(contract.contextLabelCount) {
                case.contextProbs[it].toFloat()
            }
            val result = engine.evaluate(
                probabilities, thresholds, contract, case.entityBuckets.toSet(),
            )
            assertEquals(case.level, result.level, "level differs for \"${case.text}\"")
            assertContentEquals(
                case.fired, result.fired.sorted(),
                "fired rules differ for \"${case.text}\"",
            )
        }
    }

    @Test
    fun `the tokenizer agrees with Python about how long each message is`() {
        // n_tokens was recorded by the training tokenizer. Matching it here is a second,
        // independent check on the tokenizer beyond the id fixtures — and it is what makes the
        // unpadded inference path safe, since the sequence length is now an input to the graph.
        val tokenizer = ModelAssetsForTest.tokenizer
        for (case in fixtures.cases) {
            assertEquals(
                case.nTokens, tokenizer.encode(case.text).length,
                "token count differs for \"${case.text}\"",
            )
        }
    }

    @Test
    fun `the fixtures record the checkpoint they came from`() {
        assertTrue(fixtures.cases.size >= 10, "expected the full fixture set")
        assertTrue(fixtures.checkpoint.isNotBlank(), "fixtures must name their checkpoint")
    }
}
