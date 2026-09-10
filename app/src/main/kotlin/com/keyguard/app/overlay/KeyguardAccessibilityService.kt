package com.keyguard.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.keyguard.app.OutcomeLog
import com.keyguard.app.R
import com.keyguard.app.family.ContentCapture
import com.keyguard.app.family.EventQueue
import com.keyguard.app.family.SampleQueue
import com.keyguard.app.family.SupervisedSettings
import com.keyguard.app.family.Supervision
import com.keyguard.app.family.SupervisionEvent
import com.keyguard.app.input.ComposeOutcome
import com.keyguard.app.settings.Settings
import com.keyguard.detect.Category
import com.keyguard.detect.CrisisResources
import com.keyguard.detect.DetectionEngine
import com.keyguard.detect.RollingContext
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import java.util.Locale

/**
 * The monitoring path.
 *
 * This is the answer to "our keyboard does not feel as smooth as Google's": stop being the
 * keyboard. The child types on whatever keyboard they already like, and Keyguard floats above
 * it. Smoothness, swipe typing, prediction, themes and every other thing a mature keyboard has
 * spent a decade on are now Google's problem rather than ours, and the app keeps only the part
 * that was ever actually its own — deciding what is worth interrupting.
 *
 * ### What this buys, beyond typing feel
 *
 * - **It sees text the IME never could.** An IME only observes what is typed on it: paste from
 *   another app, autofill, voice input and swipe-typed words committed by a different keyboard
 *   were all invisible. This sees the field's contents.
 * - **It can act on the field.** [removeFlaggedSpan] edits the host's text directly, which is
 *   what makes "force delete" a real capability rather than an instruction to the user.
 * - **It makes full-content review possible at all**, which is what
 *   [com.keyguard.app.family.ReviewScope] needs and what an IME could only have approximated.
 *
 * ### What it costs, stated plainly
 *
 * - **Play reviews this under the Accessibility API policy**, which is stricter than the
 *   keyboard one and is a genuine rejection risk. The service declares its purpose in
 *   `accessibility_service_config.xml` and the app declares itself a monitoring tool; whether
 *   that is sufficient is unverified until a submission comes back.
 * - **There is no iOS equivalent.** The keyboard extension was portable in principle; this is
 *   not portable at all. The `:detect` module stays pure Kotlin and still ports, but the
 *   monitoring shell on iOS would have to be something else entirely.
 * - **The block is weaker than the keyboard's was.** See [KeyboardShadeView].
 *
 * ### Privacy rules this file has to keep
 *
 * The service is handed far more than the IME ever was, so the same invariants need restating
 * where they can actually be broken:
 *
 * - **Password fields are never read.** [MonitoredField] gates every event, using two
 *   independent checks.
 * - **The host app is never recorded.** The package name is read to decide whether to look at
 *   a field and is never attached to an event or a sample.
 * - **Nothing is persisted here.** [RollingContext] and the last-seen text are in memory and
 *   cleared on every field change, so context never crosses apps or conversations.
 * - **Reporting still goes through the queues**, never straight to the network, so the upload
 *   remains in the app process where [com.keyguard.app.family.SupervisionSync] can own it.
 */
class KeyguardAccessibilityService : AccessibilityService() {

    private lateinit var engine: DetectionEngine
    private lateinit var settings: Settings
    private lateinit var supervision: Supervision
    private lateinit var effective: SupervisedSettings
    private lateinit var outcomeLog: OutcomeLog

    private var host: OverlayHost? = null
    private var eventQueue: EventQueue? = null
    private var sampleQueue: SampleQueue? = null

    /** In-memory only, cleared on every field change. Never persisted, never uploaded. */
    private var context = RollingContext()

    /** The field currently being watched, so a stale node is never written to. */
    private var targetNode: AccessibilityNodeInfo? = null

    private var lastText: String = ""
    private var scanResult: ScanResult = ScanResult.EMPTY
    private var acknowledged = false
    private var fieldProtected = false

