package com.keyguard.app.overlay

/**
 * Where on screen the floating warning and the keyboard shade go.
 *
 * This is the part of the overlay design with no equivalent in the IME, and the part most
 * likely to look wrong on a device nobody tested. The keyboard owned a fixed row directly above
 * its own keys and could never be in the wrong place; a floating window has to *find* that same
 * spot on top of somebody else's keyboard, whose height it does not control and cannot assume.
 *
 * The answer is to ask the platform. An accessibility service with
 * `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` can enumerate windows and read the bounds of the one whose
 * type is `TYPE_INPUT_METHOD`, which gives the keyboard's exact top edge whatever keyboard it is
 * and whatever the user has done to its height. That is what makes "overlay on top of the
 * existing Google keyboard" a real position rather than a guess.
 *
 * **When the platform will not say, this degrades rather than guesses.** A missing IME window is
 * common — some keyboards do not report, some OEM builds withhold the window list — and the
 * temptation is to assume a typical keyboard height and shade the bottom 45% of the screen. That
 * is the wrong trade: guessing wrong means covering the message someone is trying to read, or
 * claiming typing is paused while it plainly is not. Instead the warning falls back to a fixed
 * offset from the bottom, and the shade simply does not appear.
 *
 * Pure, so the fallbacks are testable without a device — which is the only way they ever get
 * tested, since reproducing "keyboard that does not report its window" on demand is not
 * something a developer can do at will.
 */
object OverlayAnchor {

    /** A window rectangle, in screen pixels. Local so this file stays free of Android types. */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height: Int get() = bottom - top
        val isUsable: Boolean get() = right > left && bottom > top
    }

    /** Where the user has asked for the warning to sit. */
    enum class Position {
        /**
         * Directly above the keyboard, where the IME's own strip used to be. The default,
         * because it is where someone typing is already looking.
         */
        ABOVE_KEYBOARD,

        /**
         * Pinned to the top of the screen.
         *
         * Worth offering rather than being a fallback nobody chose: on a short screen, or with
         * a tall keyboard and a chat app whose latest message sits just above it, a banner
         * above the keyboard covers the thing being replied to. At the top it never does.
         */
        SCREEN_TOP,
    }

    data class Placement(
        /** Distance from the bottom of the screen, or 0 when [fromTop] is set. */
        val bottomMarginPx: Int,
        val fromTop: Boolean,
    )

    /**
     * Where to put the warning.
     *
     * @param imeBounds the keyboard window, or null when the platform did not report one.
     * @param fieldBounds the field being typed into, when the service could read it. See below.
     * @param fallbackBottomMarginPx used when the keyboard cannot be located. Sized by the
     *   caller from a dp value, so it scales with density rather than being a pixel constant.
     */
    fun placeWarning(
        position: Position,
        screenHeight: Int,
        imeBounds: Bounds?,
        fallbackBottomMarginPx: Int,
        fieldBounds: Bounds? = null,
    ): Placement {
        if (position == Position.SCREEN_TOP) return Placement(bottomMarginPx = 0, fromTop = true)

        val keyboardTop = imeBounds?.takeIf { it.isUsable }?.top
        // A reported top of 0, or one below the bottom of the screen, means the window list
        // gave us something that is not a visible keyboard. Treated as "no answer" rather than
        // trusted, since either would place the warning somewhere absurd.
        if (keyboardTop == null || keyboardTop <= 0 || keyboardTop >= screenHeight) {
            return Placement(bottomMarginPx = fallbackBottomMarginPx, fromTop = false)
        }

        // Clear the *field*, not just the keyboard.
        //
        // Sitting exactly on the keyboard's top edge is the obvious placement and it is wrong:
        // in every chat app the composer occupies precisely that strip, so the warning lands on
        // top of the text it is asking the user to look at and remove. Found in the browser
        // harness, where it is immediately visible and on a device is easy to miss because the
        // warning looks correctly positioned right up until you try to act on it.
        //
        // The field's own top edge is preferred when the service could read it, falling back to
        // the keyboard edge when it could not — which is no worse than before.
        val fieldTop = fieldBounds?.takeIf { it.isUsable }?.top
        val anchorTop = when {
            fieldTop == null -> keyboardTop
            fieldTop <= 0 || fieldTop > keyboardTop -> keyboardTop
            // A field taller than half the screen is a full-screen editor, not a composer;
            // anchoring above it would push the warning off the top.
            keyboardTop - fieldTop > screenHeight / 2 -> keyboardTop
            else -> fieldTop
        }
        return Placement(bottomMarginPx = screenHeight - anchorTop, fromTop = false)
    }

    /**
     * The rectangle to cover so typing genuinely stops, or null when it cannot be determined.
     *
     * Null is a real answer and callers must handle it as one: it means the block could not be
     * enforced, the warning must not claim typing is paused, and the child is left with a
     * warning they can type past. That is worse than the IME's guarantee and is stated plainly
     * in the README rather than papered over — an overlay cannot refuse a keystroke the way a
     * keyboard that owns the keys can.
     */
    fun shadeRect(
        screenWidth: Int,
        screenHeight: Int,
        imeBounds: Bounds?,
    ): Bounds? {
        val bounds = imeBounds?.takeIf { it.isUsable } ?: return null
        if (bounds.top <= 0 || bounds.top >= screenHeight) return null
        // Widened to the full screen width and extended to the bottom edge, rather than using
        // the reported rectangle exactly. A keyboard that insets itself would otherwise leave
        // live strips down either side, which is the whole block defeated by a few pixels.
        return Bounds(left = 0, top = bounds.top, right = screenWidth, bottom = screenHeight)
    }

    /** Fallback distance from the bottom, in dp, when no keyboard window is reported. */
    const val FALLBACK_BOTTOM_MARGIN_DP = 280
}
