package com.keyguard.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.keyguard.app.settings.Appearance
import com.keyguard.app.settings.OverlayPosition
import com.keyguard.app.settings.Settings
import kotlin.math.roundToInt

/**
 * Owns the two floating windows and keeps them in step with an [OverlayState].
 *
 * Two windows rather than one, because they have opposite requirements. The warning must not
 * take touches away from the app underneath except on its own buttons, and must never take
 * focus. The shade exists precisely to take touches. Trying to be both in one window means
 * either a warning whose buttons do not work or a shade that does not block.
 *
 * ### The flag that matters
 *
 * `FLAG_NOT_FOCUSABLE` on the warning window is load-bearing and easy to lose. Without it the
 * overlay takes input focus the moment it appears, the host app's field loses it, and the
 * keyboard closes — which from the user's side looks exactly like the app they were typing in
 * has crashed. The buttons still work without focus because touch dispatch does not require it;
 * only text input does, and this window has no text input.
 *
 * Every `WindowManager` call here is wrapped, because they throw for reasons outside our
 * control — the permission revoked while running, a token that has gone stale after the service
 * was rebound, a manufacturer limit on overlay windows. A safety overlay that crashes its own
 * accessibility service takes the protection down with it, so the failure mode is always
 * "no overlay this time" rather than a thrown exception.
 */
