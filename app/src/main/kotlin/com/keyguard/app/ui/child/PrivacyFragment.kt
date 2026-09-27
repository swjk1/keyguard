package com.keyguard.app.ui.child

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.keyguard.app.OutcomeLog
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentPrivacyBinding
import com.keyguard.app.family.SupervisedSettings
import com.keyguard.app.family.Supervision
import com.keyguard.app.settings.Settings
import com.keyguard.app.ui.SectionFragment
import com.keyguard.detect.DetectionEngine

/**
 * What the app can see, what leaves the device, and what has been counted.
 *
 * The disclosure used to be the first thing on the old single-page screen, above every control,
 * which meant it was scrolled past rather than read. Putting it here with the two things it
 * actually governs — the verification toggle and the outcome counters — makes the page answer
 * one question instead of gating an unrelated one.
 *
 * It still gates setup: the first step on the home screen links here, and the keyboard screen's
 * button stays disabled until the box is ticked. The outcome counts are summarised as tiles on
 * home; this is where the detail, the rule-pack line and the reset live.
 */
class PrivacyFragment : SectionFragment(), ChildScreen {

    override val titleRes: Int = R.string.screen_privacy

    private var _binding: FragmentPrivacyBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: Settings
    private lateinit var effective: SupervisedSettings
    private lateinit var outcomeLog: OutcomeLog

    private val engine by lazy { DetectionEngine.withBundledPack() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPrivacyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        settings = Settings(context)
        effective = SupervisedSettings(settings, Supervision(context))
        outcomeLog = OutcomeLog(context)

        binding.disclosureCheckbox.setOnCheckedChangeListener { _, checked ->
            settings.disclosureAccepted = checked
            refresh()
        }

        binding.aiSwitch.setOnCheckedChangeListener { _, checked ->
            // While a parent owns this toggle the switch is a read-only mirror, so re-rendering
            // it must not write the forced value over what the child themselves chose.
            if (effective.aiVerificationLocked) return@setOnCheckedChangeListener
            settings.aiVerificationEnabled = checked
        }

        binding.resetStatsButton.setOnClickListener {
            outcomeLog.clear()
            renderStats()
        }

        // With no endpoint there is no verification to offer, so the card goes rather than
        // sitting there disabled under a note explaining that it does nothing. In the solo
        // flavor that is the permanent state, and a settings screen should not advertise a
        // feature the build does not contain.
        Disclosure.render(binding.disclosureBody, layoutInflater)

        binding.aiCard.visibility =
            if (endpointConfigured()) View.VISIBLE else View.GONE
        // Same reasoning for the paragraph about what travels: in a build that cannot open a
        // socket, describing the conditions under which it would is noise.
        binding.disclosureNetworkText.visibility =
            if (endpointConfigured()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return

        binding.disclosureCheckbox.isChecked = settings.disclosureAccepted

        // Withdrawing consent to the disclosure withdraws consent to transmission with it —
        // unless a parent forced it, which is a decision this checkbox cannot undo.
        if (!settings.disclosureAccepted && settings.aiVerificationEnabled) {
            settings.aiVerificationEnabled = false
        }
        // A parent who forced the toggle either way owns it, so the child's switch goes
        // read-only rather than pretending to be theirs.
        binding.aiSwitch.isEnabled = endpointConfigured() &&
            settings.disclosureAccepted &&
            !effective.aiVerificationLocked
        binding.aiSwitch.isChecked = effective.aiVerificationEnabled
        binding.aiLockedText.visibility =
            if (effective.aiVerificationLocked) View.VISIBLE else View.GONE

        renderStats()
    }

    /** The outcome counters, plus a rule-pack line that proves the engine actually loaded. */
    private fun renderStats() {
        val totals = outcomeLog.total()
        val none = totals.warningsShown == 0
        binding.statsText.visibility = if (none) View.VISIBLE else View.GONE
        binding.statsText.setText(R.string.stats_none)
        binding.statsTable.visibility = if (none) View.GONE else View.VISIBLE

        // A two-column table built in code rather than the old `stats_body` string, whose
        // alignment relied on runs of spaces that resource compilation collapses.
        val rate = totals.heedRate
        val rows = listOf(
            R.string.privacy_stat_shown to totals.warningsShown.toString(),
            R.string.home_stat_changed to totals.heeded.toString(),
            R.string.home_stat_sent to totals.sentAnyway.toString(),
            // A dash rather than 0% when nothing has been decided yet, so "no data" never reads
            // as "the product never works".
            R.string.privacy_stat_rate to if (rate == null) "—" else "%.0f%%".format(rate * 100),
        )
        val table = binding.statsTable
        table.removeAllViews()
        for ((labelRes, value) in rows) {
            table.addView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val gap = resources.getDimensionPixelSize(R.dimen.space_xs)
                    setPadding(0, gap, 0, gap)
                    addView(
                        TextView(context).apply {
                            setTextAppearance(R.style.TextAppearance_Keyguard_Label)
                            setText(labelRes)
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        TextView(context).apply {
                            setTextAppearance(R.style.TextAppearance_Keyguard_Label)
                            setTypeface(typeface, Typeface.BOLD)
                            text = value
                        },
                    )
                },
            )
        }

        binding.engineText.text = getString(
            R.string.stats_engine,
            engine.packVersion,
            engine.packRevision,
            engine.termCount,
        )
    }

    private fun endpointConfigured(): Boolean =
        getString(R.string.verify_base_url).isNotBlank()
}
