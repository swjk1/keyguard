package com.keyguard.app.ui.child

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.keyguard.app.R
import com.keyguard.app.databinding.ItemFactBinding

/**
 * Lays out the disclosure - "what Keyguard can see" - from the one string it is written in.
 *
 * `setup_disclosure_body` is what a child consents to in onboarding and what they can re-read
 * under Privacy, so it stays a single string: two copies of a consent text are two chances for
 * them to drift. Only the presentation changes here. Paragraphs stay paragraphs, and each "• "
 * line becomes a row with a tick, so the list of promises reads as a list rather than as the
 * wall of text it used to be on both screens.
 */
internal object Disclosure {

    private const val BULLET = "•"

    fun render(container: LinearLayout, inflater: LayoutInflater) {
        val context = container.context
        container.removeAllViews()
        val tint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent_icon))
        val disc = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent_soft))
        val density = context.resources.displayMetrics.density
        val gap = context.resources.getDimensionPixelSize(R.dimen.space_sm)

        for (paragraph in context.getString(R.string.setup_disclosure_body).split("\n\n")) {
            val lines = paragraph.lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty() && lines.all { it.trimStart().startsWith(BULLET) }) {
                for (line in lines) {
                    val fact = ItemFactBinding.inflate(inflater, container, true)
                    fact.factIcon.setImageResource(R.drawable.ic_check)
                    fact.factIcon.imageTintList = tint
                    fact.factIcon.backgroundTintList = disc
                    fact.factText.text = line.trimStart().removePrefix(BULLET).trim()
                }
            } else {
                container.addView(
                    TextView(context).apply {
                        setTextAppearance(R.style.TextAppearance_Keyguard_Body)
                        // Line spacing is not part of a text appearance, so the style's 3dp is
                        // restated to match the layout-declared paragraphs around it.
                        setLineSpacing(3 * density, 1f)
                        text = paragraph.trim()
                        setPadding(0, gap, 0, gap)
                    },
                )
            }
        }
    }
}
