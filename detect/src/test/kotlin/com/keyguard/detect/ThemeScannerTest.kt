package com.keyguard.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reporting vocabulary.
 *
 * The assertions worth having here are not "does the word list contain this word" — that is a
 * property of a JSON file and tests nothing. They are the three invariants that make the
 * summary safe to send to a parent at all: it never carries text, it never influences what the
 * person typing sees, and it stays coarse enough that a theme list cannot be read back as a
 * sentence.
 */
class ThemeScannerTest {

    private val engine = DetectionEngine.withBundledPack()

    @Test
    fun `picks up the obvious themes of a message`() {
        val summary = engine.summarize("got so much homework tonight and my best friend is ignoring me")
        assertTrue(Theme.SCHOOL in summary.themes, "expected SCHOOL in ${summary.themes}")
        assertTrue(Theme.FRIENDSHIP in summary.themes, "expected FRIENDSHIP in ${summary.themes}")
    }

    @Test
    fun `picks up mood words`() {
        val summary = engine.summarize("i'm so stressed about the exam, barely slept")
        assertTrue(Emotion.STRESSED in summary.emotions, "expected STRESSED in ${summary.emotions}")
    }

    @Test
    fun `an ordinary message with no topic words summarizes to nothing`() {
        // The common case by far. A summary that invents a theme for "ok see you then" would
        // fill a weekly report with noise and make the real signals unreadable.
        assertTrue(engine.summarize("ok sounds good see you then").isEmpty)
    }

    @Test
    fun `blank input is empty rather than throwing`() {
        assertEquals(MessageSummary.EMPTY, engine.summarize(""))
        assertEquals(MessageSummary.EMPTY, engine.summarize("   "))
    }

    @Test
    fun `themes are capped so a long message cannot become a tag cloud`() {
        val everything = buildString {
            append("homework and my best friend and my mum and my girlfriend and fortnite ")
            append("and football and netflix and instagram and pocket money and pizza ")
            append("and the doctor and my hair and university and holiday and my dog")
        }
        val summary = engine.summarize(everything)
        assertTrue(summary.themes.size <= 3, "themes not capped: ${summary.themes}")
        assertTrue(summary.emotions.size <= 2, "emotions not capped: ${summary.emotions}")
    }

    @Test
    fun `themes are word bounded by default`() {
        // "class" contains "ass" in the harm lexicon; the same problem applies here. "classic"
        // must not report SCHOOL, or every message about a song does.
        val summary = engine.summarize("that album is such a classic")
        assertFalse(Theme.SCHOOL in summary.themes, "substring match leaked: ${summary.themes}")
    }

    @Test
    fun `summarizing never produces findings and scanning never produces themes`() {
        // The two layers are separate on purpose: one interrupts the user, the other only ever
        // reaches a parent. This asserts the separation rather than trusting the call sites.
        val text = "so stressed about my exam tomorrow"
        assertTrue(engine.scan(text).findings.isEmpty(), "topic words must not warn the user")
        assertTrue(engine.summarize(text).themes.isNotEmpty(), "expected a theme for the report")
    }

    @Test
    fun `a flagged message still summarizes, so a report can place it in context`() {
        // A parent seeing "a self-harm warning fired" is told very little. The same warning
        // alongside SCHOOL and STRESSED across a week is the thing they can act on.
        val summary = engine.summarize("i can't do this anymore, the exams are too much")
        assertTrue(Theme.SCHOOL in summary.themes, "expected SCHOOL in ${summary.themes}")
    }

    @Test
    fun `the summary carries length but not content`() {
        val text = "my address is 123 Main Street"
        val summary = engine.summarize(text)
        assertEquals(text.length, summary.chars)
        // MessageSummary is a data class with three fields and none of them is a String, so
        // its toString cannot leak a buffer into a log. Asserted rather than assumed, because
        // adding one is a one-line change that nothing else would catch.
        assertFalse(summary.toString().contains("Main Street"))
    }

    @Test
    fun `the pack actually loaded a reporting vocabulary`() {
        // Same reasoning as termCount: a release build whose shrinking broke deserialization
        // would otherwise report empty summaries forever and look like a quiet child.
        assertTrue(engine.themeTermCount > 100, "reporting vocabulary too small: ${engine.themeTermCount}")
    }
}
