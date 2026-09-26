package com.keyguard.app.ui.parent

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentSettingsBinding
import com.keyguard.app.databinding.ItemRowBinding
import com.keyguard.app.family.FamilyPolicy
import com.keyguard.app.family.OverrideLevel
import com.keyguard.app.settings.Intensity
import com.keyguard.app.ui.SectionFragment

/**
 * The parent's settings, as grouped rows.
 *
 * Replaces the Rules and Account tabs. Both were destinations a parent visits at setup and
 * then rarely, and as tabs they sat level with the two screens that answer questions. Here each
 * is a row whose subtitle states the current answer - the rules in force, whether alerts are on,
 * who is signed in - so most visits end without opening anything.
 *
 * The recovery code is shown here rather than on the sign-in panel that produced it, because
 * that panel disappears the instant registration succeeds. The shell holds the code until this
 * tab asks for it, and the shell switches to this tab when there is one to show.
 */
class SettingsFragment : SectionFragment(), ParentScreen {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost

    /** Held until the parent dismisses it, including across a rotation. See the class note. */
    private var recoveryCode: String? = null

    private var signingOut = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun screenTitle(): CharSequence = getString(R.string.parent_tab_settings)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recoveryCode = savedInstanceState?.getString(STATE_RECOVERY_CODE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        recoveryCode?.let { outState.putString(STATE_RECOVERY_CODE, it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recoveryCopyButton.setOnClickListener { copyRecoveryCode() }
        binding.recoveryDoneButton.setOnClickListener {
            recoveryCode = null
            refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        // Shown once, then never again - the server does not keep a readable copy, so a parent
        // who does not write it down has lost it. Consumed from the shell rather than peeked,
        // and then held here until the parent says they have saved it.
        host.consumeRecoveryCode()?.let { recoveryCode = it }
        binding.recoveryCard.visibility = if (recoveryCode != null) View.VISIBLE else View.GONE
        binding.recoveryCodeText.text = recoveryCode

        renderFamilyRows()
        renderAccountRows()
    }

    private fun renderFamilyRows() {
        val rows = binding.familyRows
        rows.removeAllViews()
        val context = requireContext()

        ParentUi.row(
            rows,
            R.drawable.ic_nav_rules,
            getString(R.string.parent_settings_rules),
            rulesSummary(host.policy ?: FamilyPolicy.DEFAULT),
        ) { host.open(RulesFragment()) }
        rows.addView(ParentUi.divider(context, insetStartDp = 74))

        ParentUi.row(
            rows,
            R.drawable.ic_parent_caregiver,
            getString(R.string.parent_settings_caregivers),
            getString(R.string.parent_settings_caregivers_hint),
        ) { host.open(CaregiversFragment()) }
        rows.addView(ParentUi.divider(context, insetStartDp = 74))

        val muted = AlertPermission.muted(context)
        val alerts = ParentUi.row(
            rows,
            if (muted) R.drawable.ic_parent_alerts_off else R.drawable.ic_nav_activity,
            getString(R.string.parent_settings_notifications),
            getString(
                if (muted) R.string.parent_notifications_off else R.string.parent_notifications_on,
            ),
        ) {
            // On: the system page is where a parent tunes or silences the channel. Off: ask if
            // the prompt can still appear, otherwise send them to that same page.
            if (AlertPermission.muted(context)) {
                AlertPermission.fix(this, notificationPermission)
            } else {
                AlertPermission.openSettings(context)
            }
        }
        if (muted) {
            paint(alerts, R.color.status_pending, R.color.status_pending_surface, title = false)
        }
    }

    private fun renderAccountRows() {
        val rows = binding.accountRows
        rows.removeAllViews()
        val context = requireContext()

        ParentUi.row(
            rows,
            R.drawable.ic_parent_mail,
            host.client?.parentEmail.orEmpty(),
            getString(R.string.parent_settings_signed_in),
        )
        rows.addView(ParentUi.divider(context, insetStartDp = 74))

        // Actions rather than destinations, so no chevron: a chevron promises another screen.
        val signOut = ParentUi.row(
            rows,
            R.drawable.ic_parent_logout,
            getString(R.string.parent_sign_out),
        ) { signOut() }
        signOut.rowChevron.visibility = View.GONE
        rows.addView(ParentUi.divider(context, insetStartDp = 74))

        val delete = ParentUi.row(
            rows,
            R.drawable.ic_parent_delete,
            getString(R.string.parent_delete_account),
        ) { confirmDelete() }
        delete.rowChevron.visibility = View.GONE
        paint(delete, R.color.status_alert, R.color.status_alert_surface, title = true)
    }

    /** Recolours a row's icon disc (and optionally its title) for a warning or destructive row. */
    private fun paint(row: ItemRowBinding, foreground: Int, surface: Int, title: Boolean) {
        val context = requireContext()
        row.rowIcon.imageTintList = ColorStateList.valueOf(context.getColor(foreground))
        ParentUi.tintBackground(row.rowIcon, surface)
        if (title) row.rowTitle.setTextColor(context.getColor(foreground))
    }

    /**
     * "Standard or stronger · Ignore small ones only". The two rules a parent most often wants
     * to check without opening the screen: how forceful warnings are and what the child may do
     * with one.
     */
    private fun rulesSummary(policy: FamilyPolicy): String = getString(
        R.string.parent_rules_summary,
        getString(
            when (policy.minIntensity) {
                Intensity.SUBTLE -> R.string.parent_intensity_short_subtle
                Intensity.STANDARD -> R.string.parent_intensity_short_standard
                Intensity.INSISTENT -> R.string.parent_intensity_short_insistent
            },
        ),
        getString(
            when (policy.overrideLevel) {
                OverrideLevel.FULL -> R.string.parent_override_full
                OverrideLevel.LIMITED -> R.string.parent_override_limited
                OverrideLevel.NONE -> R.string.parent_override_none
            },
        ),
    )

    /**
     * Copies the recovery code, flagged as sensitive so Android 13+ keeps it out of the
     * clipboard preview - it is, after all, a password reset for the whole family.
     */
    private fun copyRecoveryCode() {
        val code = recoveryCode ?: return
        val context = requireContext()
        val clip = ClipData.newPlainText(getString(R.string.parent_recovery_title), code)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        // Android 13+ confirms a copy itself; a toast on top would say it twice.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.parent_recovery_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun signOut() {
        val client = host.client ?: return
        // One request, however many times the row is tapped while it is in flight.
        if (signingOut) return
        signingOut = true
        val parent = host
        parent.background({ client.signOutParent() }) {
            signingOut = false
            // Signed out on this phone either way: `signOutParent` clears the local session
            // whether or not the server heard about it.
            parent.onSignedOut()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.parent_delete_account)
            .setMessage(R.string.parent_delete_account_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.parent_delete_account) { _, _ ->
                val client = host.client ?: return@setPositiveButton
                val parent = host
                parent.background({ client.deleteParentAccount() }) { deleted ->
                    if (deleted == true) {
                        parent.onSignedOut()
                    } else {
                        showStatus(R.string.parent_failed)
                    }
                }
            }
            .show()
    }

    private fun showStatus(messageRes: Int) {
        if (_binding == null) return
        binding.settingsStatusText.setText(messageRes)
        binding.settingsStatusText.visibility = View.VISIBLE
    }

    private companion object {
        const val STATE_RECOVERY_CODE = "recoveryCode"
    }
}
