package com.keyguard.app.overlay

import com.keyguard.app.family.OverrideLevel
import com.keyguard.detect.Category
import com.keyguard.detect.FieldPolicy
import com.keyguard.detect.Finding
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import com.keyguard.detect.VerifyReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The overlay's pure decisions: what may be watched, what is shown, and where it goes.
 *
 * These matter more than the IME's equivalents did. The keyboard only ever saw what was typed
 * on it and could only dim its own keys; the accessibility service sees every editable field in
 * every app and covers somebody else's keyboard. Both of those are properties nobody can check
 * by looking at a phone for a minute, so they are checked here.
 */
class OverlayTest {

    private val ourPackage = "com.keyguard.app"

    private fun result(
        severity: Severity,
        crisis: Boolean = false,
        empty: Boolean = false,
    ) = ScanResult(
        findings = if (empty) {
            emptyList()
        } else {
            listOf(
                Finding(
                    start = 0,
                    end = 4,
                    category = if (crisis) Category.SELF_HARM else Category.PII_DISCLOSURE,
                    severity = severity,
                    ruleId = "test.rule",
                    message = "test",
                ),
            )
        },
        maxSeverity = if (empty) Severity.NONE else severity,
        eligibleForVerification = false,
        verifyReason = null as VerifyReason?,
        requiresCrisisResponse = crisis,
    )

    private fun decide(
        severity: Severity,
        level: OverrideLevel = OverrideLevel.FULL,
        crisis: Boolean = false,
        acknowledged: Boolean = false,
        fieldProtected: Boolean = false,
        blockingEnabled: Boolean = true,
        empty: Boolean = false,
    ) = OverlayDecision.decide(
        result = result(severity, crisis, empty),
        fieldProtected = fieldProtected,
        overrideLevel = level,
        blockingEnabled = blockingEnabled,
        acknowledged = acknowledged,
        summaryFor = { "summary" },
        detailFor = { "detail" },
        crisisMessage = "crisis",
        crisisResourceLabel = "Samaritans",
    )

    // region MonitoredField

    @Test
    fun `a password field is never monitored`() {
        assertFalse(
            MonitoredField.mayMonitor(
                packageName = "com.example.chat",
                ourPackage = ourPackage,
                editable = true,
                password = true,
                inputType = FieldPolicy.TYPE_CLASS_TEXT,
            ),
        )
    }

    @Test
    fun `a field the platform did not flag but whose input type says password is not monitored`() {
        // The second of the two independent checks. Some custom and WebView inputs never set
        // isPassword but do declare the input type, and either check alone has known gaps.
        assertFalse(
            MonitoredField.mayMonitor(
                packageName = "com.example.bank",
                ourPackage = ourPackage,
                editable = true,
                password = false,
                inputType = FieldPolicy.TYPE_CLASS_TEXT or
                    FieldPolicy.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            ),
        )
    }

    @Test
    fun `non-editable nodes are not monitored`() {
        // Labels, buttons and list rows all surface as nodes with text. Reading them would turn
        // "what my child wrote" into "everything my child looked at".
        assertFalse(
            MonitoredField.mayMonitor(
                packageName = "com.example.chat",
                ourPackage = ourPackage,
                editable = false,
                password = false,
                inputType = FieldPolicy.TYPE_CLASS_TEXT,
            ),
        )
    }

    @Test
    fun `our own app is not monitored`() {
        // The setup screen has a "try it here" field; watching it would be a feedback loop.
        assertFalse(
            MonitoredField.mayMonitor(
                packageName = ourPackage,
                ourPackage = ourPackage,
                editable = true,
                password = false,
                inputType = FieldPolicy.TYPE_CLASS_TEXT,
            ),
        )
    }

    @Test
    fun `an ordinary chat field is monitored`() {
        assertTrue(
            MonitoredField.mayMonitor(
                packageName = "com.example.chat",
                ourPackage = ourPackage,
                editable = true,
                password = false,
                inputType = FieldPolicy.TYPE_CLASS_TEXT,
            ),
        )
    }

