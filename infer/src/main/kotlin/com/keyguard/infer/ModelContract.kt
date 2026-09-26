package com.keyguard.infer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The exporter's description of what the model emits, read rather than transcribed.
 *
 * Label order is the wire format. The eight context probabilities come out of the graph in a fixed
 * order and the 23 BIO tags are indexed by position, so an index that drifts between the exporter
 * and this runtime is not a crash — it is eight plausible probabilities attached to the wrong
 * signals, and a model that appears to work while warning about the wrong things. [assertMatches]
 * turns that into a startup failure, which is the only point at which it is cheap.
 *
 * Nothing here is hardcoded in Kotlin on purpose. A second copy of the taxonomy is a second thing
 * to forget to update.
 */
@Serializable
data class LabelContract(
    @SerialName("contract_version") val contractVersion: Int = 1,
    @SerialName("context_labels") val contextLabels: List<String>,
    @SerialName("pii_tags") val piiTags: List<String>,
    @SerialName("pii_entities") val piiEntities: List<String> = emptyList(),
    @SerialName("safety_entities") val safetyEntities: List<String> = emptyList(),
    @SerialName("entity_to_safety_entity") val entityToSafetyEntity: Map<String, String> = emptyMap(),
    @SerialName("ignore_index") val ignoreIndex: Int = -100,
) {
    val contextLabelCount: Int get() = contextLabels.size
    val tagCount: Int get() = piiTags.size

    /**
     * Fails loudly when the graph and the contract disagree about shape.
     *
     * Called once, at load, against the dimensions the ONNX session reports. The alternative is
     * discovering it as a subtly wrong warning six weeks later.
     */
    fun assertMatches(contextOutputs: Int, tagOutputs: Int) {
        check(contextOutputs == contextLabelCount) {
            "model emits $contextOutputs context probabilities but the label contract names " +
                "$contextLabelCount ($contextLabels). The export and the assets are from " +
                "different runs."
        }
        check(tagOutputs == tagCount) {
            "model emits $tagOutputs BIO tags but the label contract names $tagCount. The export " +
                "and the assets are from different runs."
        }
    }

    /** Coarse bucket for a BIO tag such as `B-STREET`, or null for `O` and unmapped entities. */
    fun safetyBucketOf(tag: String): String? {
        if (tag.length < 3) return null
        return entityToSafetyEntity[tag.substring(2)]
    }
}

/**
 * The per-label operating points.
 *
 * Deliberately outside the ONNX graph. Temperature scaling is folded in — it is a property of how
 * the model was fitted — but thresholds are a product decision: they express how often this app is
 * willing to interrupt a child, they are re-fitted whenever the calibration set changes, and they
 * have to be readable by the Python that reports metrics as well as by this. Shipping them inside
 * the graph would make changing the interruption rate a retraining exercise.
 */
@Serializable
data class Thresholds(
    val thresholds: Map<String, Double>,
    val note: String = "",
) {
    fun forLabel(label: String): Double =
        thresholds[label] ?: error("no threshold for context label '$label' in thresholds.json")

    /** Thresholds as an array in the contract's label order, ready to compare against the graph. */
    fun ordered(contract: LabelContract): DoubleArray =
        DoubleArray(contract.contextLabelCount) { forLabel(contract.contextLabels[it]) }
}

/** One rule of the shipped policy. Conjunctions over context signals and entity buckets. */
@Serializable
data class RiskRule(
    val id: String,
    val level: Int,
    @SerialName("all_of") val allOf: List<String> = emptyList(),
    @SerialName("any_of") val anyOf: List<String> = emptyList(),
    @SerialName("none_of") val noneOf: List<String> = emptyList(),
    val message: String = "",
) {
    fun matches(active: Set<String>): Boolean {
        if (!active.containsAll(allOf)) return false
        if (anyOf.isNotEmpty() && anyOf.none { it in active }) return false
        if (noneOf.any { it in active }) return false
        return true
    }
}

@Serializable
data class RiskRuleTable(
    val version: Int = 1,
    val levels: Map<String, String> = emptyMap(),
    @SerialName("context_labels") val contextLabels: List<String> = emptyList(),
    @SerialName("safety_entities") val safetyEntities: List<String> = emptyList(),
    @SerialName("entity_to_safety_entity") val entityToSafetyEntity: Map<String, String> = emptyMap(),
    val rules: List<RiskRule> = emptyList(),
)

/**
 * Lenient about fields this runtime does not read, strict about the ones it does.
 *
 * `ignoreUnknownKeys` is on because the exporter writes more than the phone needs — label
 * definitions, discarded entity lists, provenance — and a new field added on the Python side
 * should not brick the app. A field this code *does* read is not optional, so a missing one still
 * throws.
 */
internal val ModelJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
}
