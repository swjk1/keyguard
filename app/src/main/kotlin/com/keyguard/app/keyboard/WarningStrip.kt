package com.keyguard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.keyguard.app.R
import com.keyguard.app.settings.Appearance
import com.keyguard.detect.Severity

/** What the strip should currently display. */
sealed interface StripState {
    data object Hidden : StripState

    data class Warning(
        val summary: String,
        /**
         * The user's text with flagged spans already highlighted. Pre-spanned by the IME,
         * which is the only place that holds both the buffer and the findings.
         */
        val preview: CharSequence,
        val detail: String,
        val severity: Severity,
        val expanded: Boolean,
        /**
         * Whether the keys are currently refusing input on account of this finding. Renders
         * the paused notice and forces the action row visible, since those two buttons are
         * the only way out and a collapsed strip would hide both.
         */
        val blocking: Boolean = false,
        /**
         * Whether to draw the Ignore button at all.
         *
         * False when a parent's [com.keyguard.app.family.OverrideLevel] has withheld the
         * override for this severity. Drawing it anyway and refusing the tap would be the worse
         * failure: a button that does nothing reads as a bug, and the child presses it again
         * instead of finding *Remove it*, which is the exit that does work.
         *
         * Defaults to true, which is what an unsupervised keyboard has always done.
         */
        val dismissible: Boolean = true,
    ) : StripState

    /**
     * A self-harm signal. Deliberately a distinct state, not a Warning: the response is
     * support and a crisis line, never a privacy alert and never a reporting threat.
     *
     * [resourceLabel] names the helpline being offered, so the user knows who they would be
     * reaching before they tap rather than being handed an anonymous number.
     */
    data class Crisis(val message: String, val resourceLabel: String) : StripState

    /** High-severity Enter gate: the user must acknowledge before the key sends. */
    data class ConfirmSend(val preview: CharSequence, val summary: String) : StripState
}

interface WarningStripListener {
    fun onToggleExpanded()
    fun onDismiss()
    fun onRewriteRequested()
    fun onCrisisHelpTapped()
    fun onConfirmSend()
    fun onCancelSend()
}

/**
 * The keyboard's own warning surface, occupying the row where a suggestion strip normally
 * sits.
 *
 * This is the primary UI rather than in-field underlining because an IME cannot reliably
 * style text inside another app's input field — hosts strip spans from committed text, and
 * composing-region styling only covers a transient region. So the flagged content is shown
 * here instead, as an echo of what the user typed with the problem parts highlighted. The
 * strip is fully ours: it renders instantly, needs no permissions, and no host app can
 * suppress it.
 *
 * Sizing comes from [Appearance]; a warning too small to notice fails at its only job, and
 * how big that needs to be depends on the person reading it.
 */
