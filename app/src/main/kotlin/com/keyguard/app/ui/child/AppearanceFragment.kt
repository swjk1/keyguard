package com.keyguard.app.ui.child

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentAppearanceBinding
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
import com.keyguard.app.overlay.OverlayActionListener
import com.keyguard.app.overlay.OverlayState
import com.keyguard.app.overlay.WarningOverlayView
import com.keyguard.app.settings.OverlayPosition
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.SectionFragment
import com.keyguard.detect.DetectionEngine
import com.keyguard.detect.Severity

/**
 * Sizing, position, and the live preview.
 *
 * The preview is built from the real warning surfaces, [KeyboardView] and [DetectionEngine]
 * rather than a mock, so it cannot drift from what actually gets drawn. That principle predates
 * the rework and is the one thing worth carrying over unchanged — which is exactly why the
 * preview now shows [WarningOverlayView] to an overlay user and [WarningStrip] to a keyboard
 * user. Showing a single surface to both would have quietly broken it. What changed is that the controls
 * and the preview are now on the same screen with nothing else competing, which is the whole
 * reason this is a tab: adjusting a slider and scrolling to find out what it did was the
 * interaction that made the old single-page version tedious.
 *
 * The keyboard sizing card hides itself unless the Keyguard keyboard is actually enabled. Seven
 * sliders for a keyboard you do not use is noise, and after the overlay landed that is the
 * common case.
 */
class AppearanceFragment : SectionFragment() {

    private var _binding: FragmentAppearanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: Settings
    private lateinit var effective: SupervisedSettings

    private var appearance: Appearance = Appearance.DEFAULT
    private val valueLabels = mutableMapOf<AppearanceControl, TextView>()
    private val sliders = mutableMapOf<AppearanceControl, Slider>()

    private var previewKeyboard: KeyboardView? = null
    private var previewStrip: WarningStrip? = null
    private var previewOverlay: WarningOverlayView? = null

    /** Built once; constructing an engine parses the rule pack and is not free. */
    private val engine by lazy { DetectionEngine.withBundledPack() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppearanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        settings = Settings(context)
        effective = SupervisedSettings(settings, Supervision(context))
        appearance = settings.appearance

        binding.overlayPositionGroup.setOnCheckedChangeListener { _, checked ->
            settings.overlayPosition = when (checked) {
                R.id.overlayPositionTop -> OverlayPosition.SCREEN_TOP
                else -> OverlayPosition.ABOVE_KEYBOARD
            }
        }

        binding.opacitySlider.valueFrom = Settings.MIN_OVERLAY_OPACITY.toFloat()
        binding.opacitySlider.valueTo = Settings.MAX_OVERLAY_OPACITY.toFloat()
        binding.opacitySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            settings.overlayOpacityPercent = value.toInt()
            renderOpacityLabel()
        }

