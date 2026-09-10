package com.keyguard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.keyguard.app.R

interface IdleToolbarListener {
    fun onEmojiRequested()
    fun onPasteRequested()
    fun onCursorLeft()
    fun onCursorRight()
    fun onSelectAll()
}

/**
 * The toolbar shown in the strip row whenever there is no warning to display.
 *
 * The strip occupies a row above the keys and sits empty most of the time, since most messages
 * are unremarkable. Putting emoji, clipboard paste, and cursor movement there closes three of
 * the largest gaps against a stock keyboard **without costing any vertical space** — which
 * matters because keyboard height is the scarcest resource on the screen.
 *
 * Hidden the moment a warning appears: a safety warning must never compete for attention with
 * a row of utility buttons.
 */
@SuppressLint("ViewConstructor")
class IdleToolbar(
    context: Context,
    private val listener: IdleToolbarListener,
) : LinearLayout(context) {

    private var pasteButton: TextView? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(context.getColor(R.color.strip_background))
        val pad = dp(6)
        setPadding(pad, dp(4), pad, dp(4))

        addView(button(R.string.toolbar_emoji, R.string.toolbar_emoji_description) {
            listener.onEmojiRequested()
        })
        pasteButton = button(R.string.toolbar_paste, R.string.toolbar_paste_description) {
            listener.onPasteRequested()
        }.also { addView(it) }
        addView(button(R.string.toolbar_select_all, R.string.toolbar_select_all_description) {
            listener.onSelectAll()
        })

        // Cursor keys pushed to the right, where a thumb reaching for text navigation expects
        // them rather than mixed in with the content actions.
        addView(
            android.view.View(context).apply {
                layoutParams = LayoutParams(0, 1, 1f)
            },
        )
        addView(button(R.string.toolbar_cursor_left, R.string.toolbar_cursor_left_description) {
            listener.onCursorLeft()
        })
        addView(button(R.string.toolbar_cursor_right, R.string.toolbar_cursor_right_description) {
            listener.onCursorRight()
        })
    }

    /** Greys out paste when the clipboard has nothing usable. */
    fun setPasteAvailable(available: Boolean) {
        pasteButton?.apply {
            isEnabled = available
            alpha = if (available) 1f else 0.35f
        }
    }

    private fun button(labelRes: Int, descriptionRes: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = context.getString(labelRes)
            contentDescription = context.getString(descriptionRes)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(context.getColor(R.color.strip_text))
            setBackgroundResource(R.drawable.key_background_modifier)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(4) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