class OverlayHost(
    private val context: Context,
    listener: OverlayActionListener,
) {

    private val windows = context.getSystemService(WindowManager::class.java)

    private val warningView = WarningOverlayView(context, listener)
    private val shadeView = KeyboardShadeView(context)

    private var warningAttached = false
    private var shadeAttached = false

    /** Whether the keyboard is actually covered right now. Drives the paused notice. */
    var shadeActive: Boolean = false
        private set

    fun updateAppearance(appearance: Appearance, opacityPercent: Int) {
        warningView.updateAppearance(appearance)
        // Clamped against the same bounds the settings slider uses, so a value written by an
        // older build cannot produce an invisible warning that still shades the keyboard.
        //
        // The card's *fill* only. This used to set the whole view's alpha, which faded the
        // words and buttons along with the background and let the chat underneath show
        // through the text — at the default 96% as well as at the minimum.
        warningView.setCardOpacity(
            opacityPercent.coerceIn(Settings.MIN_OVERLAY_OPACITY, Settings.MAX_OVERLAY_OPACITY),
        )
    }

    /**
     * Makes the windows match [state].
     *
     * @param imeBounds the keyboard window if the platform reported one, else null.
     */
    fun render(
        state: OverlayState,
        position: OverlayPosition,
        imeBounds: OverlayAnchor.Bounds?,
        fieldBounds: OverlayAnchor.Bounds? = null,
    ) {
        mainHandler.removeCallbacks(releaseHeldIme)
        heldRender = null
        if (state is OverlayState.Hidden) {
            hide()
            return
        }

        val ime = steadyImeBounds(imeBounds)
        if (ime != null && imeBounds == null) {
            // Running on borrowed bounds. Re-render without them once the grace period is up,
            // so a keyboard that really has gone cannot leave a shade behind even if nothing
            // else happens to trigger a render.
            heldRender = HeldRender(state, position, fieldBounds)
            mainHandler.postAtTime(releaseHeldIme, lastImeAtMs + IME_HOLD_MS + 1)
        }

        val wantsShade = state is OverlayState.Warning && state.shaded
        val shadeRect = if (wantsShade) {
            OverlayAnchor.shadeRect(screenWidth(), screenHeight(), ime)
        } else {
            null
        }

        // The shade goes up first and comes down last, so there is never a frame in which
        // typing is possible while the warning claims it is paused.
        if (shadeRect != null) showShade(shadeRect) else hideShade()
        shadeActive = shadeAttached

        warningView.render(state, shadeActive)
        showWarning(position, ime, fieldBounds)
    }

    /**
     * The keyboard bounds to use for this render: the reported ones, or — for a short grace
     * period after the last real report — the last ones that were reported.
     *
     * The keyboard window drops out of the accessibility window list now and then while the
     * user types (see [OverlayAnchor.placeWarning]). The biggest cause was the shade itself,
     * fixed in [showShade]; what remains are short gaps, seen with Gboard while it resizes its
     * window for a suggestion popup. The warning's placement already tolerates a gap by falling
     * back to the field. The shade cannot: without this it comes down for the gap and goes
     * back up after it, dimming and undimming the whole keyboard and growing and shrinking the
     * card as the paused notice comes and goes.
     *
     * The hold is deliberately short and time-boxed rather than "until the keyboard comes back":
     * the cost of holding too long is a shade over app content where the keyboard used to be,
     * which [releaseHeldIme] bounds to [IME_HOLD_MS].
     */
    private fun steadyImeBounds(reported: OverlayAnchor.Bounds?): OverlayAnchor.Bounds? {
        val now = SystemClock.uptimeMillis()
        if (reported != null) {
            lastImeBounds = reported
            lastImeAtMs = now
            return reported
        }
        val held = lastImeBounds ?: return null
        if (now - lastImeAtMs <= IME_HOLD_MS) return held
        lastImeBounds = null
        return null
    }

    /** What to re-render with once borrowed keyboard bounds expire. */
    private data class HeldRender(
        val state: OverlayState,
        val position: OverlayPosition,
        val fieldBounds: OverlayAnchor.Bounds?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var heldRender: HeldRender? = null
    private var lastImeBounds: OverlayAnchor.Bounds? = null
    private var lastImeAtMs = 0L

    private val releaseHeldIme = Runnable {
        val held = heldRender ?: return@Runnable
        render(held.state, held.position, imeBounds = null, fieldBounds = held.fieldBounds)
    }

    fun hide() {
        mainHandler.removeCallbacks(releaseHeldIme)
        heldRender = null
        // A keyboard seen before the warning went away says nothing about the next one.
        lastImeBounds = null
        hideShade()
        shadeActive = false
        detach(warningView) { warningAttached = false }
    }

    /** Called when the service stops. Leaving a window attached would outlive its own context. */
    fun destroy() {
        hide()
    }

    private fun showWarning(
        position: OverlayPosition,
        imeBounds: OverlayAnchor.Bounds?,
        fieldBounds: OverlayAnchor.Bounds?,
    ) {
        val placement = OverlayAnchor.placeWarning(
            position = position.toAnchor(),
            screenHeight = screenHeight(),
            imeBounds = imeBounds,
            fallbackBottomMarginPx = dp(OverlayAnchor.FALLBACK_BOTTOM_MARGIN_DP),
            fieldBounds = fieldBounds,
        )

        // Sized to the card, not to the screen. The card floats with a margin either side, and
        // a MATCH_PARENT window would own those margins too: a window takes every touch inside
        // its rectangle whether anything is drawn there or not, so taps on the chat beside the
        // card would silently land on nothing. What remains transparent is the view's small
        // shadow inset, which is the least the elevation shadow needs to draw into.
        val screenWidth = screenWidth()
        val width = OverlayCardMetrics.windowWidthPx(screenWidth, density())
        val geometry = WindowGeometry(
            gravity = if (placement.fromTop) {
                Gravity.TOP or Gravity.START
            } else {
                Gravity.BOTTOM or Gravity.START
            },
            x = OverlayCardMetrics.windowX(screenWidth, width),
            // At the top, clear the status bar. OverlayAnchor's 0 means "the top of the usable
            // screen"; the old full-width band could sit under the clock and still be read, but
            // a floating card tucked under the status bar icons looks broken.
            y = if (placement.fromTop) statusBarHeight() else placement.bottomMarginPx,
            width = width,
        )

        // The service renders on every keystroke. An update with nothing to change still costs
        // the window a relayout, so an unchanged placement is not sent at all.
        if (warningAttached && geometry == lastWarningGeometry) return

        val params = WindowManager.LayoutParams(
            geometry.width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps the host's keyboard open; see the class note. LAYOUT_IN_SCREEN
            // makes the y offset measured against the whole screen rather than the app area, so
            // the placement maths matches what OverlayAnchor computed from the IME bounds.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = geometry.gravity
            x = geometry.x
            y = geometry.y
        }

        attachOrUpdate(warningView, params, warningAttached) { warningAttached = it }
        lastWarningGeometry = if (warningAttached) geometry else null
    }

    /** The parts of the warning window's layout that vary, so an unchanged one can be skipped. */
    private data class WindowGeometry(val gravity: Int, val x: Int, val y: Int, val width: Int)

    private var lastWarningGeometry: WindowGeometry? = null

    /**
     * The status bar's height, or 0 if nothing will say. Wrapped like every other window call
     * here: window metrics from a service context are not guaranteed on every build, and a
     * card a little too high is far better than a service that throws.
     */
    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                windows?.currentWindowMetrics?.windowInsets
                    ?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                    ?.top
            }.getOrNull()?.let { return it }
        }
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun showShade(rect: OverlayAnchor.Bounds) {
        val params = WindowManager.LayoutParams(
            rect.right - rect.left,
            // One pixel short of the rectangle, at the bottom edge of the screen.
            //
            // The accessibility window list leaves out any window that is completely covered by
            // windows above it, and a shade covering exactly the keyboard's area is exactly
            // that. So the moment the shade went up, the keyboard vanished from the list, the
            // next render could not find it and took the shade down, the keyboard reappeared,
            // and the shade went back up: on and off on alternate keystrokes, dimming and
            // undimming the whole keyboard. One uncovered row keeps the keyboard reported. It
            // is the last row of the screen — the gesture-navigation strip, never a key — so
            // the block itself is unaffected.
            (rect.height - 1).coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Deliberately *without* NOT_TOUCHABLE: absorbing touches is this window's entire
            // purpose. Still not focusable, so the host keyboard stays open underneath and the
            // child can see what they are being asked to remove.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
        }

        attachOrUpdate(shadeView, params, shadeAttached) { shadeAttached = it }
    }

    private fun hideShade() = detach(shadeView) { shadeAttached = false }

    private fun attachOrUpdate(
        view: View,
        params: WindowManager.LayoutParams,
        attached: Boolean,
        setAttached: (Boolean) -> Unit,
    ) {
        val manager = windows ?: return
        runCatching {
            if (attached) manager.updateViewLayout(view, params) else manager.addView(view, params)
            setAttached(true)
        }.onFailure {
            // An add that threw leaves nothing attached; an update that threw usually means the
            // view is already gone. Either way the flag has to come down or every later call
            // takes the update branch against a window that does not exist.
            setAttached(false)
        }
    }

    private fun detach(view: View, clear: () -> Unit) {
        val manager = windows ?: return
        runCatching { manager.removeView(view) }
        clear()
    }

    @Suppress("DEPRECATION")
    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    @Suppress("DEPRECATION")
    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private companion object {
        /**
         * How long the last reported keyboard bounds stand in for a missing report. Covers the
         * gaps measured while typing on the emulator (up to about 300ms), and short enough that
         * a keyboard that really closed leaves its shade behind for under half a second.
         */
        const val IME_HOLD_MS = 400L
    }

    private fun density(): Float = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density()).roundToInt()
}
