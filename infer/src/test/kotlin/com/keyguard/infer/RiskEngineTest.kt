package com.keyguard.infer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin rule engine must agree with the Python one, case for case.
 *
 * The cases below are the five the exporter prints on every run (`keyguard_ml/risk_engine.py`), so
 * this is a direct comparison against output the training side published rather than against a
 * reading of the rules. They are chosen to separate *ownership* from *mention*, which is the
 * distinction the whole context head exists for: the same address reaches Level 2 or Level 0
 * depending on whose it is.
 *
 * If these diverge, the experiment record has stopped describing the shipped product — the report
 * would be measuring a policy no phone runs.
 */
class RiskEngineTest {

    private val engine = ModelAssetsForTest.riskEngine
    private val contract = ModelAssetsForTest.contract

    private fun level(signals: Set<String>, entities: Set<String>) =
        engine.evaluate(signals, entities)

    @Test
    fun `a child's own address reaches Level 2`() {
        val result = level(setOf("child_ownership", "child_location"), setOf("exact_location"))
        assertEquals(2, result.level)
        assertContentEquals(listOf("risk.address_disclosure"), result.fired)
    }

    @Test
    fun `somebody else's address reaches nothing`() {
        // No child_ownership. This single term is what stops "the pizza place is at 24 Oak Street"
        // from warning, and it is why the context head exists at all.
        val result = level(emptySet(), setOf("exact_location"))
        assertEquals(0, result.level)
        assertTrue(result.fired.isEmpty())
    }

    @Test
    fun `address plus alone plus a time reaches Level 3`() {
        val result = level(
            setOf("child_ownership", "child_location", "alone", "specific_time"),
            setOf("exact_location", "time_signal"),
        )
        assertEquals(3, result.level)
        assertContentEquals(
            listOf("risk.address_alone", "risk.unsupervised_window"),
            result.fired.sorted(),
        )
    }

    @Test
    fun `a child's own phone number reaches Level 2`() {
        val result = level(setOf("child_ownership"), setOf("contact_info"))
        assertEquals(2, result.level)
        assertContentEquals(listOf("risk.contact_disclosure"), result.fired)
    }

    @Test
    fun `somebody else's phone number reaches nothing`() {
        val result = level(emptySet(), setOf("contact_info"))
        assertEquals(0, result.level)
    }

    @Test
    fun `the level is the maximum, not the first match`() {
        // The Level 3 case above also matches several Level 1 and Level 2 rules. Order in the
        // table is for readability; if it ever became precedence, this catches it.
        val result = level(
            setOf("child_ownership", "child_location", "alone", "specific_time"),
            setOf("exact_location", "time_signal"),
        )
        assertTrue(result.fired.all { id -> id.startsWith("risk.") })
        assertEquals(3, result.level)
    }

    @Test
    fun `the shipped table is the one that was exported`() {
        assertEquals(18, engine.ruleCount, "run 2 exported 18 rules")
    }

    @Test
    fun `thresholds cover every context label in the contract`() {
        val thresholds = ModelAssetsForTest.thresholds
        val missing = contract.contextLabels.filterNot { it in thresholds.thresholds }
        assertTrue(missing.isEmpty(), "thresholds.json has no entry for $missing")
        assertEquals(contract.contextLabelCount, thresholds.ordered(contract).size)
    }

    @Test
    fun `probabilities are gated by the per-label thresholds, not a flat one half`() {
        val thresholds = ModelAssetsForTest.thresholds.ordered(contract)
        val meetup = contract.contextLabels.indexOf("meetup")
        assertTrue(meetup >= 0)

        // run 2 shipped meetup at 0.975. A flat 0.5 would fire here and the real operating point
        // does not — which is the calibration behaviour this whole path depends on.
        val probabilities = FloatArray(contract.contextLabelCount) { 0f }
        probabilities[contract.contextLabels.indexOf("child_ownership")] = 1f
        probabilities[meetup] = 0.8f

        val result = engine.evaluate(probabilities, thresholds, contract, emptySet())
        assertTrue(
            "risk.meetup_context" !in result.fired,
            "meetup at 0.8 must not fire when its shipped threshold is ${thresholds[meetup]}",
        )
    }

    @Test
    fun `the label contract and the rule table agree about the taxonomy`() {
        // Two files from one export run. If they disagree, they came from different runs, and
        // every number downstream is describing a model that does not exist.
        val table = ModelJson.decodeFromString(
            RiskRuleTable.serializer(),
            ModelAssetsForTest.text("risk_rules.json"),
        )
        assertContentEquals(contract.contextLabels, table.contextLabels)
        assertContentEquals(contract.safetyEntities, table.safetyEntities)
        assertEquals(contract.entityToSafetyEntity, table.entityToSafetyEntity)
    }
}
