package com.keyguard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.keyguard.app.R
import com.keyguard.app.settings.Appearance

/** Callbacks the IME implements. */
interface KeyboardListener {
    fun onText(text: String)
    fun onBackspace()
    fun onEnter()
    fun onNextKeyboard()
}

/**
 * The key grid, built programmatically from [KeyboardLayout].
 *
 * Views rather than Compose: an IME is memory-constrained (severely so on iOS, where the
 * extension ceiling is around 30-50MB) and this needs to inflate fast on every field focus.
 *
 * Geometry comes from [Appearance] rather than resources so the user can size it, and so the
 * settings screen can render a live preview using this same class.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val listener: KeyboardListener,
    appearance: Appearance = Appearance.DEFAULT,
) : LinearLayout(context) {

    private var appearance: Appearance = appearance.sanitized()
    private var bottomInsetPx = 0

    private var plane = Plane.LETTERS
    private var shifted = false
    private var capsLocked = false

    private val handler = Handler(Looper.getMainLooper())
    private var backspaceRepeat: Runnable? = null
    private var longPressRunnable: Runnable? = null

    private val alternatesPopup = AlternatesPopup(context)

    /** Mirrors the user's haptics preference; re-read whenever appearance is applied. */
    var hapticsEnabled: Boolean = true

    private val characterKeys = mutableListOf<Pair<TextView, Key.Character>>()
    private var shiftKeyView: TextView? = null

    /**
     * Keys that grey out while input is blocked. Backspace and the keyboard switcher are
     * deliberately absent: one is how the user resolves the warning and the other is how they
     * leave, so showing either as inert would be a lie.
     *
     * Refusal itself lives in the IME, not here — alpha does not stop a touch, and routing
     * every entry point (keys, emoji, paste, suggestions) through one guard there is what
     * makes the block actually hold.
     */
    private val dimmedWhenBlocked = mutableListOf<View>()
    private var inputBlocked = false

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.keyboard_background))
        applyPadding()
        render()
    }

    /** Replaces the sizing and re-renders. Safe to call while visible. */
    fun updateAppearance(newAppearance: Appearance) {
        val clean = newAppearance.sanitized()
        if (clean == appearance) return
        appearance = clean
        applyPadding()
        render()
    }

    /** Extra bottom inset so the last row clears the navigation bar or gesture area. */
    fun applyBottomInset(insetPx: Int) {
        if (insetPx == bottomInsetPx) return
        bottomInsetPx = insetPx
        applyPadding()
    }

    /** The height this grid wants, in pixels, excluding the warning strip. */
    fun desiredHeightPx(): Int = dp(appearance.keyboardHeightDp) + bottomInsetPx

    private fun applyPadding() {
        setPadding(
            dp(appearance.sidePaddingDp),
            dp(PADDING_TOP_DP),
            dp(appearance.sidePaddingDp),
            dp(appearance.bottomPaddingDp) + bottomInsetPx,
        )
    }

    /**
     * Greys out the keys that cannot currently produce text. The IME calls this alongside
     * rendering the warning strip, so the two always agree about whether input is live.
     */
    fun setInputBlocked(blocked: Boolean) {
        if (blocked == inputBlocked) return
        inputBlocked = blocked
        applyBlockedState()
    }

    private fun applyBlockedState() {
        val alpha = if (inputBlocked) BLOCKED_KEY_ALPHA else 1f
        for (view in dimmedWhenBlocked) view.alpha = alpha
    }

    private fun render() {
        removeAllViews()
        characterKeys.clear()
        dimmedWhenBlocked.clear()
        shiftKeyView = null

        for (row in KeyboardLayout.rowsFor(plane)) {
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                // Equal row weights give every row the same height; the per-key weights
                // inside each row are what align columns.
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (key in row) {
                rowView.addView(buildKey(key))
            }
            addView(rowView)
        }
        applyShiftState()
        // A plane switch rebuilds the grid, so the dimming has to be reapplied to the new
        // views or blocked input would silently look live again.
        applyBlockedState()
    }

    private fun buildKey(key: Key): View {
        val gap = dp(appearance.keyGapDp)

        // A spacer only reserves width. Giving it no background is what makes the middle
        // row read as indented rather than as two odd empty keys.
        if (key is Key.Spacer) {
            return View(context).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight)
            }
        }

        val view = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (key is Key.Character) {
                    appearance.keyTextSp.toFloat()
                } else {
                    modifierTextSp()
                },
            )
            setTextColor(context.getColor(R.color.key_text))
            setBackgroundResource(
                when (key) {
                    is Key.Character, Key.Space -> R.drawable.key_background
                    else -> R.drawable.key_background_modifier
                },
            )
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight).apply {
                setMargins(gap, dp(appearance.keyGapDp + 1), gap, dp(appearance.keyGapDp + 1))
            }
            isClickable = true
            isFocusable = false
        }

        when (key) {
            is Key.Character -> {
                view.text = key.lower
                characterKeys += view to key
                attachCharacterTouch(view, key)
            }

            Key.Space -> {
                view.text = ""
                view.contentDescription = context.getString(R.string.key_space_description)
                view.setOnClickListener { emitText(" ") }
            }

            Key.Enter -> {
                view.text = context.getString(R.string.key_enter)
                view.setTypeface(null, Typeface.BOLD)
                view.setBackgroundResource(R.drawable.key_background_accent)
                view.setOnClickListener {
                    performHaptic()
                    listener.onEnter()
                }
            }

            Key.Backspace -> {
                view.text = context.getString(R.string.key_backspace)
                attachBackspaceRepeat(view)
            }

            Key.Shift -> {
                view.text = context.getString(R.string.key_shift)
                shiftKeyView = view
                view.setOnClickListener {
                    performHaptic()
                    toggleShift()
                }
            }

            is Key.SwitchPlane -> {
                view.text = key.label
                view.setOnClickListener {
                    performHaptic()
                    plane = key.target
                    shifted = false
                    capsLocked = false
                    render()
                }
            }

            Key.NextKeyboard -> {
                view.text = context.getString(R.string.key_next_keyboard)
                view.setOnClickListener {
                    performHaptic()
                    listener.onNextKeyboard()
                }
            }

            is Key.Spacer -> Unit // handled above
        }

        if (key != Key.Backspace && key != Key.NextKeyboard) dimmedWhenBlocked += view
        return view
    }

    /** Modifier labels are words and symbols, so they track key size but stay smaller. */
    private fun modifierTextSp(): Float =
        (appearance.keyTextSp * MODIFIER_TEXT_RATIO).coerceIn(10f, 20f)

    /**
     * Character keys need raw touch handling rather than a click listener, because a long
     * press has to open the alternates popup, track the finger across it, and commit whichever
     * option the finger is on at release.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachCharacterTouch(view: TextView, key: Key.Character) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    scheduleLongPress(v, key)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (alternatesPopup.isShowing) alternatesPopup.trackTouch(event.rawX)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    cancelPendingLongPress()
                    if (alternatesPopup.isShowing) {
                        // Committing the highlighted alternate, not the base character.
                        alternatesPopup.selected?.let { emitText(it) }
                        alternatesPopup.dismiss()
                    } else {
                        emitCharacter(key)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    cancelPendingLongPress()
                    alternatesPopup.dismiss()
                    true
                }

                else -> false
            }
        }
    }

    private fun scheduleLongPress(anchor: View, key: Key.Character) {
        cancelPendingLongPress()
        // Digits are only offered on the letters plane, where they save a plane switch.
        val includeDigits = plane == Plane.LETTERS
        val alternates = Alternates.forKey(key.lower, includeDigits)
        if (alternates.isEmpty()) return

        val runnable = Runnable {
            performHaptic()
            alternatesPopup.show(anchor, alternates, upper = shifted || capsLocked)
        }
        longPressRunnable = runnable
        handler.postDelayed(runnable, LONG_PRESS_DELAY_MS)
    }

    /** Named to avoid colliding with [View.cancelLongPress], which does something different. */
    private fun cancelPendingLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun emitCharacter(key: Key.Character) {
        emitText(if (shifted || capsLocked) key.upper else key.lower)
        if (shifted && !capsLocked) {
            shifted = false
            applyShiftState()
        }
    }

    private fun emitText(text: String) {
        performHaptic()
        listener.onText(text)
    }

    /**
     * Keypress feedback. Cheap, and its absence is one of the first things that makes a
     * keyboard feel unfinished.
     */
    private fun performHaptic() {
        if (!hapticsEnabled) return
        performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )
    }

    private var lastShiftTapAt = 0L

    /** Single tap shifts the next character; double tap within the timeout locks caps. */
    private fun toggleShift() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTapAt < DOUBLE_TAP_MS) {
            capsLocked = !capsLocked
            shifted = capsLocked
        } else {
            if (capsLocked) {
                capsLocked = false
                shifted = false
            } else {
                shifted = !shifted
            }
        }
        lastShiftTapAt = now
        applyShiftState()
    }

    private fun applyShiftState() {
        val upper = shifted || capsLocked
        for ((view, key) in characterKeys) {
            view.text = if (upper) key.upper else key.lower
        }
        shiftKeyView?.apply {
            text = context.getString(
                when {
                    capsLocked -> R.string.key_shift_locked
                    shifted -> R.string.key_shift_active
                    else -> R.string.key_shift
                },
            )
            setBackgroundResource(
                if (upper) R.drawable.key_background_accent else R.drawable.key_background_modifier,
            )
        }
    }

    /** Hold-to-repeat, which users expect and notice the absence of immediately. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachBackspaceRepeat(view: TextView) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    // Only the initial press buzzes; repeating would vibrate continuously
                    // while the key is held.
                    performHaptic()
                    listener.onBackspace()
                    scheduleRepeat(INITIAL_REPEAT_DELAY_MS)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    cancelRepeat()
                    true
                }

                else -> false
            }
        }
    }

    private fun scheduleRepeat(delayMs: Long) {
        cancelRepeat()
        val runnable = object : Runnable {
            override fun run() {
                listener.onBackspace()
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        backspaceRepeat = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelRepeat() {
        backspaceRepeat?.let { handler.removeCallbacks(it) }
        backspaceRepeat = null
    }

    override fun onDetachedFromWindow() {
        cancelRepeat()
        cancelPendingLongPress()
        // A PopupWindow outliving its anchor leaks the window; dismiss before detaching.
        alternatesPopup.dismiss()
        super.onDetachedFromWindow()
    }

    /** Resets transient state when a new field gains focus. */
    fun resetForNewField() {
        if (plane != Plane.LETTERS) {
            plane = Plane.LETTERS
            render()
        }
        shifted = false
        capsLocked = false
        applyShiftState()
        setInputBlocked(false)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val INITIAL_REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
        const val DOUBLE_TAP_MS = 300L
        const val LONG_PRESS_DELAY_MS = 300L
        const val PADDING_TOP_DP = 6
        const val MODIFIER_TEXT_RATIO = 0.67f

        /** Low enough to read as unavailable, high enough that the letters stay legible. */
        const val BLOCKED_KEY_ALPHA = 0.35f
    }
}
