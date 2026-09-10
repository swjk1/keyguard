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
}
