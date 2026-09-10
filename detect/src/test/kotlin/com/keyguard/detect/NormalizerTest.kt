package com.keyguard.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NormalizerTest {

    @Test
    fun `lowercases and folds leetspeak`() {
        assertEquals("send nudes", Normalizer.normalize("S3ND NUD3S").loose.text)
        assertEquals("attack", Normalizer.normalize("@tt4ck").loose.text)
    }

    @Test
    fun `collapses runs of three or more but preserves ordinary doubled letters`() {
        // The threshold is the whole point: collapsing pairs would turn "cool" into "col"
        // and break matching on perfectly normal words.
        assertEquals("cool", Normalizer.normalize("cool").loose.text)
        assertEquals("send", Normalizer.normalize("seeeend").loose.text)
        assertEquals("nudes", Normalizer.normalize("nuuuudes").loose.text)
        assertEquals("letter", Normalizer.normalize("letter").loose.text)
    }

    @Test
    fun `strips zero width characters used to break up words`() {
        val withZwnj = "f‌u‌c‌k"
        assertEquals("fuck", Normalizer.normalize(withZwnj).loose.text)
    }

    @Test
    fun `tight form removes separators while loose form keeps them`() {
        val forms = Normalizer.normalize("s.e.n.d n.u.d.e.s")
        assertEquals("sendnudes", forms.tight.text)
        assertTrue(forms.loose.text.contains('.'), "loose form must retain separators for word boundaries")
    }

    @Test
    fun `maps spans back to the original buffer through collapsing`() {
        val original = "seeeend"
        val forms = Normalizer.normalize(original)
        assertEquals("send", forms.loose.text)

        // "send" spans the entire original string: the collapsed run must absorb every
        // original character it stands for, or the highlight would stop short.
        val span = forms.loose.mapSpan(0, 4)
        assertEquals(0, span.first)
        assertEquals(original.length - 1, span.last)
    }

    @Test
    fun `maps spans back correctly when characters were dropped`() {
        val original = "hi f‌uck"
        val forms = Normalizer.normalize(original)
        val index = forms.loose.text.indexOf("fuck")
        assertTrue(index >= 0, "expected normalized text to contain the term, got '${forms.loose.text}'")

        val span = forms.loose.mapSpan(index, index + 4)
        assertEquals(3, span.first, "should start at the 'f' in the original")
        assertEquals(original.length - 1, span.last, "should end at the final 'k' in the original")
    }

    @Test
    fun `word boundary detection distinguishes whole words from substrings`() {
        val forms = Normalizer.normalize("classic weed tweed")
        val loose = forms.loose

        val standalone = loose.text.indexOf("weed")
        assertTrue(loose.isWordBounded(standalone, standalone + 4), "standalone 'weed' is word bounded")

        val insideTweed = loose.text.indexOf("weed", standalone + 1)
        assertFalse(loose.isWordBounded(insideTweed, insideTweed + 4), "'weed' inside 'tweed' is not word bounded")
    }
}