    @Test
    fun `an unknown package is not monitored`() {
        assertFalse(
            MonitoredField.mayMonitor(
                packageName = null,
                ourPackage = ourPackage,
                editable = true,
                password = false,
                inputType = FieldPolicy.TYPE_CLASS_TEXT,
            ),
        )
    }

    // endregion

    // region OverlayDecision

    @Test
    fun `nothing found means no windows`() {
        assertEquals(OverlayState.Hidden, decide(Severity.NONE, empty = true))
    }

    @Test
    fun `a protected field shows nothing whatever the scan said`() {
        assertEquals(OverlayState.Hidden, decide(Severity.HIGH, fieldProtected = true))
    }

    @Test
    fun `high severity shades the keyboard and offers both exits at full permission`() {
        val state = decide(Severity.HIGH, OverrideLevel.FULL)
        assertTrue(state is OverlayState.Warning)
        assertTrue(state.shaded)
        assertTrue(state.dismissible)
        assertTrue(state.removable)
    }

    @Test
    fun `the strictest level shades and offers only removal`() {
        val state = decide(Severity.HIGH, OverrideLevel.NONE)
        assertTrue(state is OverlayState.Warning)
        assertTrue(state.shaded)
        assertFalse(state.dismissible, "OverrideLevel.NONE must not offer Ignore")
        assertTrue(state.removable, "removal is the only exit and must always be offered")
    }

    @Test
    fun `medium severity warns but never shades`() {
        // The shade is the overlay's version of dead keys, and it fires on the same condition
        // the IME's block did: high severity only.
        val state = decide(Severity.MEDIUM, OverrideLevel.NONE)
        assertTrue(state is OverlayState.Warning)
        assertFalse(state.shaded)
    }

    @Test
    fun `a parent who disabled blocking gets no shade`() {
        val state = decide(Severity.HIGH, OverrideLevel.NONE, blockingEnabled = false)
        assertTrue(state is OverlayState.Warning)
        assertFalse(state.shaded)
    }

    @Test
    fun `crisis never shades, at any override level`() {
        // The overlay's version of the invariant InputGate holds: someone writing about hurting
        // themselves keeps their keyboard. Covering it would be worse than the IME's dead keys,
        // because a shade physically blocks the screen.
        for (level in OverrideLevel.entries) {
            val state = decide(Severity.HIGH, level, crisis = true)
            assertTrue(state is OverlayState.Crisis, "crisis became ${state::class.simpleName} at $level")
        }
    }

    @Test
    fun `an acknowledgement hides the overlay only when the policy allowed it`() {
        assertEquals(
            OverlayState.Hidden,
            decide(Severity.HIGH, OverrideLevel.FULL, acknowledged = true),
        )

        // Same flag, stricter policy. Checking the policy again rather than trusting the flag
        // is what makes a tightening sync take effect on the next keystroke.
        val state = decide(Severity.HIGH, OverrideLevel.NONE, acknowledged = true)
        assertTrue(state is OverlayState.Warning)
        assertTrue(state.shaded)
    }

    // endregion

    // region OverlayAnchor

    private val screenHeight = 2400
    private val screenWidth = 1080
    private val keyboard = OverlayAnchor.Bounds(0, 1500, 1080, 2400)

    @Test
    fun `the warning sits directly on top of the reported keyboard`() {
        val placement = OverlayAnchor.placeWarning(
            OverlayAnchor.Position.ABOVE_KEYBOARD,
            screenHeight,
            keyboard,
            fallbackBottomMarginPx = 800,
        )
        assertFalse(placement.fromTop)
        assertEquals(screenHeight - keyboard.top, placement.bottomMarginPx)
    }

