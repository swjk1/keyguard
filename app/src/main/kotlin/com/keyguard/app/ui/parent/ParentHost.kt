package com.keyguard.app.ui.parent

import androidx.fragment.app.Fragment
import com.keyguard.app.family.FamilyClient
import com.keyguard.app.family.FamilyOverview
import com.keyguard.app.family.FamilyPolicy

/**
 * What the parent screens need from the shell that hosts them.
 *
 * Every screen reads the same family overview and talks to the same server, so fetching per
 * screen would mean several requests for one screenful of data and several different answers
 * on a flaky connection. The activity owns one client, one executor and one loaded overview;
 * screens read it and are told when it changes.
 *
 * It also owns navigation, because the rules for it live in one place: a detail screen is
 * pushed over the tab it came from and popped back to it, and nothing else in the app
 * touches the back stack. A screen asks to [open] another rather than running a transaction
 * of its own, which is how the duplicate-fragment traps described in `Shell` stay closed.
 *
 * An interface rather than a direct cast to the activity, so a screen depends on the things it
 * actually uses rather than on a class with a fragment manager and a navigation bar attached.
 *
 * Deliberately not a ViewModel. The state is one nullable object and one executor; the app has
 * no other use for the lifecycle library, and the reload-on-resume behaviour a parent screen
 * wants is the opposite of what a retained ViewModel gives you.
 */
interface ParentHost {

    /** Null when the build has no endpoint configured. Every screen renders disabled then. */
    val client: FamilyClient?

    /** The last successful load, or null before the first one lands. */
    val overview: FamilyOverview?

    /** The policy the server last confirmed. Null until the first load. */
    val policy: FamilyPolicy?

    /**
     * True when the most recent load failed. Together with a null [overview] it means "we
     * have never been able to reach the server", which is an error state worth drawing; with
     * an overview loaded it only means the data on screen may be a little old.
     */
    val lastLoadFailed: Boolean

    /**
     * Runs [work] off the main thread and delivers the result on it, skipping the callback if
     * the screen has gone away.
     *
     * Null always means "did not happen", whether that was a throw or the client's own null.
     */
    fun <T> background(work: () -> T?, onResult: (T?) -> Unit)

    /**
     * Re-fetches the overview and tells every built screen about it.
     *
     * @param visible true when the parent asked for this (Refresh, Try again), so the shell
     *   shows progress; false for the background poll on the Add child screen.
     */
    fun reload(visible: Boolean = false)

    /** Pushes a detail screen over the current tab. Back returns to the tab. */
    fun open(screen: Fragment)

    /** Opens a child's page. */
    fun openChild(installId: String) = open(ChildDetailFragment.newInstance(installId))

    /**
     * Replaces the top detail screen with [screen], so back skips the one being replaced -
     * how Add a child hands over to the new child's page without leaving a spent pairing code
     * one back-press away.
     */
    fun replaceTop(screen: Fragment)

    /** Leaves the top detail screen, as the back button would. */
    fun closeTop()

    /** A detail screen's title changed (a child's name arrived); redraw the toolbar. */
    fun onScreenChanged()

    /**
     * The recovery code shown once after registering or recovering, or null. Consumed rather
     * than peeked: it is shown once and never again.
     */
    fun consumeRecoveryCode(): String?

    /** Called after a sign-out or an account deletion, to drop back to the welcome panel. */
    fun onSignedOut()
}

/**
 * A screen that can name itself in the toolbar and say whether Refresh belongs there.
 *
 * Tabs and detail screens both implement it; the shell asks whichever is on top.
 */
interface ParentScreen {

    /** The toolbar title while this screen is on top. */
    fun screenTitle(): CharSequence

    /** Whether the toolbar's Refresh action makes sense here. */
    val offersRefresh: Boolean get() = false
}
