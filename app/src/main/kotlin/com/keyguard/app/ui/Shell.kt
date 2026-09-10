package com.keyguard.app.ui

import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * One section of a tabbed screen.
 *
 * [create] is a factory rather than an instance, because a section is only ever built the first
 * time someone navigates to it — see [Shell.install]. The child app's Look section constructs a
 * real `KeyboardView` and runs the detection engine over a sample, which is not work to do on
 * launch for a tab most users open once.
 */
data class Section(
    @IdRes val itemId: Int,
    @StringRes val titleRes: Int,
    val create: () -> Fragment,
)

/**
 * A fragment that re-reads its state whenever it becomes the visible section.
 *
 * The reason this exists rather than each fragment overriding `onResume`: [Shell] switches
 * sections with `show`/`hide` rather than `replace`, so a hidden fragment stays RESUMED and
 * never gets another `onResume` when it comes back. Only [onHiddenChanged] fires. Miss that and
 * a section shows whatever it happened to be displaying when the user last left it — which for
 * the Protection tab means a stale answer to "am I protected", the one thing it must never get
 * wrong.
 *
 * `show`/`hide` is worth the extra rule. `replace` would rebuild the keyboard preview and rerun
 * the engine on every tab switch, and would lose scroll position everywhere.
 */
abstract class SectionFragment : Fragment() {

    /** Re-read everything this section displays. Called on entry and on every return to it. */
    abstract fun refresh()

    override fun onResume() {
        super.onResume()
        if (!isHidden) refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }
}

/**
 * Bottom-navigation plumbing, shared by the child and parent shells.
 *
 * Both screens are the same shape — a toolbar whose title follows the tab, a container, and a
 * navigation bar — and the only interesting part is the show/hide bookkeeping, which is exactly
 * the part worth writing once.
 */
object Shell {

    private const val STATE_SELECTED = "keyguard.shell.selected"

    /**
     * Wires [nav] to [sections], restoring the previously selected tab across recreation.
     *
     * @param savedSelection the value returned by [selectedItemId] before the activity was
     *   destroyed, or null on a cold start. Restoring it matters more than it sounds: without
     *   it a rotation drops a parent back to Children from the middle of editing Rules.
     */
    fun install(
        activity: AppCompatActivity,
        nav: BottomNavigationView,
        toolbar: MaterialToolbar,
        @IdRes containerId: Int,
        sections: List<Section>,
        savedSelection: Int? = null,
    ) {
        val manager = activity.supportFragmentManager
        val start = savedSelection?.takeIf { id -> sections.any { it.itemId == id } }
            ?: sections.first().itemId

        // Selected *before* the listener is attached, so the initial selection does not fire a
        // callback. This ordering is load-bearing: assigning selectedItemId with a listener
        // already installed runs one transaction, and the explicit show below would then run a
        // second one before the first had executed — and because `commit` is asynchronous,
        // `findFragmentByTag` in that second pass would still see nothing and add a *duplicate*
        // fragment underneath the first. Two Protection tabs stacked, one of them stale.
        nav.selectedItemId = start

        nav.setOnItemSelectedListener { item ->
            val section = sections.firstOrNull { it.itemId == item.itemId }
                ?: return@setOnItemSelectedListener false
            show(manager, containerId, sections, section)
            toolbar.title = activity.getString(section.titleRes)
            true
        }
        // Reselecting the current tab is a no-op rather than a rebuild. Without this, tapping
        // the tab you are already on re-runs the transaction and, on the Look tab, rebuilds the
        // keyboard preview for no reason.
        nav.setOnItemReselectedListener { }

        val section = sections.first { it.itemId == start }
        show(manager, containerId, sections, section)
        toolbar.title = activity.getString(section.titleRes)
    }

    fun selectedItemId(nav: BottomNavigationView): Int = nav.selectedItemId

    fun saveSelection(outState: android.os.Bundle, nav: BottomNavigationView) {
        outState.putInt(STATE_SELECTED, nav.selectedItemId)
    }

    fun restoreSelection(savedInstanceState: android.os.Bundle?): Int? =
        savedInstanceState?.getInt(STATE_SELECTED)?.takeIf { it != 0 }

    /**
     * Makes [target] the visible section, creating it on first use.
     *
     * Fragments are tagged by their menu item id, which is stable across recreation, so a
     * fragment restored by the system is found here rather than being added a second time
     * underneath the one already on screen.
     */
    private fun show(
        manager: FragmentManager,
        @IdRes containerId: Int,
        sections: List<Section>,
        target: Section,
    ) {
        val transaction = manager.beginTransaction()

        for (section in sections) {
            val tag = section.itemId.toString()
            val existing = manager.findFragmentByTag(tag)

            when {
                section.itemId == target.itemId && existing == null ->
                    transaction.add(containerId, section.create(), tag)

                section.itemId == target.itemId -> transaction.show(existing!!)

                existing != null -> transaction.hide(existing)
            }
        }

        // Committing without state loss protection would crash on a tab switch that races an
        // activity going to the background - rare, and entirely avoidable.
        transaction.commitAllowingStateLoss()
    }

    /** Tells every section currently built to re-read its state. */
    fun refreshAll(manager: FragmentManager) {
        for (fragment in manager.fragments) {
            (fragment as? SectionFragment)?.takeIf { it.isAdded }?.refresh()
        }
    }
}