    @Test
    fun `an unreported keyboard falls back rather than guessing`() {
        val placement = OverlayAnchor.placeWarning(
            OverlayAnchor.Position.ABOVE_KEYBOARD,
            screenHeight,
            imeBounds = null,
            fallbackBottomMarginPx = 800,
        )
        assertEquals(800, placement.bottomMarginPx)
    }

    @Test
    fun `a keyboard that drops out for a frame still leaves the warning above the composer`() {
        // Seen on the emulator with Google Messages: every other render had no IME window, and
        // the fixed fallback put the warning's buttons over the host's send button.
        val composer = OverlayAnchor.Bounds(190, 1370, 796, 1496)
        val placement = OverlayAnchor.placeWarning(
            OverlayAnchor.Position.ABOVE_KEYBOARD,
            screenHeight,
            imeBounds = null,
            fallbackBottomMarginPx = 800,
            fieldBounds = composer,
        )
        assertEquals(screenHeight - composer.top, placement.bottomMarginPx)

        // A document-sized field is no anchor; above it is off the top of the screen.
        val document = OverlayAnchor.Bounds(0, 300, 1080, 2300)
        val fallback = OverlayAnchor.placeWarning(
            OverlayAnchor.Position.ABOVE_KEYBOARD,
            screenHeight,
            imeBounds = null,
            fallbackBottomMarginPx = 800,
            fieldBounds = document,
        )
        assertEquals(800, fallback.bottomMarginPx)
    }

    @Test
    fun `nonsense keyboard bounds are treated as no answer`() {
        // A reported top of zero, or one past the bottom of the screen, means the window list
        // handed us something that is not a visible keyboard. Trusting it would put the warning
        // somewhere absurd.
        for (bad in listOf(
            OverlayAnchor.Bounds(0, 0, 1080, 2400),
            OverlayAnchor.Bounds(0, 2400, 1080, 2600),
            OverlayAnchor.Bounds(0, 1500, 0, 1400),
        )) {
            val placement = OverlayAnchor.placeWarning(
                OverlayAnchor.Position.ABOVE_KEYBOARD,
                screenHeight,
                bad,
                fallbackBottomMarginPx = 800,
            )
            assertEquals(800, placement.bottomMarginPx, "trusted bad bounds $bad")
            assertNull(OverlayAnchor.shadeRect(screenWidth, screenHeight, bad))
        }
    }

    @Test
    fun `the warning clears the text field, not just the keyboard`() {
        // The bug the browser harness surfaced. Anchoring to the keyboard's top edge is the
        // obvious placement and it lands the warning exactly on the composer, so the message
        // asking someone to remove the flagged text covers the text.
        val composer = OverlayAnchor.Bounds(0, 1380, 1080, 1500)
        val placement = OverlayAnchor.placeWarning(
            OverlayAnchor.Position.ABOVE_KEYBOARD,
            screenHeight,
            keyboard,
            fallbackBottomMarginPx = 800,
            fieldBounds = composer,
        )
        assertEquals(screenHeight - composer.top, placement.bottomMarginPx)
        assertTrue(
            placement.bottomMarginPx > screenHeight - keyboard.top,
            "the warning must sit higher than the keyboard edge, not on it",
        )
    }

    @Test
    fun `an unreadable field falls back to the keyboard edge`() {
        // No worse than before the field was consulted at all.
        for (bad in listOf<OverlayAnchor.Bounds?>(
            null,
            OverlayAnchor.Bounds(0, 0, 1080, 100),
            // Below the keyboard: not a composer we are typing into.
            OverlayAnchor.Bounds(0, 1900, 1080, 2000),
            // Degenerate.
            OverlayAnchor.Bounds(0, 1380, 0, 1300),
        )) {
            val placement = OverlayAnchor.placeWarning(
                OverlayAnchor.Position.ABOVE_KEYBOARD,
                screenHeight,
                keyboard,
                fallbackBottomMarginPx = 800,
                fieldBounds = bad,
            )
            assertEquals(
                screenHeight - keyboard.top,
                placement.bottomMarginPx,
                "bad field bounds $bad should fall back to the keyboard edge",
            )
        }
    }

