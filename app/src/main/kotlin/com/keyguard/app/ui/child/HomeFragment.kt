package com.keyguard.app.ui.child

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.google.android.material.divider.MaterialDivider
import com.keyguard.app.OutcomeLog
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentHomeBinding
import com.keyguard.app.databinding.ItemSetupStepBinding
import com.keyguard.app.family.ReviewScope
import com.keyguard.app.family.SupervisedSettings
import com.keyguard.app.family.Supervision
import com.keyguard.app.overlay.OverlayPermissions
import com.keyguard.app.settings.Intensity
import com.keyguard.app.settings.KeyboardStatus
import com.keyguard.app.settings.OnboardingProgress
import com.keyguard.app.settings.OnboardingStep
import com.keyguard.app.settings.OverlayPosition
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.Rows
import com.keyguard.app.ui.SectionFragment

/**
 * The child app's hub: whether protection is actually on, and one row per thing to adjust.
 *
 * The hero card is the reason this screen exists. A safety app whose status has to be inferred
 * from a list of permission rows is a safety app people assume is working when it is not, so
 * the first and largest thing on screen is one sentence. While setup is unfinished the same card
 * carries the numbered steps and a single button for the next one, so there is never a question
 * of which of three things to do first.
 *
 * The half-granted overlay - able to read what someone types, unable to warn them about it - is
 * the state that must never render as working, and it is exactly where someone lands if they
 * grant one permission and get distracted. The body line names the *specific* missing piece for
 * it rather than "finish the steps below".
 */
class HomeFragment : SectionFragment(), ChildScreen {

