package com.keyguard.app.ui.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.keyguard.app.R
import com.keyguard.app.databinding.FragmentChildrenBinding
import com.keyguard.app.family.ChildActivity
import com.keyguard.app.family.Elapsed
import com.keyguard.app.family.PairingCode
import com.keyguard.app.family.ReportedEvent
import com.keyguard.app.ui.SectionFragment
import com.keyguard.detect.Category
import com.keyguard.detect.Severity

/**
 * Who is paired, and what has happened.
 *
 * The section a parent opens the app to reach, so it contains nothing configurable — the policy
 * editor that used to sit directly above the activity feed is its own tab now. That was the
 * single worst thing about the old screen: six radio groups between a parent and the thing they
 * came to look at, which made every visit feel like a settings screen.
 *
 * Each child is a card with a status line and its recent warnings, rather than a heading
 * followed by a flat run of text. With more than one child the old layout gave no visual
 * boundary at all between one child's events and the next's.
 */
class ChildrenFragment : SectionFragment() {

    private var _binding: FragmentChildrenBinding? = null
    private val binding get() = _binding!!

    private val host get() = requireActivity() as ParentHost

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChildrenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.codeButton.setOnClickListener { requestPairingCode() }
        binding.refreshButton.setOnClickListener {
            binding.refreshButton.isEnabled = false
            host.reload()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh() {
        if (_binding == null) return
        binding.refreshButton.isEnabled = true
        binding.statusText.visibility = View.GONE
        renderChildren(host.overview?.children.orEmpty())
    }

    private fun requestPairingCode() {
        val client = host.client ?: return
        binding.codeButton.isEnabled = false

        host.background({ client.issuePairingCode() }) { invite ->
            if (_binding == null) return@background
            binding.codeButton.isEnabled = true
            if (invite == null) {
                showStatus(getString(R.string.parent_failed))
                return@background
            }
            binding.codeText.text = PairingCode.format(invite.code)
            binding.codeText.visibility = View.VISIBLE
            binding.statusText.visibility = View.GONE
            // Asking for a code creates the family server-side, so the overview now has one.
            host.reload()
        }
    }

    private fun renderChildren(children: List<ChildActivity>) {
        val container = binding.childrenContainer
        container.removeAllViews()

        if (children.isEmpty()) {
            container.addView(
                body(getString(R.string.parent_children_empty)).apply {
                    setPadding(0, dp(8), 0, dp(8))
                },
            )
            return
        }

        val now = System.currentTimeMillis()
        for (child in children) container.addView(childCard(child, now))
    }

    private fun childCard(child: ChildActivity, now: Long): View {
        val context = requireContext()
        val card = MaterialCardView(
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
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        content.addView(
            TextView(context).apply {
                text = child.label
                setTextAppearance(R.style.TextAppearance_Keyguard_CardTitle)
            },
        )
        content.addView(
            TextView(context).apply {
                // "Hasn't reported yet" rather than a fabricated timestamp. A device that has
                // never synced is a real and common state - a child who paired and has not typed
                // anything - and showing it as "last seen just now" would be a lie.
                text = if (child.lastSeen == 0L) {
                    getString(R.string.parent_child_never_seen)
                } else {
                    getString(R.string.parent_child_last_seen, describe(child.lastSeen, now))
                }
                setTextAppearance(R.style.TextAppearance_Keyguard_OptionHint)
                setPadding(0, dp(2), 0, dp(10))
            },
        )

        if (child.events.isEmpty()) {
            content.addView(body(getString(R.string.parent_child_quiet)))
        } else {
            content.addView(
                TextView(context).apply {
                    setText(R.string.parent_child_events_heading)
                    setTextAppearance(R.style.TextAppearance_Keyguard_Label)
                    setPadding(0, 0, 0, dp(4))
                },
            )
            // Capped rather than unbounded. A hundred events is a few kilobytes on the wire and
            // an unreadable wall on screen; the recent end is what a parent is looking at.
            for (reported in child.events.take(MAX_EVENTS_SHOWN)) {
                content.addView(eventRow(reported, now))
            }
            if (child.events.size > MAX_EVENTS_SHOWN) {
                content.addView(
                    body(
                        getString(
                            R.string.parent_child_more_events,
                            child.events.size - MAX_EVENTS_SHOWN,
                        ),
                    ),
                )
            }
        }

        content.addView(
            MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                setText(R.string.parent_remove_child)
                setTextColor(context.getColor(R.color.warn_medium))
                setOnClickListener { confirmRemoval(child) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            },
        )

        card.addView(content)
        return card
    }

    /** One warning as a sentence: what kind, what they did, how long ago. */
    private fun eventRow(reported: ReportedEvent, now: Long): View {
        val context = requireContext()
        val event = reported.event
        val outcome = when {
            event.heeded -> getString(R.string.parent_outcome_heeded)
            event.outcome.name.startsWith("SENT") -> getString(R.string.parent_outcome_sent)
            else -> getString(R.string.parent_outcome_abandoned)
        }

        return TextView(context).apply {
            text = getString(
                R.string.parent_event_line,
                describe(reported.receivedAt, now),
                getString(categoryLabel(event.category)),
                outcome,
            )
            setTextAppearance(R.style.TextAppearance_Keyguard_Body)
            // High severity is the only thing coloured, and the sentence still says what
            // happened, so colour is never the only signal.
            if (event.severity == Severity.HIGH) {
                setTextColor(context.getColor(R.color.warn_medium))
            }
            setPadding(0, dp(3), 0, dp(3))
        }
    }

    private fun confirmRemoval(child: ChildActivity) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.parent_remove_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.parent_remove_child) { _, _ ->
                val client = host.client ?: return@setPositiveButton
                host.background({ client.removeChild(child.installId) }) { removed ->
                    if (removed == true) host.reload() else showStatus(getString(R.string.parent_failed))
                }
            }
            .show()
    }

    private fun showStatus(message: String) {
        if (_binding == null) return
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
    }

    private fun body(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_Keyguard_Body)
    }

    private fun describe(timestamp: Long, now: Long): String =
        when (val elapsed = Elapsed.between(timestamp, now)) {
            is Elapsed.JustNow -> getString(R.string.time_just_now)
            is Elapsed.Minutes -> getString(R.string.time_minutes, elapsed.value)
            is Elapsed.Hours -> getString(R.string.time_hours, elapsed.value)
            is Elapsed.Days -> getString(R.string.time_days, elapsed.value)
        }

    private fun categoryLabel(category: Category): Int = when (category) {
        Category.PII_DISCLOSURE -> R.string.category_pii
        Category.HARASSMENT -> R.string.category_harassment
        Category.SEXUAL_SOLICITATION -> R.string.category_solicitation
        Category.SELF_HARM -> R.string.category_self_harm
        Category.VIOLENCE_THREAT -> R.string.category_violence
        Category.IN_PERSON_MEETUP -> R.string.category_meetup
        Category.SUBSTANCE -> R.string.category_substance
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_EVENTS_SHOWN = 12
    }
}