    @Test
    fun `a full-screen editor does not push the warning off the top`() {
        // A note-taking app's field can be most of the screen. Anchoring above it would put the
        // warning above the status bar, which is worse than sitting on the field.
        val fullScreenField = OverlayAnchor.Bounds(0, 100, 1080, 1500)
        val placement = OverlayAnchor.placeWarning(
            OverlayAnchor.Position.ABOVE_KEYBOARD,
            screenHeight,
            keyboard,
            fallbackBottomMarginPx = 800,
            fieldBounds = fullScreenField,
        )
        assertEquals(screenHeight - keyboard.top, placement.bottomMarginPx)
    }

    @Test
    fun `a document editor anchors to the keyboard, not above the document`() {
        // Real geometry, measured from Google Docs on a Pixel 9 Pro: a 2142px screen, the
        // keyboard reporting its top at 1352, and a field running from y=297 to the bottom.
        //
        // The previous rule compared the field height above the keyboard (1055) against half
        // the screen (1071) and missed by sixteen pixels, so the warning anchored above the
        // document and rendered over the status bar. Nothing about that was visible in the
        // browser harness, which has no status bar and no real IME to report a top edge.
        val docsScreenHeight = 2142
        val docsKeyboard = OverlayAnchor.Bounds(0, 1352, 960, 2142)
        val document = OverlayAnchor.Bounds(0, 297, 960, 2142)

        val placement = OverlayAnchor.placeWarning(
            OverlayAnchor.Position.ABOVE_KEYBOARD,
            docsScreenHeight,
            docsKeyboard,
            fallbackBottomMarginPx = 800,
            fieldBounds = document,
        )

        assertEquals(docsScreenHeight - docsKeyboard.top, placement.bottomMarginPx)
        assertFalse(placement.fromTop, "a document editor must not pin the warning to the top")
    }

    @Test
    fun `the top position ignores the keyboard entirely`() {
        val placement = OverlayAnchor.placeWarning(
            OverlayAnchor.Position.SCREEN_TOP,
            screenHeight,
            keyboard,
            fallbackBottomMarginPx = 800,
        )
        assertTrue(placement.fromTop)
    }

    @Test
    fun `the shade covers the full width and reaches the bottom edge`() {
        // A keyboard that insets itself would otherwise leave live strips down either side,
        // which is the whole block defeated by a few pixels.
        val rect = OverlayAnchor.shadeRect(
            screenWidth,
            screenHeight,
            OverlayAnchor.Bounds(40, 1500, 1040, 2380),
        )
        assertEquals(OverlayAnchor.Bounds(0, 1500, screenWidth, screenHeight), rect)
    }

    @Test
    fun `no keyboard means no shade, and the caller must handle that`() {
        // Null is a real answer: the block could not be enforced. The view uses this to decide
        // whether to claim typing is paused, which is the one message that would be a lie.
        assertNull(OverlayAnchor.shadeRect(screenWidth, screenHeight, null))
    }

    // endregion

    // region FieldWatch

    private fun watch(
        sighting: FieldWatch.Sighting,
        monitorable: Boolean = true,
        sameAsTarget: Boolean = false,
    ) = FieldWatch.decide(sighting, monitorable, sameAsTarget)

    @Test
    fun `a wake-up that resolved to an unreadable node does not end the watch`() {
        // The flicker. TYPE_WINDOW_CONTENT_CHANGED fires continuously while someone types, and
        // on hosts whose input focus sits on a non-editable wrapper the node it wakes us up to
        // read is one MonitoredField refuses. Releasing on that hid the warning; the next
        // keystroke put it back; at a 60ms rate limit the two alternated for as long as the
        // user kept typing.
        assertEquals(
            FieldWatch.Action.IGNORE,
            watch(FieldWatch.Sighting.WAKE_UP, monitorable = false),
        )
    }

