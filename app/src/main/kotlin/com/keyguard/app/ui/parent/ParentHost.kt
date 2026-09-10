package com.keyguard.app.ui.parent

import com.keyguard.app.family.FamilyClient
import com.keyguard.app.family.FamilyOverview
import com.keyguard.app.family.FamilyPolicy

/**
 * What the parent sections need from the shell that hosts them.
 *
 * The four sections all read the same family overview and all talk to the same server, so
 * fetching per section would mean four requests for one screenful of data and four different
 * answers on a flaky connection. The activity owns one client, one executor and one loaded
 * overview; sections read it and are told when it changes.
 *
 * An interface rather than a direct cast to the activity, so a section depends on the six things
 * it actually uses rather than on a class with a fragment manager and a navigation bar attached.
 * It is also what keeps the sections testable in principle — nothing here needs an Activity.
 *
 * Deliberately not a ViewModel. The state is one nullable object and one executor; the app has
 * no other use for the lifecycle library, and the reload-on-resume behaviour a parent screen
 * wants is the opposite of what a retained ViewModel gives you.
 */
interface ParentHost {

    /** Null when the build has no endpoint configured. Every section renders disabled then. */
    val client: FamilyClient?

    /** The last successful load, or null before the first one lands. */
    val overview: FamilyOverview?

    /** The policy the server last confirmed. Null until the first load. */
    val policy: FamilyPolicy?

    /**
     * Runs [work] off the main thread and delivers the result on it, skipping the callback if
     * the screen has gone away.
     *
     * Null always means "did not happen", whether that was a throw or the client's own null.
     */
    fun <T> background(work: () -> T?, onResult: (T?) -> Unit)

    /** Re-fetches the overview and tells every built section about it. */
    fun reload()

    /** Called after a sign-out or an account deletion, to drop back to the auth panel. */
    fun onSignedOut()
}
