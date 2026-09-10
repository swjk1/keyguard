package com.keyguard.app.ui.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentRulesBinding
import com.keyguard.app.family.FamilyPolicy
import com.keyguard.app.family.OverrideLevel
import com.keyguard.app.family.PolicyToggle
import com.keyguard.app.family.ReviewScope
import com.keyguard.app.settings.Intensity
import com.keyguard.app.ui.SectionFragment

/**
 * What the parent has decided.
 *
 * Split out of the dashboard so a parent checking on their child does not walk past six radio
 * groups to get there. Every option carries a one-line hint in the layout: these are decisions
 * with real consequences for someone else, and "Nothing can be ignored" or "Everything they
 * type" cannot be understood from a label alone.
 *
 * Save is explicit rather than per-control. Several of these interact — a review scope with
 * reports switched off, an override level with blocking disabled — and a half-applied policy
 * syncing to a child's phone mid-edit is worse than an unsaved one.
 */
class RulesFragment : SectionFragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost

    /** Guards re-entrancy while the controls are re-checked from a loaded policy. */
    private var rendering = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.saveButton.setOnClickListener { save() }

        // Turning on full review is the one control here that changes what a child experiences
        // rather than what this screen shows, so it asks. Everything else saves silently, which
        // is right for a floor or a toggle and wrong for this.
        binding.scopeGroup.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            if (checked == R.id.scopeFull && host.policy?.reviewScope != ReviewScope.FULL_TEXT) {
                confirmFullTextReview()
            }
        }

        // With no endpoint there is no verification to govern, so the card goes rather than
        // offering a choice about a feature the build does not contain.
        binding.aiCard.visibility =
            if (getString(R.string.verify_base_url).isNotBlank()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        binding.rulesStatusText.visibility = View.GONE
        binding.saveButton.isEnabled = host.client != null
        render(host.policy ?: FamilyPolicy.DEFAULT)
    }

    private fun render(policy: FamilyPolicy) {
        rendering = true

        binding.minIntensityGroup.check(
            when (policy.minIntensity) {
                Intensity.SUBTLE -> R.id.minIntensitySubtle
                Intensity.STANDARD -> R.id.minIntensityStandard
                Intensity.INSISTENT -> R.id.minIntensityInsistent
            },
        )
        binding.lockSwitch.isChecked = policy.lockSettings
        binding.blockSwitch.isChecked = policy.blockAtHigh

        binding.overrideGroup.check(
            when (policy.overrideLevel) {
                OverrideLevel.FULL -> R.id.overrideFull
                OverrideLevel.LIMITED -> R.id.overrideLimited
                OverrideLevel.NONE -> R.id.overrideNone
            },
        )
        binding.scopeGroup.check(
            when (policy.reviewScope) {
                ReviewScope.CONCERNING_ONLY -> R.id.scopeConcerning
                ReviewScope.THEMES -> R.id.scopeThemes
                ReviewScope.FULL_TEXT -> R.id.scopeFull
            },
        )
        binding.reportsSwitch.isChecked = policy.reportsEnabled

        binding.aiGroup.check(
            when (policy.aiVerification) {
                // FORCED_ON is not offered — the server refuses it — so it renders as the
                // child's own choice rather than as a fourth option nobody can select.
                PolicyToggle.CHILD_CHOICE, PolicyToggle.FORCED_ON -> R.id.aiChildChoice
                PolicyToggle.FORCED_OFF -> R.id.aiForcedOff
            },
        )

        rendering = false
    }

    /**
     * Confirms a move to full message review.
     *
     * Cancelling puts the control back where it was rather than leaving it on an option the
     * parent declined — a radio button that stayed selected after "no" would be saved on the
     * next unrelated policy change, which is the worst possible way to turn this on.
     */
    private fun confirmFullTextReview() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.parent_scope_full_confirm)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                render(host.policy ?: FamilyPolicy.DEFAULT)
            }
            .setOnCancelListener { render(host.policy ?: FamilyPolicy.DEFAULT) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun save() {
        val client = host.client ?: return
        val desired = readControls()
        binding.saveButton.isEnabled = false

        host.background({ client.writePolicy(desired) }) { saved ->
            if (_binding == null) return@background
            binding.saveButton.isEnabled = true
            if (saved == null) {
                binding.rulesStatusText.setText(R.string.parent_failed)
                binding.rulesStatusText.visibility = View.VISIBLE
                return@background
            }
            binding.rulesStatusText.visibility = View.GONE
            Toast.makeText(requireContext(), R.string.parent_policy_saved, Toast.LENGTH_LONG).show()
            // Reload rather than trusting what was sent: the server assigns the version, and it
            // may also have narrowed something the client did not ask about.
            host.reload()
        }
    }

    private fun readControls() = FamilyPolicy(
        // Ignored by the server, which assigns its own. Sent as whatever was last seen so the
        // object is well-formed rather than carrying a lie about being version zero.
        version = host.policy?.version ?: 0,
        minIntensity = when (binding.minIntensityGroup.checkedRadioButtonId) {
            R.id.minIntensityStandard -> Intensity.STANDARD
            R.id.minIntensityInsistent -> Intensity.INSISTENT
            else -> Intensity.SUBTLE
        },
        blockAtHigh = binding.blockSwitch.isChecked,
        aiVerification = when (binding.aiGroup.checkedRadioButtonId) {
            R.id.aiForcedOff -> PolicyToggle.FORCED_OFF
            else -> PolicyToggle.CHILD_CHOICE
        },
        lockSettings = binding.lockSwitch.isChecked,
        overrideLevel = when (binding.overrideGroup.checkedRadioButtonId) {
            R.id.overrideLimited -> OverrideLevel.LIMITED
            R.id.overrideNone -> OverrideLevel.NONE
            else -> OverrideLevel.FULL
        },
        reviewScope = when (binding.scopeGroup.checkedRadioButtonId) {
            R.id.scopeThemes -> ReviewScope.THEMES
            R.id.scopeFull -> ReviewScope.FULL_TEXT
            else -> ReviewScope.CONCERNING_ONLY
        },
        reportsEnabled = binding.reportsSwitch.isChecked,
    )
}
