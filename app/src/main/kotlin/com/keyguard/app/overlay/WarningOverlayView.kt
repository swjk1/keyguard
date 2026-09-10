package com.keyguard.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.keyguard.app.R
import com.keyguard.app.settings.Appearance
import com.keyguard.detect.Severity

interface OverlayActionListener {
    /** Ignore. Only ever reachable when the policy allowed it to be drawn. */
    fun onOverlayDismiss()

    /** Remove it. Deletes the flagged span from the host field. Present at every level. */
    fun onOverlayRemove()

    /** Crisis path only: open a helpline. */
    fun onOverlayCrisisHelp()
}

/**
 * The floating warning.
 *
 * The IME's `WarningStrip` could rely on being inside our own window, on a background we chose,
 * in a row nothing else could occupy. This one is a window floating over an arbitrary app, and
 * that changes three things about how it has to be built:
 *
 * - **It carries its own contrast.** The strip could inherit a keyboard background; this can be
 *   over a white chat, a dark chat, or a photo. The background is always painted and always
 *   opaque enough to read against, which is what the opacity setting clamps to a floor rather
 *   than allowing all the way to transparent.
 * - **It shows no echo of the user's text.** The strip's most distinctive feature was rendering
 *   the buffer back with flagged spans highlighted, because an IME cannot style text inside
 *   another app's field. An overlay is floating directly *over* that field, so repeating the
 *   sentence a centimetre above where it already appears is noise — and it would put the
 *   child's own words on screen in a window that a parent walking past can read from further
 *   away than the app itself. The category and the reason are enough.
 * - **It must never take focus.** Handled by the window flags in [OverlayHost] rather than
 *   here, but it is the reason this view uses plain [Button]s and no editable content: a focus
 *   grab would close the host app's keyboard, which would look exactly like the app crashing.
 *
 * Sizing comes from [Appearance] so the warning-text controls a user already had keep working
 * against the surface that replaced the strip.
 */
@SuppressLint("ViewConstructor")
class WarningOverlayView(
    context: Context,
    private val listener: OverlayActionListener,
    appearance: Appearance = Appearance.DEFAULT,
) : LinearLayout(context) {

    private var appearance: Appearance = appearance.sanitized()

    private val summaryView: TextView
    private val detailView: TextView
    private val pausedView: TextView
    private val actionRow: LinearLayout
    private val dismissButton: Button
    private val removeButton: Button
    private val helpButton: Button

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))

        summaryView = TextView(context).apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.strip_text))
        }
        detailView = TextView(context).apply {
            setTextColor(context.getColor(R.color.strip_text_muted))
        }
        pausedView = TextView(context).apply {
            setTextColor(context.getColor(R.color.strip_text))
            setTypeface(typeface, Typeface.BOLD)
            visibility = GONE
        }

        dismissButton = actionButton(R.string.overlay_ignore) { listener.onOverlayDismiss() }
        removeButton = actionButton(R.string.overlay_remove) { listener.onOverlayRemove() }
        helpButton = actionButton(R.string.overlay_get_help) { listener.onOverlayCrisisHelp() }

        actionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            addView(dismissButton)
            addView(removeButton)
            addView(helpButton)
        }

        addView(summaryView)
        addView(detailView)
        addView(pausedView)
        addView(actionRow)
        applyAppearance(this.appearance)
    }

    fun updateAppearance(appearance: Appearance) {
        this.appearance = appearance.sanitized()
        applyAppearance(this.appearance)
    }

    private fun applyAppearance(appearance: Appearance) {
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.alarmTextSp.toFloat())
        pausedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.alarmTextSp.toFloat())
        detailView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (appearance.alarmTextSp - 2).coerceAtLeast(10).toFloat(),
        )
    }

    /**
     * Renders a state.
     *
     * @param shadeActive whether the keyboard is *actually* covered, which is not the same as
     *   the state having asked for it — see [OverlayAnchor.shadeRect], which returns null when
     *   the keyboard could not be located. The paused notice is driven by this rather than by
     *   [OverlayState.Warning.shaded], because telling someone typing is paused while it is not
     *   is the one message here that would be a straightforward lie.
     */
    fun render(state: OverlayState, shadeActive: Boolean) {
        when (state) {
            is OverlayState.Hidden -> visibility = GONE

            is OverlayState.Warning -> {
                visibility = VISIBLE
                setBackgroundColor(backgroundFor(state.severity))
                summaryView.text = state.summary
                detailView.text = state.detail
                detailView.visibility = if (state.detail.isBlank()) GONE else VISIBLE

                pausedView.setText(R.string.overlay_typing_paused)
                pausedView.visibility = if (shadeActive) VISIBLE else GONE

                dismissButton.visibility = if (state.dismissible) VISIBLE else GONE
                removeButton.visibility = if (state.removable) VISIBLE else GONE
                helpButton.visibility = GONE
                actionRow.visibility = VISIBLE
            }

            is OverlayState.Crisis -> {
                visibility = VISIBLE
                // Teal, never red. This is a support message, not a problem with the message,
                // and styling it as an error would be wrong — the same rule the strip follows.
                setBackgroundColor(context.getColor(R.color.crisis_background))
                summaryView.text = state.message
                detailView.text = context.getString(R.string.overlay_crisis_detail, state.resourceLabel)
                detailView.visibility = VISIBLE
                pausedView.visibility = GONE

                // No Ignore and no Remove. Neither is a thing to offer someone here: one
                // dismisses an offer of help, the other tells them to delete what they said.
                dismissButton.visibility = GONE
                removeButton.visibility = GONE
                helpButton.visibility = VISIBLE
                actionRow.visibility = VISIBLE
            }
        }
    }

    /**
     * Two red weights, matching the strip, so a serious case stays distinguishable from a
     * moderate one — and never carrying the signal by colour alone, since the summary text
     * always says what is wrong.
     */
    private fun backgroundFor(severity: Severity): Int = when (severity) {
        Severity.HIGH -> context.getColor(R.color.overlay_high_background)
        else -> context.getColor(R.color.overlay_medium_background)
    }

    private fun actionButton(labelRes: Int, onClick: () -> Unit) = Button(context).apply {
        setText(labelRes)
        setOnClickListener { onClick() }
        minimumWidth = dp(88)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * The opaque sheet that covers the keyboard while a block is in force.
 *
 * This is the overlay's replacement for the IME's dead keys, and it is a genuinely weaker
 * mechanism, which is worth stating where the code lives rather than only in the README. The
 * keyboard *refused* keystrokes at the source; this covers the keys so they cannot be pressed.
 * The difference shows up in three places: a hardware keyboard is unaffected, voice input is
 * unaffected, and if the platform will not tell us where the keyboard is then nothing is
 * covered at all.
 *
 * What it does do is consume every touch that lands on it, so it does not merely discourage
 * typing on the soft keyboard — it stops it.
 */
@SuppressLint("ViewConstructor")
class KeyboardShadeView(context: Context) : View(context) {

    init {
        setBackgroundColor(context.getColor(R.color.overlay_shade))
        // Swallow every touch rather than letting it through to the keyboard underneath.
        // Returning true from a click listener is not enough; the view has to be clickable for
        // the touch to be delivered to it in the first place.
        isClickable = true
        isFocusable = false
        setOnClickListener { /* deliberately nothing: the point is to absorb the press */ }
    }
}
