package com.keyguard.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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
        if (state is OverlayState.Hidden) {
            hide()
            return
        }


        val wantsShade = state is OverlayState.Warning && state.shaded
        val shadeRect = if (wantsShade) {
            OverlayAnchor.shadeRect(screenWidth(), screenHeight(), imeBounds)
        } else {
            null
        }

        // The shade goes up first and comes down last, so there is never a frame in which
        // typing is possible while the warning claims it is paused.
        if (shadeRect != null) showShade(shadeRect) else hideShade()
        shadeActive = shadeAttached

        warningView.render(state, shadeActive)
        showWarning(position, imeBounds, fieldBounds)
    }


    fun hide() {
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
            rect.height,
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


    private fun density(): Float = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density()).roundToInt()
}
