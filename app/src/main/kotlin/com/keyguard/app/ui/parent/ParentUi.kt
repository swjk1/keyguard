package com.keyguard.app.ui.parent

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import com.google.android.material.divider.MaterialDivider
import com.keyguard.app.R
import com.keyguard.app.databinding.ItemRowBinding
import com.keyguard.app.databinding.ViewParentEmptyBinding
import com.keyguard.app.family.ChildActivity
import com.keyguard.app.family.Elapsed
import com.keyguard.app.family.SupervisionEvent
import com.keyguard.app.ui.Rows
import com.keyguard.detect.Category
import com.keyguard.detect.Severity

/**
 * The small pieces every parent screen draws the same way: times, outcomes, severity colours,
 * avatars, status pills, dividers and empty states.
 *
 * One place for them because the failure mode of copies is not a crash but drift - the Family
 * card calling a warning "serious" in red while the Activity feed calls the same one amber.
 * A parent comparing the two screens would reasonably conclude one of them is wrong.
 */
internal object ParentUi {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** "2 h ago". Coarse on purpose - see [Elapsed]. */
    fun ago(context: Context, timestamp: Long, now: Long): String =
        when (val elapsed = Elapsed.between(timestamp, now)) {
            is Elapsed.JustNow -> context.getString(R.string.time_just_now)
            is Elapsed.Minutes -> context.getString(R.string.time_minutes, elapsed.value)
            is Elapsed.Hours -> context.getString(R.string.time_hours, elapsed.value)
            is Elapsed.Days -> context.getString(R.string.time_days, elapsed.value)
        }

    /** "Last seen 2 h ago", or the honest line for a phone that has never checked in. */
    fun lastSeen(context: Context, child: ChildActivity, now: Long): String =
        // "Hasn't reported yet" rather than a fabricated timestamp. A device that has never
        // synced is a real and common state - a child who paired and has not typed anything -
        // and showing it as "last seen just now" would be a lie.
        if (child.lastSeen == 0L) {
            context.getString(R.string.parent_child_never_seen)
        } else {
            context.getString(R.string.parent_child_last_seen, ago(context, child.lastSeen, now))
        }

    /** What the child did about the warning, as the end of a sentence. */
    fun outcome(context: Context, event: SupervisionEvent): String = context.getString(
        when {
            event.heeded -> R.string.parent_outcome_heeded
            event.outcome.name.startsWith("SENT") -> R.string.parent_outcome_sent
            else -> R.string.parent_outcome_abandoned
        },
    )

    fun severityLabel(context: Context, severity: Severity): String = context.getString(
        when (severity) {
            Severity.HIGH -> R.string.parent_severity_high
            Severity.MEDIUM -> R.string.parent_severity_medium
            else -> R.string.parent_severity_low
        },
    )

    /**
     * Red for serious, amber for moderate, grey below that - the same scale as the status
     * pills, so a red dot on a child's timeline means what a red pill on their card means.
     */
    @ColorRes
    fun severityColor(severity: Severity): Int = when (severity) {
        Severity.HIGH -> R.color.status_alert
        Severity.MEDIUM -> R.color.status_pending
        else -> R.color.status_neutral
    }

    @ColorRes
    fun severitySurface(severity: Severity): Int = when (severity) {
        Severity.HIGH -> R.color.status_alert_surface
        Severity.MEDIUM -> R.color.status_pending_surface
        else -> R.color.chip_surface
    }

    fun tintBackground(view: View, @ColorRes color: Int) {
        ViewCompat.setBackgroundTintList(
            view,
            ColorStateList.valueOf(view.context.getColor(color)),
        )
    }

    private val AVATAR_COLORS = listOf(
        R.color.avatar_1_bg to R.color.avatar_1_fg,
        R.color.avatar_2_bg to R.color.avatar_2_fg,
        R.color.avatar_3_bg to R.color.avatar_3_fg,
        R.color.avatar_4_bg to R.color.avatar_4_fg,
        R.color.avatar_5_bg to R.color.avatar_5_fg,
    )

    /** The child's initial on their colour. Same colour on every screen - see FamilyDigest. */
    fun bindAvatar(view: TextView, child: ChildActivity) {
        val (background, foreground) =
            AVATAR_COLORS[FamilyDigest.avatarIndex(child.installId, AVATAR_COLORS.size)]
        view.text = FamilyDigest.initial(child.label)
        view.setTextColor(view.context.getColor(foreground))
        tintBackground(view, background)
    }

