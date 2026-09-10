package com.keyguard.app.ui.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentReportsBinding
import com.keyguard.app.family.Elapsed
import com.keyguard.app.family.FamilyReport
import com.keyguard.app.family.ReportPeriod
import com.keyguard.app.family.ReviewScope
import com.keyguard.app.ui.SectionFragment
import com.keyguard.detect.Emotion
import com.keyguard.detect.Theme

/**
 * Daily and weekly summaries.
 *
 * One child at a time. A report is a paragraph, and three paragraphs stacked with no way to tell
 * whose is whose is not a screen anyone reads — so children are chips and the row hides itself
 * when there is only one, which is most families.
 *
 * The order on screen is deliberate and is the opposite of how the data arrives: the narrative
 * first, then the line saying what it was built from, then the counts, then the detail. A parent
 * reading a fluent paragraph about their child needs to know immediately whether it came from
 * keyword tags or from their actual messages, because the two deserve very different amounts of
 * trust and read identically otherwise.
 */
class ReportsFragment : SectionFragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost

    private var period: ReportPeriod = ReportPeriod.DAY
    private var childInstallId: String? = null

    /** Guards re-entrancy while the chip row is rebuilt from data. */
    private var suppressChipCallback = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.periodToggle.check(R.id.periodDay)
        binding.periodToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            period = if (checkedId == R.id.periodWeek) ReportPeriod.WEEK else ReportPeriod.DAY
            load()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        renderChildChips()
        load()
    }

    private fun renderChildChips() {
        val children = host.overview?.children.orEmpty()
        val group: ChipGroup = binding.childChips

        // One child needs no selector. Showing a single chip that cannot be deselected is a
        // control that does nothing, which is worse than no control.
        if (children.size < 2) {
            group.visibility = View.GONE
            childInstallId = children.firstOrNull()?.installId
            return
        }

        suppressChipCallback = true
        group.removeAllViews()
        // Keep whichever child was being read if they are still paired; otherwise fall back to
        // the first, so removing a child does not leave this section pointing at nobody.
        if (children.none { it.installId == childInstallId }) {
            childInstallId = children.first().installId
        }

        for (child in children) {
            val chip = Chip(requireContext()).apply {
                text = child.label
                isCheckable = true
                isChecked = child.installId == childInstallId
                setOnClickListener {
                    if (suppressChipCallback) return@setOnClickListener
                    childInstallId = child.installId
                    load()
                }
            }
            group.addView(chip)
        }
        group.visibility = View.VISIBLE
        suppressChipCallback = false
    }

    private fun load() {
        if (_binding == null) return
        val container = binding.reportContainer
        container.removeAllViews()

        val client = host.client
        val childId = childInstallId
        if (client == null || childId == null) {
            container.addView(placeholder(getString(R.string.parent_report_no_children)))
            return
        }

        container.addView(placeholder(getString(R.string.parent_report_loading)))

        val requestedFor = childId to period
        host.background({ client.fetchReport(childId, period) }) { report ->
            if (_binding == null) return@background
            // Discard a reply for a selection the parent has already moved past, the same rule
            // the keyboard applies to a late verification verdict.
            if (requestedFor != childInstallId to period) return@background

            binding.reportContainer.removeAllViews()
            if (report == null) {
                binding.reportContainer.addView(placeholder(getString(R.string.parent_report_failed)))
            } else {
                renderReport(report)
            }
        }
    }

    private fun renderReport(report: FamilyReport) {
        val context = requireContext()
        val container = binding.reportContainer

        if (report.isEmpty) {
            container.addView(placeholder(getString(R.string.parent_report_empty)))
            return
        }

        val card = card()
        val content = card.getChildAt(0) as LinearLayout

        content.addView(
            TextView(context).apply {
                text = report.summary
                setTextAppearance(R.style.TextAppearance_Keyguard_Label)
                setLineSpacing(dp(4).toFloat(), 1f)
            },
        )

        // The honesty line, directly under the narrative rather than at the bottom.
        content.addView(
            TextView(context).apply {
                setText(
                    when (report.basis) {
                        ReviewScope.CONCERNING_ONLY -> R.string.parent_report_basis_concerning
                        ReviewScope.THEMES -> R.string.parent_report_basis_themes
                        ReviewScope.FULL_TEXT -> R.string.parent_report_basis_full
                    },
                )
                setTextAppearance(R.style.TextAppearance_Keyguard_OptionHint)
                setPadding(0, dp(10), 0, 0)
            },
        )
        content.addView(
            TextView(context).apply {
                text = getString(
                    R.string.parent_report_counts,
                    report.messageCount,
                    report.flaggedCount,
                )
                setTextAppearance(R.style.TextAppearance_Keyguard_OptionHint)
            },
        )
        container.addView(card)

        // Each of these is absent on a quiet week, and correctly so. An empty "Worth a look" is
        // the answer most weeks should give, and rendering the heading over nothing would make
        // it look like something failed to load.
        addListCard(R.string.parent_report_concerns, report.concerns)
        addListCard(R.string.parent_report_interests, report.interests)
        addChipCard(
            R.string.parent_report_themes,
            report.themes.map { getString(themeLabel(it.theme)) to it.count },
        )
        addChipCard(
            R.string.parent_report_emotions,
            report.emotions.map { getString(emotionLabel(it.emotion)) to it.count },
        )

        container.addView(
            TextView(context).apply {
                text = getString(
                    R.string.parent_report_generated,
                    describe(report.generatedAt),
                )
                setTextAppearance(R.style.TextAppearance_Keyguard_OptionHint)
                setPadding(0, dp(4), 0, 0)
            },
        )
    }

    private fun addListCard(titleRes: Int, items: List<String>) {
        if (items.isEmpty()) return
        val context = requireContext()
        val card = card()
        val content = card.getChildAt(0) as LinearLayout

        content.addView(
            TextView(context).apply {
                setText(titleRes)
                setTextAppearance(R.style.TextAppearance_Keyguard_CardTitle)
                setPadding(0, 0, 0, dp(6))
            },
        )
        for (item in items) {
            content.addView(
                TextView(context).apply {
                    text = "•  $item"
                    setTextAppearance(R.style.TextAppearance_Keyguard_Body)
                    setPadding(0, dp(3), 0, dp(3))
                },
            )
        }
        binding.reportContainer.addView(card)
    }

    /**
     * Themes and moods as chips with counts.
     *
     * Counts are shown on the chip rather than left out, because a distribution is the only
     * honest way to read these — one message tagged "sad" means nothing and fifteen means
     * something, and a bare list of words hides exactly that difference.
     */
    private fun addChipCard(titleRes: Int, items: List<Pair<String, Int>>) {
        if (items.isEmpty()) return
        val context = requireContext()
        val card = card()
        val content = card.getChildAt(0) as LinearLayout

        content.addView(
            TextView(context).apply {
                setText(titleRes)
                setTextAppearance(R.style.TextAppearance_Keyguard_CardTitle)
                setPadding(0, 0, 0, dp(6))
            },
        )

        val group = ChipGroup(context)
        for ((label, count) in items) {
            group.addView(
                Chip(context).apply {
                    text = getString(R.string.parent_report_chip, label, count)
                    isClickable = false
                    isCheckable = false
                    chipBackgroundColor =
                        android.content.res.ColorStateList.valueOf(context.getColor(R.color.chip_surface))
                    setTextColor(context.getColor(R.color.chip_text))
                },
            )
        }
        content.addView(group)
        binding.reportContainer.addView(card)
    }

    private fun card(): MaterialCardView {
        val context = requireContext()
        return MaterialCardView(
            context,
            null,
            com.google.android.material.R.attr.materialCardViewOutlinedStyle,
        ).apply {
            radius = resources.getDimension(R.dimen.card_radius)
            cardElevation = 0f
            setCardBackgroundColor(context.getColor(R.color.card_surface))
            strokeColor = context.getColor(R.color.card_stroke)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                },
            )
        }
    }

    private fun placeholder(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_Keyguard_Body)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun describe(timestamp: Long): String =
        when (val elapsed = Elapsed.between(timestamp, System.currentTimeMillis())) {
            is Elapsed.JustNow -> getString(R.string.time_just_now)
            is Elapsed.Minutes -> getString(R.string.time_minutes, elapsed.value)
            is Elapsed.Hours -> getString(R.string.time_hours, elapsed.value)
            is Elapsed.Days -> getString(R.string.time_days, elapsed.value)
        }

    private fun themeLabel(theme: Theme): Int = when (theme) {
        Theme.SCHOOL -> R.string.theme_school
        Theme.FRIENDSHIP -> R.string.theme_friendship
        Theme.FAMILY -> R.string.theme_family
        Theme.ROMANCE -> R.string.theme_romance
        Theme.GAMING -> R.string.theme_gaming
        Theme.SPORT -> R.string.theme_sport
        Theme.MUSIC_AND_SHOWS -> R.string.theme_music
        Theme.ONLINE_LIFE -> R.string.theme_online
        Theme.MONEY -> R.string.theme_money
        Theme.FOOD -> R.string.theme_food
        Theme.HEALTH -> R.string.theme_health
        Theme.APPEARANCE -> R.string.theme_appearance
        Theme.FUTURE_PLANS -> R.string.theme_future
        Theme.TRAVEL -> R.string.theme_travel
        Theme.PETS -> R.string.theme_pets
        Theme.CREATIVE -> R.string.theme_creative
        Theme.CONFLICT -> R.string.theme_conflict
    }

    private fun emotionLabel(emotion: Emotion): Int = when (emotion) {
        Emotion.HAPPY -> R.string.emotion_happy
        Emotion.EXCITED -> R.string.emotion_excited
        Emotion.AFFECTIONATE -> R.string.emotion_affectionate
        Emotion.SAD -> R.string.emotion_sad
        Emotion.ANXIOUS -> R.string.emotion_anxious
        Emotion.ANGRY -> R.string.emotion_angry
        Emotion.LONELY -> R.string.emotion_lonely
        Emotion.STRESSED -> R.string.emotion_stressed
        Emotion.TIRED -> R.string.emotion_tired
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
