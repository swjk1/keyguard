package com.keyguard.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppearanceTest {

    @Test
    fun `defaults are inside their own ranges`() {
        // A default outside its slider range would make the slider jump on first open.
        val d = Appearance.DEFAULT
        assertTrue(d.keyboardHeightDp in Appearance.KEYBOARD_HEIGHT_DP)
        assertTrue(d.sidePaddingDp in Appearance.SIDE_PADDING_DP)
        assertTrue(d.bottomPaddingDp in Appearance.BOTTOM_PADDING_DP)
        assertTrue(d.keyGapDp in Appearance.KEY_GAP_DP)
        assertTrue(d.keyTextSp in Appearance.KEY_TEXT_SP)
        assertTrue(d.alarmTextSp in Appearance.ALARM_TEXT_SP)
        assertTrue(d.alarmPreviewLines in Appearance.ALARM_PREVIEW_LINES)
        assertEquals(d, d.sanitized())
    }

    @Test
    fun `out of range values are clamped rather than trusted`() {
        // Guards against a value written by a different build leaving an unusable keyboard -
        // a zero-height keyboard would be unrecoverable from inside the keyboard itself.
        val absurd = Appearance(
            keyboardHeightDp = 5_000,
            sidePaddingDp = -40,
            bottomPaddingDp = 9_999,
            keyGapDp = -1,
            keyTextSp = 0,
            alarmTextSp = 500,
            alarmPreviewLines = 0,
        ).sanitized()

        assertEquals(Appearance.KEYBOARD_HEIGHT_DP.last, absurd.keyboardHeightDp)
        assertEquals(Appearance.SIDE_PADDING_DP.first, absurd.sidePaddingDp)
        assertEquals(Appearance.BOTTOM_PADDING_DP.last, absurd.bottomPaddingDp)
        assertEquals(Appearance.KEY_GAP_DP.first, absurd.keyGapDp)
        assertEquals(Appearance.KEY_TEXT_SP.first, absurd.keyTextSp)
        assertEquals(Appearance.ALARM_TEXT_SP.last, absurd.alarmTextSp)
        assertEquals(Appearance.ALARM_PREVIEW_LINES.first, absurd.alarmPreviewLines)
    }

    @Test
    fun `sanitizing is idempotent`() {
        val once = Appearance(keyboardHeightDp = 10_000).sanitized()
        assertEquals(once, once.sanitized())
    }

    @Test
    fun `derived alarm sizes track the base size`() {
        val small = Appearance(alarmTextSp = 10)
        val large = Appearance(alarmTextSp = 24)

        assertTrue(large.alarmSummarySp > small.alarmSummarySp)
        assertTrue(large.alarmPreviewSp > small.alarmPreviewSp)
        assertTrue(large.alarmDetailSp > small.alarmDetailSp)
        assertTrue(
            large.alarmPaddingDp > small.alarmPaddingDp,
            "padding should grow with text so the strip stays balanced",
        )
    }

    @Test
    fun `derived alarm sizes stay legible at the extremes`() {
        for (size in Appearance.ALARM_TEXT_SP) {
            val appearance = Appearance(alarmTextSp = size)
            assertTrue(appearance.alarmDetailSp >= 9, "detail text must stay readable at $size")
            assertTrue(appearance.alarmPaddingDp in 4..24, "padding out of bounds at $size")
        }
    }

    @Test
    fun `every control round trips through its read and write`() {
        // Catches a copy-paste error where a control's write lambda targets the wrong field,
        // which would silently move the wrong slider.
        for (control in AppearanceControl.ALL) {
            val target = control.range.first + 1
            val updated = control.write(Appearance.DEFAULT, target)
            assertEquals(
                target,
                control.read(updated),
                "control for label ${control.labelRes} does not read back what it wrote",
            )
        }
    }

    @Test
    fun `controls only change their own field`() {
        for (control in AppearanceControl.ALL) {
            val changed = control.write(Appearance.DEFAULT, control.range.last)
            val otherControls = AppearanceControl.ALL.filter { it != control }
            for (other in otherControls) {
                assertEquals(
                    other.read(Appearance.DEFAULT),
                    other.read(changed),
                    "control ${control.labelRes} also modified the field read by ${other.labelRes}",
                )
            }
        }
    }

    @Test
    fun `all adjustable fields are exposed as controls`() {
        // A field added to Appearance without a matching control would be unreachable to the
        // user. Setting every control to its maximum must therefore change every field away
        // from its default; a field left at its default means nothing drives it.
        val maxed = AppearanceControl.ALL.fold(Appearance.DEFAULT) { acc, control ->
            control.write(acc, control.range.last)
        }

        assertEquals(
            Appearance(
                keyboardHeightDp = Appearance.KEYBOARD_HEIGHT_DP.last,
                sidePaddingDp = Appearance.SIDE_PADDING_DP.last,
                bottomPaddingDp = Appearance.BOTTOM_PADDING_DP.last,
                keyGapDp = Appearance.KEY_GAP_DP.last,
                keyTextSp = Appearance.KEY_TEXT_SP.last,
                alarmTextSp = Appearance.ALARM_TEXT_SP.last,
                alarmPreviewLines = Appearance.ALARM_PREVIEW_LINES.last,
            ),
            maxed,
            "every Appearance field must be driven by a control",
        )
    }

    @Test
    fun `control count matches the number of adjustable fields`() {
        // Deliberately a hardcoded tripwire rather than reflection over Kotlin internals:
        // if you add a field and a control, bump this and the assertion above.
        assertEquals(7, AppearanceControl.ALL.size)
    }
}
