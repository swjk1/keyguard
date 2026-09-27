package com.keyguard.app.ui.parent

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentChildDetailBinding
import com.keyguard.app.databinding.ItemTimelineBinding
import com.keyguard.app.family.ChildActivity
import com.keyguard.app.family.FamilyReport
import com.keyguard.app.family.ReportPeriod
import com.keyguard.app.family.ReportedEvent
import com.keyguard.app.ui.CategoryLabels
import com.keyguard.app.ui.SectionFragment
import java.util.Date

/**
 * One child's page: header, warning timeline, report, and the device.
 *
 * Pushed over the Family or Activity tab. It reads the child out of the shared overview by
 * install id on every refresh rather than holding a copy, so a reload - the parent pressing
 * Refresh, or coming back to the app - redraws it with the rest of the app. If the child is no
 * longer in the overview (another caregiver unpaired them) the page closes itself rather than
 * showing a device the family no longer has.
 *
 * The report was its own tab before. It is fetched here, for this child only, when the page is
 * shown or the period changes - not on every overview reload, which would re-run a model-written
 * summary each time a parent pressed Refresh to see if a new warning had come in.
 */
class ChildDetailFragment : SectionFragment(), ParentScreen {

    private var _binding: FragmentChildDetailBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost

    private val installId: String get() = requireArguments().getString(ARG_INSTALL_ID).orEmpty()

    /** Kept for the toolbar across a rotation, before the overview has reloaded. */
    private var label: String? = null

    private var period: ReportPeriod = ReportPeriod.DAY
    private var showAll = false

    /** Which (period) the report on screen belongs to, so a refresh does not refetch it. */
    private var reportShownFor: ReportPeriod? = null
    private var reportRequestedFor: ReportPeriod? = null

    override fun screenTitle(): CharSequence = label ?: getString(R.string.parent_tab_family)

