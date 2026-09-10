package com.keyguard.app.overlay

import com.keyguard.detect.FieldPolicy

/**
 * Whether a field the accessibility service just saw may be looked at.
 *
 * The IME had one built-in protection this does not: it only ever saw fields the user had
 * deliberately focused *and typed into with our keyboard*. An accessibility service sees every
 * editable node in every app, including ones the user never touched, which makes the gate here
 * strictly more important than the equivalent check in the IME rather than a copy of it.
 *
 * Pure, and takes primitives rather than an `AccessibilityNodeInfo`, so every rule below is
 * unit-testable on the JVM. That matters more here than anywhere else in the app: this is the
 * function that decides whether a password manager's field, a banking app's PIN entry, or our
 * own settings screen gets read, and "we tested it by hand on a phone once" is not an adequate
 * answer for any of those.
 *
 * ### What is deliberately *not* a rule here
 *
 * There is no allow-list of messaging apps. It would be the obvious design — watch WhatsApp
 * and Instagram, ignore everything else — and it fails in both directions: it misses whatever
 * app a teenager moved to last month, which is precisely the one a grooming conversation
 * migrates to, and it silently stops working when an app changes its package name. The gate is
 * the *kind of field*, not the app it belongs to.
 *
 * There is also no recording of which app a field belonged to. The package name is used here,
 * in memory, to answer a yes/no question and is never attached to anything that leaves the
 * device — the same rule [com.keyguard.app.family.SupervisionEvent] states for the IME path,
 * and the reason it is restated here is that this class is the first place in the codebase that
 * has the package name conveniently to hand.
 */
object MonitoredField {

    /**
     * Packages whose fields are never read.
     *
     * Our own package is on it for an ordinary reason — the setup screen has a "try it here"
     * field and monitoring our own preview would be a feedback loop — and the system UI
     * packages are on it because their editable nodes are things like the lock screen PIN pad,
     * which [FieldPolicy] would usually catch but which are not worth relying on it for.
     */
    private val NEVER_MONITORED = setOf(
        "com.android.systemui",
        "com.android.settings",
        "android",
    )

    /**
     * @param packageName host app of the field. Used only for the checks here; never reported.
     * @param ourPackage this app's own package, passed in rather than read, so the rule stays pure.
     * @param editable whether the node accepts text at all.
     * @param password the platform's own `isPassword` flag on the node.
     * @param inputType the node's input type, for [FieldPolicy].
     */
    fun mayMonitor(
        packageName: String?,
        ourPackage: String,
        editable: Boolean,
        password: Boolean,
        inputType: Int,
    ): Boolean {
        // Not a field someone types into. Labels, buttons and list rows all surface as nodes
        // with text, and reading them would turn "what my child wrote" into "everything my
        // child looked at" — a completely different product with a completely different
        // consent conversation.
        if (!editable) return false

        // Two independent password checks, because they catch different things and either one
        // alone has known gaps. `isPassword` is set by the platform for standard widgets and
        // is missed by some custom and WebView inputs; FieldPolicy reads the declared input
        // type, which those same custom inputs often do set. Both must pass.
        if (password) return false
        if (FieldPolicy.isProtectedField(inputType, imeOptions = 0)) return false

        if (packageName == null) return false
        if (packageName == ourPackage) return false
        if (packageName in NEVER_MONITORED) return false

        return true
    }
}
