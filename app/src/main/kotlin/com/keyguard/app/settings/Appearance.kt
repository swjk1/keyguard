package com.keyguard.app.settings

/**
 * Every user-adjustable dimension of the keyboard and the warning strip.
 *
 * These were originally fixed values in `dimens.xml`. They live here instead because sizing
 * is genuinely personal — thumb size, screen size, and eyesight all differ — and because a
 * safety warning that is too small to notice fails at its only job.
 *
 * All values are density-independent (dp) or scale-independent (sp) integers, so a slider
 * with a step of 1 maps directly onto them.
 */
data class Appearance(
    /** Height of the key grid, excluding the warning strip and the navigation-bar inset. */
    val keyboardHeightDp: Int = DEFAULT.keyboardHeightDp,
    val sidePaddingDp: Int = DEFAULT.sidePaddingDp,
    /** Space below the last row, on top of whatever the navigation bar already reserves. */
    val bottomPaddingDp: Int = DEFAULT.bottomPaddingDp,
    /** Half-gap: applied as a margin per key, so adjacent keys sit twice this apart. */
    val keyGapDp: Int = DEFAULT.keyGapDp,
    val keyTextSp: Int = DEFAULT.keyTextSp,
    /** Base size for warning text. Other strip text is derived from it. */
    val alarmTextSp: Int = DEFAULT.alarmTextSp,
    /** How many lines of the flagged message the strip will show. */
    val alarmPreviewLines: Int = DEFAULT.alarmPreviewLines,
) {
    /** Warning summary size. */
    val alarmSummarySp: Int get() = alarmTextSp

    /** The echoed message sits slightly larger, since it carries the red highlight. */
    val alarmPreviewSp: Int get() = alarmTextSp + 1

    val alarmDetailSp: Int get() = (alarmTextSp - 1).coerceAtLeast(9)

    /** Strip padding grows with its text so the layout stays balanced at any size. */
    val alarmPaddingDp: Int
        get() = (ALARM_BASE_PADDING_DP * alarmTextSp / DEFAULT.alarmTextSp.toFloat())
            .toInt()
            .coerceIn(4, 24)

    /** Clamps every field into its supported range. */
    fun sanitized(): Appearance = Appearance(
        keyboardHeightDp = keyboardHeightDp.coerceIn(KEYBOARD_HEIGHT_DP),
        sidePaddingDp = sidePaddingDp.coerceIn(SIDE_PADDING_DP),
        bottomPaddingDp = bottomPaddingDp.coerceIn(BOTTOM_PADDING_DP),
        keyGapDp = keyGapDp.coerceIn(KEY_GAP_DP),
        keyTextSp = keyTextSp.coerceIn(KEY_TEXT_SP),
        alarmTextSp = alarmTextSp.coerceIn(ALARM_TEXT_SP),
        alarmPreviewLines = alarmPreviewLines.coerceIn(ALARM_PREVIEW_LINES),
    )

    companion object {
        /**
         * Defaults matched to the familiar Android system keyboard, so the keyboard feels
         * ordinary before anyone touches a slider.
         */
        val DEFAULT = Appearance(
            keyboardHeightDp = 228,
            sidePaddingDp = 4,
            bottomPaddingDp = 10,
            keyGapDp = 2,
            keyTextSp = 21,
            alarmTextSp = 13,
            alarmPreviewLines = 2,
        )

        private const val ALARM_BASE_PADDING_DP = 8

        // Ranges are shared by the sliders and by [sanitized], so the UI cannot offer a
        // value that persistence would silently reject.
        val KEYBOARD_HEIGHT_DP = 150..360
        val SIDE_PADDING_DP = 0..28
        val BOTTOM_PADDING_DP = 0..56
        val KEY_GAP_DP = 0..8
        val KEY_TEXT_SP = 12..32
        val ALARM_TEXT_SP = 10..26
        val ALARM_PREVIEW_LINES = 1..5

        private fun Int.coerceIn(range: IntRange): Int = coerceIn(range.first, range.last)
    }
}
