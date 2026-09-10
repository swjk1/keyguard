package com.keyguard.app.settings

import com.keyguard.app.R

/**
 * One adjustable dimension, described as data.
 *
 * Declaring the controls rather than hand-writing seven near-identical slider blocks in XML
 * keeps the range, the label, and the read/write path for each setting in one place, so
 * adding a control is a single list entry and the UI cannot drift from [Appearance].
 */
data class AppearanceControl(
    val labelRes: Int,
    val range: IntRange,
    val unit: Unit_,
    val read: (Appearance) -> Int,
    val write: (Appearance, Int) -> Appearance,
) {
    /** Display suffix for the current value. */
    enum class Unit_(val suffix: String) {
        DP("dp"),
        SP("sp"),
        LINES(" lines"),
    }

    companion object {
        /** Controls for the key grid. */
        val KEYBOARD: List<AppearanceControl> = listOf(
            AppearanceControl(
                labelRes = R.string.appearance_keyboard_height,
                range = Appearance.KEYBOARD_HEIGHT_DP,
                unit = Unit_.DP,
                read = { it.keyboardHeightDp },
                write = { a, v -> a.copy(keyboardHeightDp = v) },
            ),
            AppearanceControl(
                labelRes = R.string.appearance_key_text,
                range = Appearance.KEY_TEXT_SP,
                unit = Unit_.SP,
                read = { it.keyTextSp },
                write = { a, v -> a.copy(keyTextSp = v) },
            ),
            AppearanceControl(
                labelRes = R.string.appearance_key_gap,
                range = Appearance.KEY_GAP_DP,
                unit = Unit_.DP,
                read = { it.keyGapDp },
                write = { a, v -> a.copy(keyGapDp = v) },
            ),
            AppearanceControl(
                labelRes = R.string.appearance_side_padding,
                range = Appearance.SIDE_PADDING_DP,
                unit = Unit_.DP,
                read = { it.sidePaddingDp },
                write = { a, v -> a.copy(sidePaddingDp = v) },
            ),
            AppearanceControl(
                labelRes = R.string.appearance_bottom_padding,
                range = Appearance.BOTTOM_PADDING_DP,
                unit = Unit_.DP,
                read = { it.bottomPaddingDp },
                write = { a, v -> a.copy(bottomPaddingDp = v) },
            ),
        )

        /** Controls for the warning strip. */
        val ALARM: List<AppearanceControl> = listOf(
            AppearanceControl(
                labelRes = R.string.appearance_alarm_text,
                range = Appearance.ALARM_TEXT_SP,
                unit = Unit_.SP,
                read = { it.alarmTextSp },
                write = { a, v -> a.copy(alarmTextSp = v) },
            ),
            AppearanceControl(
                labelRes = R.string.appearance_alarm_lines,
                range = Appearance.ALARM_PREVIEW_LINES,
                unit = Unit_.LINES,
                read = { it.alarmPreviewLines },
                write = { a, v -> a.copy(alarmPreviewLines = v) },
            ),
        )

        val ALL: List<AppearanceControl> get() = KEYBOARD + ALARM
    }
}
