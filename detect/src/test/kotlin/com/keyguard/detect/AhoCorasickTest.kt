package com.keyguard.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AhoCorasickTest {

    @Test
    fun `finds all patterns including overlaps`() {
        val patterns = listOf("he", "she", "his", "hers")
        val automaton = AhoCorasick.build(patterns)

        val text = "ushers"
        val found = automaton.search(text).map { patterns[it.patternIndex] to it.start }.toSet()

        // "she" and "he" both end inside "ushers"; a fail-link walk must report both.
        assertTrue("she" to 1 in found, "expected 'she' at 1, got $found")
        assertTrue("he" to 2 in found, "expected 'he' at 2, got $found")
        assertTrue("hers" to 2 in found, "expected 'hers' at 2, got $found")
    }

    @Test
    fun `reports accurate spans`() {
        val patterns = listOf("nudes")
        val automaton = AhoCorasick.build(patterns)
        val match = automaton.search("send nudes now").single()

        assertEquals(5, match.start)
        assertEquals(10, match.end)
    }

    @Test
    fun `handles empty input and empty pattern set`() {
        assertTrue(AhoCorasick.build(listOf("abc")).search("").isEmpty())
        assertTrue(AhoCorasick.build(emptyList()).search("anything").isEmpty())
    }

    @Test
    fun `finds repeated occurrences of the same pattern`() {
        val automaton = AhoCorasick.build(listOf("ab"))
        assertEquals(3, automaton.search("abxabxab").size)
    }

    @Test
    fun `matches a pattern that is a suffix of another`() {
        val patterns = listOf("kill yourself", "yourself")
        val automaton = AhoCorasick.build(patterns)
        val found = automaton.search("go kill yourself").map { patterns[it.patternIndex] }.toSet()
        assertEquals(setOf("kill yourself", "yourself"), found)
    }
}
