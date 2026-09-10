package com.keyguard.app.settings

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Whether this app's IME is allowed, and whether it is the one currently in use.
 *
 * Two different questions with two different answers, which is exactly why they are two steps
 * in onboarding: Android will happily let a keyboard be enabled and never selected, and a user
 * in that state sees no Keyguard at all while the setup screen would otherwise call them done.
 *
 * Extracted from [com.keyguard.app.SetupActivity] when onboarding needed the same two checks.
 * They feed [OnboardingProgress], so a second copy that drifted would put the two screens on
 * different steps for the same device state.
 */
object KeyboardStatus {

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        return manager.enabledInputMethodList.any { it.packageName == context.packageName }
    }

    /**
     * Read from `Settings.Secure` rather than `InputMethodManager`, because the platform has no
     * public "am I current" call for an IME and the secure setting is what the system itself
     * consults.
     */
    fun isSelected(context: Context): Boolean =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )?.startsWith("${context.packageName}/") == true
}
