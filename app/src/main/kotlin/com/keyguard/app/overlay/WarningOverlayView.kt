package com.keyguard.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.keyguard.app.R
import com.keyguard.app.settings.Appearance
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.CategoryLabels
import com.keyguard.detect.Category
import com.keyguard.detect.CrisisResources
import com.keyguard.detect.Severity
import kotlin.math.min
import kotlin.math.roundToInt

interface OverlayActionListener {
    /** Ignore. Only ever reachable when the policy allowed it to be drawn. */
    fun onOverlayDismiss()

    /** Remove it. Deletes the flagged span from the host field. Present at every level. */
    fun onOverlayRemove()

    /** Crisis path only: open a helpline. */
    fun onOverlayCrisisHelp()
}

/**
 * The floating card's geometry and opacity curve, kept free of Android types so the numbers the
 * window and the view must agree on are defined once and can be unit-tested.
 *
 * The window is sized to the card rather than to the screen. A full-width window with a
 * transparent margin would swallow every tap in that margin — on the chat behind it — because
 * a window consumes touches across its whole rectangle whether or not anything is drawn there.
 * The only transparent space the window keeps is a thin inset for the card's shadow.
 */
internal object OverlayCardMetrics {

    /** Visual gap between the card and each side of the screen. */
    const val SIDE_MARGIN_DP = 12

    /**
     * How much of that gap is inside the window, so the elevation shadow has somewhere to draw.
     * Shadows fall downward, so the bottom inset is the deepest.
     */
    const val SHADOW_INSET_SIDE_DP = 8
    const val SHADOW_INSET_TOP_DP = 4
    const val SHADOW_INSET_BOTTOM_DP = 10

    /** On a tablet or in landscape a full-width card is a banner again; cap it and centre it. */
    const val MAX_CARD_WIDTH_DP = 560

    /**
     * The card's background alpha at the bottom of the opacity slider.
     *
     * Not the slider's own 40%. The setting fades only the card's fill — text and buttons stay
     * solid — but a 40% dark fill over a white chat leaves the muted text at about 2.3:1, which
     * is unreadable. So the slider's range is mapped onto 85–100% rather than applied as-is. It
     * still visibly fades; it cannot fade the words away.
     *
     * 80% is where the arithmetic stops passing (every colour on the card just clears 4.5:1 over
     * pure white — see the note in `colors.xml`), but on a device the host's own icons and
     * headings at 80% sat visibly behind the category label, and a warning has to be read at a
     * glance. 85% keeps every colour above 5.4:1 over white and the host a faint shape.
     */
    const val MIN_BACKGROUND_ALPHA = 0.85f

    /** Width of the warning window in pixels: the card plus the side shadow insets. */
    fun windowWidthPx(screenWidthPx: Int, density: Float): Int {
        val card = min(
            screenWidthPx - 2 * SIDE_MARGIN_DP * density,
            MAX_CARD_WIDTH_DP * density,
        )
        return (card + 2 * SHADOW_INSET_SIDE_DP * density).roundToInt().coerceIn(0, screenWidthPx)
    }

    /** Left edge of the window that centres it on the screen. */
    fun windowX(screenWidthPx: Int, windowWidthPx: Int): Int =
        ((screenWidthPx - windowWidthPx) / 2).coerceAtLeast(0)

    /** The card fill's alpha for an opacity setting, clamped the same way the setting is. */
    fun backgroundAlpha(opacityPercent: Int): Float {
        val min = Settings.MIN_OVERLAY_OPACITY
        val max = Settings.MAX_OVERLAY_OPACITY
        val t = (opacityPercent.coerceIn(min, max) - min) / (max - min).toFloat()
        return MIN_BACKGROUND_ALPHA + (1f - MIN_BACKGROUND_ALPHA) * t
    }
}

