package com.keyguard.app.ui.child

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentProtectionBinding
import com.keyguard.app.family.SupervisedSettings
import com.keyguard.app.family.Supervision
import com.keyguard.app.overlay.OverlayPermissions
import com.keyguard.app.settings.Intensity
import com.keyguard.app.settings.KeyboardStatus
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.SectionFragment

/**
 * The child app's home: whether protection is actually on, and what is left to do.
 *
 * The banner is the reason this section exists. A safety app whose status has to be inferred
 * from a list of permission rows is a safety app people assume is working when it is not, so the
 * first and largest thing on screen is one of three sentences, and the third one — *Almost
 * there* — exists specifically for the half-granted overlay: able to read what someone types,
 * unable to warn them about it. That state must never render as working, and it is exactly where
 * someone lands if they grant one permission and get distracted.
 *
 * The keyboard card is deliberately last and deliberately worded as an alternative. It is the
 * older path; presenting it alongside the overlay as an equal choice would make a user think
 * they need both.
 */
class ProtectionFragment : SectionFragment() {

    private var _binding: FragmentProtectionBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: Settings
    private lateinit var effective: SupervisedSettings

    /** Guards the reentrancy of re-checking the intensity group from inside its own listener. */
    private var suppressIntensityCallback = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProtectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        settings = Settings(context)
        effective = SupervisedSettings(settings, Supervision(context))