    override val titleRes: Int = R.string.app_name

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: Settings
    private lateinit var supervision: Supervision
    private lateinit var effective: SupervisedSettings
    private lateinit var outcomeLog: OutcomeLog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        settings = Settings(context)
        supervision = Supervision(context)
        effective = SupervisedSettings(settings, supervision)
        outcomeLog = OutcomeLog(context)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        renderHero()
        renderFamilyLine()
        renderStats()
        renderRows()
    }

    // region hero

    private fun renderHero() {
        val context = requireContext()
        val accessibility = OverlayPermissions.isServiceEnabled(context)
        val overlay = OverlayPermissions.canDrawOverlays(context)
        val keyboardEnabled = KeyboardStatus.isEnabled(context)
        val keyboardReady = keyboardEnabled && KeyboardStatus.isSelected(context)

        // Either complete path counts. Someone running the Keyguard keyboard is protected and
        // must not be told otherwise just because they never granted an accessibility service
        // they have no use for.
        // The disclosure is part of "protected", not a formality before it: the service reads
        // nothing until it has been accepted, so both permissions without it is a phone that
        // is not being watched and must not be told that it is.
        val protected = settings.disclosureAccepted &&
            ((accessibility && overlay) || keyboardReady)

        val bodyRes = when {
            protected -> R.string.protection_on_body
            !settings.disclosureAccepted -> R.string.protection_off_body
            // The dangerous middle state gets said out loud: Keyguard can see everything and
            // warn about nothing.
            accessibility && !overlay -> R.string.overlay_status_missing_draw
            overlay && !accessibility -> R.string.overlay_status_missing_accessibility
            keyboardEnabled -> R.string.protection_partial_body
            else -> R.string.protection_off_body
        }

        val tint = if (protected) R.color.status_ok else R.color.status_pending
        binding.heroCard.setCardBackgroundColor(
            color(if (protected) R.color.status_ok_surface else R.color.status_pending_surface),
        )
        binding.heroIconDisc.backgroundTintList =
            ColorStateList.valueOf(color(R.color.card_surface))
        binding.heroIcon.setImageResource(
            if (protected) R.drawable.ic_shield_check else R.drawable.ic_shield_alert,
        )
        binding.heroIcon.imageTintList = ColorStateList.valueOf(color(tint))
        binding.heroTitle.setText(
            if (protected) R.string.protection_on_title else R.string.home_title_setup,
        )
        binding.heroTitle.setTextColor(color(tint))
        binding.heroBody.setText(bodyRes)

        if (protected) {
            binding.stepsContainer.visibility = View.GONE
            binding.heroButton.visibility = View.GONE
            return
        }
        renderSteps(accessibility, overlay)
    }

    /**
     * The three overlay steps, numbered, and one button for whichever comes next.
     *
     * The button asks [OnboardingProgress] rather than walking the three rows, so it stays in
     * step with onboarding on the one case the rows do not show: someone part-way down the
     * keyboard path is walked to the end of *that* path rather than restarted on the overlay.
     */
    private fun renderSteps(accessibility: Boolean, overlay: Boolean) {
        val context = requireContext()
        val next = OnboardingProgress.next(
            disclosureAccepted = settings.disclosureAccepted,
            keyboardEnabled = KeyboardStatus.isEnabled(context),
            keyboardSelected = KeyboardStatus.isSelected(context),
            accessibilityGranted = accessibility,
            overlayGranted = overlay,
        )

        val steps = listOf(
            Triple(
                settings.disclosureAccepted,
                R.string.home_step_disclosure,
                R.string.home_step_disclosure_hint,
            ),
            Triple(
                accessibility,
                R.string.overlay_grant_accessibility,
                R.string.overlay_grant_accessibility_hint,
            ),
            Triple(overlay, R.string.overlay_grant_draw, R.string.overlay_grant_draw_hint),
        )
        val current = steps.indexOfFirst { !it.first }

        val container = binding.stepsContainer
        container.removeAllViews()
        container.visibility = View.VISIBLE
        steps.forEachIndexed { index, (done, titleRes, hintRes) ->
            val step = ItemSetupStepBinding.inflate(layoutInflater, container, true)
            val isCurrent = index == current
            val number = index + 1
            step.stepNumber.text = number.toString()
            step.stepNumber.visibility = if (done) View.GONE else View.VISIBLE
            step.stepCheck.visibility = if (done) View.VISIBLE else View.GONE
            step.stepBadge.backgroundTintList = ColorStateList.valueOf(
                color(
                    when {
                        done -> R.color.status_ok
                        isCurrent -> R.color.accent
                        else -> R.color.card_surface
                    },
                ),
            )
            step.stepNumber.setTextColor(
                color(if (isCurrent) android.R.color.white else R.color.on_surface_muted),
            )
            step.stepBadge.contentDescription = getString(
                if (done) R.string.home_step_badge_done else R.string.home_step_badge_todo,
                number,
            )
            step.stepTitle.setText(titleRes)
            step.stepTitle.setTextColor(
                color(if (done) R.color.on_surface_muted else R.color.on_surface),
            )
            step.stepTitle.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            step.stepHint.setText(hintRes)
            step.stepHint.visibility = if (isCurrent) View.VISIBLE else View.GONE
        }

        val (labelRes, action) = actionFor(next)
        binding.heroButton.visibility = if (labelRes == null) View.GONE else View.VISIBLE
        labelRes?.let { binding.heroButton.setText(it) }
        binding.heroButton.setOnClickListener { action() }
    }

    private fun actionFor(step: OnboardingStep): Pair<Int?, () -> Unit> {
        val context = requireContext()
        return when (step) {
            // Sends them to the screen that owns the disclosure rather than duplicating the
            // checkbox here. Two places to accept the same thing is two places for it to get out
            // of step.
            OnboardingStep.READ_PRIVACY ->
                R.string.home_action_disclosure to { openScreen(PrivacyFragment()) }

            OnboardingStep.GRANT_ACCESSIBILITY -> R.string.home_action_accessibility to {
                runCatching { startActivity(OverlayPermissions.accessibilitySettings()) }
                Unit
            }

            OnboardingStep.GRANT_OVERLAY -> R.string.home_action_overlay to {
                runCatching { startActivity(OverlayPermissions.drawOverAppsSettings(context)) }
                Unit
            }

            OnboardingStep.ENABLE_KEYBOARD -> R.string.home_action_enable_keyboard to {
                startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
            }

            OnboardingStep.CHOOSE_KEYBOARD -> R.string.home_action_choose_keyboard to {
                context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                Unit
            }

            OnboardingStep.READY -> null to {}
        }
    }

    // endregion

    /**
     * The one-line version of the Family screen, on a supervised phone only.
     *
     * Worded per review scope, and the full-review version says so in as many words: "never your
     * messages" would be a lie told to a child about what an adult can read.
     */
    private fun renderFamilyLine() {
        val supervised = supervision.isSupervised
        binding.familyCard.visibility = if (supervised) View.VISIBLE else View.GONE
        val container = binding.familyRowContainer
        container.removeAllViews()
        if (!supervised) return

        val row = Rows.add(
            parent = container,
            icon = R.drawable.ic_nav_family,
            title = getString(R.string.home_family_title),
            subtitle = getString(
                when (effective.reviewScope) {
                    ReviewScope.CONCERNING_ONLY -> R.string.home_family_concerning
                    ReviewScope.THEMES -> R.string.home_family_themes
                    ReviewScope.FULL_TEXT -> R.string.home_family_full
                },
            ),
            onClick = { openScreen(FamilyFragment()) },
        )
        // Full review is the one scope where this line is a warning rather than reassurance,
        // so it takes the amber status colour rather than the muted one.
        if (effective.reviewScope == ReviewScope.FULL_TEXT) {
            row.rowSubtitle.setTextColor(color(R.color.status_pending))
        }
    }

    private fun renderStats() {
        val totals = outcomeLog.total()
        binding.statShownValue.text = totals.warningsShown.toString()
        binding.statChangedValue.text = totals.heeded.toString()
        binding.statSentValue.text = totals.sentAnyway.toString()
    }

    /**
     * One row per detail screen, rebuilt on every refresh.
     *
     * Rebuilt rather than updated in place because the Family row can appear or disappear
     * between visits - a sync can pair or unpair the phone - and four rows cost nothing to
     * inflate.
     */
    private fun renderRows() {
        val context = requireContext()
        val container = binding.rowsContainer
        container.removeAllViews()

        addRow(R.drawable.ic_warning, getString(R.string.row_warnings), warningsSummary()) {
            openScreen(WarningsFragment())
        }

        if (ChildNav.familyReachable(context)) {
            addRow(
                R.drawable.ic_nav_family,
                getString(R.string.row_family),
                getString(
                    if (supervision.isSupervised) {
                        R.string.row_family_supervised
                    } else {
                        R.string.row_family_unpaired
                    },
                ),
            ) { openScreen(FamilyFragment()) }
        }

        addRow(
            R.drawable.ic_nav_privacy,
            getString(R.string.row_privacy),
            getString(R.string.row_privacy_summary),
        ) { openScreen(PrivacyFragment()) }

        addRow(
            R.drawable.ic_keyboard,
            getString(R.string.row_keyboard),
            getString(
                when {
                    KeyboardStatus.isEnabled(context) && KeyboardStatus.isSelected(context) ->
                        R.string.row_keyboard_in_use
                    KeyboardStatus.isEnabled(context) -> R.string.row_keyboard_enabled
                    else -> R.string.row_keyboard_off
                },
            ),
        ) { openScreen(KeyboardFragment()) }
    }

    private fun addRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
        val container = binding.rowsContainer
        if (container.childCount > 0) {
            container.addView(
                MaterialDivider(requireContext()).apply {
                    dividerInsetStart = dp(74)
                    dividerColor = color(R.color.card_stroke)
                },
            )
        }
        Rows.add(container, icon, title, subtitle, onClick)
    }

    /** "Standard · above the keyboard", or "Turned off" when warnings are switched off. */
    private fun warningsSummary(): String {
        if (!settings.overlayEnabled) return getString(R.string.row_warnings_off)
        val intensity = getString(
            when (effective.intensity) {
                Intensity.SUBTLE -> R.string.intensity_subtle
                Intensity.STANDARD -> R.string.intensity_standard
                Intensity.INSISTENT -> R.string.intensity_insistent
            },
        )
        val position = getString(
            when (settings.overlayPosition) {
                OverlayPosition.ABOVE_KEYBOARD -> R.string.position_above_short
                OverlayPosition.SCREEN_TOP -> R.string.position_top_short
            },
        )
        return getString(R.string.row_warnings_summary, intensity, position)
    }

    private fun color(@ColorRes res: Int): Int = ContextCompat.getColor(requireContext(), res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