    override val offersRefresh: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            label = it.getString(STATE_LABEL)
            period = ReportPeriod.fromName(it.getString(STATE_PERIOD))
            showAll = it.getBoolean(STATE_SHOW_ALL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LABEL, label)
        outState.putString(STATE_PERIOD, period.name)
        outState.putBoolean(STATE_SHOW_ALL, showAll)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChildDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reportShownFor = null
        reportRequestedFor = null
        binding.periodToggle.check(
            if (period == ReportPeriod.WEEK) R.id.periodWeek else R.id.periodDay,
        )
        binding.periodToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            period = if (checkedId == R.id.periodWeek) ReportPeriod.WEEK else ReportPeriod.DAY
            loadReport()
        }
        binding.showAllButton.setOnClickListener {
            showAll = !showAll
            refresh()
        }
        binding.unpairButton.setOnClickListener { confirmRemoval() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        val overview = host.overview
        val child = overview?.children?.firstOrNull { it.installId == installId }

        if (child == null) {
            // Unpaired from somewhere else. Only once an overview has actually loaded: before
            // the first load after a rotation, "not in the overview" means "no overview yet".
            if (overview != null) close()
            return
        }

        if (label != child.label) {
            label = child.label
            host.onScreenChanged()
        }
        val now = System.currentTimeMillis()
        renderHeader(child, now)
        renderTimeline(child, now)
        renderDevice(child)
        // Hidden behind another detail (none today) or mid-transition: leave the report be.
        if (!isHidden && reportShownFor != period && reportRequestedFor != period) loadReport()
    }

    private fun renderHeader(child: ChildActivity, now: Long) {
        ParentUi.bindAvatar(binding.detailAvatar, child)
        binding.detailName.text = child.label
        binding.detailLastSeen.text = ParentUi.lastSeen(requireContext(), child, now)
        ParentUi.bindStatus(binding.detailStatus, FamilyDigest.status(child, now))
    }

    /**
     * The warning timeline, capped until the parent asks for more.
     *
     * Capped rather than unbounded: a hundred events is a few kilobytes on the wire and an
     * unreadable wall on screen, and the recent end is what a parent is looking at.
     */
    private fun renderTimeline(child: ChildActivity, now: Long) {
        val container = binding.timelineContainer
        container.removeAllViews()
        val ordered = child.events.sortedByDescending { it.receivedAt }

        binding.timelineEmpty.visibility = if (ordered.isEmpty()) View.VISIBLE else View.GONE
        if (ordered.isEmpty()) renderTimelineEmpty(neverReported = child.lastSeen == 0L)
        val shown = if (showAll) ordered else ordered.take(TIMELINE_CAP)
        shown.forEachIndexed { index, reported ->
            bindTimelineItem(reported, now, first = index == 0, last = index == shown.lastIndex)
        }

        if (ordered.size > TIMELINE_CAP) {
            binding.showAllButton.visibility = View.VISIBLE
            binding.showAllButton.text = if (showAll) {
                getString(R.string.parent_show_fewer_warnings)
            } else {
                getString(R.string.parent_show_all_warnings, ordered.size)
            }
        } else {
            binding.showAllButton.visibility = View.GONE
        }
    }

    /**
     * A green tick for a quiet month; a neutral "waiting" for a phone that has never checked
     * in, which has told us nothing and must not look like a clean record.
     */
    private fun renderTimelineEmpty(neverReported: Boolean) {
        val (icon, foreground, surface) = if (neverReported) {
            Triple(R.drawable.ic_pending, R.color.status_neutral, R.color.chip_surface)
        } else {
            Triple(R.drawable.ic_check, R.color.status_ok, R.color.status_ok_surface)
        }
        binding.timelineEmptyIcon.setImageResource(icon)
        binding.timelineEmptyIcon.imageTintList =
            ColorStateList.valueOf(requireContext().getColor(foreground))
        ParentUi.tintBackground(binding.timelineEmptyIcon, surface)
        binding.timelineEmptyTitle.setText(
            if (neverReported) R.string.parent_timeline_waiting_title
            else R.string.parent_timeline_empty_title,
        )
        binding.timelineEmptyBody.setText(
            if (neverReported) R.string.parent_timeline_waiting_body
            else R.string.parent_timeline_empty_body,
        )
    }

    private fun bindTimelineItem(
        reported: ReportedEvent,
        now: Long,
        first: Boolean,
        last: Boolean,
    ) {
        val context = requireContext()
        val event = reported.event
        val item = ItemTimelineBinding.inflate(layoutInflater, binding.timelineContainer, true)
        val category = getString(CategoryLabels.res(event.category))
        val severity = ParentUi.severityLabel(context, event.severity)
        val outcome = ParentUi.outcome(context, event)
        val time = ParentUi.ago(context, reported.receivedAt, now)

        item.timelineTitle.text = category
        item.timelineSubtitle.text = getString(R.string.parent_timeline_line, severity, outcome)
        item.timelineTime.text = time
        ParentUi.tintBackground(item.timelineDot, ParentUi.severityColor(event.severity))
        // The rail joins dots; it does not run off above the first or below the last.
        item.railTop.visibility = if (first) View.INVISIBLE else View.VISIBLE
        item.railBottom.visibility = if (last) View.INVISIBLE else View.VISIBLE
        item.root.contentDescription =
            getString(R.string.parent_warning_description, severity, category, outcome, time)
    }

    private fun renderDevice(child: ChildActivity) {
        val rows = binding.deviceRows
        rows.removeAllViews()
        val paired = if (child.pairedAt > 0) {
            getString(
                R.string.parent_paired_on,
                DateFormat.getMediumDateFormat(requireContext()).format(Date(child.pairedAt)),
            )
        } else {
            null
        }
        ParentUi.row(rows, R.drawable.ic_parent_phone, child.label, paired)
    }

    private fun loadReport() {
        if (_binding == null) return
        val client = host.client ?: return
        val requested = period
        reportRequestedFor = requested
        // Keep whatever is on screen while the next one loads, unless it is for the other
        // period - then "Loading…" is more honest than yesterday's answer to a different
        // question.
        if (reportShownFor != requested) {
            ReportRenderer(requireContext(), binding.reportContainer)
                .placeholder(getString(R.string.parent_report_loading))
        }

        val childId = installId
        host.background({ client.fetchReport(childId, requested) }) { report: FamilyReport? ->
            if (_binding == null) return@background
            // Discard a reply for a period the parent has already moved past, the same rule
            // the keyboard applies to a late verification verdict.
            if (requested != period) return@background
            reportRequestedFor = null
            val renderer = ReportRenderer(requireContext(), binding.reportContainer)
            if (report == null) {
                reportShownFor = null
                renderer.placeholder(getString(R.string.parent_report_failed))
            } else {
                reportShownFor = requested
                renderer.render(report, System.currentTimeMillis())
            }
        }
    }

    private fun confirmRemoval() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.parent_remove_child)
            .setMessage(R.string.parent_remove_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.parent_remove_child) { _, _ -> unpair() }
            .show()
    }

    private fun unpair() {
        val client = host.client ?: return
        val childId = installId
        binding.unpairButton.isEnabled = false
        binding.unpairButton.setText(R.string.parent_unpairing)
        binding.detailStatusText.visibility = View.GONE
        // Captured: the parent may have backed out of this page before the reply lands, and
        // the reload must still happen then.
        val parent = host
        parent.background({ client.removeChild(childId) }) { removed ->
            if (removed == true) {
                // Closed first, then reloaded, so the Family tab redraws without them.
                close()
                parent.reload()
                return@background
            }
            if (_binding == null) return@background
            binding.unpairButton.isEnabled = true
            binding.unpairButton.setText(R.string.parent_remove_child)
            binding.detailStatusText.setText(R.string.parent_unpair_failed)
            binding.detailStatusText.visibility = View.VISIBLE
        }
    }

    /**
     * Pops this page, once. Refreshes can arrive in a burst (a reload lands while the unpair
     * reply is being handled), and each asking for a pop would pop the tab's other screens
     * too. Also a no-op once the parent has already backed out of this page themselves.
     */
    private fun close() {
        if (closing || !isAdded || isRemoving || isHidden) return
        closing = true
        host.closeTop()
    }

    private var closing = false

    companion object {
        private const val ARG_INSTALL_ID = "installId"
        private const val STATE_LABEL = "label"
        private const val STATE_PERIOD = "period"
        private const val STATE_SHOW_ALL = "showAll"
        private const val TIMELINE_CAP = 8

        fun newInstance(installId: String) = ChildDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_INSTALL_ID, installId) }
        }
    }
}
