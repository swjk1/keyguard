package com.keyguard.detect

/**
 * What a message was *about*, as opposed to what was wrong with it.
 *
 * [Category] answers "is this dangerous". Theme answers "is this a normal Tuesday", and the two
 * are deliberately different vocabularies. A parent reading a weekly report needs the second
 * one far more often than the first: almost every week contains no findings at all, and a
 * report that can only say "nothing was flagged" is a report nobody opens twice.
 *
 * These are coarse on purpose, and the coarseness is the privacy argument. A theme set is
 * derived, lossy, and one-way — knowing a message touched [SCHOOL] and [FRIENDSHIP] does not
 * reconstruct a sentence, name a person, or identify a conversation. That is what makes it
 * shareable with a parent under a review scope of THEMES without breaking the promise that no text
 * leaves the device.
 *
 * The list is intentionally about a young person's ordinary life rather than a general topic
 * taxonomy. A theme earns its place by being something a parent would recognise as news about
 * their child, not by carving the space evenly.
 */
enum class Theme {
    SCHOOL,
    FRIENDSHIP,
    FAMILY,
    ROMANCE,
    GAMING,
    SPORT,
    MUSIC_AND_SHOWS,
    ONLINE_LIFE,
    MONEY,
    FOOD,
    HEALTH,
    APPEARANCE,
    FUTURE_PLANS,
    TRAVEL,
    PETS,
    CREATIVE,
    CONFLICT,
    ;

    companion object {
        fun fromName(name: String?): Theme? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The emotional colour of a message, as far as a word list can tell.
 *
 * **This is the weakest signal in the product and it should be read that way.** Lexical mood
 * detection cannot see sarcasm, cannot weigh "not happy" against "happy", and will call a
 * message about a sad film sad. It is included because the aggregate is more honest than any
 * single reading: one message tagged [SAD] means nothing, and thirty of them across a week
 * against a baseline of four means something worth a parent's attention.
 *
 * The report layer is therefore required to present emotions as a distribution over a period,
 * never as a verdict on a message — and the AI summary pass is what turns a distribution into
 * a sentence a parent can actually act on.
 */
enum class Emotion {
    HAPPY,
    EXCITED,
    AFFECTIONATE,
    SAD,
    ANXIOUS,
    ANGRY,
    LONELY,
    STRESSED,
    TIRED,
    ;

    /**
     * Whether this reading is one a parent should look at twice.
     *
     * Used for the report's "concerns" section, which is a different question from severity:
     * nothing here is a [Category] and none of it produces a warning. A run of [LONELY] is not
     * an incident, and treating it as one would be exactly the overreach that makes a
     * monitored teenager stop typing honestly.
     */
    val isNegative: Boolean
        get() = this == SAD || this == ANXIOUS || this == ANGRY ||
            this == LONELY || this == STRESSED

    companion object {
        fun fromName(name: String?): Emotion? = entries.firstOrNull { it.name == name }
    }
}

/**
 * One composed message, reduced to what may be reported.
 *
 * Produced once per finished message rather than per keystroke — see
 * [DetectionEngine.summarize] — because nothing here needs to be live and the keystroke budget
 * is spent on detection.
 *
 * Note there is no text field, for the same reason [Finding] has none. Where a parent has
 * turned on full-text review the message body travels in a separate structure that says so in
 * its name, so no code path can carry text by accident while believing it holds a summary.
 */
data class MessageSummary(
    val themes: List<Theme>,
    val emotions: List<Emotion>,
    /** Length of the composed message. A volume signal, not content. */
    val chars: Int,
) {
    val isEmpty: Boolean get() = themes.isEmpty() && emotions.isEmpty()

    companion object {
        val EMPTY = MessageSummary(emptyList(), emptyList(), 0)
    }
}
