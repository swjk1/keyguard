package com.keyguard.app.family

import com.keyguard.app.input.ComposeOutcome
import com.keyguard.detect.Emotion
import com.keyguard.detect.MessageSummary
import com.keyguard.detect.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate that decides whether a child's words leave their phone.
 *
 * This is the most consequential pure function added to the app, so it is tested as a decision
 * table rather than by example: every scope crossed with the things that can override it. The
 * failure mode being guarded against is not a crash — it is a scope quietly permitting one more
 * thing than a family agreed to, which nobody would notice from the outside.
 */
class ContentCaptureTest {

    private val summary = MessageSummary(
        themes = listOf(Theme.SCHOOL, Theme.FRIENDSHIP),
        emotions = listOf(Emotion.ANXIOUS),
        chars = 30,
    )

    private fun capture(
        scope: ReviewScope,
        text: String = "double maths tomorrow and lucy still isn't speaking to me",
        protectedField: Boolean = false,
    ) = ContentCapture.sample(
        scope = scope,
        summary = summary,
        outcome = ComposeOutcome.SENT,
        atMs = 1_700_000_000_000,
        flagged = false,
        text = text,
        protectedField = protectedField,
    )

    @Test
    fun `concerning only records nothing at all`() {
        // The default, and the promise the product shipped with. An ordinary message produces
        // no record of any kind — not a redacted one, not an empty one.
        assertNull(capture(ReviewScope.CONCERNING_ONLY))
    }

    @Test
    fun `themes records the tags and never the text`() {
        val sample = assertNotNull(capture(ReviewScope.THEMES))
        assertEquals(listOf(Theme.SCHOOL, Theme.FRIENDSHIP), sample.themes)
        assertEquals(listOf(Emotion.ANXIOUS), sample.emotions)
        assertNull(sample.text, "THEMES must never carry message text")
    }

    @Test
    fun `full text records the text`() {
        val sample = assertNotNull(capture(ReviewScope.FULL_TEXT))
        assertEquals("double maths tomorrow and lucy still isn't speaking to me", sample.text)
    }

    @Test
    fun `a protected field is captured at no scope`() {
        // No parental setting can reach past FieldPolicy. Checked here as well as at the call
        // site, because that claim is worth enforcing twice.
        for (scope in ReviewScope.entries) {
            assertNull(
                capture(scope, protectedField = true),
                "$scope captured a protected field",
            )
        }
    }

    @Test
    fun `a blank message is never a sample`() {
        for (scope in ReviewScope.entries) {
            assertNull(capture(scope, text = "   "), "$scope sampled whitespace")
        }
    }

    @Test
    fun `long messages are truncated rather than stored whole`() {
        val long = "a".repeat(ActivitySample.MAX_TEXT + 500)
        val sample = assertNotNull(capture(ReviewScope.FULL_TEXT, text = long))
        assertEquals(ActivitySample.MAX_TEXT, sample.text?.length)
        // The length signal survives truncation, so a report can still tell a one-word reply
        // from an essay.
        assertEquals(long.length, sample.chars)
    }

    @Test
    fun `the scope properties agree with what capture actually does`() {
        // The properties are read at several call sites; capture is the thing that matters.
        // Asserting they agree stops the two drifting apart.
        for (scope in ReviewScope.entries) {
            val sample = capture(scope)
            assertEquals(
                scope.recordsEveryMessage,
                sample != null,
                "recordsEveryMessage disagrees with capture for $scope",
            )
            assertEquals(
                scope.recordsText,
                sample?.text != null,
                "recordsText disagrees with capture for $scope",
            )
        }
    }

    @Test
    fun `narrowing is ordered so a sync can tell which direction a change went`() {
        assertTrue(ReviewScope.CONCERNING_ONLY.narrowerThan(ReviewScope.THEMES))
        assertTrue(ReviewScope.CONCERNING_ONLY.narrowerThan(ReviewScope.FULL_TEXT))
        assertTrue(ReviewScope.THEMES.narrowerThan(ReviewScope.FULL_TEXT))

        assertTrue(!ReviewScope.FULL_TEXT.narrowerThan(ReviewScope.THEMES))
        assertTrue(!ReviewScope.THEMES.narrowerThan(ReviewScope.THEMES))
    }

    @Test
    fun `an unrecognised scope falls back to collecting the least`() {
        // The opposite direction from OverrideLevel's fallback, and deliberately so. There the
        // safe reading is the permissive one, because an invented restriction locks a child out
        // of their keyboard. Here the safe reading is the restrictive one, because an invented
        // permission starts collecting a child's messages on the strength of a typo.
        assertEquals(ReviewScope.CONCERNING_ONLY, ReviewScope.fromName("EVERYTHING"))
        assertEquals(ReviewScope.CONCERNING_ONLY, ReviewScope.fromName(null))
    }
}
