package com.keyguard.app.family

/**
 * How much of what a child types their parent can see.
 *
 * **This is the setting that decides whether the product's central privacy claim still holds**,
 * so it is worth being blunt about what each rung costs rather than describing them as three
 * points on a slider.
 *
 * Until this existed, the answer was fixed at [CONCERNING_ONLY] and the whole codebase was
 * built around it: [SupervisionEvent] has no text field, the server's ingest schema is
 * `strict()`, and `SupervisionEventTest` asserts the absence against the serialized bytes. That
 * arrangement is not being dismantled — [CONCERNING_ONLY] remains the default and remains
 * exactly as it was. What is being added is a rung a parent can *choose* to climb, with the
 * child told each time.
 *
 * The middle rung is the one worth arguing for. A parent asking "what is my kid actually up to"
 * is not usually asking to read their messages; they are asking whether this week was normal.
 * [THEMES] answers that question from derived tags — school, a falling-out, three anxious days
 * — while no sentence ever leaves the device. Offering only "nothing" and "everything" would
 * push families to "everything" for a question that never needed it.
 *
 * ### What each rung changes off the device
 *
 * | | events | per-message tags | message text |
 * |---|---|---|---|
 * | [CONCERNING_ONLY] | flagged only | no | never |
 * | [THEMES] | flagged only | every message | never |
 * | [FULL_TEXT] | flagged only | every message | every message |
 *
 * Note that the *warning* stream is identical at all three. Raising the scope never makes the
 * keyboard interrupt more; it only changes what is reported afterwards. Those are separate
 * decisions and conflating them would mean a parent who wanted a better weekly summary
 * accidentally made their child's keyboard stricter.
 */
enum class ReviewScope {
    /**
     * Only warnings, and only category, severity, outcome and a timestamp.
     *
     * The default, and the promise the product was built on. Nothing a child types is recorded
     * unless the engine flagged it, and even then the words are not.
     */
    CONCERNING_ONLY,

    /**
     * Every message contributes derived themes and mood. Still no text, ever.
     *
     * A [com.keyguard.detect.MessageSummary] is one-way: a list of at most three themes and two
     * emotions cannot be read back as a sentence, name a person, or identify a conversation.
     * That is what makes this rung defensible to a monitored teenager in a way [FULL_TEXT] is
     * not — and the child's supervision screen says which rung is in force, in these terms.
     */
    THEMES,

    /**
     * Every message's text is captured and readable by the parent.
     *
     * **This breaks the invariant the rest of the codebase enforces**, deliberately and on the
     * parent's explicit instruction. Turning it on changes the Play Data Safety declaration,
     * changes what the child-facing disclosure has to say, and moves the product from "a
     * keyboard that warns you" to "a keyboard that reports you". It is worth having because
     * some families genuinely need it — a child already being groomed, a live safeguarding
     * concern — and worth making loud because most do not.
     *
     * Password fields are still never captured at this rung. That gate is [FieldPolicy] and it
     * sits below every scope; no parental setting can reach past it.
     */
    FULL_TEXT,
    ;

    /** Whether unflagged messages are recorded at all. */
    val recordsEveryMessage: Boolean get() = this != CONCERNING_ONLY

    /** Whether what the child actually wrote leaves the device. */
    val recordsText: Boolean get() = this == FULL_TEXT

    /**
     * How much this scope collects, as a number, so two scopes can be compared.
     *
     * An exhaustive `when` rather than [Enum.ordinal], for the same reason
     * [FamilyPolicy.strictness] is: the comparison is the whole mechanism behind "did the
     * parent narrow the scope", and ordinal would silently reorder if anyone inserted a value
     * here. This way that edit stops the build instead of quietly changing whether a device
     * throws away queued messages.
     */
    val depth: Int
        get() = when (this) {
            CONCERNING_ONLY -> 0
            THEMES -> 1
            FULL_TEXT -> 2
        }

    /** True when moving from [other] to this scope collects strictly less than before. */
    fun narrowerThan(other: ReviewScope): Boolean = depth < other.depth

    companion object {
        /** The rung a family starts on, and the one that preserves today's behaviour exactly. */
        val DEFAULT = CONCERNING_ONLY

        fun fromName(name: String?): ReviewScope =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