@SuppressLint("ViewConstructor")
class WarningStrip(
    context: Context,
    private val listener: WarningStripListener,
    appearance: Appearance = Appearance.DEFAULT,
) : LinearLayout(context) {

    private var appearance: Appearance = appearance.sanitized()

    private val summaryView: TextView
    private val previewView: TextView
    private val blockedView: TextView
    private val detailView: TextView
    private val actionRow: LinearLayout

    private var state: StripState = StripState.Hidden

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.strip_background))
        visibility = GONE

        summaryView = TextView(context).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(summaryView)

        // The echo of the user's own text. This is what carries the red highlight.
        previewView = TextView(context).apply {
            setTextColor(context.getColor(R.color.strip_text))
            ellipsize = TextUtils.TruncateAt.START
        }
        addView(previewView)

        // Says that the keys have stopped and names both ways out. Bold and separate from
        // the detail line, because a user who cannot type needs to be told why in words —
        // the greyed-out keys alone read as a bug.
        blockedView = TextView(context).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(context.getColor(R.color.warn_high))
            visibility = GONE
        }
        addView(blockedView)

        detailView = TextView(context).apply {
            setTextColor(context.getColor(R.color.strip_text_muted))
            visibility = GONE
        }
        addView(detailView)

        actionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
        }
        addView(actionRow)

        applyAppearance()

        setOnClickListener {
            when (state) {
                is StripState.Warning -> listener.onToggleExpanded()
                is StripState.Crisis -> listener.onCrisisHelpTapped()
                else -> Unit
            }
        }
    }

    /** Replaces the sizing. Safe to call while visible; re-renders the current state. */
    fun updateAppearance(newAppearance: Appearance) {
        val clean = newAppearance.sanitized()
        if (clean == appearance) return
        appearance = clean
        applyAppearance()
        render(state)
    }

    private fun applyAppearance() {
        val hPad = dp(12)
        val vPad = dp(appearance.alarmPaddingDp)
        setPadding(hPad, vPad, hPad, vPad)

        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.alarmSummarySp.toFloat())

        previewView.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.alarmPreviewSp.toFloat())
        previewView.maxLines = appearance.alarmPreviewLines
        previewView.setPadding(0, dp(5), 0, 0)

        blockedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.alarmSummarySp.toFloat())
        blockedView.setPadding(0, dp(5), 0, 0)

        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.alarmDetailSp.toFloat())
        detailView.setPadding(0, dp(5), 0, 0)

        actionRow.setPadding(0, dp(8), 0, 0)
    }

    fun render(newState: StripState) {
        state = newState
        when (newState) {
            StripState.Hidden -> visibility = GONE
            is StripState.Warning -> renderWarning(newState)
            is StripState.Crisis -> renderCrisis(newState)
            is StripState.ConfirmSend -> renderConfirm(newState)
        }
    }

    private fun renderWarning(warning: StripState.Warning) {
        visibility = VISIBLE
        setBackgroundColor(context.getColor(R.color.strip_background))

        val accent = when (warning.severity) {
            Severity.HIGH -> R.color.warn_high
            else -> R.color.warn_medium
        }
        summaryView.setTextColor(context.getColor(accent))
        summaryView.text = warning.summary
        summaryView.visibility = VISIBLE

        previewView.text = warning.preview
        previewView.visibility = if (warning.preview.isEmpty()) GONE else VISIBLE

        // The background stays neutral even when blocking. Flooding it red would put red
        // highlight spans on a red field and make the flagged text — the thing the user has
        // to find and delete — the hardest part to read.
        // Two different sentences, because they name different exits. The usual one offers
        // Ignore or Remove; when the policy has taken Ignore away, saying so is the difference
        // between a constraint and a keyboard that appears to have broken.
        blockedView.setText(
            if (warning.dismissible) R.string.warning_blocked else R.string.warning_blocked_locked,
        )
        blockedView.visibility = if (warning.blocking) VISIBLE else GONE

        detailView.setTextColor(context.getColor(R.color.strip_text_muted))
        detailView.text = warning.detail
        detailView.visibility = if (warning.expanded) VISIBLE else GONE

        // Blocking always shows the buttons, whatever the expand state says. They are the
        // only two ways to get the keys back, so hiding them behind a tap on the strip would
        // leave a user who does not know that stuck with a dead keyboard.
        val showActions = warning.expanded || warning.blocking
        actionRow.visibility = if (showActions) VISIBLE else GONE
        if (showActions) {
            actionRow.removeAllViews()
            // Remove it is added first and unconditionally: it is the exit that exists at every
            // override level, and at the strictest one it is the only exit.
            actionRow.addView(actionButton(R.string.action_rewrite) { listener.onRewriteRequested() })
            if (warning.dismissible) {
                actionRow.addView(actionButton(R.string.action_dismiss) { listener.onDismiss() })
            }
        }
    }

    private fun renderCrisis(crisis: StripState.Crisis) {
        visibility = VISIBLE
        setBackgroundColor(context.getColor(R.color.crisis_background))

        summaryView.setTextColor(context.getColor(R.color.warn_text))
        summaryView.text = crisis.message
        summaryView.visibility = VISIBLE

        // No echo of what they typed here. Reflecting those words back would be unkind.
        previewView.visibility = GONE

        // The crisis path never takes the keyboard away; see InputGate.
        blockedView.visibility = GONE

        detailView.setTextColor(context.getColor(R.color.warn_text))
        detailView.text = context.getString(R.string.crisis_detail, crisis.resourceLabel)
        detailView.visibility = VISIBLE

        actionRow.visibility = VISIBLE
        actionRow.removeAllViews()
        actionRow.addView(actionButton(R.string.action_get_help) { listener.onCrisisHelpTapped() })
        actionRow.addView(actionButton(R.string.action_dismiss) { listener.onDismiss() })
    }

    private fun renderConfirm(confirm: StripState.ConfirmSend) {
        visibility = VISIBLE
        setBackgroundColor(context.getColor(R.color.warn_high))

        summaryView.setTextColor(context.getColor(R.color.warn_text))
        summaryView.text = context.getString(R.string.confirm_send_title)
        summaryView.visibility = VISIBLE

        previewView.text = confirm.preview
        previewView.visibility = if (confirm.preview.isEmpty()) GONE else VISIBLE

        blockedView.visibility = GONE

        detailView.setTextColor(context.getColor(R.color.warn_text))
        detailView.text = confirm.summary
        detailView.visibility = VISIBLE

        actionRow.visibility = VISIBLE
        actionRow.removeAllViews()
        actionRow.addView(actionButton(R.string.action_send_anyway) { listener.onConfirmSend() })
        actionRow.addView(actionButton(R.string.action_go_back) { listener.onCancelSend() })
    }

    private fun actionButton(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = context.getString(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.alarmSummarySp.toFloat())
            setTypeface(null, Typeface.BOLD)
            setTextColor(context.getColor(R.color.warn_text))
            setBackgroundResource(R.drawable.action_background)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
