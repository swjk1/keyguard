package com.keyguard.infer

import java.io.File

/**
 * Reads the shipped model assets from disk during unit tests.
 *
 * The tests deliberately read the same bytes the device will, rather than a copy under
 * `src/test/resources`. A second copy of the vocabulary or the rule table is a second thing to
 * forget to update, and the whole point of these tests is that the runtime agrees with what was
 * exported — which a stale duplicate would quietly hide.
 *
 * The path comes from a system property set in `build.gradle.kts`, not from the working directory,
 * because AGP makes no promise about what the working directory of a unit test is.
 */
internal object ModelAssetsForTest {

    private val root: File by lazy {
        val path = System.getProperty("keyguard.model.assets")
            ?: error(
                "keyguard.model.assets is not set. It is configured in infer/build.gradle.kts; " +
                    "running these tests outside Gradle needs it passed explicitly."
            )
        File(path).also {
            check(it.isDirectory) { "model assets directory does not exist: $it" }
        }
    }

    fun file(name: String): File = File(root, name).also {
        check(it.isFile) {
            "missing model asset '$name'. The export sidecars travel as one set — copy them " +
                "together from a single export run, or the runtime will pair a vocabulary with " +
                "thresholds fitted for a different model."
        }
    }

    fun text(name: String): String = file(name).readText(Charsets.UTF_8)

    val tokenizer: WordPieceTokenizer by lazy {
        file("vocab.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            WordPieceTokenizer(WordPieceTokenizer.vocabularyFrom(lines))
        }
    }

    val contract: LabelContract by lazy {
        ModelJson.decodeFromString(LabelContract.serializer(), text("label_contract.json"))
    }

    val thresholds: Thresholds by lazy {
        ModelJson.decodeFromString(Thresholds.serializer(), text("thresholds.json"))
    }

    val riskEngine: RiskEngine by lazy {
        RiskEngine(ModelJson.decodeFromString(RiskRuleTable.serializer(), text("risk_rules.json")))
    }
}
