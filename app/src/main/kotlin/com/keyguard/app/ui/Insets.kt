package com.keyguard.app.ui

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps content out from under the status and navigation bars.
 *
 * Android 15 made edge-to-edge mandatory for apps targeting SDK 35 and above, and this one
 * targets 36. Every activity therefore draws behind the system bars whether it asked to or not,
 * and an app that does not apply the insets renders its toolbar title on top of the clock. That
 * is exactly what the first install on a Pixel 9 Pro showed: "Parent" printed over the status
 * bar icons.
 *
 * Worth noting why no amount of harness work would have caught it. `backend/app/preview` draws
 * a phone frame in a browser — there is no system bar to collide with, so the harness renders
 * these screens correctly and the device does not. It is a good example of the class of bug
 * that only a device finds, and the reason "it has never run on hardware" was worth stating as
 * a risk rather than a formality.
 *
 * The `displayCutout` type is included alongside `systemBars` because a landscape phone with a
 * punch-hole or notch puts the cutout on the side, where the status bar inset is zero.
 */
fun applySystemBarInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
        val bars: Insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)

        // Consumed, not passed on. The root has absorbed the whole inset as padding, so a child
        // that applied it again would double the gap — and the bottom navigation in `Shell` is
        // exactly the kind of child that would.
        WindowInsetsCompat.CONSUMED
    }

    // A listener set after the first pass has already happened would not fire until something
    // else triggered one, leaving the very first frame overlapping.
    ViewCompat.requestApplyInsets(root)
}
