package com.keyguard.app.ui.child

import android.content.Context
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.keyguard.app.R
import com.keyguard.app.settings.Appearance
import com.keyguard.app.settings.AppearanceControl

/**
 * One labelled slider per [AppearanceControl], shared by the Warnings and keyboard screens.
 *
 * The sizing controls used to live on one tab; the redesign splits them by what they size (the
 * floating warning's text on Warnings, the keys and the keyboard's strip on the keyboard
 * screen). Both halves have to build, label and re-sync sliders identically, so it is written
 * once here rather than drifting apart in two fragments.
 */
internal object AppearanceSliders {

    /**
     * Builds the label and slider for [control]. [onChange] runs only for user drags, so
     * re-syncing a slider from settings never writes back to settings.
     */
    fun build(
        context: Context,
        control: AppearanceControl,
        initial: Appearance,
        labels: MutableMap<AppearanceControl, TextView>,
        sliders: MutableMap<AppearanceControl, Slider>,
        onChange: (Int) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val top = (6 * context.resources.displayMetrics.density).toInt()
            setPadding(0, top, 0, 0)
        }

        val label = TextView(context).apply {
            setTextAppearance(R.style.TextAppearance_Keyguard_Label)
        }
        labels[control] = label
        row.addView(label)

        val slider = Slider(context).apply {
            valueFrom = control.range.first.toFloat()
            valueTo = control.range.last.toFloat()
            stepSize = 1f
            // Ticks on a 20-step range are a row of dots, not information.
            isTickVisible = false
            // The Material3 default inactive track is the baseline purple, which this app's
            // blue accent does not otherwise use anywhere.
            trackInactiveTintList = ColorStateList.valueOf(context.getColor(R.color.accent_soft))
            value = control.read(initial).toFloat().coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) onChange(value.toInt())
            }
        }
        sliders[control] = slider
        row.addView(slider)
        return row
    }

    fun updateLabel(
        fragment: Fragment,
        control: AppearanceControl,
        appearance: Appearance,
        labels: Map<AppearanceControl, TextView>,
    ) {
        labels[control]?.text = fragment.getString(
            R.string.appearance_value,
            fragment.getString(control.labelRes),
            control.read(appearance),
            control.unit.suffix,
        )
    }

    fun sync(
        fragment: Fragment,
        appearance: Appearance,
        sliders: Map<AppearanceControl, Slider>,
        labels: Map<AppearanceControl, TextView>,
    ) {
        for ((control, slider) in sliders) {
            val value = control.read(appearance).toFloat()
            if (slider.value != value) {
                slider.value = value.coerceIn(slider.valueFrom, slider.valueTo)
            }
            updateLabel(fragment, control, appearance, labels)
        }
    }
}
