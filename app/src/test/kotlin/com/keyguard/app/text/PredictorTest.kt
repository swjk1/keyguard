package com.keyguard.app.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordTrieTest {

    private fun trie(vararg words: String) = WordTrie().apply {
        words.forEachIndexed { rank, word -> insert(word, rank) }
    }

    @Test
    fun `completes a prefix best first`() {
        // Insertion order is the frequency rank, so "hello" must outrank "helmet".
        val trie = trie("hello", "help", "helmet", "held")
        assertEquals(listOf("hello", "help", "helmet"), trie.completions("hel", limit = 3))
    }

    @Test
    fun `the prefix itself is not offered back`() {
        val trie = trie("help", "helpful")
        assertFalse(trie.completions("help", limit = 3).contains("help"))
        assertTrue(trie.completions("help", limit = 3).contains("helpful"))
    }

    @Test
    fun `an unknown prefix yields nothing`() {
        assertTrue(trie("hello").completions("xyz", limit = 3).isEmpty())
        assertTrue(trie("hello").completions("", limit = 3).isEmpty())
    }

    @Test
    fun `membership and rank lookups work`() {
        val trie = trie("the", "and")
        assertTrue(trie.contains("the"))
        assertFalse(trie.contains("th"), "a prefix is not a word")
        assertFalse(trie.contains("theory"))
        assertEquals(0, trie.rankOf("the"))
        assertEquals(1, trie.rankOf("and"))
    }

    @Test
    fun `duplicates keep the better rank and are counted once`() {
        val trie = WordTrie()
        trie.insert("the", 50)
        trie.insert("the", 1)
        assertEquals(1, trie.rankOf("the"))
        assertEquals(1, trie.size)
    }

    @Test
    fun `limit is respected`() {
        val trie = trie("aa", "ab", "ac", "ad", "ae", "af")
        assertEquals(2, trie.completions("a", limit = 2).size)
    }
}

class VocabularyTest {

    private fun load(
        words: String = "the be to of and i you a in that hello help held helmet friend receive",
        bigrams: String = "i am was will\nhow are you\nthank you so",
    ) = Vocabulary.load(words.byteInputStream(), bigrams.byteInputStream())

    @Test
    fun `parses words in frequency order`() {
        val vocab = load()
        assertTrue(vocab.isKnown("the"))
        assertTrue(vocab.isKnown("THE"), "lookups are case-insensitive")
        assertFalse(vocab.isKnown("xylophone"))
        assertEquals(0, vocab.rankOf("the"))
    }

    @Test
    fun `comment lines and junk tokens are skipped`() {
        val vocab = load(words = "# a comment line\nthe and\n123 ok!!\nfriend")
        assertTrue(vocab.isKnown("the"))
        assertTrue(vocab.isKnown("friend"))
        assertFalse(vocab.isKnown("123"))
        assertFalse(vocab.isKnown("ok!!"))
        assertFalse(vocab.isKnown("comment"), "comment lines must not enter the vocabulary")
    }

    @Test
    fun `parses next word predictions`() {
        val vocab = load()
        assertEquals(listOf("am", "was", "will"), vocab.following("i"))
        // "how are you": every token after the key is a ranked prediction, so both are returned.
        assertEquals(listOf("are", "you"), vocab.following("how"))
        assertTrue(vocab.following("unknownword").isEmpty())
    }

    @Test
    fun `completions come back best first`() {
        val completions = load().completions("hel", limit = 3)
        assertTrue(completions.isNotEmpty())
        assertEquals("hello", completions.first(), "hello precedes help in the list")
    }

    @Test
    fun `nearby finds close words for a typo`() {
        // 'p' neighbours 'o', so "helo" should surface "hello" or "help".
        val nearby = load().nearby("helo", limit = 5)
        assertTrue(nearby.contains("hello") || nearby.contains("help"), "got $nearby")
    }

    @Test
    fun `nearby never returns the identical word`() {
        assertFalse(load().nearby("friend", limit = 5).contains("friend"))
    }

    @Test
    fun `nearby ignores very short input`() {
        assertTrue(load().nearby("he", limit = 5).isEmpty())
    }

    @Test
    fun `the bundled assets parse and are usefully populated`() {
        // Guards against a malformed asset shipping silently, which would leave the strip empty
        // with no error anywhere.
        val words = javaClass.classLoader!!.getResourceAsStream("vocab/words.txt")
        val bigrams = javaClass.classLoader!!.getResourceAsStream("vocab/bigrams.txt")
        if (words == null || bigrams == null) return // assets are not on the unit-test classpath

        val vocab = Vocabulary.load(words, bigrams)
        assertTrue(vocab.wordCount > 500, "vocabulary looks too small: ${vocab.wordCount}")
        assertTrue(vocab.bigramCount > 50, "bigram table looks too small: ${vocab.bigramCount}")
    }
}

