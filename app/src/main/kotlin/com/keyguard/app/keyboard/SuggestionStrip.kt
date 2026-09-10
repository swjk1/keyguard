package com.keyguard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.keyguard.app.R
import com.keyguard.app.settings.Appearance
import com.keyguard.app.text.CorrectionRanker
import com.keyguard.app.text.Predictor

interface SuggestionStripListener {
    fun onSuggestionPicked(word: String)
}

/**
 * The correction candidates shown while typing a word.
 *
 * Shares the row above the keys with the warning strip and the idle toolbar; exactly one of the
 * three is ever visible. Suggestions yield to any warning, because a safety message must never
 * be pushed off screen by a spelling hint.
 */
@SuppressLint("ViewConstructor")
class SuggestionStrip(
    context: Context,
    private val listener: SuggestionStripListener,
    appearance: Appearance = Appearance.DEFAULT,
) : LinearLayout(context) {

    private var appearance: Appearance = appearance.sanitized()
    private val slots: List<TextView>

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(context.getColor(R.color.strip_background))
        visibility = GONE

        slots = (0 until CorrectionRanker.MAX_SUGGESTIONS).map { index ->
            TextView(context).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                isClickable = true
                setTextColor(context.getColor(R.color.strip_text))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    (tag as? String)?.let(listener::onSuggestionPicked)
                }
            }.also { slot ->
                addView(slot)
                if (index < CorrectionRanker.MAX_SUGGESTIONS - 1) addView(divider())
            }
        }
        applyAppearance()
    }

    fun updateAppearance(newAppearance: Appearance) {
        val clean = newAppearance.sanitized()
        if (clean == appearance) return
        appearance = clean
        applyAppearance()
    }

    private fun applyAppearance() {
        val vPad = dp(appearance.alarmPaddingDp)
        setPadding(dp(4), vPad, dp(4), vPad)
        for (slot in slots) {
            slot.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.alarmPreviewSp.toFloat())
            slot.setPadding(dp(6), dp(6), dp(6), dp(6))
        }
    }

    /**
     * Renders [suggestions], or hides the strip when there are none.
     *
     * Follows the stock-keyboard convention for a pending autocorrect: the word about to be
     * applied is bold, and the user's literal typing is shown beside it in quotes. Seeing the
     * correction before it lands is what lets someone decline it, rather than discovering it
     * afterwards and having to undo.
     */
    fun render(suggestions: Predictor.Suggestions) {
        if (suggestions.isEmpty) {
            visibility = GONE
            return
        }
        visibility = VISIBLE

        slots.forEachIndexed { index, slot ->
            val word = suggestions.items.getOrNull(index)
            if (word == null) {
                slot.text = ""
                slot.tag = null
                slot.visibility = INVISIBLE
                return@forEachIndexed
            }

            val isAutoApplied = index == suggestions.autoApplyIndex
            val isLiteral = index == suggestions.literalIndex

            slot.tag = word
            slot.visibility = VISIBLE
            // Quoting the literal marks it as "what you typed" rather than another candidate.
            slot.text = if (isLiteral) "“$word”" else word
            slot.setTypeface(null, if (isAutoApplied) Typeface.BOLD else Typeface.NORMAL)
            slot.setTextColor(
                context.getColor(
                    when {
                        isAutoApplied -> R.color.strip_text
                        isLiteral -> R.color.strip_text_muted
                        else -> R.color.strip_text
                    },
                ),
            )
        }
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(context.getColor(R.color.key_face_modifier))
        layoutParams = LayoutParams(dp(1), LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
