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
import com.google.android.material.slider.Slider
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentKeyboardBinding
import com.keyguard.app.family.SupervisedSettings
import com.keyguard.app.family.Supervision
import com.keyguard.app.input.InputGate
import com.keyguard.app.keyboard.Highlighter
import com.keyguard.app.keyboard.KeyboardListener
import com.keyguard.app.keyboard.KeyboardView
import com.keyguard.app.keyboard.StripState
import com.keyguard.app.keyboard.WarningStrip
import com.keyguard.app.keyboard.WarningStripListener
import com.keyguard.app.settings.Appearance
import com.keyguard.app.settings.AppearanceControl
import com.keyguard.app.settings.KeyboardStatus
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.SectionFragment
import com.keyguard.detect.DetectionEngine
import com.keyguard.detect.Severity

/**
 * The Keyguard keyboard: turning it on, and sizing it once it is.
 *
 * Worded as an alternative and reached from the last row on the hub, deliberately. It is the
 * older path; presenting it next to the overlay as an equal choice would make a child think
 * they need both.
 *
 * The sizing card, with the real [KeyboardView] and [WarningStrip] as its preview, only appears
 * once the keyboard is enabled - the same rule the old Look tab applied. Everything the strip
 * owns that the overlay does not (lines of echoed text, key sizes, autocorrect, haptics) lives
 * here, so the Warnings screen only carries controls that change the floating warning.
 */
class KeyboardFragment : SectionFragment(), ChildScreen {

    override val titleRes: Int = R.string.screen_keyboard

    private var _binding: FragmentKeyboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: Settings
    private lateinit var effective: SupervisedSettings

    private var appearance: Appearance = Appearance.DEFAULT
    private val valueLabels = mutableMapOf<AppearanceControl, TextView>()
    private val sliders = mutableMapOf<AppearanceControl, Slider>()

    private var previewKeyboard: KeyboardView? = null
    private var previewStrip: WarningStrip? = null

