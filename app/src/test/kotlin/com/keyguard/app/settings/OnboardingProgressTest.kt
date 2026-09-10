package com.keyguard.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the user is told to do next.
 *
 * Rewritten when the overlay replaced the keyboard as the protection path. The property that
 * matters now is that **there are two ways to be finished**, and neither may nag about the
 * other: a user protected by the overlay must never be told to enable a keyboard, and a user
 * protected by the keyboard must never be told to grant an accessibility service. Getting that
 * wrong is not cosmetic — this is the screen that decides whether people finish setup at all.
 */
class OnboardingProgressTest {

    @Test
    fun `privacy comes before any system settings screen`() {
        // Ahead of both paths, and ahead of anything that leaves the app. Nobody is sent to
        // grant an accessibility service before being told what it can see.
        assertEquals(
            OnboardingStep.READ_PRIVACY,
            OnboardingProgress.next(
                disclosureAccepted = false,
                keyboardEnabled = false,
                keyboardSelected = false,
            ),
        )
        assertEquals(
            OnboardingStep.READ_PRIVACY,
            OnboardingProgress.next(
                disclosureAccepted = false,
                keyboardEnabled = true,
                keyboardSelected = true,
                accessibilityGranted = true,
                overlayGranted = true,
            ),
        )
    }

    @Test
    fun `a fresh install is pointed at the overlay`() {
        // The recommended path, because it leaves the user typing on the keyboard they already
        // like — which was the whole reason for building it.
        assertEquals(
            OnboardingStep.GRANT_ACCESSIBILITY,
            OnboardingProgress.next(
                disclosureAccepted = true,
                keyboardEnabled = false,
                keyboardSelected = false,
            ),
        )
    }

    @Test
    fun `the overlay path completes in two grants`() {
        assertEquals(
            OnboardingStep.GRANT_OVERLAY,
            OnboardingProgress.next(
                disclosureAccepted = true,
                keyboardEnabled = false,
                keyboardSelected = false,
                accessibilityGranted = true,
            ),
        )
        assertEquals(
            OnboardingStep.READY,
            OnboardingProgress.next(
                disclosureAccepted = true,
                keyboardEnabled = false,
                keyboardSelected = false,
                accessibilityGranted = true,
                overlayGranted = true,
            ),
        )
    }

    @Test
    fun `a user part-way through the keyboard path is walked to the end of it`() {
        // Not restarted on the overlay. Being moved to a different set of steps after doing one
        // reads as the app losing track of what you did, and is how people give up.
        assertEquals(
            OnboardingStep.CHOOSE_KEYBOARD,
            OnboardingProgress.next(
                disclosureAccepted = true,
                keyboardEnabled = true,
                keyboardSelected = false,
            ),
        )
    }

    @Test
    fun `the keyboard path still finishes onboarding on its own`() {
        // The existing install base, and what the solo build's story rests on. Someone using
        // the Keyguard keyboard is protected and must never be nagged for an accessibility
        // grant they have no need for.
        assertEquals(
            OnboardingStep.READY,
            OnboardingProgress.next(
                disclosureAccepted = true,
                keyboardEnabled = true,
                keyboardSelected = true,
            ),
        )
    }

    @Test
    fun `a half-granted overlay is not finished`() {
        // The dangerous middle state: the service could see everything and warn about nothing.
        // It must read as incomplete, not as ready.
        assertEquals(
            OnboardingStep.GRANT_OVERLAY,
            OnboardingProgress.next(
                disclosureAccepted = true,
                keyboardEnabled = false,
                keyboardSelected = false,
                accessibilityGranted = true,
                overlayGranted = false,
            ),
        )
    }

    @Test
    fun `either path alone is enough, and having both is not required`() {
        data class Case(
            val label: String,
            val keyboardEnabled: Boolean,
            val keyboardSelected: Boolean,
            val accessibility: Boolean,
            val overlay: Boolean,
        )

        val cases = listOf(
            Case("keyboard only", keyboardEnabled = true, keyboardSelected = true, accessibility = false, overlay = false),
            Case("overlay only", keyboardEnabled = false, keyboardSelected = false, accessibility = true, overlay = true),
            Case("both", keyboardEnabled = true, keyboardSelected = true, accessibility = true, overlay = true),
        )

        for (case in cases) {
            assertEquals(
                OnboardingStep.READY,
                OnboardingProgress.next(
                    disclosureAccepted = true,
                    keyboardEnabled = case.keyboardEnabled,
                    keyboardSelected = case.keyboardSelected,
                    accessibilityGranted = case.accessibility,
                    overlayGranted = case.overlay,
                ),
                "expected READY for ${case.label}",
            )
        }
    }
}
