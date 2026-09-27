package com.keyguard.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.snackbar.Snackbar
import com.keyguard.app.databinding.ActivityParentBinding
import com.keyguard.app.family.FamilyClient
import com.keyguard.app.family.FamilyOverview
import com.keyguard.app.family.FamilyPolicy
import com.keyguard.app.family.ParentAlertNotice
import com.keyguard.app.family.ParentAuthOutcome
import com.keyguard.app.family.ParentSync
import com.keyguard.app.family.Supervision
import com.keyguard.app.ui.Section
import com.keyguard.app.ui.Shell
import com.keyguard.app.ui.parent.ActivityFragment
import com.keyguard.app.ui.parent.FamilyHomeFragment
import com.keyguard.app.ui.parent.ParentHost
import com.keyguard.app.ui.parent.ParentScreen
import com.keyguard.app.ui.parent.SettingsFragment
import java.util.concurrent.Executors

/**
 * The parent app.
 *
 * Two states. Signed out, the screen is the welcome panel and nothing else - no navigation bar
 * leading to sections that cannot load. Signed in, the panel goes and three tabs appear:
 * Family (each child, and a way to add one), Activity (every warning, newest first) and
 * Settings. Anything deeper - a child's page, Add a child, the warning rules, caregivers - is a
 * detail screen pushed over the tab it was opened from.
 *
 * ### Navigation, and why it is all here
 *
 * Tabs are switched by [Shell] with show/hide, so each keeps its scroll position and nothing
 * is rebuilt on a tab tap. A detail screen is *added* over the tab in the same container, the
 * tab is hidden in the same transaction, and the transaction goes on the back stack - so the
 * system back button pops it and the tab reappears exactly as it was, and back from a tab
 * with nothing pushed leaves the app. While a detail is showing the bottom bar hides and the
 * toolbar grows an up arrow; the back stack listener is the single place that decides both,
 * so the chrome cannot disagree with what is on screen.
 *
 * On recreation (rotation) the FragmentManager restores the tabs, the detail and the back
 * stack by itself. The one thing that must not happen then is [Shell.install] showing the
 * selected tab again underneath a restored detail - see its `showSelection` parameter.
 *
 * ### Data
 *
 * The activity keeps what every screen shares: one client, one background executor, and one
 * loaded overview. Fetching per screen would mean several requests for one screenful of data
 * and several different answers on a flaky connection.
 *
 * Every line the screens render is still a category, a severity and an outcome unless a parent
 * has explicitly raised the review scope - see ReviewScope. That is worth restating in the class
 * that would be the natural place to add a message field.
 */
class ParentActivity : AppCompatActivity(), ParentHost {

    private lateinit var binding: ActivityParentBinding
    private lateinit var supervision: Supervision

    private var familyClient: FamilyClient? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var loadedOverview: FamilyOverview? = null
    private var loadedPolicy: FamilyPolicy? = null
    private var loadFailed = false
    private var navigationInstalled = false

    /**
     * The tab on screen, as Shell last reported it. Not read from the navigation bar: its
     * selection listener runs *before* the bar records the new item, so asking the bar from
     * inside that callback answers with the tab being left.
     */
    private var shownTab: Int? = null

    /** Loads the parent asked for that have not landed yet; drives the progress bar. */
    private var visibleLoads = 0

    override val client: FamilyClient? get() = familyClient
    override val overview: FamilyOverview? get() = loadedOverview
    override val policy: FamilyPolicy? get() = loadedPolicy
    override val lastLoadFailed: Boolean get() = loadFailed

    private val signedIn: Boolean get() = familyClient?.hasParentSession == true

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before any view is inflated, so every parent screen - and its dialogs - gets the
        // accent-tinted containers rather than Material's default lilac. See the style.
        theme.applyStyle(R.style.ThemeOverlay_Keyguard_Parent, true)
        super.onCreate(savedInstanceState)
        binding = ActivityParentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        supervision = Supervision(this)
        pendingRecoveryCode = savedInstanceState?.getString(STATE_RECOVERY_CODE)
        @Suppress("DEPRECATION")
        (lastCustomNonConfigurationInstance as? FamilyOverview)?.let {
            loadedOverview = it
            loadedPolicy = it.policy
        }