    /** Built once; constructing an engine parses the rule pack and is not free. */
    private val engine by lazy { DetectionEngine.withBundledPack() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentKeyboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        settings = Settings(context)
        effective = SupervisedSettings(settings, Supervision(context))
        appearance = settings.appearance

        binding.enableButton.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.switchButton.setOnClickListener {
            context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        binding.autocorrectSwitch.setOnCheckedChangeListener { _, checked ->
            settings.autocorrectEnabled = checked
        }
        binding.hapticsSwitch.setOnCheckedChangeListener { _, checked ->
            settings.hapticsEnabled = checked
            previewKeyboard?.hapticsEnabled = checked
        }

        // Key sizes, then the strip's lines of echoed text - the one alarm control that sizes
        // the keyboard's warning rather than the floating one.
        buildControls(
            binding.keyboardControls,
            AppearanceControl.KEYBOARD + AppearanceControl.ALARM.drop(1),
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewKeyboard = null
        previewStrip = null
        valueLabels.clear()
        sliders.clear()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        val context = requireContext()
        appearance = settings.appearance
        val enabled = KeyboardStatus.isEnabled(context)
        val selected = KeyboardStatus.isSelected(context)

        binding.keyboardStatusText.setText(
            when {
                enabled && selected -> R.string.keyboard_status_in_use
                enabled -> R.string.keyboard_status_enabled
                else -> R.string.keyboard_status_off
            },
        )
        // Same gate as before the rework: nobody reaches the system keyboard settings without
        // having read what the keyboard can see.
        binding.enableButton.isEnabled = settings.disclosureAccepted
        binding.disclosureFirstText.visibility =
            if (settings.disclosureAccepted) View.GONE else View.VISIBLE
        binding.switchButton.isEnabled = enabled

        binding.autocorrectSwitch.isChecked = settings.autocorrectEnabled
        binding.hapticsSwitch.isChecked = settings.hapticsEnabled

        // Hidden rather than disabled: these controls are meaningless without the keyboard,
        // and a greyed-out card still costs a screenful of scrolling to get past. The preview
        // is only built the first time it is needed, since it constructs a real keyboard.
        binding.keyboardSizingCard.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled && previewKeyboard == null) buildPreview()

        AppearanceSliders.sync(this, appearance, sliders, valueLabels)
        applyAppearanceToPreview()
    }

    private fun buildControls(container: LinearLayout, controls: List<AppearanceControl>) {
        container.removeAllViews()
        for (control in controls) {
            container.addView(
                AppearanceSliders.build(
                    context = requireContext(),
                    control = control,
                    initial = appearance,
                    labels = valueLabels,
                    sliders = sliders,
                ) { value ->
                    appearance = control.write(appearance, value).sanitized()
                    settings.appearance = appearance
                    AppearanceSliders.updateLabel(this, control, appearance, valueLabels)
                    applyAppearanceToPreview()
                },
            )
            AppearanceSliders.updateLabel(this, control, appearance, valueLabels)
        }
    }

    // region live preview

    private fun buildPreview() {
        val context = requireContext()
        // A preview must not type anywhere or trigger a crisis dial-out, so the listeners are
        // inert. Everything else is the real component.
        val inertKeyboard = object : KeyboardListener {
            override fun onText(text: String) = Unit
            override fun onBackspace() = Unit
            override fun onEnter() = Unit
            override fun onNextKeyboard() = Unit
        }
        val inertStrip = object : WarningStripListener {
            override fun onToggleExpanded() = Unit
            override fun onDismiss() = Unit
            override fun onRewriteRequested() = Unit
            override fun onCrisisHelpTapped() = Unit
            override fun onConfirmSend() = Unit
            override fun onCancelSend() = Unit
        }

        val strip = WarningStrip(context, inertStrip, appearance)
        val keys = KeyboardView(context, inertKeyboard, appearance).apply {
            hapticsEnabled = settings.hapticsEnabled
        }
        previewStrip = strip
        previewKeyboard = keys

        binding.previewContainer.removeAllViews()
        binding.previewContainer.addView(
            strip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        binding.previewContainer.addView(
            keys,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                keys.desiredHeightPx(),
            ),
        )
    }

    private fun applyAppearanceToPreview() {
        previewKeyboard?.let { keys ->
            keys.updateAppearance(appearance)
            keys.layoutParams = keys.layoutParams?.apply { height = keys.desiredHeightPx() }
            keys.requestLayout()
        }
        previewStrip?.updateAppearance(appearance)
        renderPreview()
    }

    /** Runs the real engine over a sample so the strip shows a genuine warning. */
    private fun renderPreview() {
        val strip = previewStrip ?: return
        val context = requireContext()
        val sample = getString(R.string.appearance_preview_sample)
        val result = engine.scan(sample)
        val top = result.findings.maxByOrNull { it.severity.level }

        if (top == null || top.severity == Severity.NONE) {
            strip.render(StripState.Hidden)
            return
        }

        strip.render(
            StripState.Warning(
                summary = top.message,
                preview = Highlighter.highlight(
                    text = sample,
                    findings = result.findings,
                    mediumColor = context.getColor(R.color.warn_medium),
                    highColor = context.getColor(R.color.warn_high),
                    onHighlightColor = context.getColor(R.color.warn_text),
                ),
                detail = getString(R.string.appearance_preview_detail),
                severity = top.severity,
                expanded = true,
                // The sample is high-severity, so in the real keyboard it would stop input.
                // Asking the same predicate the IME asks keeps the preview honest about how tall
                // the strip actually gets.
                blocking = InputGate.qualifies(
                    result,
                    fieldProtected = false,
                    blockingEnabled = effective.blocksAtHighSeverity,
                ),
                dismissible = effective.overrideLevel.mayDismiss(top.severity),
            ),
        )
    }

    // endregion
}