    @Test
    fun `focus moving to a password field still hides, whatever else is being watched`() {
        // The case the protected flag exists for. A focus change speaks for a field, so this
        // one is believed - the wake-up exemption above must not weaken it.
        assertEquals(
            FieldWatch.Action.RELEASE,
            watch(FieldWatch.Sighting.FOCUS, monitorable = false),
        )
    }

    @Test
    fun `a text change from a field we may not read hides too`() {
        assertEquals(
            FieldWatch.Action.RELEASE,
            watch(FieldWatch.Sighting.TEXT, monitorable = false),
        )
    }

    @Test
    fun `a wake-up may still adopt a field, which is what a canvas editor needs`() {
        // Google Docs emits no text-changed event at all. If a wake-up could not adopt, the
        // one host this event type was subscribed for would be the one it did not cover.
        assertEquals(FieldWatch.Action.ADOPT, watch(FieldWatch.Sighting.WAKE_UP))
    }

    @Test
    fun `seeing the field we are already on is not a re-adoption`() {
        for (sighting in FieldWatch.Sighting.entries) {
            assertEquals(
                FieldWatch.Action.KEEP,
                watch(sighting, sameAsTarget = true),
                "re-adopted on $sighting, which would clear lastText and re-scan",
            )
        }
    }

    @Test
    fun `the keyboard changing its own window is not the conversation ending`() {
        // An emoji panel, a height change, one-handed mode, voice input. Ending the
        // composition on these hid the warning mid-message and reported a spurious
        // ABANDONED_SWITCHED to the parent for a message still being typed.
        assertFalse(FieldWatch.endsComposition(FieldWatch.WindowKind.KEYBOARD))
        assertFalse(FieldWatch.endsComposition(FieldWatch.WindowKind.SYSTEM))
    }

    @Test
    fun `leaving for another app still ends the composition, and so does not knowing`() {
        assertTrue(FieldWatch.endsComposition(FieldWatch.WindowKind.APPLICATION))
        // Hide when unsure: a stale warning over someone else's app is worse than one that
        // vanished.
        assertTrue(FieldWatch.endsComposition(FieldWatch.WindowKind.UNKNOWN))
    }

    // endregion

    // region FlaggedSpans

    private fun span(text: String, part: String, severity: Severity = Severity.HIGH): Finding {
        val start = text.indexOf(part)
        return Finding(start, start + part.length, Category.PII_DISCLOSURE, severity, "t", "t")
    }

    @Test
    fun `remove takes the address, not just the phrase that introduced it`() {
        // Seen on the emulator: the lead-in went, the address stayed, and the warning came back.
        val text = "my address is 123 Main Street"
        val findings = listOf(span(text, "my address is"), span(text, "123 Main Street"))
        assertEquals("", FlaggedSpans.remove(text, findings))
    }

    @Test
    fun `remove keeps the rest of the message and one space between the survivors`() {
        val text = "come over, 123 Main Street is mine"
        assertEquals(
            "come over, is mine",
            FlaggedSpans.remove(text, listOf(span(text, "123 Main Street"))),
        )
    }

    @Test
    fun `remove merges overlapping spans and leaves lower severities alone`() {
        val text = "hi my address is 123 Main Street lol"
        val findings = listOf(
            span(text, "address is 123"),
            span(text, "123 Main Street"),
            span(text, "lol", Severity.LOW),
        )
        assertEquals("hi my lol", FlaggedSpans.remove(text, findings))
    }

    @Test
    fun `remove refuses spans the text no longer contains`() {
        // The field moved on between the scan and the tap.
        val stale = Finding(10, 40, Category.PII_DISCLOSURE, Severity.HIGH, "t", "t")
        assertNull(FlaggedSpans.remove("short", listOf(stale)))
        assertNull(FlaggedSpans.remove("anything", emptyList()))
    }

    // endregion
}