/** A fake learned store, so the merge rules can be tested without a Context. */
private class FakeLearned(
    private val nextWords: Map<String, List<String>> = emptyMap(),
    private val words: List<String> = emptyList(),
) : LearnedWords {
    override fun following(previous: String, limit: Int): List<String> =
        nextWords[previous.lowercase()].orEmpty().take(limit)

    override fun completions(prefix: String, limit: Int): List<String> =
        words.filter { it.startsWith(prefix.lowercase()) && it != prefix.lowercase() }.take(limit)

    override fun isKnown(word: String): Boolean = words.contains(word.lowercase())
}

class PredictorTest {

    private val vocabulary = Vocabulary.load(
        (
            "the be to of and i you a in that have it for not on with he as do at " +
                "hello help held helmet friend friends going gonna want need think know receive"
            ).byteInputStream(),
        "i am was will\nhow are you\nthank you so\ngoing to be out".byteInputStream(),
    )

    private fun predictor(learned: LearnedWords = LearnedWords.Empty) =
        Predictor(vocabulary, learned)

    @Test
    fun `an empty prefix predicts the next word`() {
        val result = predictor().suggest(previousWord = "i", prefix = "")
        assertEquals(Predictor.Kind.PREDICTION, result.kind)
        assertEquals(listOf("am", "was", "will"), result.items)
        assertEquals(-1, result.autoApplyIndex, "a prediction must never auto-apply")
    }

    @Test
    fun `learned pairs outrank the bundled table`() {
        // What this person actually types beats a generic frequency table.
        val learned = FakeLearned(nextWords = mapOf("i" to listOf("reckon")))
        val result = predictor(learned).suggest(previousWord = "i", prefix = "")
        assertEquals("reckon", result.items.first())
        assertTrue(result.items.contains("am"), "bundled predictions still fill the remaining slots")
    }

    @Test
    fun `sentence start falls back to the most common words`() {
        val result = predictor().suggest(previousWord = null, prefix = "")
        assertEquals(Predictor.Kind.PREDICTION, result.kind)
        assertEquals(listOf("the", "be", "to"), result.items)
    }

    @Test
    fun `a known word gets completed rather than corrected`() {
        val result = predictor().suggest(previousWord = null, prefix = "hel")
        assertEquals(Predictor.Kind.COMPLETION, result.kind)
        assertTrue(result.items.contains("hello"))
        assertEquals(-1, result.autoApplyIndex, "a completion must never auto-apply")
    }

    @Test
    fun `a prefix that real words start with is completed, not corrected`() {
        // The distinction that matters: "hel" is an unfinished word, "helzz" is a mistake.
        assertEquals(Predictor.Kind.COMPLETION, predictor().suggest(null, "hel").kind)
        assertEquals(Predictor.Kind.CORRECTION, predictor().suggest(null, "recieve").kind)
    }

    @Test
    fun `an unknown word is corrected and keeps the literal visible`() {
        val result = predictor().suggest(
            previousWord = null,
            prefix = "recieve",
            spellCheckerCandidates = listOf("receive"),
        )
        assertEquals(Predictor.Kind.CORRECTION, result.kind)
        assertEquals(0, result.literalIndex)
        assertEquals("recieve", result.items[0], "the literal must stay visible")
        assertEquals(1, result.autoApplyIndex)
        assertEquals("receive", result.items[1])
    }

    @Test
    fun `a word the user types often is never corrected`() {
        // Autocorrecting somebody's own name, or a friend's, is infuriating and is exactly what
        // the learned dictionary exists to prevent.
        val learned = FakeLearned(words = listOf("priya"))
        val result = predictor(learned).suggest(previousWord = null, prefix = "priya")
        assertTrue(
            result.kind != Predictor.Kind.CORRECTION,
            "a learned word must not be treated as a typo, got ${result.kind}",
        )
    }

    @Test
    fun `learned completions are offered before bundled ones`() {
        val learned = FakeLearned(words = listOf("helsinki"))
        val result = predictor(learned).suggest(previousWord = null, prefix = "hel")
        assertEquals("helsinki", result.items.first())
    }

    @Test
    fun `capitalisation carries onto suggestions`() {
        val result = predictor().suggest(previousWord = null, prefix = "Hel")
        assertTrue(
            result.items.all { it.first().isUpperCase() },
            "suggestions must match the typed case, got ${result.items}",
        )
    }

    @Test
    fun `an unpredictable previous word yields nothing rather than noise`() {
        val result = predictor().suggest(previousWord = "helmet", prefix = "")
        assertTrue(result.isEmpty, "no prediction is better than an irrelevant one")
    }

    @Test
    fun `suggestion count never exceeds the strip capacity`() {
        val cases = listOf("" to "i", "hel" to null, "recieve" to null)
        for ((prefix, previous) in cases) {
            val result = predictor().suggest(previous, prefix)
            assertTrue(
                result.items.size <= CorrectionRanker.MAX_SUGGESTIONS,
                "'$prefix' produced ${result.items.size} suggestions",
            )
        }
    }
}
