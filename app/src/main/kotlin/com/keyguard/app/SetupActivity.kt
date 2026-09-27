package com.keyguard.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.keyguard.app.databinding.ActivitySetupBinding
import com.keyguard.app.family.Supervision
import com.keyguard.app.family.SupervisionNotice
import com.keyguard.app.family.SupervisionSync
import com.keyguard.app.overlay.OverlayPermissions
import com.keyguard.app.settings.KeyboardStatus
import com.keyguard.app.settings.OnboardingProgress
import com.keyguard.app.settings.OnboardingStep
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.Shell
import com.keyguard.app.ui.applySystemBarInsets
import com.keyguard.app.ui.child.ChildNav
import com.keyguard.app.ui.child.ChildScreen
import com.keyguard.app.ui.child.HomeFragment

/**
 * The child app.
 *
 * This class used to be 691 lines and this file used to be the entire product. It then became
 * a four-tab shell, which fixed the line count but not the feel: every tab was still a long
 * scroll of cards. It is now a hub-and-detail frame. [HomeFragment] answers the one question
 * people reopen the app to ask ("am I protected?") and leads to Warnings, Family, Privacy and
 * the optional keyboard, each pushed over it with the toolbar's up arrow to come back. The
 * screens live in `ui/child/`; the navigation rules are in [ChildNav].
 *
 * The class name is unchanged deliberately. [SupervisionNotice]'s content intent targets it, and
 * a supervised child tapping that notice must land on the screen it refers to - which is the
 * hub, whose family line says what the parent can see and leads to the full list.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var settings: Settings
    private lateinit var supervision: Supervision

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        settings = Settings(this)
        supervision = Supervision(this)

        // Covers the case that matters most: a device paired on a previous launch whose notice
        // was cleared by a reboot or a permission change.
        SupervisionNotice.refresh(this, supervision)
        if (supervision.isSupervised) SupervisionSync.schedule(this)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        supportFragmentManager.addOnBackStackChangedListener { renderToolbar() }

        // Only on a fresh instance. After a rotation the FragmentManager has already restored
        // the hub *and* whatever detail screen was on top of it, and adding the hub again here
        // would stack a second one under the restored stack.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(ChildNav.CONTAINER_ID, HomeFragment(), HomeFragment::class.java.simpleName)
                .commitNow()
        }
        renderToolbar()

        // Setup that is not finished gets the guided flow; the hub is the full surface behind
        // it, which is what a user who skips lands on.
        //
        // From onCreate and only on a fresh instance, deliberately not from onResume:
        // onboarding is dismissible, and re-launching it on every resume would make Skip unable
        // to skip.
        if (savedInstanceState == null && !isSetUp()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is the most reliable sync opportunity a supervised device gets; the
        // periodic job is the fallback, not the primary path. Screens re-read themselves on
        // entry - see SectionFragment - so this only has to nudge the one on screen once a
        // sync actually lands.
        if (!supervision.isSupervised) return
        val endpoint = getString(R.string.verify_base_url)
        if (endpoint.isBlank()) return

        val sync = SupervisionSync(this, endpoint)
        sync.sync { reached ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed && reached) {
                    Shell.refreshAll(supportFragmentManager)
                }
            }
            sync.shutdown()
        }
    }

    /**
     * Title and up arrow follow whatever is on top.
     *
     * Read from the fragment rather than tracked alongside the transactions, so a restored
     * stack after rotation gets the right title with no bookkeeping of its own. The hub shows
     * the app's name and no arrow: there is nowhere up to go from it.
     */
    private fun renderToolbar() {
        val top = supportFragmentManager.findFragmentById(ChildNav.CONTAINER_ID)
        val detail = supportFragmentManager.backStackEntryCount > 0
        binding.toolbar.title = getString((top as? ChildScreen)?.titleRes ?: R.string.app_name)
        if (detail) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            binding.toolbar.setNavigationContentDescription(R.string.navigate_up)
        } else {
            binding.toolbar.navigationIcon = null
        }
    }

    private fun isSetUp(): Boolean = OnboardingProgress.next(
        disclosureAccepted = settings.disclosureAccepted,
        keyboardEnabled = KeyboardStatus.isEnabled(this),
        keyboardSelected = KeyboardStatus.isSelected(this),
        accessibilityGranted = OverlayPermissions.isServiceEnabled(this),
        overlayGranted = OverlayPermissions.canDrawOverlays(this),
    ) == OnboardingStep.READY
}
