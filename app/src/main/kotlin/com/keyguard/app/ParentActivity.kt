package com.keyguard.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.keyguard.app.databinding.ActivityParentBinding
import com.keyguard.app.family.FamilyClient
import com.keyguard.app.family.FamilyOverview
import com.keyguard.app.family.FamilyPolicy
import com.keyguard.app.family.ParentAuthOutcome
import com.keyguard.app.family.Supervision
import com.keyguard.app.ui.Section
import com.keyguard.app.ui.Shell
import com.keyguard.app.ui.parent.AccountFragment
import com.keyguard.app.ui.parent.ChildrenFragment
import com.keyguard.app.ui.parent.ParentHost
import com.keyguard.app.ui.parent.ReportsFragment
import com.keyguard.app.ui.parent.RulesFragment
import com.keyguard.app.ui.applySystemBarInsets
import java.util.concurrent.Executors

/**
 * The parent app.
 *
 * Two states, and the split is the main thing this rework changed. Signed out, the screen is the
 * sign-in form and nothing else - no navigation bar leading to four sections that cannot load.
 * Signed in, the form goes and the sections appear.
 *
 * The sections themselves are in `ui/parent/`. The activity keeps only what all four share: one
 * client, one background executor, and one loaded overview. Fetching per section would mean four
 * requests for one screenful of data and four different answers on a flaky connection.
 *
 * Every line the sections render is still a category, a severity and an outcome unless a parent
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
    private var navigationInstalled = false

    override val client: FamilyClient? get() = familyClient
    override val overview: FamilyOverview? get() = loadedOverview
    override val policy: FamilyPolicy? get() = loadedPolicy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        supervision = Supervision(this)

        val endpoint = getString(R.string.verify_base_url)
        if (endpoint.isBlank()) {
            // Same rule as verification: with no endpoint there is no client at all, so a
            // default build cannot reach the network from this screen either.
            binding.authPanel.authStatusText.text = getString(R.string.supervision_not_configured)
            binding.authPanel.authStatusText.visibility = View.VISIBLE
            setAuthEnabled(false)
            return
        }
        familyClient = FamilyClient(this, endpoint)

        wireAuthPanel()
        renderAuthState(savedInstanceState)
        if (familyClient?.hasParentSession == true) reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (navigationInstalled) Shell.saveSelection(outState, binding.bottomNav)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
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
                // Account section rather than flashed on a panel that is about to disappear.
                outcome.recoveryCode?.let { pendingRecoveryCode = it }
                panel.authStatusText.visibility = View.GONE
                renderAuthState(null)
                reload()
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
     * Held here rather than in the Account section because that section does not exist yet at
     * the moment it arrives - the sections are only built once the auth panel goes away.
     */
    var pendingRecoveryCode: String? = null
        private set

    fun consumeRecoveryCode(): String? = pendingRecoveryCode.also { pendingRecoveryCode = null }

    private fun renderAuthState(savedInstanceState: Bundle?) {
        val signedIn = familyClient?.hasParentSession == true
        binding.authContainer.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.bottomNav.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.sectionContainer.visibility = if (signedIn) View.VISIBLE else View.GONE

        if (!signedIn) {
            binding.toolbar.title = getString(R.string.parent_title)
            return
        }
        if (navigationInstalled) return

        Shell.install(
            activity = this,
            nav = binding.bottomNav,
            toolbar = binding.toolbar,
            containerId = binding.sectionContainer.id,
            sections = listOf(
                Section(R.id.nav_children, R.string.nav_children) { ChildrenFragment() },
                Section(R.id.nav_reports, R.string.nav_reports) { ReportsFragment() },
                Section(R.id.nav_rules, R.string.nav_rules) { RulesFragment() },
                Section(R.id.nav_account, R.string.nav_account) { AccountFragment() },
            ),
            savedSelection = Shell.restoreSelection(savedInstanceState),
        )
        navigationInstalled = true
    }

    override fun onSignedOut() {
        loadedOverview = null
        loadedPolicy = null
        supervision.clearParent()
        // Sections are torn down rather than left holding a family they can no longer read.
        // Without this, signing back in as a different account would show the previous one's
        // children until the first load landed.
        if (navigationInstalled) {
            val transaction = supportFragmentManager.beginTransaction()
            for (fragment in supportFragmentManager.fragments) transaction.remove(fragment)
            transaction.commitAllowingStateLoss()
            supportFragmentManager.executePendingTransactions()
            navigationInstalled = false
        }
        renderAuthState(null)
    }

    // endregion

    override fun reload() {
        val client = familyClient ?: return
        background({ client.fetchOverview() }) { overview ->
            if (overview == null) {
                if (!client.hasParentSession) {
                    // The session expired underneath us. Dropping to the auth panel is the only
                    // honest response; leaving four sections showing stale data is not.
                    onSignedOut()
                    showAuthError(R.string.parent_auth_invalid)
                }
                Shell.refreshAll(supportFragmentManager)
                return@background
            }
            // A null family id is the ordinary first-run state - a parent who has not asked for
            // a code yet - so it loads an empty overview rather than an error.
            overview.familyId?.let(supervision::becomeParent)
            loadedOverview = overview
            loadedPolicy = overview.policy
            Shell.refreshAll(supportFragmentManager)
        }
    }

    override fun <T> background(work: () -> T?, onResult: (T?) -> Unit) {
        executor.execute {
            val result = runCatching(work).getOrNull()
            main.post { if (!isFinishing && !isDestroyed) onResult(result) }
        }
    }
}
