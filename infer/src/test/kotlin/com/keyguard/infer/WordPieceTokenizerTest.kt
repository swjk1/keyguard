package com.keyguard.infer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The gate on believing anything else this module produces.
 *
 * Every other failure in the inference path announces itself — a missing native library throws, a
 * shape mismatch throws. A tokenizer that disagrees with training does not: it yields valid ids,
 * in-range probabilities and plausible answers that are merely worse than the model tested at.
 * There is no runtime check that catches it and no metric on the device that would move.
 *
 * So the export ships 50 texts with the exact ids the training tokenizer produced, and this
 * asserts all of them exactly. Not a similarity measure, not a majority — every id, in order. A
 * single mismatch means the model on the phone is not the model that was evaluated.
 */
class WordPieceTokenizerTest {

    @Serializable
    private data class Fixture(
        val text: String,
        @SerialName("input_ids") val inputIds: List<Int>,
        val tokens: List<String> = emptyList(),
    )

    private val tokenizer = ModelAssetsForTest.tokenizer

    private val fixtures: List<Fixture> by lazy {
        ModelJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Fixture.serializer()),
            ModelAssetsForTest.text("tokenizer_fixtures.json"),
        )
    }

    @Test
    fun `every shipped fixture tokenizes to exactly the training ids`() {
        val failures = mutableListOf<String>()
        for (fixture in fixtures) {
            val actual = tokenizer.encodeToIds(fixture.text).toList()
            if (actual != fixture.inputIds) {
                failures += buildString {
                    appendLine("  text     ${fixture.text}")
                    appendLine("  expected ${fixture.inputIds}")
                    appendLine("  actual   $actual")
                    if (fixture.tokens.isNotEmpty()) {
                        appendLine("  wanted   ${fixture.tokens}")
                        appendLine("  got      ${tokenizer.encode(fixture.text).tokens.take(actual.size)}")
                    }
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${fixtures.size} tokenizer fixtures disagree with the " +
                    "training tokenizer. The model on the device is not the model that was " +
                    "evaluated.\n\n" + failures.joinToString("\n")
            )
        }
    }

    @Test
    fun `the fixture set is the one the exporter wrote`() {
        // A truncated or empty fixture file would make the test above pass vacuously, which is
        // the one way this gate could fail open.
        assertEquals(50, fixtures.size, "expected the exporter's 50 fixtures")
        assertTrue(fixtures.all { it.inputIds.size >= 2 }, "every fixture carries at least [CLS] [SEP]")
    }

    @Test
    fun `vocabulary loaded with the special tokens at their exported ids`() {
        val encoded = tokenizer.encode("hello")
        assertEquals(101, encoded.ids[0], "[CLS] must be id 101")
        assertEquals(102, encoded.ids[encoded.length - 1], "[SEP] must be id 102")
        assertEquals(0, tokenizer.padId, "[PAD] must be id 0")
        assertEquals(30_522, tokenizer.vocabSize, "bert_uncased vocabulary size")
    }

    @Test
    fun `accents are stripped even though the config says strip_accents is null`() {
        // BertNormalizer's strip_accents is an Option<bool> defaulting to the value of lowercase,
        // and the export sets lowercase true. Reading the null as "off" is the most likely way to
        // get this wrong, and it would only show on text carrying diacritics — names and places,
        // which is exactly what this model looks for.
        assertEquals(
            tokenizer.encodeToIds("jose").toList(),
            tokenizer.encodeToIds("José").toList(),
        )
    }

    @Test
    fun `punctuation is isolated, including the ascii symbols that are not unicode punctuation`() {
        val tokens = tokenizer.encode("meet me at 5:30pm!").tokens
        assertTrue(":" in tokens, "colon must be its own token, got $tokens")
        assertTrue("!" in tokens, "bang must be its own token, got $tokens")
    }

    @Test
    fun `encoding pads to the window and marks only real positions`() {
        val encoded = tokenizer.encode("im home alone")
        assertEquals(WordPieceTokenizer.MAX_LENGTH, encoded.ids.size)
        assertEquals(WordPieceTokenizer.MAX_LENGTH, encoded.attentionMask.size)
        assertEquals(encoded.length, encoded.attentionMask.sum())
        assertTrue(encoded.ids.drop(encoded.length).all { it == tokenizer.padId })
    }

    @Test
    fun `a message longer than the window is truncated rather than rejected`() {
        val long = List(400) { "alone" }.joinToString(" ")
        val encoded = tokenizer.encode(long)
        assertEquals(WordPieceTokenizer.MAX_LENGTH, encoded.length)
        assertEquals(102, encoded.ids[WordPieceTokenizer.MAX_LENGTH - 1], "[SEP] still terminates")
    }

    @Test
    fun `offsets point into the original text, not the normalized form`() {
        val text = "I live at 24 Oak Street"
        val encoded = tokenizer.encode(text)
        val oak = encoded.tokens.indexOf("oak")
        assertTrue(oak > 0, "expected an 'oak' token in ${encoded.tokens}")
        val range = encoded.offsets[oak] ?: fail("no offset recorded for 'oak'")
        assertEquals("Oak", text.substring(range.first, range.last + 1))
    }
}
