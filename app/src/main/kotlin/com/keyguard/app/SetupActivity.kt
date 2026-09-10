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
import com.keyguard.app.ui.Section
import com.keyguard.app.ui.Shell
import com.keyguard.app.ui.child.AppearanceFragment
import com.keyguard.app.ui.child.FamilyFragment
import com.keyguard.app.ui.child.PrivacyFragment
import com.keyguard.app.ui.child.ProtectionFragment
import com.keyguard.app.ui.applySystemBarInsets

/**
 * The child app.
 *
 * This class used to be 691 lines and this file used to be the entire product: disclosure,
 * permissions, keyboard setup, seven sliders, a live keyboard preview, an AI toggle, the pairing
 * form and the outcome counters, all in one scroll behind one `refresh()` that touched every one
 * of them. It is now a shell - four sections, each owning its own state - and the sections live
 * in `ui/child/`.
 *
 * What the split bought beyond the line count: the question people actually reopen the app to
 * ask ("am I protected?") is the first thing on the first tab instead of six screens down, and a
 * change to the sizing sliders can no longer break the pairing form by way of a shared refresh.
 *
 * The class name is unchanged deliberately. [SupervisionNotice]'s content intent targets it, and
 * a supervised child tapping that notice must land on the screen it refers to.
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

        val sections = buildList {
            add(Section(R.id.nav_protection, R.string.nav_protection) { ProtectionFragment() })
            add(Section(R.id.nav_appearance, R.string.nav_appearance) { AppearanceFragment() })
            if (familyReachable()) {
                add(Section(R.id.nav_family, R.string.nav_family) { FamilyFragment() })
            }
            add(Section(R.id.nav_privacy, R.string.nav_privacy) { PrivacyFragment() })
        }

        // A tab that cannot do anything is removed rather than shown disabled. In the solo
        // flavor that is the permanent state, and an app should not advertise a section the
        // build does not contain. A *paired* device always keeps it, whatever the endpoint
        // says, because that tab carries the disclosure a monitored user is owed and a
        // misconfigured build must never be a way to make supervision invisible.
        if (!familyReachable()) binding.bottomNav.menu.removeItem(R.id.nav_family)

        Shell.install(
            activity = this,
            nav = binding.bottomNav,
            toolbar = binding.toolbar,
            containerId = binding.sectionContainer.id,
            sections = sections,
            savedSelection = Shell.restoreSelection(savedInstanceState),
        )

        // Setup that is not finished gets the guided flow; this screen is the full settings
        // surface behind it, which is what a user who skips lands on.
        //
        // From onCreate and only on a fresh instance, deliberately not from onResume:
        // onboarding is dismissible, and re-launching it on every resume would make Skip unable
        // to skip.
        if (savedInstanceState == null && !isSetUp()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Shell.saveSelection(outState, binding.bottomNav)
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is the most reliable sync opportunity a supervised device gets; the
        // periodic job is the fallback, not the primary path. Sections re-read themselves on
        // entry - see SectionFragment - so this only has to nudge the ones already built once a
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

    /** Whether the Family section has anything to offer: a server to pair with, or a pairing. */
    private fun familyReachable(): Boolean =
        getString(R.string.verify_base_url).isNotBlank() || supervision.isSupervised

    private fun isSetUp(): Boolean = OnboardingProgress.next(
        disclosureAccepted = settings.disclosureAccepted,
        keyboardEnabled = KeyboardStatus.isEnabled(this),
        keyboardSelected = KeyboardStatus.isSelected(this),
        accessibilityGranted = OverlayPermissions.isServiceEnabled(this),
        overlayGranted = OverlayPermissions.canDrawOverlays(this),
    ) == OnboardingStep.READY
}
