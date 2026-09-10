package com.keyguard.app.family

import com.keyguard.app.settings.Intensity
import org.json.JSONObject

/** How a parent has settled a switch the child would otherwise own. */
enum class PolicyToggle {
    /** The parent has not taken this one over. The child's own setting wins. */
    CHILD_CHOICE,
    FORCED_ON,
    FORCED_OFF,
    ;

    fun resolve(childChoice: Boolean): Boolean = when (this) {
        CHILD_CHOICE -> childChoice
        FORCED_ON -> true
        FORCED_OFF -> false
    }

    companion object {
        fun fromName(name: String?): PolicyToggle =
            entries.firstOrNull { it.name == name } ?: CHILD_CHOICE
    }
}

/**
 * The controls half of parental controls: what a parent has decided on a supervised device.
 *
 * The organising rule is that **a policy sets a floor, not a value**. [minIntensity] is the
 * least protection the child may run with, and the child can still turn it up; only
 * [lockSettings] pins it exactly. A parent who sets *Standard* and finds their child has
 * chosen *Insistent* has got what they asked for, and a child who wants more protection than
 * their parent required should never have to argue for it. The direction that matters is the
 * one the floor blocks: the child cannot go below.
 *
 * Every field resolves through a pure function here rather than at the call site, because the
 * failure mode is a supervised setting that appears to apply on the settings screen and does
 * not apply in the keyboard, or vice versa. One resolution path, tested, used by both.
 *
 * There is no policy at all on an unpaired device — [Supervision.policy] is null and every
 * caller falls back to the child's own settings, so pairing is the only thing that can change
 * how the keyboard behaves.
 */
