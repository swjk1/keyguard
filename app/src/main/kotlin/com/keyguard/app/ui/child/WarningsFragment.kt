package com.keyguard.app.ui.child

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentWarningsBinding
import com.keyguard.app.family.SupervisedSettings
import com.keyguard.app.family.Supervision
import com.keyguard.app.input.InputGate
import com.keyguard.app.overlay.OverlayActionListener
import com.keyguard.app.overlay.OverlayState
import com.keyguard.app.overlay.WarningOverlayView
import com.keyguard.app.settings.Appearance
import com.keyguard.app.settings.AppearanceControl
import com.keyguard.app.settings.Intensity
import com.keyguard.app.settings.OverlayPosition
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.SectionFragment
import com.keyguard.detect.DetectionEngine
import com.keyguard.detect.Severity

/**
 * The floating warning: whether it shows, where, how opaque, how large, and how forceful.
 *
 * Merges what used to be the Protection tab's intensity card and the whole of the Look tab,
 * because they are one subject - "what happens when Keyguard spots something" - and splitting
 * them across two tabs was part of what made the app read as a settings dump.
 *
 * The preview at the top is the real [WarningOverlayView] rendering a state produced by the real
 * [DetectionEngine] and the same policy predicates the service asks, so it cannot drift from what
 * actually gets drawn - the principle the old Look tab was built on. What changed is what it
 * floats over: a mock chat with a composer and a keyboard instead of an empty box, so the card
 * is seen where it will actually sit, and the two position choices visibly move it.
 */
class WarningsFragment : SectionFragment(), ChildScreen {

    override val titleRes: Int = R.string.screen_warnings

    private var _binding: FragmentWarningsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: Settings
    private lateinit var effective: SupervisedSettings

    private var appearance: Appearance = Appearance.DEFAULT
    private val valueLabels = mutableMapOf<AppearanceControl, TextView>()
    private val sliders = mutableMapOf<AppearanceControl, Slider>()

    private var previewOverlay: WarningOverlayView? = null

    /** Guards the reentrancy of re-checking the intensity group from inside its own listener. */
    private var suppressIntensityCallback = false

    /** Built once; constructing an engine parses the rule pack and is not free. */
    private val engine by lazy { DetectionEngine.withBundledPack() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentWarningsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        settings = Settings(context)
        effective = SupervisedSettings(settings, Supervision(context))
        appearance = settings.appearance

        binding.overlayEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            settings.overlayEnabled = checked
        }

        binding.overlayPositionGroup.setOnCheckedChangeListener { _, checked ->
            settings.overlayPosition = when (checked) {
                R.id.overlayPositionTop -> OverlayPosition.SCREEN_TOP
                else -> OverlayPosition.ABOVE_KEYBOARD
            }
            placePreview()
        }

