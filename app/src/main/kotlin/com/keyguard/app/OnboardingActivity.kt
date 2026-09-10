package com.keyguard.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.keyguard.app.databinding.ActivityOnboardingBinding
import com.keyguard.app.settings.KeyboardStatus
import com.keyguard.app.overlay.OverlayPermissions
import com.keyguard.app.settings.OnboardingProgress
import com.keyguard.app.settings.OnboardingStep
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.applySystemBarInsets

/**
 * The three things a user has to do before this keyboard does anything, one screen at a time.
 *
 * Every step is a system action taken outside this app - accept the disclosure, allow the IME,
 * select the IME - so the screen cannot know it succeeded except by re-asking. It therefore
 * renders from [OnboardingProgress] on every `onResume` and never tracks a step of its own.
 * Coming back from the system keyboard settings advances the screen with no button to press,
 * and a user who turns the keyboard off again is walked back rather than left on "all set".
 *
 * Skipping is always available. Setup that cannot be postponed is setup a user abandons at the
 * one step they cannot complete right now, and the settings screen carries all the same actions.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var settings: Settings

    /**
     * The tick, before it is committed.
     *
     * Held here rather than written straight to [Settings] because accepting the disclosure is
     * what moves the derived step off `READ_PRIVACY` - so persisting on the tick would replace
     * the screen under the user's finger and leave Continue with nothing to do. Consent is
     * recorded when they press the button, which is also the more honest moment to record it.
     */
    private var disclosureTicked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        settings = Settings(this)
        disclosureTicked = settings.disclosureAccepted

        binding.disclosureCheckbox.isChecked = disclosureTicked
        binding.disclosureCheckbox.setOnCheckedChangeListener { _, checked ->
            disclosureTicked = checked
            render()
        }
        binding.skipButton.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val step = OnboardingProgress.next(
            disclosureAccepted = settings.disclosureAccepted,
            keyboardEnabled = KeyboardStatus.isEnabled(this),
            keyboardSelected = KeyboardStatus.isSelected(this),
            accessibilityGranted = OverlayPermissions.isServiceEnabled(this),
            overlayGranted = OverlayPermissions.canDrawOverlays(this),
        )

        binding.stepText.setText(
            when (step) {
                OnboardingStep.READ_PRIVACY -> R.string.setup_progress_privacy
                OnboardingStep.GRANT_ACCESSIBILITY -> R.string.setup_progress_accessibility
                OnboardingStep.GRANT_OVERLAY -> R.string.setup_progress_overlay
                OnboardingStep.ENABLE_KEYBOARD -> R.string.setup_progress_enable
                OnboardingStep.CHOOSE_KEYBOARD -> R.string.setup_progress_choose
                OnboardingStep.READY -> R.string.setup_progress_ready
            },
        )

        binding.headingText.setText(
            when (step) {
                OnboardingStep.READ_PRIVACY -> R.string.setup_disclosure_heading
                OnboardingStep.GRANT_ACCESSIBILITY -> R.string.onboarding_accessibility_heading
                OnboardingStep.GRANT_OVERLAY -> R.string.onboarding_overlay_heading
                OnboardingStep.ENABLE_KEYBOARD -> R.string.onboarding_enable_heading
                OnboardingStep.CHOOSE_KEYBOARD -> R.string.onboarding_choose_heading
                OnboardingStep.READY -> R.string.onboarding_ready_heading
            },
        )

        binding.bodyText.text = when (step) {
            // Both halves, because the flavor-specific paragraph is the part that says whether
            // anything leaves the device - the half a user most needs before ticking the box.
            OnboardingStep.READ_PRIVACY ->
                getString(R.string.setup_disclosure_body) + "\n\n" +
                    getString(R.string.setup_disclosure_network)
            OnboardingStep.GRANT_ACCESSIBILITY -> getString(R.string.onboarding_accessibility_body)
            OnboardingStep.GRANT_OVERLAY -> getString(R.string.onboarding_overlay_body)
            OnboardingStep.ENABLE_KEYBOARD -> getString(R.string.onboarding_enable_body)
            OnboardingStep.CHOOSE_KEYBOARD -> getString(R.string.onboarding_choose_body)
            // Which "you're done" message depends on which path finished, and getting this
            // wrong is not cosmetic. The overlay deliberately never reads Keyguard's own
            // fields - see MonitoredField - so telling an overlay user to type in the box
            // below offers them a demonstration that cannot possibly fire, and an app that
            // fails its own first test looks broken rather than careful.
            OnboardingStep.READY -> getString(
                if (keyboardPathComplete()) {
                    R.string.onboarding_ready_body
                } else {
                    R.string.onboarding_ready_body_overlay
                },
            )
        }

        binding.disclosureCheckbox.visibility =
            if (step == OnboardingStep.READ_PRIVACY) View.VISIBLE else View.GONE

        // Only offered on the keyboard path, for the reason above.
        binding.tryField.visibility =
            if (step == OnboardingStep.READY && keyboardPathComplete()) View.VISIBLE else View.GONE

        binding.primaryButton.setText(
            when (step) {
                OnboardingStep.READ_PRIVACY -> R.string.onboarding_continue
                OnboardingStep.GRANT_ACCESSIBILITY -> R.string.overlay_grant_accessibility
                OnboardingStep.GRANT_OVERLAY -> R.string.overlay_grant_draw
                OnboardingStep.ENABLE_KEYBOARD -> R.string.setup_enable_button
                OnboardingStep.CHOOSE_KEYBOARD -> R.string.setup_switch_button
                OnboardingStep.READY -> R.string.onboarding_done
            },
        )
        // Step 1's button leads nowhere until the box is ticked, and saying so with a disabled
        // button is clearer than a toast fired after the tap.
        binding.primaryButton.isEnabled =
            step != OnboardingStep.READ_PRIVACY || disclosureTicked
        binding.primaryButton.setOnClickListener { advance(step) }

        binding.skipButton.setText(
            if (step == OnboardingStep.READY) R.string.onboarding_settings else R.string.onboarding_skip,
        )
    }

    /**
     * Whether the Keyguard keyboard is the thing protecting this device.
     *
     * Not the same question as "is setup finished" - both paths finish setup, and only this one
     * can demonstrate itself inside our own app.
     */
    private fun keyboardPathComplete(): Boolean =
        KeyboardStatus.isEnabled(this) && KeyboardStatus.isSelected(this)

    private fun advance(step: OnboardingStep) {
        when (step) {
            // The one place consent is written. `render()` then recomputes the step, which is
            // now past READ_PRIVACY, and the screen moves on.
            OnboardingStep.READ_PRIVACY -> {
                settings.disclosureAccepted = disclosureTicked
                render()
            }
            // Both open a system settings screen and come back with nothing to report, so
            // neither tracks what it asked for — `onResume` re-reads the real state, which is
            // the only thing that can be trusted for a permission granted outside the app.
            OnboardingStep.GRANT_ACCESSIBILITY -> {
                runCatching { startActivity(OverlayPermissions.accessibilitySettings()) }
            }

            OnboardingStep.GRANT_OVERLAY -> {
                runCatching { startActivity(OverlayPermissions.drawOverAppsSettings(this)) }
            }

            OnboardingStep.ENABLE_KEYBOARD ->
                startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
            OnboardingStep.CHOOSE_KEYBOARD ->
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showInputMethodPicker()
            OnboardingStep.READY -> finish()
        }
    }
}