/**
 * The floating warning: a dark card that sits just above the composer.
 *
 * The IME's `WarningStrip` could rely on being inside our own window, on a background we chose,
 * in a row nothing else could occupy. This one is a window floating over an arbitrary app, and
 * that changes three things about how it has to be built:
 *
 * - **It carries its own contrast.** The strip could inherit a keyboard background; this can be
 *   over a white chat, a dark chat, or a photo. The card is always painted and always opaque
 *   enough to read against, which is why the opacity setting fades only the card's fill, and
 *   only down to a floor — see [OverlayCardMetrics.MIN_BACKGROUND_ALPHA]. Fading the whole
 *   view, as an earlier version did, let the chat underneath show through the words.
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
 * ### Tone
 *
 * Modelled on the "are you sure?" nudges teenagers already know (Instagram's Rethink, iOS
 * Communication Safety) rather than on an error dialog. Severity is carried by a small icon in a
 * tinted disc, a hairline border and the category label — shield and red for high, lightbulb and
 * amber for medium, heart and teal for crisis — never by flooding the card. A whole surface in
 * red read as "you did something wrong", and a warning that makes someone defensive is one they
 * learn to tap past. There is one clear primary action, and it is the safe one.
 *
 * ### Two homes
 *
 * The same view is the real overlay (in a floating window, via [OverlayHost]) and the live
 * preview on the child's Warnings screen (in an ordinary layout). Nothing here may assume a
 * window of its own: the shadow inset is this view's padding, not the window's, and the
 * entrance animation keys off attachment rather than off anything [OverlayHost] does.
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

    /** What the card is currently dressed as. Drawables are rebuilt only when this changes. */
    private enum class Tone { HIGH, MEDIUM, CRISIS }

    private var appearance: Appearance = appearance.sanitized()
    private var tone: Tone? = null
    private var backgroundAlpha: Float =
        OverlayCardMetrics.backgroundAlpha(Settings.DEFAULT_OVERLAY_OPACITY)

    private val card: LinearLayout
    private val cardBackground = GradientDrawable()
    private val iconDisc: FrameLayout
    private val iconView: ImageView
    private val eyebrowView: TextView
    private val titleView: TextView
    private val supportView: TextView
    private val pausedRow: LinearLayout
    private val pausedIcon: ImageView
    private val pausedView: TextView
    private val actionRow: LinearLayout
    private val dismissButton: Button
    private val removeButton: Button
    private val helpButton: Button

    init {
        orientation = VERTICAL
        // The shadow inset. Transparent, and small on purpose: it is part of the window, so
        // every pixel of it is a pixel of the chat underneath that cannot be tapped.
        setPadding(
            dp(OverlayCardMetrics.SHADOW_INSET_SIDE_DP),
            dp(OverlayCardMetrics.SHADOW_INSET_TOP_DP),
            dp(OverlayCardMetrics.SHADOW_INSET_SIDE_DP),
            dp(OverlayCardMetrics.SHADOW_INSET_BOTTOM_DP),
        )
        clipToPadding = false
        clipChildren = false

        iconView = ImageView(context).apply {
            // The severity is spoken through the title and the category label; the icon adds
            // nothing for TalkBack except a word, which the disc carries instead.
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        iconDisc = FrameLayout(context).apply {
            addView(iconView, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }

        eyebrowView = text(bold = true)
        titleView = text(bold = true, color = R.color.overlay_text).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }
        supportView = text(color = R.color.overlay_text_muted)

        pausedIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_overlay_lock)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        pausedView = text(color = R.color.overlay_text)
        pausedRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            addView(pausedIcon, LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
            addView(pausedView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(eyebrowView)
            addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(2) })
            addView(supportView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(3) })
            addView(pausedRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })
        }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(iconDisc, LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        dismissButton = button(R.string.overlay_ignore) { listener.onOverlayDismiss() }
        removeButton = button(R.string.overlay_remove) { listener.onOverlayRemove() }
        helpButton = button(R.string.overlay_get_help) { listener.onOverlayCrisisHelp() }

        actionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            // Dismissive on the left, the safe action on the right, as in every Android dialog.
            addView(dismissButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48))
                .apply { marginEnd = dp(4) })
            addView(removeButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
            addView(helpButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        }

        card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(6))
            background = cardBackground
            elevation = dp(6).toFloat()
            // A custom outline rather than the background's own: GradientDrawable reports a
            // fully transparent outline — and so casts no shadow at all — whenever its fill is
            // not opaque, which at the default 96% opacity is always.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                    outline.alpha = backgroundAlpha
                }
            }
            // Polite: announced after whatever TalkBack is currently reading, not over it.
            // Covers the card changing under the user as they type; the pane title below
            // covers it appearing.
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            addView(header)
            addView(actionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) })
        }
        cardBackground.cornerRadius = dp(20).toFloat()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            accessibilityPaneTitle = context.getString(R.string.overlay_pane_title)
        }

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyAppearance(this.appearance)
    }

    fun updateAppearance(appearance: Appearance) {
        val sanitized = appearance.sanitized()
        // The service calls this on every render, which is every keystroke. Re-applying equal
        // sizes still costs a layout pass per keystroke, so an unchanged value is a no-op.
        if (sanitized == this.appearance) return
        this.appearance = sanitized
        applyAppearance(sanitized)
    }

    /**
     * Fades the card's fill — and only the fill — for the opacity setting. Text, icon and
     * buttons stay fully opaque whatever this is set to. See [OverlayCardMetrics.backgroundAlpha]
     * for why 40% on the slider is not 40% on screen.
     */
    fun setCardOpacity(opacityPercent: Int) {
        val alpha = OverlayCardMetrics.backgroundAlpha(opacityPercent)
        if (alpha == backgroundAlpha) return
        backgroundAlpha = alpha
        cardBackground.setColor(withAlpha(context.getColor(R.color.overlay_card), alpha))
        card.invalidateOutline()
    }

    private fun applyAppearance(appearance: Appearance) {
        val base = appearance.alarmTextSp.toFloat()
        val small = appearance.alarmDetailSp.toFloat()
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, base)
        eyebrowView.setTextSize(TypedValue.COMPLEX_UNIT_SP, small)
        supportView.setTextSize(TypedValue.COMPLEX_UNIT_SP, small)
        pausedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, small)
        // Buttons get a floor: a 10sp action label is too small to aim at, whatever the
        // warning text is set to.
        val buttonSp = (appearance.alarmTextSp + 1).coerceAtLeast(14).toFloat()
        listOf(dismissButton, removeButton, helpButton).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonSp)
        }
    }

    /**
     * Renders a state.
     *
     * Called on every keystroke while a warning is up, so everything here is idempotent and
     * cheap when nothing changed: text is only set when it differs, and drawables are only
     * rebuilt when the card's tone does. Anything that restarted, re-laid-out or re-announced
     * on an unchanged state would read as the card flickering under the user's typing.
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
                applyTone(if (state.severity == Severity.HIGH) Tone.HIGH else Tone.MEDIUM)

                val category = state.category
                setTextIfChanged(
                    eyebrowView,
                    category?.let { context.getString(CategoryLabels.res(it)) },
                )
                setTextIfChanged(titleView, state.summary)
                setTextIfChanged(
                    supportView,
                    category?.let { context.getString(whyRes(it)) } ?: state.detail,
                )

                pausedRow.visibility = if (shadeActive) VISIBLE else GONE
                if (shadeActive) {
                    setTextIfChanged(
                        pausedView,
                        context.getString(
                            if (state.dismissible) {
                                R.string.overlay_typing_paused_or_ignore
                            } else {
                                R.string.overlay_typing_paused
                            },
                        ),
                    )
                }

                dismissButton.visibility = if (state.dismissible) VISIBLE else GONE
                removeButton.visibility = if (state.removable) VISIBLE else GONE
                helpButton.visibility = GONE
                actionRow.visibility =
                    if (state.dismissible || state.removable) VISIBLE else GONE
            }

            is OverlayState.Crisis -> {
                visibility = VISIBLE
                // Teal and a heart, never red and never a warning sign. This is a support
                // message, not a problem with the message, and styling it as an error would be
                // wrong — the same rule the strip follows.
                applyTone(Tone.CRISIS)
                setTextIfChanged(eyebrowView, context.getString(R.string.category_self_harm))
                setTextIfChanged(titleView, state.message)
                setTextIfChanged(supportView, crisisSupportLine(state.resourceLabel))
                pausedRow.visibility = GONE

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
     * A short fade and rise when the card appears.
     *
     * Keyed off attachment, which in [OverlayHost] is exactly "the warning window was just
     * added", and never off [render], which runs on every keystroke — an entrance replayed per
     * keystroke is the flicker [FieldWatch] exists to prevent. `animate()` honours the system
     * animator scale, so "Remove animations" in accessibility settings makes this instant.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        card.animate().cancel()
        card.alpha = 0f
        card.translationY = dp(12).toFloat()
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTRANCE_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    override fun onDetachedFromWindow() {
        // Leave the card at rest, so a cancelled entrance never strands it half-faded the next
        // time it is shown somewhere that does not animate.
        card.animate().cancel()
        card.alpha = 1f
        card.translationY = 0f
        super.onDetachedFromWindow()
    }

    private fun applyTone(next: Tone) {
        if (next == tone) return
        tone = next

        val accent = context.getColor(
            when (next) {
                Tone.HIGH -> R.color.overlay_high_accent
                Tone.MEDIUM -> R.color.overlay_medium_accent
                Tone.CRISIS -> R.color.crisis_accent
            },
        )
        iconView.setImageResource(
            when (next) {
                Tone.HIGH -> R.drawable.ic_overlay_shield
                Tone.MEDIUM -> R.drawable.ic_overlay_lightbulb
                Tone.CRISIS -> R.drawable.ic_overlay_heart
            },
        )
        iconView.imageTintList = ColorStateList.valueOf(accent)
        iconDisc.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(accent, DISC_ALPHA))
        }
        // Spoken for the disc, so the severity has a word as well as a colour and a shape.
        iconDisc.contentDescription = context.getString(
            when (next) {
                Tone.HIGH -> R.string.overlay_icon_high
                Tone.MEDIUM -> R.string.overlay_icon_medium
                Tone.CRISIS -> R.string.overlay_icon_crisis
            },
        )
        eyebrowView.setTextColor(accent)
        pausedIcon.imageTintList = ColorStateList.valueOf(accent)

        cardBackground.setColor(withAlpha(context.getColor(R.color.overlay_card), backgroundAlpha))
        cardBackground.setStroke(dp(1), withAlpha(accent, BORDER_ALPHA))
        card.invalidateOutline()
    }

    private fun crisisSupportLine(resourceLabel: String): String =
        if (resourceLabel == CrisisResources.FALLBACK.label) {
            context.getString(R.string.overlay_crisis_support_generic)
        } else {
            context.getString(R.string.overlay_crisis_detail, resourceLabel)
        }

    private fun whyRes(category: Category): Int = when (category) {
        Category.PII_DISCLOSURE -> R.string.overlay_why_pii
        Category.HARASSMENT -> R.string.overlay_why_harassment
        Category.SEXUAL_SOLICITATION -> R.string.overlay_why_solicitation
        Category.SELF_HARM -> R.string.overlay_why_self_harm
        Category.VIOLENCE_THREAT -> R.string.overlay_why_violence
        Category.IN_PERSON_MEETUP -> R.string.overlay_why_meetup
        Category.SUBSTANCE -> R.string.overlay_why_substance
    }

    /** Null or blank hides the view, so a caller without a category gets no empty line. */
    private fun setTextIfChanged(view: TextView, text: String?) {
        if (text.isNullOrBlank()) {
            view.visibility = GONE
            return
        }
        if (view.text?.toString() != text) view.text = text
        view.visibility = VISIBLE
    }

    /**
     * An unstyled TextView. Built with no default style so the host's theme — a service context
     * in the overlay, the app's Material theme in the preview — cannot restyle the card.
     */
    private fun text(bold: Boolean = false, color: Int? = null) =
        TextView(context, null, 0, 0).apply {
            if (bold) typeface = Typeface.DEFAULT_BOLD
            color?.let { setTextColor(context.getColor(it)) }
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    /**
     * One of the card's three buttons, each 48dp tall for the touch target with a 40dp pill
     * drawn inside it. Plain [Button]s with no default style, for the same reason as [text]:
     * the platform's grey button — and whatever tint a Material theme would lay over it — is
     * what made the old warning's actions hard to read.
     */
    private fun button(labelRes: Int, onClick: () -> Unit): Button =
        Button(context, null, 0, 0).apply {
            setText(labelRes)
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minWidth = dp(64)
            minimumWidth = dp(64)
            minHeight = dp(48)
            minimumHeight = dp(48)
            val (fill, textColor, ripple) = when (labelRes) {
                R.string.overlay_get_help -> Triple(
                    R.color.crisis_accent,
                    R.color.crisis_on_accent,
                    R.color.overlay_ripple_on_light,
                )
                R.string.overlay_remove -> Triple(
                    R.color.overlay_primary,
                    R.color.overlay_on_primary,
                    R.color.overlay_ripple_on_light,
                )
                else -> Triple(null, R.color.overlay_text_button, R.color.overlay_ripple_on_dark)
            }
            setTextColor(context.getColor(textColor))
            background = pill(fill?.let(context::getColor), context.getColor(ripple))
            // A filled pill wants more air around its label than a bare text button does.
            val side = if (fill != null) dp(20) else dp(14)
            setPadding(side, 0, side, 0)
            setOnClickListener { onClick() }
            // Clickable views default to key-focusable, and outside touch mode (a hardware
            // keyboard, or any key event) the first one took focus and drew a permanent grey
            // highlight on Ignore. This window never takes key focus anyway, and TalkBack's
            // accessibility focus does not depend on this flag.
            isFocusable = false
        }

    /**
     * A rounded pill with a ripple, inset vertically so a 48dp touch target draws as 40dp. A
     * null fill is a text button: nothing is drawn until pressed, and the ripple is still
     * clipped to the pill rather than spilling across the card.
     */
    private fun pill(fill: Int?, rippleColor: Int): Drawable {
        fun shape(color: Int) = InsetDrawable(
            GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(color)
            },
            0, dp(4), 0, dp(4),
        )
        val ripple = ColorStateList.valueOf(rippleColor)
        return if (fill != null) {
            RippleDrawable(ripple, shape(fill), null)
        } else {
            RippleDrawable(ripple, null, shape(Color.WHITE))
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val ENTRANCE_MS = 200L

        /** The icon disc: the accent at low alpha, so it tints the dark card without glowing. */
        const val DISC_ALPHA = 0.18f

        /** The hairline border. Enough to separate the card from a dark host, no more. */
        const val BORDER_ALPHA = 0.35f
    }
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