    /**
     * Whether the message currently in the field was ever flagged.
     *
     * Kept across edits within one composition. A message that was flagged and then edited down
     * to something clean still produced a warning worth reporting, so the composition is worth
     * finishing even when the field ends up empty and [peakResult] is what gets reported rather
     * than the final scan.
     */
    private var everFlagged = false
    private var peakResult: ScanResult = ScanResult.EMPTY

    /**
     * Whether the flagged text left the field because the user pressed *Remove it*.
     *
     * The only deletion this service can be certain about, and therefore the only basis on
     * which it reports one — see [finishComposition].
     */
    private var removedByUser = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = DetectionEngine.withBundledPack()
        settings = Settings(this)
        supervision = Supervision(this)
        effective = SupervisedSettings(settings, supervision)
        outcomeLog = OutcomeLog(this)
        eventQueue = if (supervision.isSupervised) EventQueue(this) else null
        sampleQueue = if (supervision.isSupervised) SampleQueue(this) else null

        // Without the draw-over permission the service would watch everything and be able to
        // say nothing, which is the one configuration this product must never run in. Better
        // to do no monitoring at all than monitoring with no visible warning surface.
        host = if (OverlayPermissions.canDrawOverlays(this)) {
            OverlayHost(this, actions).also {
                it.updateAppearance(settings.appearance, settings.overlayOpacityPercent)
            }
        } else {
            null
        }
    }

    override fun onDestroy() {
        host?.destroy()
        host = null
        clearTarget()
        super.onDestroy()
    }

    override fun onInterrupt() {
        host?.hide()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return
        if (!settings.overlayEnabled) return
        if (event.packageName == packageName) return

        when (event.eventType) {
            // A new window is a new conversation as far as we are concerned. Context must not
            // survive it, or a theme from one app would colour a scan in the next.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> finishComposition(switched = true)

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                finishComposition(switched = true)
                adopt(event.source)
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event)

            else -> Unit
        }
    }

    private fun adopt(node: AccessibilityNodeInfo?) {
        clearTarget()
        if (node == null) return

        val allowed = MonitoredField.mayMonitor(
            packageName = node.packageName?.toString(),
            ourPackage = packageName,
            editable = node.isEditable,
            password = node.isPassword,
            inputType = node.inputType,
        )
        fieldProtected = !allowed
        if (!allowed) {
            host?.hide()
            return
        }
        targetNode = node
    }

    private fun onTextChanged(event: AccessibilityEvent) {
        val node = event.source
        if (node == null) {
            // Some hosts report text changes without a source node. There is nothing safe to
            // do with those: we cannot check whether the field is a password, so we do not look.
            return
        }
        adopt(node)
        if (fieldProtected || targetNode == null) return

        val text = node.text?.toString().orEmpty()
        if (text == lastText) return
        lastText = text

        if (text.isBlank()) {
            // The field emptied. Whether that was a send or a deletion is the question the IME
            // answered with SendInference; here the distinction is coarser, because an overlay
            // cannot see the host's send button either. A field that empties having previously
            // held flagged content is recorded as abandoned-deleted only when we saw the text
            // shrink to nothing, which is what `everFlagged` plus an empty field means.
            finishComposition(switched = false)
            return
        }

        rescan(text)
    }

    private fun rescan(text: String) {
        val now = System.currentTimeMillis()
        val result = engine.scan(text, context, now)
        scanResult = result

        if (result.maxSeverity.level > peakResult.maxSeverity.level) peakResult = result
        if (result.findings.isNotEmpty()) everFlagged = true

        render()
    }

    private fun render() {
        val host = host ?: return
        // Re-applied per render rather than once at connect. The service outlives the settings
        // screen by design, so a size or opacity change would otherwise not take effect until
        // the user turned the accessibility service off and on again.
        host.updateAppearance(settings.appearance, settings.overlayOpacityPercent)

        val state = OverlayDecision.decide(
            result = scanResult,
            fieldProtected = fieldProtected,
            overrideLevel = effective.overrideLevel,
            blockingEnabled = effective.blocksAtHighSeverity,
            acknowledged = acknowledged,
            summaryFor = { topMessage(it) },
            detailFor = { detailFor(it) },
            crisisMessage = getString(R.string.crisis_summary),
            crisisResourceLabel = crisisResource().label,
        )
        host.render(state, settings.overlayPosition, imeBounds(), fieldBounds())
    }

    /**
     * The keyboard window's bounds, or null when the platform does not report one.
     *
     * This is what turns "float somewhere near the bottom" into "sit exactly on top of whatever
     * keyboard they are using". Null is common enough that every caller treats it as ordinary —
     * see [OverlayAnchor].
     */
    private fun imeBounds(): OverlayAnchor.Bounds? = runCatching {
        val rect = android.graphics.Rect()
        windows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.also { it.getBoundsInScreen(rect) }
            ?.let { OverlayAnchor.Bounds(rect.left, rect.top, rect.right, rect.bottom) }
    }.getOrNull()

    /**
     * The focused field's rectangle, so the warning can sit above the composer rather than on
     * top of it. Null whenever the node will not say, which the anchor treats as "no answer".
     */
    private fun fieldBounds(): OverlayAnchor.Bounds? = runCatching {
        val node = targetNode ?: return null
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        OverlayAnchor.Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }.getOrNull()

    // region actions

    private val actions = object : OverlayActionListener {
        override fun onOverlayDismiss() {
            // Checked here as well as when the button was drawn. The policy can change between
            // a sync and a tap, and a button that outlived the permission behind it would be
            // the one place the override level could be beaten by timing.
            if (!effective.overrideLevel.mayDismiss(scanResult.maxSeverity)) return
            acknowledged = true
            render()
        }

        override fun onOverlayRemove() = removeFlaggedSpan()

        override fun onOverlayCrisisHelp() = openCrisisResource()
    }

    /**
     * Deletes the flagged span from the host's field.
     *
     * The exit that exists at every override level, and the only one at
     * [com.keyguard.app.family.OverrideLevel.NONE]. It edits *only* the flagged span rather
     * than clearing the field, which matters more than it sounds: most of a flagged message is
     * ordinarily fine, and a tool that eats an entire paragraph to remove an address teaches
     * people to compose somewhere else and paste it in, which defeats the product entirely.
     *
     * `ACTION_SET_TEXT` replaces the whole value because the platform offers no span-level
     * edit, so the new value is computed here and written in one go.
     */
    private fun removeFlaggedSpan() {
        val node = targetNode ?: return
        val finding = scanResult.findings.maxWithOrNull(
            compareBy({ it.severity.level }, { -it.start }),
        ) ?: return

        // A cached node goes stale the moment the host re-lays out, and writing to a stale one
        // either fails silently or edits a field that has moved on. Refreshing first is cheap
        // and turns both into a no-op instead.
        if (!runCatching { node.refresh() }.getOrDefault(false)) return

        val current = node.text?.toString() ?: return
        if (finding.end > current.length || finding.length <= 0) return

        val replacement = current.removeRange(finding.start, finding.end)
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                replacement,
            )
        }
        val applied = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrDefault(false)

        if (!applied) return
        removedByUser = true
        lastText = replacement
        // Re-scanning immediately rather than waiting for the resulting text-changed event
        // means the overlay comes down on the same frame as the tap. Waiting made the button
        // feel broken on hosts that batch their accessibility events.
        rescan(replacement)
    }

    private fun openCrisisResource() {
        val resource = crisisResource()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resource.primaryUri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { startActivity(intent); true }.getOrDefault(false)
        if (!launched && resource.primaryUri != resource.web) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(resource.web))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    // endregion

    /**
     * The message is over: sent, deleted, or the field was left.
     *
     * Where reporting happens, and the only place in this file that writes to a queue. Both
     * writes are gated: events only on a supervised device, samples only when the review scope
     * asks for them.
     */
    private fun finishComposition(switched: Boolean) {
        val text = lastText

        // The IME could tell a send from a deletion because it owned the keys: SendInference
        // watched *which mutation* emptied the buffer. An overlay has no such evidence. It sees
        // a field with text, then a field without, and in a chat app that is overwhelmingly a
        // send — the host clearing its own input — but it is indistinguishable from someone
        // holding backspace.
        //
        // So the guess goes the conservative way. `heeded` is computed from
        // ABANDONED_DELETED, and a parent reading "the warning worked" when the message was
        // actually sent is the failure that matters: it reports the product working when it did
        // not. SENT_INFERRED under-claims instead, which is safe.
        //
        // The one deletion this service *can* be sure of is its own: pressing Remove it is an
        // action we performed and observed, so that case is reported as what it is.
        val outcome = when {
            switched -> ComposeOutcome.ABANDONED_SWITCHED
            removedByUser -> ComposeOutcome.ABANDONED_DELETED
            else -> ComposeOutcome.SENT_INFERRED
        }

        if (text.isNotBlank() || everFlagged) {
            recordOutcome(outcome, text)
        }

        everFlagged = false
        removedByUser = false
        peakResult = ScanResult.EMPTY
        scanResult = ScanResult.EMPTY
        acknowledged = false
        lastText = ""
        context = RollingContext()
        host?.hide()
        if (switched) clearTarget()
    }

    private fun recordOutcome(outcome: ComposeOutcome, text: String) {
        val peak = peakResult
        val heeded = outcome == ComposeOutcome.ABANDONED_DELETED &&
            peak.maxSeverity.level >= Severity.MEDIUM.level

        if (peak.maxSeverity != Severity.NONE) {
            outcomeLog.record(peak.maxSeverity, outcome, heeded)
            SupervisionEvent.dominantCategory(peak)?.let { category ->
                eventQueue?.append(
                    SupervisionEvent(
                        at = System.currentTimeMillis(),
                        category = category,
                        severity = peak.maxSeverity,
                        outcome = outcome,
                        heeded = heeded,
                    ),
                )
            }
        }

        // The review-scope gate. ContentCapture returns null for CONCERNING_ONLY, which is why
        // there is no scope check written out here — putting one here as well would be a second
        // place for the two to disagree.
        val queue = sampleQueue ?: return
        if (!effective.reportsEnabled) return
        ContentCapture.sample(
            scope = effective.reviewScope,
            summary = engine.summarize(text),
            outcome = outcome,
            atMs = System.currentTimeMillis(),
            flagged = peak.maxSeverity != Severity.NONE,
            text = text,
            protectedField = fieldProtected,
        )?.let(queue::append)
    }

    private fun clearTarget() {
        targetNode = null
        lastText = ""
        fieldProtected = false
    }

    private fun topMessage(result: ScanResult): String =
        result.findings
            .maxWithOrNull(compareBy({ it.severity.level }, { -it.start }))
            ?.message
            .orEmpty()

    private fun detailFor(result: ScanResult): String {
        val finding = result.findings.maxWithOrNull(
            compareBy({ it.severity.level }, { -it.start }),
        ) ?: return ""
        val categoryLabel = when (finding.category) {
            Category.PII_DISCLOSURE -> R.string.category_pii
            Category.HARASSMENT -> R.string.category_harassment
            Category.SEXUAL_SOLICITATION -> R.string.category_solicitation
            Category.SELF_HARM -> R.string.category_self_harm
            Category.VIOLENCE_THREAT -> R.string.category_violence
            Category.IN_PERSON_MEETUP -> R.string.category_meetup
            Category.SUBSTANCE -> R.string.category_substance
        }
        return getString(R.string.warning_detail, getString(categoryLabel))
    }

    private fun crisisResource(): CrisisResources.Resource {
        val region = resources.configuration.locales.get(0)?.country
            ?: Locale.getDefault().country
        return CrisisResources.forRegion(region)
    }
}
