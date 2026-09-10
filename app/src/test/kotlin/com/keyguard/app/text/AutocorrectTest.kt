package com.keyguard.app.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordScannerTest {

    @Test
    fun `finds the word at the cursor`() {
        val word = WordScanner.currentWord("hello wor")
        assertEquals("wor", word.text)
        assertEquals(6, word.start)
        assertEquals(9, word.end)
    }

    @Test
    fun `apostrophes stay inside a word`() {
        // Correcting "dont" to "don't" is one of the most common corrections there is, so the
        // apostrophe must not split the word into fragments.
        assertEquals("don't", WordScanner.currentWord("i don't").text)
        assertEquals("it's", WordScanner.currentWord("it's").text)
    }

    @Test
    fun `a trailing separator means no word is in progress`() {
        assertTrue(WordScanner.currentWord("hello ").isEmpty)
        assertTrue(WordScanner.currentWord("done.").isEmpty)
    }

    @Test
    fun `finds the word behind a just typed separator`() {
        val word = WordScanner.wordBeforeSeparator("hello teh ")
        assertEquals("teh", word.text)
        assertEquals(6, word.start)
        assertEquals(9, word.end)
    }

    @Test
    fun `identifies word terminators`() {
        assertTrue(WordScanner.isWordTerminator(" "))
        assertTrue(WordScanner.isWordTerminator("."))
        assertTrue(WordScanner.isWordTerminator("!"))
        assertFalse(WordScanner.isWordTerminator("a"))
        assertFalse(WordScanner.isWordTerminator("'"))
        assertFalse(WordScanner.isWordTerminator(""))
    }

    @Test
    fun `only plausible words are correctable`() {
        assertTrue(WordScanner.isCorrectable("teh"))
        assertTrue(WordScanner.isCorrectable("recieve"))

        assertFalse(WordScanner.isCorrectable("hi"), "too short to rank usefully")
        assertFalse(WordScanner.isCorrectable("abc123"), "contains digits")
        assertFalse(WordScanner.isCorrectable("NASA"), "acronyms are not typos")
        assertFalse(WordScanner.isCorrectable(""))
    }

    @Test
    fun `capitalisation is carried onto the replacement`() {
        assertEquals("The", WordScanner.matchCase("Teh", "the"))
        assertEquals("the", WordScanner.matchCase("teh", "the"))
        assertEquals("THE", WordScanner.matchCase("TEH", "the"))
    }
}

class KeyProximityTest {

    @Test
    fun `adjacent keys are recognised`() {
        assertTrue(KeyProximity.areAdjacent('q', 'w'))
        assertTrue(KeyProximity.areAdjacent('a', 's'))
        assertTrue(KeyProximity.areAdjacent('e', 'd'))
        assertFalse(KeyProximity.areAdjacent('q', 'p'))
        assertFalse(KeyProximity.areAdjacent('a', 'l'))
    }

    @Test
    fun `substituting a neighbour costs less than a distant key`() {
        // This is the whole point of geometry-aware scoring: hitting 'p' instead of 'o' is a
        // slip, hitting 'p' instead of 'q' is a different word.
        assertTrue(KeyProximity.substitutionCost('o', 'p') < KeyProximity.substitutionCost('o', 'q'))
        assertEquals(0f, KeyProximity.substitutionCost('a', 'a'))
    }

    @Test
    fun `transpositions are cheap`() {
        // "teh" for "the" and "adn" for "and" are among the most common real typing errors, so
        // they must rank above a same-length substitution.
        val transposed = KeyProximity.editDistance("teh", "the")
        val substituted = KeyProximity.editDistance("tex", "the")
        assertTrue(transposed < substituted, "transposition $transposed should beat $substituted")
    }

    @Test
    fun `identical words have zero distance`() {
        assertEquals(0f, KeyProximity.editDistance("hello", "hello"))
        assertEquals(0f, KeyProximity.editDistance("Hello", "hello"), "comparison is case-insensitive")
    }

