package com.keyguard.app.ui.parent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Whether this phone can tell the parent about a serious warning, and how to fix it if not.
 *
 * The alert is the one thing the parent app does without being opened, so "notifications are
 * off" is the product quietly not doing what it says. Two screens surface it - a banner on
 * Family and a row in Settings - and both need the same three answers: is it off, can we ask,
 * and where do we send the parent if we cannot.
 *
 * "Can we ask" is the awkward one. Android shows the permission prompt at most twice; after a
 * second refusal a request returns "denied" instantly with no UI, and a button that visibly
 * does nothing is worse than no button. So once we have asked and the system says no
 * rationale is due, the answer is the app's notification settings page instead.
 */
internal object AlertPermission {

    private const val PREFS = "keyguard_parent_ui"
    private const val KEY_ASKED = "alerts_permission_asked"

    /** True when a serious warning would not reach this phone as a notification. */
    fun muted(context: Context): Boolean =
        !NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Asks for the permission if the system will still show a prompt, otherwise opens the
     * app's notification settings. Does nothing on a detached fragment.
     */
    fun fix(fragment: Fragment, launcher: ActivityResultLauncher<String>) {
        val context = fragment.context ?: return
        if (canPrompt(fragment, context)) {
            markAsked(context)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSettings(context)
        }
    }

    /**
     * Asked for when a code is issued, the moment "we will tell you if something happens"
     * starts to mean something - never at launch. Only if the prompt can still appear; this
     * path must never bounce a parent into system settings they did not ask for.
     */
    fun requestIfPromptable(fragment: Fragment, launcher: ActivityResultLauncher<String>) {
        val context = fragment.context ?: return
        if (!muted(context) || !canPrompt(fragment, context)) return
        markAsked(context)
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun canPrompt(fragment: Fragment, context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        // Granted but still muted means the parent switched the app off in system settings,
        // which only system settings can undo.
        if (granted) return false
        val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)
        return !asked ||
            fragment.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun openSettings(context: Context) {
        // minSdk is 26, so the per-app notification page always exists.
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }
    }
}
