package com.keyguard.infer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Span decoding, including the repair that exists because an undertrained model gets BIO wrong.
 *
 * Probabilities are synthesised rather than taken from the model: this tests the decoder, and a
 * test that needed a 67 MB session to run would not be one anybody runs.
 */
class BioDecoderTest {

    private val contract = ModelAssetsForTest.contract
    private val tokenizer = ModelAssetsForTest.tokenizer

    /** One-hot tag distribution per position, by tag name. `null` means `O`. */
    private fun probabilities(encoding: Encoding, tags: List<String?>): Array<FloatArray> =
        Array(encoding.ids.size) { position ->
            val row = FloatArray(contract.tagCount)
            val tag = tags.getOrNull(position) ?: "O"
            row[contract.piiTags.indexOf(tag)] = 1f
            row
        }

    @Test
    fun `a well-formed B I sequence decodes to one span`() {
        val text = "i live at 24 oak street"
        val encoding = tokenizer.encode(text)
        val tags = encoding.tokens.map { token ->
            when (token) {
                "24" -> "B-BUILDINGNUM"
                "oak" -> "B-STREET"
                "street" -> "I-STREET"
                else -> null
            }
        }

        val spans = BioDecoder.decode(probabilities(encoding, tags), encoding, contract)
        val street = spans.single { it.entity == "STREET" }
        assertEquals("oak street", text.substring(street.range.first, street.range.last + 1))
        assertEquals("exact_location", street.bucket)
    }

    @Test
    fun `subwords of one word collapse into a single span`() {
        // The failure this repairs: an undertrained model emits B- on every wordpiece, so
        // "cranbrook" decoded as two STREET spans and the overlay drew two underlines under
        // one word.
        val text = "i live on cranbrook ave"
        val encoding = tokenizer.encode(text)
        val cranbrookPieces = encoding.tokens.withIndex()
            .filter { (i, _) -> encoding.offsets[i]?.let { text.substring(it.first, it.last + 1) } == "cranbrook" }
            .map { it.index }
        assertTrue(cranbrookPieces.size >= 2, "expected 'cranbrook' to split; got ${encoding.tokens}")

        val tags = encoding.tokens.indices.map { if (it in cranbrookPieces) "B-STREET" else null }
        val spans = BioDecoder.decode(probabilities(encoding, tags), encoding, contract)

        assertEquals(1, spans.size, "every subword opened a span and they were not merged: $spans")
        assertEquals("cranbrook", text.substring(spans[0].range.first, spans[0].range.last + 1))
    }

    @Test
    fun `a space is a character, so two words do not merge on their own`() {
        // The merge repairs tokenization artefacts, not model mistakes. Two separately-opened
        // spans with a space between them stay separate — otherwise "call 24 oak" and "oak" in
        // the next clause would silently become one entity.
        val text = "toronto ottawa"
        val encoding = tokenizer.encode(text)
        val tags = encoding.tokens.map { if (it == "toronto" || it == "ottawa") "B-CITY" else null }

        val spans = BioDecoder.decode(probabilities(encoding, tags), encoding, contract)
        assertEquals(2, spans.size, "expected two cities, got $spans")
    }

    @Test
    fun `an O breaks a run`() {
        val text = "oak and elm"
        val encoding = tokenizer.encode(text)
        val tags = encoding.tokens.map { if (it == "oak" || it == "elm") "B-STREET" else null }

        val spans = BioDecoder.decode(probabilities(encoding, tags), encoding, contract)
        assertEquals(2, spans.size)
    }

    @Test
    fun `buckets are the coarse terms the rule table references`() {
        val text = "email me at a@b.com or call 5145551234"
        val encoding = tokenizer.encode(text)
        val tags = encoding.tokens.map { token ->
            when {
                token.contains("@") || token == "com" || token == "b" -> "I-EMAIL"
                token == "5145551234" -> "B-TELEPHONENUM"
                else -> null
            }
        }

        val buckets = BioDecoder.bucketsOf(
            BioDecoder.decode(probabilities(encoding, tags), encoding, contract)
        )
        assertTrue("contact_info" in buckets, "expected contact_info in $buckets")
    }

    @Test
    fun `padding positions are never decoded`() {
        val encoding = tokenizer.encode("hi")
        // Tag every position, padding included. Only the real ones may produce a span.
        val rows = Array(encoding.ids.size) { position ->
            FloatArray(contract.tagCount).also { it[contract.piiTags.indexOf("B-CITY")] = 1f }
                .takeIf { position < encoding.ids.size } ?: FloatArray(contract.tagCount)
        }
        val spans = BioDecoder.decode(rows, encoding, contract)
        assertTrue(spans.size <= 1, "decoded past the attention mask: $spans")
    }
}
