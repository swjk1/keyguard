package com.keyguard.app.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils

/**
 * The two grants the overlay cannot work without, and how to ask for them.
 *
 * Both are system-settings toggles rather than runtime dialogs, which is the honest shape of
 * this product: there is no one-tap version, and pretending otherwise in the UI just means a
 * user who taps *Allow* and finds nothing has changed. Onboarding walks them through both.
 *
 * They fail in different ways and are therefore checked separately rather than as one boolean:
 *
 * - Without **accessibility**, the app sees nothing at all. Silent, total failure.
 * - Without **draw over other apps**, the app sees everything and can say nothing. Worse than
 *   the first, because the child is being monitored with no visible warning surface — which is
 *   precisely the arrangement the product exists not to be.
 *
 * That asymmetry is why [missingDrawOverApps] is treated as a blocking setup error rather than
 * a degraded mode, and why the service refuses to run without it.
 */
object OverlayPermissions {

    /**
     * Whether our accessibility service is switched on.
     *
     * Read from `Settings.Secure` rather than from `AccessibilityManager`, for the same reason
     * [com.keyguard.app.settings.KeyboardStatus] reads the default IME that way: the platform
     * has no public "is *my* service enabled" call, and the secure setting is what the system
     * itself consults. `getEnabledAccessibilityServiceList` answers a related but different
     * question and reports services that are enabled but not yet bound.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, KeyguardAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        // Colon-separated, and an entry can be written either fully qualified or with the
        // package elided, so a plain `contains` on the flattened name misses the short form.
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            val component = ComponentName.unflattenFromString(entry) ?: continue
            if (component.packageName == context.packageName &&
                component.className == KeyguardAccessibilityService::class.java.name
            ) {
                return true
            }
        }
        return false
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun missingAccessibility(context: Context): Boolean = !isServiceEnabled(context)

    fun missingDrawOverApps(context: Context): Boolean = !canDrawOverlays(context)

    fun isFullyGranted(context: Context): Boolean =
        isServiceEnabled(context) && canDrawOverlays(context)

    /**
     * Opens the accessibility settings list.
     *
     * There is no reliable way to deep-link to a specific service's toggle — the extras that
     * appear to do it are undocumented and OEM-dependent — so the user lands on the list and
     * has to find the entry themselves. The setup screen therefore names what they are looking
     * for rather than saying "turn it on in the screen that just opened".
     */
    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun drawOverAppsSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