        binding.toolbar.inflateMenu(R.menu.menu_parent_toolbar)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_refresh) {
                reload(visible = true)
                true
            } else {
                false
            }
        }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        supportFragmentManager.addOnBackStackChangedListener { renderChrome() }

        val endpoint = getString(R.string.verify_base_url)
        if (endpoint.isBlank()) {
            // Same rule as verification: with no endpoint there is no client at all, so a
            // default build cannot reach the network from this screen either.
            binding.authPanel.authStatusText.text = getString(R.string.supervision_not_configured)
            binding.authPanel.authStatusText.visibility = View.VISIBLE
            setAuthEnabled(false)
            renderAuthState(null)
            return
        }
        familyClient = FamilyClient(this, endpoint)

        wireAuthPanel()
        if (savedInstanceState?.getBoolean(STATE_RECOVER_OPEN) == true) {
            binding.authPanel.recoverPanel.visibility = View.VISIBLE
        }
        renderAuthState(savedInstanceState)
        // No load here: `onResume` always follows, and doing both would spend two requests on
        // every cold start for one screenful of data.
        if (signedIn) ParentSync.schedule(this)
    }

    /**
     * Re-fetch on every return to the screen, not only on a fresh instance.
     *
     * Screens re-render on resume from the overview this activity is holding, so coming back
     * to a warm task would redraw data that could be hours old. The fetch is one request for
     * the whole app and every screen re-renders from the result.
     */
    override fun onResume() {
        super.onResume()
        if (signedIn) reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (navigationInstalled) Shell.saveSelection(outState, binding.bottomNav)
        // Survives a rotation between registering and first opening Settings. Losing it there
        // would lose it for good - the server keeps no readable copy.
        pendingRecoveryCode?.let { outState.putString(STATE_RECOVERY_CODE, it) }
        // A parent halfway through recovering who rotates the phone should not have to find
        // the collapsed link again. The fields keep their own text; only the panel needs this.
        outState.putBoolean(
            STATE_RECOVER_OPEN,
            binding.authPanel.recoverPanel.visibility == View.VISIBLE,
        )
    }

    /**
     * The overview, carried across a rotation.
     *
     * Only for the few hundred milliseconds until the reload in `onResume` lands - but without
     * it every screen redraws its loading state on each rotation, and the Family tab flashes
     * its spinner (or worse, "Add your first child") at a parent who has three.
     */
    @Deprecated("Deprecated in ComponentActivity; still the lightest way to carry one object.")
    override fun onRetainCustomNonConfigurationInstance(): Any? = loadedOverview

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    /**
     * System bars, display cutout and the keyboard, added to the root's own padding.
     *
     * The shared `applySystemBarInsets` leaves the keyboard out, which is right for screens
     * with nothing to type into. This one has the sign-in form and the caregiver code field,
     * and with edge-to-edge enforced the keyboard no longer resizes the window by itself - so
     * without the IME inset it covers the very field being typed in. Same rules otherwise:
     * padding captured once, and the inset consumed so no child applies it twice.
     */
    private fun applyInsets(root: View) {
        val initial = Insets.of(
            root.paddingLeft,
            root.paddingTop,
            root.paddingRight,
            root.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                initial.left + bars.left,
                initial.top + bars.top,
                initial.right + bars.right,
                initial.bottom + maxOf(bars.bottom, ime.bottom),
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    // region auth

    private fun wireAuthPanel() {
        val panel = binding.authPanel
        panel.loginButton.setOnClickListener { authenticate(register = false) }
        panel.registerButton.setOnClickListener { authenticate(register = true) }
        panel.recoverButton.setOnClickListener { recoverAccount() }

        // Recovery is collapsed by default. It is the rarest path in the app and, expanded, it
        // doubles the length of the first screen a new parent sees with a form they have no code
        // for yet.
        panel.recoverToggle.setOnClickListener {
            panel.recoverPanel.visibility =
                if (panel.recoverPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun authenticate(register: Boolean) {
        val client = familyClient ?: return
        val email = binding.authPanel.emailField.text.toString()
        val password = binding.authPanel.passwordField.text.toString()
        setAuthEnabled(false)
        background({
            if (register) client.registerParent(email, password) else client.loginParent(email, password)
        }) { outcome ->
            setAuthEnabled(true)
            handleAuth(outcome)
        }
    }

    private fun recoverAccount() {
        val client = familyClient ?: return
        val panel = binding.authPanel
        val email = panel.emailField.text.toString()
        val password = panel.passwordField.text.toString()
        val recovery = panel.recoveryCodeField.text.toString()
        setAuthEnabled(false)
        background({ client.recoverParent(email, recovery, password) }) { outcome ->
            setAuthEnabled(true)
            handleAuth(outcome)
        }
    }

    private fun handleAuth(outcome: ParentAuthOutcome?) {
        val panel = binding.authPanel
        when (outcome) {
            is ParentAuthOutcome.Success -> {
                panel.passwordField.text?.clear()
                panel.recoveryCodeField.text?.clear()
                // A recovery code is shown once and never again, so it is stashed for the
                // Settings tab rather than flashed on a panel that is about to disappear.
                outcome.recoveryCode?.let { pendingRecoveryCode = it }
                panel.authStatusText.visibility = View.GONE
                renderAuthState(null)
                // Signing in is what makes the background poll meaningful, so it is what
                // starts it. Scheduling is idempotent, so a parent who signs in twice does
                // not end up with two.
                ParentSync.schedule(this)
                reload()
                // Straight to the code, on the tab that shows it. Selected through the
                // navigation bar so Shell does the switching - never a transaction of our own.
                // Pending transactions are run first: the Family tab `Shell.install` just added
                // is still queued, and switching before it lands would leave it visible under
                // Settings - the duplicate-tab trap Shell's own comments describe.
                if (pendingRecoveryCode != null) {
                    supportFragmentManager.executePendingTransactions()
                    binding.bottomNav.selectedItemId = R.id.nav_settings
                }
            }
            ParentAuthOutcome.Invalid -> showAuthError(R.string.parent_auth_invalid)
            ParentAuthOutcome.EmailInUse -> showAuthError(R.string.parent_auth_email_used)
            ParentAuthOutcome.RateLimited -> showAuthError(R.string.parent_auth_rate_limited)
            else -> showAuthError(R.string.parent_auth_failed)
        }
    }

    private fun showAuthError(messageRes: Int) {
        binding.authPanel.authStatusText.setText(messageRes)
        binding.authPanel.authStatusText.visibility = View.VISIBLE
    }

    private fun setAuthEnabled(enabled: Boolean) {
        val panel = binding.authPanel
        panel.loginButton.isEnabled = enabled
        panel.registerButton.isEnabled = enabled
        panel.recoverButton.isEnabled = enabled
    }

    /**
     * The code shown once after registering or recovering.
     *
     * Held here rather than in the Settings tab because that tab does not exist yet at the
     * moment it arrives - the tabs are only built once the welcome panel goes away.
     */
    private var pendingRecoveryCode: String? = null

    override fun consumeRecoveryCode(): String? =
        pendingRecoveryCode.also { pendingRecoveryCode = null }

    private fun renderAuthState(savedInstanceState: Bundle?) {
        val signedIn = signedIn
        binding.authPanel.root.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.sectionContainer.visibility = if (signedIn) View.VISIBLE else View.GONE

        if (signedIn && !navigationInstalled) {
            Shell.install(
                activity = this,
                nav = binding.bottomNav,
                toolbar = binding.toolbar,
                containerId = binding.sectionContainer.id,
                sections = listOf(
                    Section(R.id.nav_family, R.string.parent_tab_family) { FamilyHomeFragment() },
                    Section(R.id.nav_activity, R.string.parent_tab_activity) { ActivityFragment() },
                    Section(R.id.nav_settings, R.string.parent_tab_settings) { SettingsFragment() },
                ),
                savedSelection = Shell.restoreSelection(savedInstanceState),
                // A restored detail screen is already on top of a hidden tab; see the class note.
                showSelection = supportFragmentManager.backStackEntryCount == 0,
                onSectionShown = { section ->
                    shownTab = section.itemId
                    renderChrome()
                },
            )
            shownTab = binding.bottomNav.selectedItemId
            navigationInstalled = true
        }
        renderChrome()
    }

    /**
     * Toolbar and bottom bar, from what is on screen. Called on every tab switch and every
     * back stack change, so the up arrow and the hidden bar always match the screen.
     */
    private fun renderChrome() {
        val toolbar = binding.toolbar
        val refresh = toolbar.menu.findItem(R.id.action_refresh)

        if (!signedIn) {
            // The welcome panel carries its own title; an empty toolbar above it is just space.
            toolbar.visibility = View.GONE
            binding.bottomNav.visibility = View.GONE
            return
        }
        toolbar.visibility = View.VISIBLE

        val inDetail = supportFragmentManager.backStackEntryCount > 0
        binding.bottomNav.visibility = if (inDetail) View.GONE else View.VISIBLE

        if (inDetail) {
            val top = supportFragmentManager.findFragmentById(binding.sectionContainer.id)
            val screen = top as? ParentScreen
            toolbar.title = screen?.screenTitle() ?: getString(R.string.parent_title)
            toolbar.setNavigationIcon(R.drawable.ic_parent_back)
            refresh?.isVisible = screen?.offersRefresh == true
        } else {
            val selected = shownTab ?: binding.bottomNav.selectedItemId
            toolbar.title = getString(
                when (selected) {
                    R.id.nav_activity -> R.string.parent_tab_activity
                    R.id.nav_settings -> R.string.parent_tab_settings
                    else -> R.string.parent_tab_family
                },
            )
            toolbar.navigationIcon = null
            refresh?.isVisible = selected != R.id.nav_settings
        }
    }

    override fun onSignedOut() {
        loadedOverview = null
        loadedPolicy = null
        loadFailed = false
        // Before `clearParent`, which drops the watermark: a poll that raced this would
        // otherwise re-establish one against a family this device no longer reads.
        ParentSync.cancel(this)
        ParentAlertNotice.hide(this)
        supervision.clearParent()
        // Screens are torn down rather than left holding a family they can no longer read.
        // Without this, signing back in as a different account would show the previous one's
        // children until the first load landed. The back stack goes first, so a detail
        // screen cannot be popped back into existence over the welcome panel.
        if (navigationInstalled) {
            val manager = supportFragmentManager
            if (!manager.isStateSaved && manager.backStackEntryCount > 0) {
                manager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
            val transaction = manager.beginTransaction()
            for (fragment in manager.fragments) transaction.remove(fragment)
            transaction.commitAllowingStateLoss()
            manager.executePendingTransactions()
            // Detached before the next sign-in installs the tabs again. Shell selects the
            // starting tab before attaching its listener precisely so that the selection runs
            // no transaction; a listener left over from this session would run one anyway,
            // and the duplicate-tab trap Shell describes would be back.
            binding.bottomNav.setOnItemSelectedListener(null)
            shownTab = null
            navigationInstalled = false
        }
        renderAuthState(null)
    }

    // endregion

    // region navigation

    override fun open(screen: Fragment) {
        val manager = supportFragmentManager
        // A tap that lands after the activity has saved its state cannot be committed safely,
        // and dropping it is better than a transaction the system will not restore.
        if (manager.isStateSaved || !signedIn) return

        val transaction = manager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(
                R.anim.parent_push_enter,
                R.anim.parent_push_exit,
                R.anim.parent_pop_enter,
                R.anim.parent_pop_exit,
            )
        // Whatever is visible - the current tab, or the detail this one is opened from - is
        // hidden in the same transaction, so popping it shows exactly that screen again.
        for (fragment in manager.fragments) {
            if (fragment.id == binding.sectionContainer.id && !fragment.isHidden) {
                transaction.hide(fragment)
            }
        }
        transaction.add(binding.sectionContainer.id, screen)
            .addToBackStack(null)
            .commit()
        // Executed now rather than on the next frame, so a second tap on the same card - the
        // double-tap every list gets - finds the list already hidden instead of opening the
        // same child twice.
        manager.executePendingTransactions()
    }

    override fun replaceTop(screen: Fragment) {
        val manager = supportFragmentManager
        if (manager.isStateSaved) return
        if (manager.backStackEntryCount > 0) manager.popBackStackImmediate()
        open(screen)
    }

    override fun closeTop() {
        val manager = supportFragmentManager
        if (!manager.isStateSaved && manager.backStackEntryCount > 0) manager.popBackStack()
    }

    override fun onScreenChanged() = renderChrome()

    // endregion

    override fun reload(visible: Boolean) {
        val client = familyClient ?: return
        if (visible) setVisibleLoads(visibleLoads + 1)
        background({ client.fetchOverview() }) { overview ->
            if (visible) setVisibleLoads(visibleLoads - 1)
            if (overview == null) {
                loadFailed = true
                if (!client.hasParentSession) {
                    // The session expired underneath us. Dropping to the welcome panel is the
                    // only honest response; leaving screens showing stale data is not.
                    onSignedOut()
                    showAuthError(R.string.parent_auth_invalid)
                    return@background
                }
                // Asked-for refreshes get told they failed. The rest stay quiet: the screens
                // already show what they last had, and a failed background poll every four
                // seconds must not turn into a stream of messages.
                if (visible && loadedOverview != null) {
                    Snackbar.make(
                        binding.sectionContainer,
                        R.string.parent_failed,
                        Snackbar.LENGTH_SHORT,
                    ).show()
                }
                Shell.refreshAll(supportFragmentManager)
                return@background
            }
            // A null family id is the ordinary first-run state - a parent who has not asked for
            // a code yet - so it loads an empty overview rather than an error.
            overview.familyId?.let(supervision::becomeParent)
            loadFailed = false
            loadedOverview = overview
            loadedPolicy = overview.policy
            Shell.refreshAll(supportFragmentManager)
        }
    }

    private fun setVisibleLoads(count: Int) {
        visibleLoads = count.coerceAtLeast(0)
        binding.loadingBar.visibility = if (visibleLoads > 0) View.VISIBLE else View.INVISIBLE
    }

    override fun <T> background(work: () -> T?, onResult: (T?) -> Unit) {
        executor.execute {
            val result = runCatching(work).getOrNull()
            main.post { if (!isFinishing && !isDestroyed) onResult(result) }
        }
    }

    private companion object {
        const val STATE_RECOVERY_CODE = "keyguard.parent.recoveryCode"
        const val STATE_RECOVER_OPEN = "keyguard.parent.recoverOpen"
    }
}
