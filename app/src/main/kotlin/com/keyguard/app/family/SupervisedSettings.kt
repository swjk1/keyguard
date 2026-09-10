package com.keyguard.app.family

import com.keyguard.app.settings.Intensity
import com.keyguard.app.settings.Settings

/**
 * The single place a caller asks what a setting actually *is*.
 *
 * [Settings] keeps holding what the person using this phone chose, untouched — a parent's
 * floor must not silently rewrite a child's preferences, or removing supervision would leave
 * behind settings nobody picked. The policy is applied on read instead, here, so there is one
 * answer rather than one per call site.
 *
 * On an unsupervised device every property is exactly the underlying setting, which is what
 * makes the whole feature invisible until someone pairs.
 */
class SupervisedSettings(
    private val settings: Settings,
    private val supervision: Supervision,
) {

    private val policy: FamilyPolicy? get() = supervision.policy

    val intensity: Intensity
        get() = policy?.effectiveIntensity(settings.intensity) ?: settings.intensity

    val aiVerificationEnabled: Boolean
        get() = policy?.effectiveAiVerification(settings.aiVerificationEnabled)
            ?: settings.aiVerificationEnabled

    /**
     * Whether high severity stops the keys.
     *
     * Unsupervised this is always true, matching what the keyboard already does. Supervised it
     * is the parent's call, which is the first coherent answer the product has had to the
     * standing contradiction between *Subtle* promising never to gate and the block firing
     * anyway.
     */
    val blocksAtHighSeverity: Boolean
        get() = policy?.blockAtHigh ?: true

    /** Whether the settings screen should render the intensity control as read-only. */
    val intensityLocked: Boolean
        get() = policy?.childMayChooseIntensity()?.not() ?: false

    /** Whether a parent has taken the AI verification decision. */
    val aiVerificationLocked: Boolean
        get() = policy?.aiVerification?.let { it != PolicyToggle.CHILD_CHOICE } ?: false

    /**
     * True when the effective intensity is stronger than what this device asked for, so the
     * settings screen can say why rather than showing a control that snaps back.
     */
    val intensityRaisedByPolicy: Boolean
        get() = intensity != settings.intensity

    /**
     * How much authority this device's user has over a warning.
     *
     * Unsupervised this is always [OverrideLevel.FULL] — the Ignore button as it has always
     * behaved — which is what keeps the whole feature invisible until someone pairs.
     */
    val overrideLevel: OverrideLevel
        get() = policy?.overrideLevel ?: OverrideLevel.DEFAULT

    /**
     * How much of what is typed here may be reported.
     *
     * Unsupervised this is [ReviewScope.CONCERNING_ONLY] and nothing is reported at all, since
     * there is no family to report to. The value still resolves rather than being null so that
     * every caller reads the same property and none of them has to remember that an unpaired
     * device is a special case.
     */
    val reviewScope: ReviewScope
        get() = policy?.reviewScope ?: ReviewScope.DEFAULT

    /** Whether this device should be composing per-message summaries for a parent report. */
    val reportsEnabled: Boolean
        get() = supervision.isSupervised && (policy?.reportsEnabled ?: false)
}
