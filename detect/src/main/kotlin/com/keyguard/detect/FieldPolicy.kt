package com.keyguard.detect

/**
 * Decides whether a given input field may be scanned at all.
 *
 * This is a hard privacy gate, not a preference: a keyboard that inspects password fields
 * is a credential harvester regardless of intent, and it is also the fastest route to a
 * store takedown. Getting it wrong once is unrecoverable, so the rule lives here in pure
 * Kotlin where it is directly unit-testable rather than buried in the IME.
 *
 * The Android constants are inlined rather than imported so this module stays free of
 * Android dependencies. They are stable platform API values.
 */
object FieldPolicy {

    // android.text.InputType
    const val TYPE_CLASS_TEXT: Int = 0x00000001
    const val TYPE_CLASS_NUMBER: Int = 0x00000002
    const val TYPE_MASK_CLASS: Int = 0x0000000f
    const val TYPE_MASK_VARIATION: Int = 0x00000ff0

    const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = 0x000000e0
    const val TYPE_NUMBER_VARIATION_PASSWORD: Int = 0x00000010

    // android.view.inputmethod.EditorInfo
    const val IME_FLAG_NO_PERSONALIZED_LEARNING: Int = 0x1000000

    /**
     * True when detection and any network activity must be fully disabled for this field.
     */
    fun isProtectedField(inputType: Int, imeOptions: Int): Boolean {
        if (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true

        val variation = inputType and TYPE_MASK_VARIATION
        return when (inputType and TYPE_MASK_CLASS) {
            TYPE_CLASS_TEXT -> variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD

            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD

            else -> false
        }
    }

    /** Inverse of [isProtectedField], for readability at call sites. */
    fun mayScan(inputType: Int, imeOptions: Int): Boolean = !isProtectedField(inputType, imeOptions)
}