data class FamilyPolicy(
    /**
     * Bumped by the server on every write. The device stores the last version it applied, so
     * a poll that finds the same number is free and a parent's change lands on the next sync.
     */
    val version: Long,
    /** The least forceful warning setting permitted. The child may choose a stronger one. */
    val minIntensity: Intensity,
    /**
     * Stop the keys at high severity whatever the intensity setting says.
     *
     * This is the supervised answer to the standing contradiction in the unsupervised build,
     * where *Subtle* promises never to gate sending and then hard-blocks typing at HIGH. Here
     * it is an explicit parental decision rather than an accident of two settings meeting.
     */
    val blockAtHigh: Boolean,
    /**
     * Whether flagged snippets go to the verification endpoint.
     *
     * `FORCED_ON` means a parent has decided that their child's flagged text leaves the
     * device. That is a real transfer of a privacy decision from the person typing to the
     * person paying, defensible only because a guardian consents on a minor's behalf — and it
     * is why the child's supervision screen names this specific setting rather than saying
     * "your parent manages your settings".
     */
    val aiVerification: PolicyToggle,
    /** Pin intensity to exactly [minIntensity]. The child cannot raise it either. */
    val lockSettings: Boolean,
    /**
     * How much authority the child has to wave a warning away.
     *
     * The one field here that is not a floor — it is a ceiling, and the direction is the
     * opposite of [minIntensity] for a reason. Intensity is about how loudly the keyboard
     * speaks, and a child wanting more of that should never have to ask. This is about whether
     * they can overrule it, and a child cannot grant themselves more of that than their parent
     * allowed, or the setting would mean nothing.
     */
    val overrideLevel: OverrideLevel = OverrideLevel.DEFAULT,
    /**
     * How much of what the child types the parent can see afterwards.
     *
     * Read [ReviewScope] before changing the default. Raising this is the single most
     * consequential thing a parent can do in this product, and the only policy field whose
     * effect the child is told about individually rather than as "your parent manages your
     * settings".
     */
    val reviewScope: ReviewScope = ReviewScope.DEFAULT,
    /**
     * Whether the device rolls up daily and weekly summaries for the parent at all.
     *
     * Separate from [reviewScope] because they answer different questions: scope is *what may
     * be collected*, this is *whether anything is composed out of it*. A parent who wants the
     * activity list but not a narrative summary is a coherent position, and a parent who turns
     * reports off should not have their child's device doing the work regardless.
     */
    val reportsEnabled: Boolean = true,
) {

    /** What the keyboard should actually use, given the child's own preference. */
    fun effectiveIntensity(childChoice: Intensity): Intensity = when {
        lockSettings -> minIntensity
        strictness(childChoice) >= strictness(minIntensity) -> childChoice
        else -> minIntensity
    }

    fun effectiveAiVerification(childChoice: Boolean): Boolean =
        aiVerification.resolve(childChoice)

    /** Whether the child may still move the intensity control at all. */
    fun childMayChooseIntensity(): Boolean = !lockSettings

    /** Whether the child may wave away a warning of this severity. */
    fun mayDismiss(severity: com.keyguard.detect.Severity): Boolean =
        overrideLevel.mayDismiss(severity)

    fun toJson(): JSONObject = JSONObject()
        .put(FIELD_VERSION, version)
        .put(FIELD_MIN_INTENSITY, minIntensity.name)
        .put(FIELD_BLOCK_AT_HIGH, blockAtHigh)
        .put(FIELD_AI_VERIFICATION, aiVerification.name)
        .put(FIELD_LOCK_SETTINGS, lockSettings)
        .put(FIELD_OVERRIDE_LEVEL, overrideLevel.name)
        .put(FIELD_REVIEW_SCOPE, reviewScope.name)
        .put(FIELD_REPORTS_ENABLED, reportsEnabled)

    companion object {
        /**
         * What a family starts with: the unsupervised defaults, so pairing on its own changes
         * nothing about how the keyboard behaves. Every difference a child notices should be
         * something their parent actually chose.
         */
        val DEFAULT = FamilyPolicy(
            version = 0,
            // No floor at all, deliberately — not [Intensity.DEFAULT]. Standard is the right
            // default for a fresh install, but as a *floor* it would silently overrule a child
            // who had already chosen Subtle, and the first thing supervision did would be a
            // change no parent asked for.
            minIntensity = Intensity.SUBTLE,
            // Matches what an unsupervised build already does at high severity.
            blockAtHigh = true,
            aiVerification = PolicyToggle.CHILD_CHOICE,
            lockSettings = false,
            // Both of the new fields default to the pre-supervision behaviour, for the same
            // reason minIntensity defaults to SUBTLE rather than STANDARD: the first thing
            // pairing does must never be a change nobody asked for. FULL is what the Ignore
            // button has always done, and CONCERNING_ONLY is the promise the product shipped
            // with.
            overrideLevel = OverrideLevel.DEFAULT,
            reviewScope = ReviewScope.DEFAULT,
            reportsEnabled = true,
        )

        /**
         * Ordering as an exhaustive `when` rather than [Enum.ordinal].
         *
         * The floor comparison is the whole mechanism, and ordinal would silently reorder if
         * anyone inserted a value into [Intensity]. This way that edit stops the build.
         */
        fun strictness(intensity: Intensity): Int = when (intensity) {
            Intensity.SUBTLE -> 0
            Intensity.STANDARD -> 1
            Intensity.INSISTENT -> 2
        }

        /**
         * Parses a policy, filling anything missing or unrecognised from [DEFAULT].
         *
         * Lenient on purpose, and in the safe direction: an unreadable field falls back to the
         * ordinary unsupervised behaviour rather than to the strictest reading. A parse bug
         * that quietly maximised restrictions would be discovered by a child locked out of
         * their own keyboard.
         */
        fun fromJson(json: JSONObject): FamilyPolicy = FamilyPolicy(
            version = json.optLong(FIELD_VERSION, DEFAULT.version),
            // Not Intensity.fromName: its fallback is Standard, which as a *floor* would be a
            // restriction invented by a parse error. Unrecognised means no floor.
            minIntensity = Intensity.entries
                .firstOrNull { it.name == json.optString(FIELD_MIN_INTENSITY) }
                ?: DEFAULT.minIntensity,
            blockAtHigh = json.optBoolean(FIELD_BLOCK_AT_HIGH, DEFAULT.blockAtHigh),
            aiVerification = PolicyToggle.fromName(json.optString(FIELD_AI_VERIFICATION)),
            lockSettings = json.optBoolean(FIELD_LOCK_SETTINGS, DEFAULT.lockSettings),
            // Both fall back to the permissive reading on an unrecognised value, matching every
            // other field here. For reviewScope that matters more than usual: a parse error
            // that silently upgraded a family to FULL_TEXT would start collecting a child's
            // messages on the strength of a typo, and nobody would find out from the outside.
            overrideLevel = OverrideLevel.fromName(json.optString(FIELD_OVERRIDE_LEVEL)),
            reviewScope = ReviewScope.fromName(json.optString(FIELD_REVIEW_SCOPE)),
            reportsEnabled = json.optBoolean(FIELD_REPORTS_ENABLED, DEFAULT.reportsEnabled),
        )

        const val FIELD_VERSION = "version"
        const val FIELD_MIN_INTENSITY = "minIntensity"
        const val FIELD_BLOCK_AT_HIGH = "blockAtHigh"
        const val FIELD_AI_VERIFICATION = "aiVerification"
        const val FIELD_LOCK_SETTINGS = "lockSettings"
        const val FIELD_OVERRIDE_LEVEL = "overrideLevel"
        const val FIELD_REVIEW_SCOPE = "reviewScope"
        const val FIELD_REPORTS_ENABLED = "reportsEnabled"
    }
}
