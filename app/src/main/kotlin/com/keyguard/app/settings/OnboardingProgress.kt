package com.keyguard.app.settings

/** Pure onboarding state so the child-facing setup screen never reports the wrong next step. */
enum class OnboardingStep {
    READ_PRIVACY,

    /**
     * Turn on the accessibility service, so Keyguard can see what is being typed.
     *
     * The first of the overlay's two grants, and the point at which the app stops being inert.
     */
    GRANT_ACCESSIBILITY,

    /**
     * Turn on drawing over other apps, so it can warn about what it sees.
     *
     * Deliberately a step of its own rather than being folded into the one above. Without it
     * the service reads text and can display nothing, which is monitoring with no visible
     * surface — the one configuration this product must never run in, and therefore worth its
     * own screen rather than a line of small print on someone else's.
     */
    GRANT_OVERLAY,

    ENABLE_KEYBOARD,
    CHOOSE_KEYBOARD,
    READY,
}

/**
 * What the user still has to do.
 *
 * Rewritten when the overlay replaced the keyboard as the protection path, and the change that
 * matters is that **there are now two ways to be finished**. Someone running the overlay on
 * Gboard is fully protected and must never be told to go and enable a keyboard they have no use
 * for; someone using the Keyguard keyboard with no accessibility grant is also fully protected,
 * which is what keeps the existing install base working and what the `solo` story rests on.
 *
 * Reporting a step the user does not need is not a cosmetic bug here. Onboarding is the screen
 * that decides whether somebody finishes setup at all, and a flow that demands two irrelevant
 * permissions after protection is already working is a flow people abandon.
 */
object OnboardingProgress {

    fun next(
        disclosureAccepted: Boolean,
        keyboardEnabled: Boolean,
        keyboardSelected: Boolean,
        accessibilityGranted: Boolean = false,
        overlayGranted: Boolean = false,
    ): OnboardingStep {
        // Always first. Nobody is sent to a system settings screen — for a keyboard or for an
        // accessibility service — before being told what the thing can see.
        if (!disclosureAccepted) return OnboardingStep.READ_PRIVACY

        // Either path being complete ends onboarding.
        val overlayReady = accessibilityGranted && overlayGranted
        val keyboardReady = keyboardEnabled && keyboardSelected
        if (overlayReady || keyboardReady) return OnboardingStep.READY

        // Neither is finished, so point at whichever is closer to being so. A user part-way
        // through the keyboard path should be walked to the end of it rather than restarted on
        // the overlay, and vice versa — being moved to a different set of steps after doing one
        // reads as the app losing track of what you did.
        if (keyboardEnabled) return OnboardingStep.CHOOSE_KEYBOARD
        if (accessibilityGranted) return OnboardingStep.GRANT_OVERLAY

        // Nothing started: offer the overlay, which is the recommended path because it leaves
        // the user typing on the keyboard they already like.
        return OnboardingStep.GRANT_ACCESSIBILITY
    }
}