    @Test
    fun `distance grows with dissimilarity`() {
        val close = KeyProximity.normalizedDistance("hellp", "hello")
        val far = KeyProximity.normalizedDistance("hellp", "goodbye")
        assertTrue(close < far)
    }

    @Test
    fun `empty inputs do not throw`() {
        assertEquals(5f, KeyProximity.editDistance("", "hello"))
        assertEquals(5f, KeyProximity.editDistance("hello", ""))
    }
}

class CorrectionRankerTest {

    @Test
    fun `the typed word is never offered back`() {
        val ranked = CorrectionRanker.rank("hello", listOf("hello", "hallo"))
        assertTrue(ranked.none { it.word.equals("hello", ignoreCase = true) })
    }

    @Test
    fun `keyboard proximity outranks the source ordering`() {
        // The spell checker put "help" first, but 'p' neighbours 'o', so "hello" is the more
        // likely intent. Re-ranking is what makes the correction feel deliberate.
        val ranked = CorrectionRanker.rank("hellp", listOf("help", "hello"))
        assertEquals("hello", ranked.first().word)
    }

    @Test
    fun `wildly different candidates are discarded`() {
        val ranked = CorrectionRanker.rank("teh", listOf("the", "elephant"))
        assertTrue(ranked.none { it.word == "elephant" })
    }

    @Test
    fun `suggestion count is capped`() {
        val many = listOf("thee", "then", "them", "they", "than", "that")
        assertTrue(CorrectionRanker.rank("the", many).size <= CorrectionRanker.MAX_SUGGESTIONS)
    }

    @Test
    fun `duplicates are collapsed`() {
        val ranked = CorrectionRanker.rank("teh", listOf("the", "The", "THE"))
        assertEquals(1, ranked.size)
    }

    @Test
    fun `a clear winner auto applies`() {
        val ranked = CorrectionRanker.rank("teh", listOf("the"))
        assertTrue(CorrectionRanker.shouldAutoApply("teh", ranked))
    }

    @Test
    fun `an ambiguous choice is offered rather than made`() {
        // A silent wrong replacement is far worse than a missed one, so two near-equal
        // candidates must not auto-apply.
        val ranked = CorrectionRanker.rank("bat", listOf("bar", "bad"))
        assertFalse(
            CorrectionRanker.shouldAutoApply("bat", ranked),
            "two equidistant candidates must not be chosen silently",
        )
    }

    @Test
    fun `nothing auto applies without candidates`() {
        assertFalse(CorrectionRanker.shouldAutoApply("teh", emptyList()))
    }

    @Test
    fun `short words never auto apply`() {
        val ranked = CorrectionRanker.rank("hi", listOf("hit"))
        assertFalse(CorrectionRanker.shouldAutoApply("hi", ranked))
    }
}

class AutocorrectStateTest {

    @Test
    fun `a recorded correction is revertible`() {
        val state = AutocorrectState()
        state.recordApplied("teh", "the", start = 6)

        val pending = state.consumeForRevert()
        assertNotNull(pending)
        assertEquals("teh", pending.original)
        assertEquals("the", pending.replacement)
        assertEquals(6, pending.start)
        assertEquals(9, pending.end)
    }

    @Test
    fun `a correction can only be reverted once`() {
        val state = AutocorrectState()
        state.recordApplied("teh", "the", start = 0)
        assertNotNull(state.consumeForRevert())
        assertNull(state.consumeForRevert())
    }

    @Test
    fun `typing on accepts the correction`() {
        // Reverting after the user has moved past the word would silently rewrite text they
        // have already accepted.
        val state = AutocorrectState()
        state.recordApplied("teh", "the", start = 0)
        state.invalidate()
        assertNull(state.revertible)
    }

    @Test
    fun `nothing is revertible initially`() {
        assertNull(AutocorrectState().consumeForRevert())
    }
}