    /** The coloured pill: words and colour together, so colour is never the only signal. */
    fun bindStatus(view: TextView, status: FamilyDigest.Status) {
        val context = view.context
        val resources = context.resources
        val (text, colors) = when (status) {
            FamilyDigest.Status.NotReporting ->
                context.getString(R.string.parent_status_not_reporting) to
                    (R.color.status_neutral to R.color.chip_surface)
            FamilyDigest.Status.AllClear ->
                context.getString(R.string.parent_status_all_clear) to
                    (R.color.status_ok to R.color.status_ok_surface)
            is FamilyDigest.Status.Warnings ->
                resources.getQuantityString(
                    R.plurals.parent_status_warnings, status.count, status.count,
                ) to (R.color.status_pending to R.color.status_pending_surface)
            is FamilyDigest.Status.Serious ->
                resources.getQuantityString(
                    R.plurals.parent_status_serious, status.count, status.count,
                ) to (R.color.status_alert to R.color.status_alert_surface)
        }
        @DrawableRes val icon = when (status) {
            FamilyDigest.Status.AllClear -> R.drawable.ic_check
            FamilyDigest.Status.NotReporting -> R.drawable.ic_pending
            else -> R.drawable.ic_parent_warning
        }
        val foreground = context.getColor(colors.first)
        view.text = text
        view.setTextColor(foreground)
        tintBackground(view, colors.second)
        val size = dp(context, 16)
        val drawable = context.getDrawable(icon)?.mutate()?.apply {
            setBounds(0, 0, size, size)
            setTint(foreground)
        }
        view.setCompoundDrawablesRelative(drawable, null, null, null)
    }

    /**
     * The severity disc used in the Activity feed. A heart rather than a warning sign for a
     * self-harm entry: what the child saw was a support message, and the parent's feed should
     * not dress the same moment up as wrongdoing. The colour still carries the severity.
     */
    fun bindSeverityIcon(view: ImageView, event: SupervisionEvent) {
        val severity = event.severity
        view.setImageResource(
            if (event.category == Category.SELF_HARM) {
                R.drawable.ic_parent_support
            } else {
                R.drawable.ic_parent_warning
            },
        )
        tintBackground(view, severitySurface(severity))
        view.imageTintList = ColorStateList.valueOf(view.context.getColor(severityColor(severity)))
    }

    /**
     * [Rows.add], with the icon in `accent_text` rather than the shared row's `accent`.
     *
     * The shared row tints its icon with the accent, which has no night variant; on the dark
     * `accent_soft` disc that is dark blue on dark blue. Retinted here rather than in the
     * shared layout, which the child app also uses and is not this screen's to change.
     */
    fun row(
        parent: ViewGroup,
        @DrawableRes icon: Int,
        title: CharSequence,
        subtitle: CharSequence? = null,
        onClick: (() -> Unit)? = null,
    ): ItemRowBinding = Rows.add(parent, icon, title, subtitle, onClick).also {
        it.rowIcon.imageTintList =
            ColorStateList.valueOf(parent.context.getColor(R.color.accent_text))
    }

    /** A hairline between rows in a card, inset to line up with the text rather than the icon. */
    fun divider(context: Context, insetStartDp: Int): View =
        MaterialDivider(context).apply {
            dividerInsetStart = dp(context, insetStartDp)
            dividerColor = context.getColor(R.color.card_stroke)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    /** A muted group heading ("Today", "Your family"). */
    fun sectionHeader(context: Context, text: CharSequence, topMarginDp: Int): TextView =
        TextView(context).apply {
            this.text = text
            setTextAppearance(R.style.TextAppearance_Keyguard_SectionHeader)
            ViewCompat.setAccessibilityHeading(this, true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, topMarginDp)
                bottomMargin = dp(context, 8)
                marginStart = dp(context, 4)
            }
        }

    /**
     * Fills an empty-state block. A null [action] hides the button; [loading] swaps the icon
     * for a spinner, so "still loading" and "genuinely empty" never look the same.
     */
    fun bindEmpty(
        binding: ViewParentEmptyBinding,
        @DrawableRes icon: Int,
        title: CharSequence,
        body: CharSequence?,
        actionLabel: CharSequence? = null,
        @DrawableRes actionIcon: Int = R.drawable.ic_parent_add,
        loading: Boolean = false,
        action: (() -> Unit)? = null,
    ) {
        binding.emptyIcon.setImageResource(icon)
        binding.emptyIcon.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        binding.emptyProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.emptyTitle.text = title
        binding.emptyBody.text = body
        binding.emptyBody.visibility = if (body.isNullOrBlank()) View.GONE else View.VISIBLE
        if (action != null && actionLabel != null) {
            binding.emptyAction.text = actionLabel
            binding.emptyAction.setIconResource(actionIcon)
            binding.emptyAction.setOnClickListener { action() }
            binding.emptyAction.visibility = View.VISIBLE
        } else {
            binding.emptyAction.visibility = View.GONE
        }
    }
}