        binding.overlayEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            settings.overlayEnabled = checked
        }

        binding.autocorrectSwitch.setOnCheckedChangeListener { _, checked ->
            settings.autocorrectEnabled = checked
        }
        binding.hapticsSwitch.setOnCheckedChangeListener { _, checked ->
            settings.hapticsEnabled = checked
            previewKeyboard?.hapticsEnabled = checked
        }

        binding.resetAppearanceButton.setOnClickListener {
            appearance = Appearance.DEFAULT
            settings.appearance = appearance
            settings.overlayOpacityPercent = Settings.DEFAULT_OVERLAY_OPACITY
            syncSlidersToAppearance()
            renderOpacityLabel()
            binding.opacitySlider.value = settings.overlayOpacityPercent.toFloat()
            applyAppearanceToPreview()
        }

        buildControls(binding.alarmControls, AppearanceControl.ALARM)
        buildControls(binding.keyboardControls, AppearanceControl.KEYBOARD)
        buildPreview()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewKeyboard = null
        previewStrip = null
        previewOverlay = null
        valueLabels.clear()
        sliders.clear()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        appearance = settings.appearance

        binding.overlayPositionGroup.check(
            when (settings.overlayPosition) {
                OverlayPosition.SCREEN_TOP -> R.id.overlayPositionTop
                OverlayPosition.ABOVE_KEYBOARD -> R.id.overlayPositionAbove
            },
        )
        binding.opacitySlider.value = settings.overlayOpacityPercent.toFloat()
            .coerceIn(binding.opacitySlider.valueFrom, binding.opacitySlider.valueTo)
        renderOpacityLabel()

        binding.overlayEnabledSwitch.isChecked = settings.overlayEnabled
        binding.autocorrectSwitch.isChecked = settings.autocorrectEnabled
        binding.hapticsSwitch.isChecked = settings.hapticsEnabled

        // Hidden rather than disabled: these controls are meaningless without the keyboard, and
        // a greyed-out card still costs a screenful of scrolling to get past.
        binding.keyboardSizingCard.visibility =
            if (KeyboardStatus.isEnabled(requireContext())) View.VISIBLE else View.GONE

        syncSlidersToAppearance()
        applyAppearanceToPreview()
    }

    private fun renderOpacityLabel() {
        binding.opacityLabel.text = getString(
            R.string.appearance_opacity_value,
            getString(R.string.overlay_opacity),
            settings.overlayOpacityPercent,
        )
    }

    // region sizing controls

    private fun buildControls(container: LinearLayout, controls: List<AppearanceControl>) {
        container.removeAllViews()
        for (control in controls) container.addView(buildControl(control))
    }

    private fun buildControl(control: AppearanceControl): LinearLayout {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextAppearance(R.style.TextAppearance_Keyguard_Label)
        }
        valueLabels[control] = label
        row.addView(label)

        val slider = Slider(context).apply {
            valueFrom = control.range.first.toFloat()
            valueTo = control.range.last.toFloat()
            stepSize = 1f
            value = control.read(appearance).toFloat().coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                appearance = control.write(appearance, value.toInt()).sanitized()
                settings.appearance = appearance
                updateLabel(control)
                applyAppearanceToPreview()
            }
        }
        sliders[control] = slider
        row.addView(slider)

        updateLabel(control)
        return row
    }

    private fun updateLabel(control: AppearanceControl) {
        valueLabels[control]?.text = getString(
            R.string.appearance_value,
            getString(control.labelRes),
            control.read(appearance),
            control.unit.suffix,
        )
    }

    private fun syncSlidersToAppearance() {
        for ((control, slider) in sliders) {
            val value = control.read(appearance).toFloat()
            if (slider.value != value) {
                slider.value = value.coerceIn(slider.valueFrom, slider.valueTo)
            }
            updateLabel(control)
        }
    }

    // endregion

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

        val inertOverlay = object : OverlayActionListener {
            override fun onOverlayDismiss() = Unit
            override fun onOverlayRemove() = Unit
            override fun onOverlayCrisisHelp() = Unit
        }

        val strip = WarningStrip(context, inertStrip, appearance)
        val overlay = WarningOverlayView(context, inertOverlay, appearance)
        val keys = KeyboardView(context, inertKeyboard, appearance)
        previewStrip = strip
        previewOverlay = overlay
        previewKeyboard = keys

        binding.previewContainer.removeAllViews()
        // Both warning surfaces are built and exactly one is shown - see renderPreview. The
        // original design principle was that the preview is assembled from the real components
        // so it cannot drift from what actually gets drawn; after the overlay landed, showing
        // the IME's strip to an overlay user would have quietly broken exactly that.
        binding.previewContainer.addView(
            overlay,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
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
        renderPreview()
    }

    private fun applyAppearanceToPreview() {
        previewKeyboard?.let { keys ->
            keys.updateAppearance(appearance)
            keys.layoutParams = keys.layoutParams?.apply { height = keys.desiredHeightPx() }
            keys.requestLayout()
        }
        previewStrip?.updateAppearance(appearance)
        previewOverlay?.updateAppearance(appearance)
        renderPreview()
    }

    /**
     * Runs the real engine over a sample so the preview shows a genuine warning.
     *
     * Which surface it shows depends on which path is actually protecting this device. An
     * overlay user gets [WarningOverlayView] and no keyboard; a keyboard user gets the strip
     * above the keys, as before. Showing the wrong one would make the preview a picture of
     * something the user will never see, which is worse than having no preview.
     */
    private fun renderPreview() {
        val strip = previewStrip ?: return
        val overlay = previewOverlay ?: return
        val context = requireContext()

        // The keyboard path is what puts a strip above keys. Anything else - including a
        // half-finished setup - previews the overlay, which is what the app is steering
        // people towards.
        val keyboardPath = KeyboardStatus.isEnabled(context) && KeyboardStatus.isSelected(context)
        strip.visibility = if (keyboardPath) View.VISIBLE else View.GONE
        overlay.visibility = if (keyboardPath) View.GONE else View.VISIBLE
        previewKeyboard?.visibility = if (keyboardPath) View.VISIBLE else View.GONE

        val sample = getString(R.string.appearance_preview_sample)
        val result = engine.scan(sample)
        val top = result.findings.maxByOrNull { it.severity.level }

        if (top == null || top.severity == Severity.NONE) {
            strip.render(StripState.Hidden)
            overlay.render(OverlayState.Hidden, shadeActive = false)
            return
        }

        val blocking = InputGate.qualifies(
            result,
            fieldProtected = false,
            blockingEnabled = effective.blocksAtHighSeverity,
        )
        overlay.render(
            OverlayState.Warning(
                summary = top.message,
                detail = getString(R.string.appearance_preview_detail),
                severity = top.severity,
                shaded = blocking,
                dismissible = effective.overrideLevel.mayDismiss(top.severity),
                removable = true,
            ),
            // The real shade is a separate window over the keyboard, which a preview inside a
            // scrolling card cannot reproduce. Reporting it as inactive keeps the paused notice
            // off a preview that is not actually pausing anything.
            shadeActive = false,
        )
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
                blocking = blocking,
                dismissible = effective.overrideLevel.mayDismiss(top.severity),
            ),
        )
    }

    // endregion

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
