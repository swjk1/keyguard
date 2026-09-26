package com.keyguard.app.ui.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentFamilyHomeBinding
import com.keyguard.app.databinding.ItemChildCardBinding
import com.keyguard.app.family.ChildActivity
import com.keyguard.app.ui.CategoryLabels
import com.keyguard.app.ui.SectionFragment

/**
 * The parent's home: one card per child.
 *
 * The screen a parent opens the app to reach, so it contains nothing configurable and nothing
 * destructive. Each card answers "are they alright?" - status pill, last check-in, the latest
 * warning - and opens that child's page, where the timeline, the report and Unpair live. With
 * more than one child the old single scroll gave no boundary between one child's events and
 * the next's; now each child is a card of its own and the detail is one tap away.
 *
 * Four states, drawn distinctly so none can be mistaken for another: loading (a spinner, not
 * an empty family), unreachable (an error with Try again, only when nothing has ever loaded),
 * empty (a friendly first step with Add a child), and the family itself.
 */
class FamilyHomeFragment : SectionFragment(), ParentScreen {

    private var _binding: FragmentFamilyHomeBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost

    /**
     * For the "alerts are off" banner. A refusal is recoverable rather than terminal: the
     * banner stays while there are children and no permission, so a parent who said no once
     * is not left with a feature that silently does nothing forever.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun screenTitle(): CharSequence = getString(R.string.parent_tab_family)

    override val offersRefresh: Boolean get() = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFamilyHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.addChildFab.setOnClickListener { host.open(AddChildFragment()) }
        binding.alertsButton.setOnClickListener {
            AlertPermission.fix(this, notificationPermission)
        }
        // The button tucks to an icon while the list scrolls and comes back at rest, so a long
        // family list is never read through a label sitting on top of the last card.
        binding.familyScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            when {
                scrollY > oldScrollY + 4 -> binding.addChildFab.shrink()
                scrollY < oldScrollY - 4 -> binding.addChildFab.extend()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        val overview = host.overview
        val children = overview?.children.orEmpty()

        val muted = children.isNotEmpty() && AlertPermission.muted(requireContext())
        binding.alertsCard.visibility = if (muted) View.VISIBLE else View.GONE

        when {
            overview == null && host.lastLoadFailed -> showEmpty(
                icon = R.drawable.ic_parent_offline,
                title = R.string.parent_family_error_title,
                body = R.string.parent_family_error_body,
                action = R.string.parent_try_again,
                actionIcon = R.drawable.ic_parent_refresh,
            ) { host.reload(visible = true) }

            overview == null -> showEmpty(
                icon = R.drawable.ic_nav_family,
                title = R.string.parent_family_loading,
                body = null,
                loading = true,
            )

            children.isEmpty() -> showEmpty(
                icon = R.drawable.ic_nav_family,
                title = R.string.parent_family_empty_title,
                body = R.string.parent_family_empty_body,
                action = R.string.parent_add_child_action,
            ) { host.open(AddChildFragment()) }

            else -> showChildren(children)
        }
    }

    private fun showEmpty(
        icon: Int,
        title: Int,
        body: Int?,
        action: Int? = null,
        actionIcon: Int = R.drawable.ic_parent_add,
        loading: Boolean = false,
        onAction: (() -> Unit)? = null,
    ) {
        binding.childrenContainer.removeAllViews()
        binding.familyHeading.visibility = View.GONE
        binding.addChildFab.visibility = View.GONE
        binding.emptyState.root.visibility = View.VISIBLE
        ParentUi.bindEmpty(
            binding.emptyState,
            icon = icon,
            title = getString(title),
            body = body?.let(::getString),
            actionLabel = action?.let(::getString),
            actionIcon = actionIcon,
            loading = loading,
            action = onAction,
        )
    }

    private fun showChildren(children: List<ChildActivity>) {
        binding.emptyState.root.visibility = View.GONE
        binding.familyHeading.visibility = View.VISIBLE
        binding.addChildFab.visibility = View.VISIBLE

        val container = binding.childrenContainer
        container.removeAllViews()
        val now = System.currentTimeMillis()
        for (child in children) bindCard(child, now)
    }

    private fun bindCard(child: ChildActivity, now: Long) {
        val context = requireContext()
        val card = ItemChildCardBinding.inflate(layoutInflater, binding.childrenContainer, true)
        ParentUi.bindAvatar(card.childAvatar, child)
        card.childName.text = child.label
        card.childLastSeen.text = ParentUi.lastSeen(context, child, now)
        val status = FamilyDigest.status(child, now)
        ParentUi.bindStatus(card.childStatus, status)

        // A phone that has never checked in has no "latest" of any kind. "No warnings in the
        // last 30 days" under it would read as a clean month rather than as silence.
        val reporting = status != FamilyDigest.Status.NotReporting
        card.childLatestDivider.visibility = if (reporting) View.VISIBLE else View.GONE
        card.childLatestRow.visibility = if (reporting) View.VISIBLE else View.GONE

        val latest = FamilyDigest.latest(child)
        if (latest == null) {
            card.childLatest.text = getString(R.string.parent_no_recent_warnings)
            ParentUi.tintBackground(card.childLatestDot, R.color.status_ok)
        } else {
            card.childLatest.text = getString(
                R.string.parent_latest_warning,
                getString(CategoryLabels.res(latest.event.category)),
                ParentUi.outcome(context, latest.event),
                ParentUi.ago(context, latest.receivedAt, now),
            )
            ParentUi.tintBackground(
                card.childLatestDot,
                ParentUi.severityColor(latest.event.severity),
            )
        }
        card.root.contentDescription = listOfNotNull(
            child.label,
            card.childLastSeen.text,
            card.childStatus.text,
            card.childLatest.text.takeIf { reporting },
        ).joinToString(". ")
        card.root.setOnClickListener { host.openChild(child.installId) }
    }
}
