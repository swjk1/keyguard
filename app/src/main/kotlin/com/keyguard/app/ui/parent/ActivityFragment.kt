package com.keyguard.app.ui.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentActivityBinding
import com.keyguard.app.databinding.ItemFeedWarningBinding
import com.keyguard.app.family.ReviewScope
import com.keyguard.app.ui.CategoryLabels
import com.keyguard.app.ui.SectionFragment
import java.time.ZoneId

/**
 * Every child's warnings, newest first, grouped by day.
 *
 * The same events the Family cards summarise, cut the other way: by time instead of by child.
 * A parent with two children who wants to know "did anything happen today?" otherwise has to
 * open each child's page in turn. Each entry says whose phone it came from and opens that
 * child's page.
 *
 * Nothing here is new data. It is drawn from the overview the activity already holds, so it
 * costs no request of its own and can never disagree with the Family tab.
 */
class ActivityFragment : SectionFragment(), ParentScreen {

    private var _binding: FragmentActivityBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost

    override fun screenTitle(): CharSequence = getString(R.string.parent_tab_activity)

    override val offersRefresh: Boolean get() = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentActivityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        val overview = host.overview
        val children = overview?.children.orEmpty()
        val now = System.currentTimeMillis()
        val groups = FamilyDigest.feed(children, now, ZoneId.systemDefault())

        binding.feedContainer.removeAllViews()
        // Only true below full review. At that scope the parent can read messages, and a line
        // saying they never can would be the one false sentence in the app.
        binding.feedPrivacyNote.visibility =
            if (groups.isNotEmpty() && overview?.policy?.reviewScope != ReviewScope.FULL_TEXT) {
                View.VISIBLE
            } else {
                View.GONE
            }

        if (groups.isEmpty()) {
            binding.emptyState.root.visibility = View.VISIBLE
            when {
                overview == null && host.lastLoadFailed -> ParentUi.bindEmpty(
                    binding.emptyState,
                    icon = R.drawable.ic_parent_offline,
                    title = getString(R.string.parent_family_error_title),
                    body = getString(R.string.parent_family_error_body),
                    actionLabel = getString(R.string.parent_try_again),
                    actionIcon = R.drawable.ic_parent_refresh,
                ) { host.reload(visible = true) }

                overview == null -> ParentUi.bindEmpty(
                    binding.emptyState,
                    icon = R.drawable.ic_nav_activity,
                    title = getString(R.string.parent_family_loading),
                    body = null,
                    loading = true,
                )

                else -> ParentUi.bindEmpty(
                    binding.emptyState,
                    icon = R.drawable.ic_nav_activity,
                    title = getString(R.string.parent_feed_empty_title),
                    body = getString(
                        if (children.isEmpty()) {
                            R.string.parent_feed_no_children_body
                        } else {
                            R.string.parent_feed_empty_body
                        },
                    ),
                )
            }
            return
        }

        binding.emptyState.root.visibility = View.GONE
        groups.forEachIndexed { index, group ->
            binding.feedContainer.addView(
                ParentUi.sectionHeader(
                    requireContext(),
                    getString(dayLabel(group.day)),
                    topMarginDp = if (index == 0) 4 else 12,
                ),
            )
            binding.feedContainer.addView(groupCard(group, now))
        }
    }

    private fun groupCard(group: FamilyDigest.FeedGroup, now: Long): View {
        val context = requireContext()
        val card = layoutInflater.inflate(
            R.layout.item_parent_card,
            binding.feedContainer,
            false,
        ) as MaterialCardView
        val rows = card.getChildAt(0) as LinearLayout
        // The shared card pads its body for prose; a list of rows runs edge to edge instead,
        // so each row's ripple fills the card and the dividers line up with the text.
        rows.setPadding(0, ParentUi.dp(context, 4), 0, ParentUi.dp(context, 4))

        group.items.forEachIndexed { index, item ->
            if (index > 0) rows.addView(ParentUi.divider(context, insetStartDp = 72))
            val row = ItemFeedWarningBinding.inflate(layoutInflater, rows, true)
            val event = item.reported.event
            val category = getString(CategoryLabels.res(event.category))
            val outcome = ParentUi.outcome(context, event)
            val time = ParentUi.ago(context, item.reported.receivedAt, now)

            ParentUi.bindSeverityIcon(row.feedIcon, event)
            row.feedTitle.text = category
            row.feedSubtitle.text =
                getString(R.string.parent_timeline_line, item.child.label, outcome)
            row.feedTime.text = time
            row.root.contentDescription = getString(
                R.string.parent_warning_description,
                ParentUi.severityLabel(context, event.severity),
                category,
                "${item.child.label}, $outcome",
                time,
            )
            row.root.setOnClickListener { host.openChild(item.child.installId) }
        }
        return card
    }

    private fun dayLabel(day: FamilyDigest.Day): Int = when (day) {
        FamilyDigest.Day.TODAY -> R.string.parent_feed_today
        FamilyDigest.Day.YESTERDAY -> R.string.parent_feed_yesterday
        FamilyDigest.Day.EARLIER -> R.string.parent_feed_earlier
    }
}
