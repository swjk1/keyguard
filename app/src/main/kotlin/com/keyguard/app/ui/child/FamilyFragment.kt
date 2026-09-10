package com.keyguard.app.ui.child

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentFamilyBinding
import com.keyguard.app.family.Elapsed
import com.keyguard.app.family.EventQueue
import com.keyguard.app.family.FamilyClient
import com.keyguard.app.family.PairingCode
import com.keyguard.app.family.ReviewScope
import com.keyguard.app.family.SupervisedSettings
import com.keyguard.app.family.Supervision
import com.keyguard.app.family.SupervisionNotice
import com.keyguard.app.family.SupervisionSync
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.SectionFragment
import java.util.concurrent.Executors

/**
 * Family, from the monitored side.
 *
 * Exactly one of the two cards is ever visible: an unpaired device sees a pairing form and
 * nothing else, a supervised one sees what its parent can see and has no pairing form to be
 * confused by.
 *
 * The supervised card is the disclosure a monitored person is owed, so it cannot be dismissed or
 * collapsed, and the review-scope line is first and bold because that is the fact that changes
 * and the one a child most needs. A misconfigured build cannot hide it either — the section is
 * only dropped from the navigation when the device is *both* unpaired and has no server, which
 * is checked in `SetupActivity.familyReachable`.
 */
class FamilyFragment : SectionFragment() {

    private var _binding: FragmentFamilyBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: Settings
    private lateinit var supervision: Supervision
    private lateinit var effective: SupervisedSettings

    private var client: FamilyClient? = null
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var resumePairAfterPermission = false

    /**
     * Asked for at the moment of pairing, not at launch: the permission exists solely to carry
     * the standing supervision notice, so requesting it before there is anything to notify about
     * would be a prompt with no explanation attached to it.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Either answer is fine. A refusal costs the notification, not the disclosure — this
            // screen says the same things and cannot be dismissed.
            if (granted) {
                if (resumePairAfterPermission) pair() else refreshNotice()
            } else {
                showPairingStatus(getString(R.string.supervision_notification_required))
            }
            resumePairAfterPermission = false
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFamilyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        settings = Settings(context)
        supervision = Supervision(context)
        effective = SupervisedSettings(settings, supervision)

        val endpoint = getString(R.string.verify_base_url)
        if (endpoint.isNotBlank()) client = FamilyClient(context, endpoint)

        binding.deviceLabelField.setText(supervision.deviceLabel)
        binding.pairButton.setOnClickListener { pair() }
        binding.restoreNotificationButton.setOnClickListener {
            resumePairAfterPermission = false
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        background.shutdownNow()
    }

    override fun refresh() {
        if (_binding == null) return
        val supervised = supervision.isSupervised

        binding.supervisionUnpaired.visibility = if (supervised) View.GONE else View.VISIBLE
        binding.supervisionActive.visibility = if (supervised) View.VISIBLE else View.GONE
        binding.supervisionNotConfigured.visibility =
            if (!supervised && client == null) View.VISIBLE else View.GONE
        binding.pairButton.isEnabled = client != null

        if (!supervised) return

        // What this child's parent can actually see, named specifically rather than left to
        // "your parent manages your settings". The scope is the one policy field whose effect a
        // monitored person is owed in as many words.
        binding.supervisionScopeText.setText(
            when (effective.reviewScope) {
                ReviewScope.CONCERNING_ONLY -> R.string.supervision_scope_concerning
                ReviewScope.THEMES -> R.string.supervision_scope_themes
                ReviewScope.FULL_TEXT -> R.string.supervision_scope_full
            },
        )

        val notificationMissing = notificationMissing()
        binding.restoreNotificationButton.visibility =
            if (notificationMissing) View.VISIBLE else View.GONE

        val lastSync = supervision.lastSyncAt
        val synced = if (lastSync == 0L) {
            getString(R.string.supervision_never_synced)
        } else {
            getString(R.string.supervision_last_sync, describe(lastSync))
        }
        val queued = EventQueue(requireContext()).size()
        binding.supervisionSyncText.text = when {
            notificationMissing -> getString(R.string.supervision_notification_required)
            queued == 0 -> synced
            else -> "$synced · ${getString(R.string.supervision_queued, queued)}"
        }
    }

    private fun pair() {
        val client = client ?: run {
            showPairingStatus(getString(R.string.supervision_not_configured))
            return
        }

        val code = PairingCode.normalize(binding.pairingCodeField.text.toString())
        if (!PairingCode.isValid(code)) {
            showPairingStatus(getString(R.string.supervision_code_invalid))
            return
        }

        if (notificationMissing()) {
            showPairingStatus(getString(R.string.supervision_notification_required))
            resumePairAfterPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        supervision.deviceLabel = binding.deviceLabelField.text.toString().trim()
        binding.pairButton.isEnabled = false
        showPairingStatus(getString(R.string.supervision_pairing))

        background.execute {
            val joined = runCatching { client.join(code, supervision.deviceLabel) }.getOrNull()
            main.post {
                if (!isAdded || _binding == null) return@post
                binding.pairButton.isEnabled = true

                if (joined == null) {
                    // The server answers a wrong code and an expired one identically, and a dead
                    // network is indistinguishable from either at this layer. One message that
                    // names both plausible causes beats three that guess.
                    showPairingStatus(getString(R.string.supervision_pair_failed))
                    return@post
                }

                val (familyId, policy) = joined
                supervision.becomeChild(familyId, policy)
                SupervisionSync.schedule(requireContext())
                refreshNotice()
                refresh()
            }
        }
    }

    private fun refreshNotice() {
        if (isAdded) SupervisionNotice.refresh(requireContext(), supervision)
    }

    private fun notificationMissing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    private fun showPairingStatus(message: String) {
        binding.pairingStatusText.text = message
        binding.pairingStatusText.visibility = View.VISIBLE
    }

    private fun describe(timestamp: Long): String =
        when (val elapsed = Elapsed.between(timestamp, System.currentTimeMillis())) {
            is Elapsed.JustNow -> getString(R.string.time_just_now)
            is Elapsed.Minutes -> getString(R.string.time_minutes, elapsed.value)
            is Elapsed.Hours -> getString(R.string.time_hours, elapsed.value)
            is Elapsed.Days -> getString(R.string.time_days, elapsed.value)
        }
}