        binding.intensityGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressIntensityCallback) return@setOnCheckedChangeListener
            settings.intensity = when (checkedId) {
                R.id.intensitySubtle -> Intensity.SUBTLE
                R.id.intensityInsistent -> Intensity.INSISTENT
                else -> Intensity.STANDARD
            }
            // A choice below a parent's floor is kept in settings but not honoured, so the group
            // snaps to what the keyboard will actually do and the note says why. A control that
            // silently lies about the current behaviour is the thing worth avoiding.
            renderIntensity()
        }

        binding.enableButton.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.switchButton.setOnClickListener {
            (context.getSystemService(InputMethodManager::class.java))?.showInputMethodPicker()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        renderStatus()
        renderChecklist()
        renderIntensity()
        renderKeyboard()
    }

    // region status

    private fun renderStatus() {
        val context = requireContext()
        val accessibility = OverlayPermissions.isServiceEnabled(context)
        val overlay = OverlayPermissions.canDrawOverlays(context)
        val keyboardReady = KeyboardStatus.isEnabled(context) && KeyboardStatus.isSelected(context)

        val (titleRes, bodyRes, tint) = when {
            // Either complete path counts. Someone running the Keyguard keyboard is protected and
            // must not be told otherwise just because they never granted an accessibility
            // service they have no use for.
            (accessibility && overlay) || keyboardReady ->
                Triple(R.string.protection_on_title, R.string.protection_on_body, R.color.status_ok)

            // Anything started but unfinished. The body names the *specific* missing piece
            // rather than saying "finish the step below", because the dangerous middle state -
            // accessibility granted, overlay permission missing - is one where Keyguard can see
            // everything and warn about nothing, and that deserves to be said out loud rather
            // than left for the reader to work out from a checklist.
            accessibility && !overlay -> Triple(
                R.string.protection_partial_title,
                R.string.overlay_status_missing_draw,
                R.color.status_pending,
            )

            overlay && !accessibility -> Triple(
                R.string.protection_partial_title,
                R.string.overlay_status_missing_accessibility,
                R.color.status_pending,
            )

            KeyboardStatus.isEnabled(context) -> Triple(
                R.string.protection_partial_title,
                R.string.protection_partial_body,
                R.color.status_pending,
            )

            else -> Triple(
                R.string.protection_off_title,
                R.string.protection_off_body,
                R.color.status_pending,
            )
        }

        binding.statusTitle.setText(titleRes)
        binding.statusTitle.setTextColor(ContextCompat.getColor(context, tint))
        binding.statusBody.setText(bodyRes)
        binding.statusCard.setCardBackgroundColor(
            ContextCompat.getColor(
                context,
                if (tint == R.color.status_ok) R.color.status_ok_surface else R.color.status_pending_surface,
            ),
        )
    }

    // endregion

    // region checklist

    /**
     * The remaining steps, built in code because every part of a row depends on state.
     *
     * A finished step keeps its row rather than disappearing. A checklist that empties itself
     * leaves someone unable to confirm they did the thing, and this is a screen people come back
     * to precisely to confirm that.
     */
    private fun renderChecklist() {
        val context = requireContext()
        val container = binding.checklistContainer
        container.removeAllViews()

        container.addView(
            step(
                done = settings.disclosureAccepted,
                title = getString(R.string.protection_step_disclosure),
                hint = getString(R.string.protection_step_disclosure_hint),
                actionLabel = getString(R.string.protection_step_disclosure_action),
                // Sends them to the section that owns the disclosure rather than duplicating the
                // checkbox here. Two places to accept the same thing is two places for it to get
                // out of step.
                action = { selectSection(R.id.nav_privacy) },
            ),
        )

        container.addView(
            step(
                done = OverlayPermissions.isServiceEnabled(context),
                title = getString(R.string.overlay_grant_accessibility),
                hint = getString(R.string.overlay_grant_accessibility_hint),
                actionLabel = getString(R.string.protection_step_open_settings),
                action = {
                    runCatching { startActivity(OverlayPermissions.accessibilitySettings()) }
                },
            ),
        )

        container.addView(
            step(
                done = OverlayPermissions.canDrawOverlays(context),
                title = getString(R.string.overlay_grant_draw),
                hint = getString(R.string.overlay_grant_draw_hint),
                actionLabel = getString(R.string.protection_step_open_settings),
                action = {
                    runCatching { startActivity(OverlayPermissions.drawOverAppsSettings(context)) }
                },
            ),
        )
    }

    private fun step(
        done: Boolean,
        title: String,
        hint: String,
        actionLabel: String,
        action: () -> Unit,
    ): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        row.addView(
            TextView(context).apply {
                text = title
                setTextAppearance(R.style.TextAppearance_Keyguard_Label)
                // The tick carries the state; the colour reinforces it and neither is alone.
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    if (done) R.drawable.ic_check else R.drawable.ic_pending,
                    0,
                    0,
                    0,
                )
                compoundDrawablePadding = dp(10)
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (done) R.color.status_ok else R.color.on_surface,
                    ),
                )
            },
        )

        if (done) {
            row.addView(
                TextView(context).apply {
                    setText(R.string.protection_step_done)
                    setTextAppearance(R.style.TextAppearance_Keyguard_OptionHint)
                    setPadding(dp(34), 0, 0, 0)
                },
            )
            return row
        }

        row.addView(
            TextView(context).apply {
                text = hint
                setTextAppearance(R.style.TextAppearance_Keyguard_OptionHint)
                setPadding(dp(34), 0, 0, dp(6))
            },
        )
        row.addView(
            MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = actionLabel
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(34) }
            },
        )
        return row
    }

    /** Moves the shell to another tab. Used by a checklist step that is owned elsewhere. */
    private fun selectSection(itemId: Int) {
        requireActivity()
            .findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                R.id.bottomNav,
            )
            ?.selectedItemId = itemId
    }

    // endregion

    private fun renderIntensity() {
        suppressIntensityCallback = true
        binding.intensityGroup.check(
            when (effective.intensity) {
                Intensity.SUBTLE -> R.id.intensitySubtle
                Intensity.STANDARD -> R.id.intensityStandard
                Intensity.INSISTENT -> R.id.intensityInsistent
            },
        )
        suppressIntensityCallback = false

        val constrained = effective.intensityLocked || effective.intensityRaisedByPolicy
        binding.intensityLockedText.visibility = if (constrained) View.VISIBLE else View.GONE
        for (index in 0 until binding.intensityGroup.childCount) {
            binding.intensityGroup.getChildAt(index).isEnabled = !effective.intensityLocked
        }
    }

    private fun renderKeyboard() {
        val context = requireContext()
        binding.keyboardStatusText.setText(
            if (KeyboardStatus.isEnabled(context)) {
                R.string.setup_status_enabled
            } else {
                R.string.setup_status_disabled
            },
        )
        // Same gate as before the rework: nobody reaches the system keyboard settings without
        // having read what the keyboard can see.
        binding.enableButton.isEnabled = settings.disclosureAccepted
        binding.switchButton.isEnabled = KeyboardStatus.isEnabled(context)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