        binding.opacitySlider.valueFrom = Settings.MIN_OVERLAY_OPACITY.toFloat()
        binding.opacitySlider.valueTo = Settings.MAX_OVERLAY_OPACITY.toFloat()
        binding.opacitySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            settings.overlayOpacityPercent = value.toInt()
            renderOpacityLabel()
            applyOpacityToPreview()
        }

        bindIntensityLabels()
        binding.intensityGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressIntensityCallback) return@setOnCheckedChangeListener
            settings.intensity = when (checkedId) {
                R.id.intensitySubtle -> Intensity.SUBTLE
                R.id.intensityInsistent -> Intensity.INSISTENT
                else -> Intensity.STANDARD
            }
            // A choice below a parent's floor is kept in settings but not honoured, so the group
            // snaps to what will actually happen and the note says why. A control that silently
            // lies about the current behaviour is the thing worth avoiding.
            renderIntensity()
        }

        binding.resetAppearanceButton.setOnClickListener {
            appearance = Appearance.DEFAULT
            settings.appearance = appearance
            settings.overlayOpacityPercent = Settings.DEFAULT_OVERLAY_OPACITY
            syncSlidersToAppearance()
            binding.opacitySlider.value = settings.overlayOpacityPercent.toFloat()
            renderOpacityLabel()
            applyAppearanceToPreview()
        }

        binding.previewComposerText.text = getString(R.string.appearance_preview_sample)

        // Text size only. The other alarm control, lines of echoed text, sizes the keyboard's
        // strip - the overlay never echoes what was typed - so it lives on the keyboard screen.
        buildControls(binding.alarmControls, listOf(AppearanceControl.ALARM.first()))
        buildPreview()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewOverlay = null
        valueLabels.clear()
        sliders.clear()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        appearance = settings.appearance

        binding.overlayEnabledSwitch.isChecked = settings.overlayEnabled
        binding.overlayPositionGroup.check(
            when (settings.overlayPosition) {
                OverlayPosition.SCREEN_TOP -> R.id.overlayPositionTop
                OverlayPosition.ABOVE_KEYBOARD -> R.id.overlayPositionAbove
            },
        )
        binding.opacitySlider.value = settings.overlayOpacityPercent.toFloat()
            .coerceIn(binding.opacitySlider.valueFrom, binding.opacitySlider.valueTo)
        renderOpacityLabel()
        renderIntensity()

        syncSlidersToAppearance()
        applyAppearanceToPreview()
        placePreview()
    }

    // region intensity

    /**
     * Two-line radio labels: the level in the label colour, the one-line consequence under it.
     *
     * Spans on a single [RadioButton] rather than a separate hint view under each one, because
     * a RadioGroup only manages the checked state of its direct RadioButton children, and a
     * row of loose TextViews between them would also split what TalkBack reads per option.
     */
    private fun bindIntensityLabels() {
        val muted = ContextCompat.getColor(requireContext(), R.color.on_surface_muted)
        val onSurface = ContextCompat.getColor(requireContext(), R.color.on_surface)
        fun label(titleRes: Int, hintRes: Int): CharSequence {
            val title = getString(titleRes)
            val hint = getString(hintRes)
            return SpannableStringBuilder().apply {
                append(title, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(onSurface), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("\n")
                val start = length
                append(hint)
                setSpan(ForegroundColorSpan(muted), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        binding.intensitySubtle.text =
            label(R.string.intensity_subtle, R.string.warnings_intensity_subtle_hint)
        binding.intensityStandard.text =
            label(R.string.intensity_standard, R.string.warnings_intensity_standard_hint)
        binding.intensityInsistent.text =
            label(R.string.intensity_insistent, R.string.warnings_intensity_insistent_hint)
    }

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

    // endregion

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

    private fun syncSlidersToAppearance() {
        AppearanceSliders.sync(this, appearance, sliders, valueLabels)
    }

    // endregion

    // region live preview

    private fun buildPreview() {
        // A preview must not remove text anywhere or trigger a crisis dial-out, so the listener
        // is inert. Everything else is the real component.
        val inert = object : OverlayActionListener {
            override fun onOverlayDismiss() = Unit
            override fun onOverlayRemove() = Unit
            override fun onOverlayCrisisHelp() = Unit
        }
        val overlay = WarningOverlayView(requireContext(), inert)
        previewOverlay = overlay
        binding.previewOverlaySlot.removeAllViews()
        binding.previewOverlaySlot.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        renderPreview()
    }

    private fun applyAppearanceToPreview() {
        previewOverlay?.updateAppearance(appearance)
        renderPreview()
    }

    /**
     * Moves the card the way the two position settings move it on a real screen: just above the
     * composer (the service anchors above the field, not on the keyboard's top edge, so it never
     * lands on the text it is asking about), or pinned to the top of the app.
     */
    private fun placePreview() {
        val slot = binding.previewOverlaySlot
        val params = slot.layoutParams as RelativeLayout.LayoutParams
        params.removeRule(RelativeLayout.ABOVE)
        params.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
        when (settings.overlayPosition) {
            OverlayPosition.ABOVE_KEYBOARD ->
                params.addRule(RelativeLayout.ABOVE, R.id.previewComposer)
            OverlayPosition.SCREEN_TOP -> params.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        slot.layoutParams = params
    }

    /**
     * Runs the real engine over the sample so the preview shows a genuine warning, decided with
     * the same predicates the service uses - so a child whose parent withheld Ignore does not
     * see an Ignore button here either.
     */
    private fun renderPreview() {
        val overlay = previewOverlay ?: return
        val sample = getString(R.string.appearance_preview_sample)
        val result = engine.scan(sample)
        val top = result.findings.maxByOrNull { it.severity.level }

        if (top == null || top.severity == Severity.NONE) {
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
                category = top.category,
            ),
            // The real shade is a separate window over the keyboard, which a preview inside a
            // scrolling card cannot reproduce. Reporting it as inactive keeps the paused notice
            // off a preview that is not actually pausing anything.
            shadeActive = false,
        )
        applyOpacityToPreview()
    }

    /**
     * Fades the card's background, never its words or buttons — through the same call the real
     * floating window uses, so the preview and the warning cannot disagree about what a given
     * slider position looks like.
     */
    private fun applyOpacityToPreview() {
        previewOverlay?.setCardOpacity(settings.overlayOpacityPercent)
    }

    // endregion
}
