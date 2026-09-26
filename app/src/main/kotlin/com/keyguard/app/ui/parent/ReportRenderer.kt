package com.keyguard.app.ui.parent

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.keyguard.app.R
import com.keyguard.app.family.FamilyReport
import com.keyguard.app.family.ReviewScope
import com.keyguard.detect.Emotion
import com.keyguard.detect.Theme

/**
 * Draws one daily or weekly report into a container, on a child's page.
 *
 * This was the body of the old Reports tab. It moved to the child's page because a report is
 * about one child, and a tab with a chip row to pick whose report you are reading was a
 * second way of choosing a child that the Family tab already is.
 *
 * The order on screen is deliberate and is the opposite of how the data arrives: the narrative
 * first, then the line saying what it was built from, then the counts, then the detail. A parent
 * reading a fluent paragraph about their child needs to know immediately whether it came from
 * keyword tags or from their actual messages, because the two deserve very different amounts of
 * trust and read identically otherwise.
 */
internal class ReportRenderer(private val context: Context, private val container: LinearLayout) {

    fun placeholder(text: CharSequence) {
        container.removeAllViews()
        val card = card()
        content(card).addView(
            TextView(context).apply {
                this.text = text
                setTextAppearance(R.style.TextAppearance_Keyguard_Body)
            },
        )
        container.addView(card)
    }

    fun render(report: FamilyReport, now: Long) {
        container.removeAllViews()

        if (report.isEmpty) {
            placeholder(context.getString(R.string.parent_report_empty))
            return
        }

        val card = card()
        val content = content(card)
        content.addView(
            TextView(context).apply {
                text = report.summary
                setTextAppearance(R.style.TextAppearance_Keyguard_Label)
                setLineSpacing(ParentUi.dp(context, 4).toFloat(), 1f)
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
                setPadding(0, ParentUi.dp(context, 12), 0, 0)
            },
        )
        content.addView(
            TextView(context).apply {
                text = context.getString(
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
            report.themes.map { context.getString(themeLabel(it.theme)) to it.count },
        )
        addChipCard(
            R.string.parent_report_emotions,
            report.emotions.map { context.getString(emotionLabel(it.emotion)) to it.count },
        )

        container.addView(
            TextView(context).apply {
                text = context.getString(
                    R.string.parent_report_generated,
                    ParentUi.ago(context, report.generatedAt, now),
                )
                setTextAppearance(R.style.TextAppearance_Keyguard_OptionHint)
                setPadding(ParentUi.dp(context, 4), 0, 0, ParentUi.dp(context, 8))
            },
        )
    }

    private fun addListCard(titleRes: Int, items: List<String>) {
        if (items.isEmpty()) return
        val card = card()
        val content = content(card)
        content.addView(title(titleRes))
        for (item in items) {
            content.addView(
                TextView(context).apply {
                    text = "•  $item"
                    setTextAppearance(R.style.TextAppearance_Keyguard_Body)
                    setPadding(0, ParentUi.dp(context, 3), 0, ParentUi.dp(context, 3))
                },
            )
        }
        container.addView(card)
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
        val card = card()
        val content = content(card)
        content.addView(title(titleRes))

        val group = ChipGroup(context)
        for ((label, count) in items) {
            group.addView(
                Chip(context).apply {
                    text = context.getString(R.string.parent_report_chip, label, count)
                    isClickable = false
                    isCheckable = false
                    chipBackgroundColor =
                        ColorStateList.valueOf(context.getColor(R.color.chip_surface))
                    chipStrokeWidth = 0f
                    setTextColor(context.getColor(R.color.chip_text))
                },
            )
        }
        content.addView(group)
        container.addView(card)
    }

    private fun title(titleRes: Int) = TextView(context).apply {
        setText(titleRes)
        setTextAppearance(R.style.TextAppearance_Keyguard_CardTitle)
        setPadding(0, 0, 0, ParentUi.dp(context, 6))
    }

    private fun card(): MaterialCardView {
        val card = LayoutInflater.from(context)
            .inflate(R.layout.item_parent_card, container, false) as MaterialCardView
        return card
    }

    private fun content(card: MaterialCardView): LinearLayout = card.getChildAt(0) as LinearLayout

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
}
