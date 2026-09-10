package com.keyguard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.keyguard.app.R

/**
 * The row of alternate characters shown while a key is held.
 *
 * A [PopupWindow] anchored to the key rather than a view inside the keyboard, so it can extend
 * above the keyboard's own bounds — the top row has nowhere inside the keyboard to draw.
 *
 * Selection tracks the finger: hold, slide across the options, release on one. Releasing
 * outside cancels, which is what users expect from a stock keyboard.
 */
@SuppressLint("ViewConstructor")
class AlternatesPopup(private val context: Context) {

    private var popup: PopupWindow? = null
    private var optionViews: List<TextView> = emptyList()
    private var options: List<String> = emptyList()
    private var selectedIndex = 0

    val isShowing: Boolean get() = popup?.isShowing == true

    /** The option currently under the finger, or null when nothing is selected. */
    val selected: String? get() = options.getOrNull(selectedIndex)

    fun show(anchor: View, alternates: List<String>, upper: Boolean) {
        dismiss()
        if (alternates.isEmpty()) return

        options = alternates.map { Alternates.applyCase(it, upper) }
        selectedIndex = 0

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.alternates_background)
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
        }

        optionViews = options.map { option ->
            TextView(context).apply {
                text = option
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(context.getColor(R.color.key_text))
                setTypeface(null, Typeface.NORMAL)
                minWidth = dp(40)
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(2) }
            }.also { container.addView(it) }
        }
        highlight()

        val window = PopupWindow(
            container,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isClippingEnabled = false
            isTouchable = false
        }
        popup = window

        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = container.measuredWidth
        val height = container.measuredHeight

        // Centre over the key, then clamp so an edge key's popup stays on screen.
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val x = (location[0] + anchor.width / 2 - width / 2).coerceIn(0, maxOf(0, screenWidth - width))
        val y = location[1] - height - dp(4)

        window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    /**
     * Updates the selection from a touch position.
     *
     * @param rawX screen x of the finger
     */
    fun trackTouch(rawX: Float) {
        if (!isShowing || optionViews.isEmpty()) return
        val index = optionViews.indices.minByOrNull { i ->
            val view = optionViews[i]
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val centre = location[0] + view.width / 2f
            kotlin.math.abs(rawX - centre)
        } ?: return

        if (index != selectedIndex) {
            selectedIndex = index
            highlight()
        }
    }

    private fun highlight() {
        optionViews.forEachIndexed { index, view ->
            view.setBackgroundResource(
                if (index == selectedIndex) R.drawable.key_background_accent else 0,
            )
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
        optionViews = emptyList()
        options = emptyList()
        selectedIndex = 0
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
