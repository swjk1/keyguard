package com.keyguard.app.settings

import android.content.Context

/**
 * How forcefully warnings present themselves.
 *
 * The meeting went back and forth between a subtle underline and an in-your-face popup and
 * never settled it. Shipping a raw popup on/off toggle would push that unresolved argument
 * onto the user. One intensity scale gives the same range of behaviour as a single
 * comprehensible choice.
 */
enum class Intensity {
    /** Warnings appear collapsed and never gate the Enter key. */
    SUBTLE,

    /** High-severity warnings expand and gate Enter. The default. */
    STANDARD,

    /** Medium and above expand and gate Enter. */
    INSISTENT,
    ;

    companion object {
        val DEFAULT = STANDARD

        fun fromName(name: String?): Intensity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var intensity: Intensity
        get() = Intensity.fromName(prefs.getString(KEY_INTENSITY, null))
        set(value) = prefs.edit().putString(KEY_INTENSITY, value.name).apply()

    /**
     * Whether the user has read the disclosure explaining what the keyboard can see. The
     * setup screen requires this before pointing anyone at the system keyboard settings.
     */
    var disclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLOSURE, value).apply()

    /** Keypress vibration. On by default, matching stock keyboard behaviour. */
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** Suggestions and automatic correction. On by default. */
    var autocorrectEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOCORRECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOCORRECT, value).apply()

    /**
     * AI verification. **Off by default, and deliberately so.**
     *
     * Enabling it means flagged snippets of what the user types leave the device. That is a
     * materially different privacy posture from the local-only default, so it has to be an
     * active choice rather than something buried in a default — nobody should discover after
     * the fact that their typing was being sent anywhere.
     */
    var aiVerificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_VERIFY, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_VERIFY, value).apply()

    /**
     * All sizing in one read. Always sanitized, so a value written by an older or newer build
     * can never produce an unusable keyboard.
     */
    var appearance: Appearance
        get() = Appearance(
            keyboardHeightDp = prefs.getInt(KEY_KEYBOARD_HEIGHT, Appearance.DEFAULT.keyboardHeightDp),
            sidePaddingDp = prefs.getInt(KEY_SIDE_PADDING, Appearance.DEFAULT.sidePaddingDp),
            bottomPaddingDp = prefs.getInt(KEY_BOTTOM_PADDING, Appearance.DEFAULT.bottomPaddingDp),
            keyGapDp = prefs.getInt(KEY_KEY_GAP, Appearance.DEFAULT.keyGapDp),
            keyTextSp = prefs.getInt(KEY_KEY_TEXT, Appearance.DEFAULT.keyTextSp),
            alarmTextSp = prefs.getInt(KEY_ALARM_TEXT, Appearance.DEFAULT.alarmTextSp),
            alarmPreviewLines = prefs.getInt(KEY_ALARM_LINES, Appearance.DEFAULT.alarmPreviewLines),
        ).sanitized()
        set(value) {
            val clean = value.sanitized()
            prefs.edit()
                .putInt(KEY_KEYBOARD_HEIGHT, clean.keyboardHeightDp)
                .putInt(KEY_SIDE_PADDING, clean.sidePaddingDp)
                .putInt(KEY_BOTTOM_PADDING, clean.bottomPaddingDp)
                .putInt(KEY_KEY_GAP, clean.keyGapDp)
                .putInt(KEY_KEY_TEXT, clean.keyTextSp)
                .putInt(KEY_ALARM_TEXT, clean.alarmTextSp)
                .putInt(KEY_ALARM_LINES, clean.alarmPreviewLines)
                .apply()
        }

    /**
     * Where the floating warning sits.
     *
     * The overlay's customisation story is deliberately different from the keyboard's. The
     * keyboard had seven sliders because its geometry was ours to get wrong and a badly sized
     * keyboard is unusable; the overlay borrows Gboard's geometry and has essentially one
     * question worth asking, which is whether the warning covering the bottom of the screen is
     * better or worse than one covering the top. Adding sliders here for their own sake would
     * be re-solving a problem we just handed to Google.
     *
     * Stored as the enum name so an unrecognised value falls back to the default rather than
     * throwing, matching every other enum-valued preference in this file.
     */
    var overlayPosition: OverlayPosition
        get() = OverlayPosition.fromName(prefs.getString(KEY_OVERLAY_POSITION, null))
        set(value) = prefs.edit().putString(KEY_OVERLAY_POSITION, value.name).apply()

    /**
     * How opaque the warning is, 40-100.
     *
     * Clamped on read as well as write, for the same reason [Appearance] is: a persisted zero
     * would be an invisible warning, which is the one failure this component cannot recover
     * from on its own — an invisible warning still shades the keyboard, so the user would have
     * a phone that stopped typing with nothing on screen explaining why.
     */
    var overlayOpacityPercent: Int
        get() = prefs.getInt(KEY_OVERLAY_OPACITY, DEFAULT_OVERLAY_OPACITY)
            .coerceIn(MIN_OVERLAY_OPACITY, MAX_OVERLAY_OPACITY)
        set(value) = prefs.edit()
            .putInt(KEY_OVERLAY_OPACITY, value.coerceIn(MIN_OVERLAY_OPACITY, MAX_OVERLAY_OPACITY))
            .apply()

    /**
     * Whether the overlay is the active protection path on this device.
     *
     * On by default in a build that has the accessibility service, because the overlay is the
     * product now; the IME remains installed and works, but a user who has granted the
     * accessibility permission should not be warned twice about the same sentence.
     */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    fun resetAppearance() {
        appearance = Appearance.DEFAULT
    }

    companion object {
        /** Public so the settings screen can build the opacity slider from the same range. */
        const val DEFAULT_OVERLAY_OPACITY = 96
        const val MIN_OVERLAY_OPACITY = 40
        const val MAX_OVERLAY_OPACITY = 100

        private const val PREFS_NAME = "keyguard_settings"
        private const val KEY_INTENSITY = "intensity"
        private const val KEY_DISCLOSURE = "disclosure_accepted"
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_AUTOCORRECT = "autocorrect_enabled"
        private const val KEY_AI_VERIFY = "ai_verification_enabled"

        private const val KEY_KEYBOARD_HEIGHT = "keyboard_height_dp"
        private const val KEY_SIDE_PADDING = "side_padding_dp"
        private const val KEY_BOTTOM_PADDING = "bottom_padding_dp"
        private const val KEY_KEY_GAP = "key_gap_dp"
        private const val KEY_KEY_TEXT = "key_text_sp"
        private const val KEY_ALARM_TEXT = "alarm_text_sp"
        private const val KEY_ALARM_LINES = "alarm_preview_lines"

        private const val KEY_OVERLAY_POSITION = "overlay_position"
        private const val KEY_OVERLAY_OPACITY = "overlay_opacity_percent"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    }
}

/**
 * Where the floating warning is anchored, as the user's stored preference.
 *
 * Mirrors [com.keyguard.app.overlay.OverlayAnchor.Position] rather than being it, so the
 * settings layer does not depend on the overlay package and the overlay's pure geometry does
 * not depend on Android preferences. The two are mapped in one place — see
 * [OverlayPosition.toAnchor] — which is where a mismatch would show up as a compile error
 * rather than as a warning that quietly ignores the setting.
 */
enum class OverlayPosition {
    ABOVE_KEYBOARD,
    SCREEN_TOP,
    ;

    fun toAnchor(): com.keyguard.app.overlay.OverlayAnchor.Position = when (this) {
        ABOVE_KEYBOARD -> com.keyguard.app.overlay.OverlayAnchor.Position.ABOVE_KEYBOARD
        SCREEN_TOP -> com.keyguard.app.overlay.OverlayAnchor.Position.SCREEN_TOP
    }

    companion object {
        val DEFAULT = ABOVE_KEYBOARD

        fun fromName(name: String?): OverlayPosition =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
