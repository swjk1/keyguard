package com.keyguard.app.ui.child

import android.content.Context
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.keyguard.app.R
import com.keyguard.app.family.Supervision

/**
 * A screen of the child app, and the title its toolbar shows.
 *
 * Declared by the fragment rather than passed along with the transaction, because the
 * toolbar has to be right after a rotation too, and after a rotation there is no transaction -
 * the FragmentManager restores the back stack on its own and the activity only gets to ask
 * what is now on top.
 */
interface ChildScreen {
    @get:StringRes
    val titleRes: Int
}

/**
 * Hub-and-detail navigation for the child app: one hub, detail screens pushed over it.
 *
 * Plain FragmentManager transactions rather than the Navigation component, to keep the
 * dependency list where it is. The rules that make that safe are all here, in one place:
 *
 * - **`replace` + `addToBackStack`, never `add`.** The FragmentManager then owns the whole
 *   stack, restores it itself on recreation, and Back pops it without any code of ours. `add`
 *   would leave the hub resumed underneath, still refreshing itself behind a detail screen.
 * - **The hub is only added on a fresh instance** - see `SetupActivity.onCreate`. Adding it
 *   again after a rotation is the classic way to end up with two of them stacked.
 * - **A second push of the screen already on top is dropped.** A double tap on a row lands two
 *   clicks before the first transaction has run, and without this check the child would have
 *   to press Back twice to leave a screen they opened once.
 */
object ChildNav {

    val CONTAINER_ID: Int get() = R.id.screenContainer

    fun push(activity: FragmentActivity, screen: Fragment) {
        val manager = activity.supportFragmentManager
        // A transaction after onSaveInstanceState would either throw or be lost; a tap that
        // races the activity going to the background is better dropped than half-applied.
        if (manager.isStateSaved) return

        // Pending transactions are run first, so the double-tap check below sees the screen
        // the first tap pushed rather than the hub it was pushed over.
        manager.executePendingTransactions()
        val top = manager.findFragmentById(CONTAINER_ID)
        if (top != null && top::class == screen::class) return

        manager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(CONTAINER_ID, screen, screen::class.java.simpleName)
            .addToBackStack(screen::class.java.simpleName)
            .commit()
    }

    /**
     * Whether the Family screen has anything to offer: a server to pair with, or a pairing.
     *
     * A screen that cannot do anything is removed rather than shown disabled. In the solo
     * flavor that is the permanent state, and an app should not advertise a section the build
     * does not contain. A *paired* device always keeps it, whatever the endpoint says, because
     * that screen carries the disclosure a monitored user is owed, and a misconfigured build
     * must never be a way to make supervision invisible.
     */
    fun familyReachable(context: Context): Boolean =
        context.getString(R.string.verify_base_url).isNotBlank() ||
            Supervision(context).isSupervised
}

/** Pushes [screen] over the current one. */
fun Fragment.openScreen(screen: Fragment) = ChildNav.push(requireActivity(), screen)
