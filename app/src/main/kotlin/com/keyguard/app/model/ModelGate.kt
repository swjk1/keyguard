package com.keyguard.app.model

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.keyguard.infer.KeyguardModel
import com.keyguard.infer.ModelVerdict

/**
 * Runs the model off the callback thread, on a typing pause, one message at a time.
 *
 * ### Why not per keystroke
 *
 * The rule engine in `:detect` is built for a 5 ms per-keystroke budget. The model is two orders
 * of magnitude away from that and should not pretend otherwise. It runs when typing stops for
 * [QUIET_MS], which is roughly the gap between words for a fast typist and comfortably shorter
 * than the pause before someone hits send. The rules keep warning on every keystroke exactly as
 * they did; this only ever arrives afterwards with a second opinion.
 *
 * ### Why a dedicated thread
 *
 * Blocking an accessibility callback degrades the whole device, not just this app — the system
 * waits on it before dispatching to anything else. So nothing here touches the caller's thread:
 * loading the session, which takes long enough to notice, happens on first use on the worker, and
 * results come back through the main looper.
 *
 * ### Cancel and replace
 *
 * Only the newest text matters. A burst of typing must not queue up inferences behind text the
 * user has already moved past, so pending work is dropped rather than pipelined, and a result
 * whose text no longer matches the field is discarded on arrival. The alternative is a warning
 * that appears half a second after it became wrong.
 *
 * ### If the model cannot load
 *
 * It stays off, permanently, and the rules carry on alone. A missing native library or a corrupt
 * asset is not a reason to stop protecting anyone — and since the rule layer is the product's
 * floor rather than a fallback, degrading to it is a real degradation and not a failure.
 */
class ModelGate(
    private val context: Context,
    private val onVerdict: (text: String, verdict: ModelVerdict) -> Unit,
) {

    private val worker = HandlerThread("keyguard-model").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var model: KeyguardModel? = null

    @Volatile
    private var unavailable = false

    /** The text the most recently scheduled run is for. Guards against stale results. */
    @Volatile
    private var pendingText: String? = null

    /** Set once a load has been attempted, so a failure is not retried on every keystroke. */
    @Volatile
    private var loadStarted = false

    /**
     * The last text actually put through the model, and what it said.
     *
     * The service re-scans whenever the focused node resolves again, which on some hosts happens
     * repeatedly while the text has not changed at all — one observed session ran 27 inferences
     * for 9 typed words, 17 of them on byte-identical text. The model is deterministic, so those
     * runs could only ever produce the answer already in hand. Replaying it costs nothing and
     * keeps the verdict flowing to the overlay exactly as before.
     */
    @Volatile
    private var lastAnalyzedText: String? = null

    @Volatile
    private var lastVerdict: ModelVerdict? = null

    val isReady: Boolean get() = model != null

    /** Diagnostics for the debug build, in the spirit of `DetectionEngine.termCount`. */
    fun describe(): String = when {
        unavailable -> "model unavailable"
        model == null -> "model loading"
        else -> model?.let {
            "vocab ${it.vocabularySize}, ${it.contextLabelCount} signals, ${it.ruleCount} rules"
        } ?: "model loading"
    }

    /**
     * Notes that the field now holds [text], and schedules a run once typing settles.
     *
     * Safe to call on every keystroke; that is the intended usage.
     */
    fun onTextChanged(text: String) {
        if (unavailable) return
        if (text.isBlank()) {
            pendingText = null
            workerHandler.removeCallbacksAndMessages(null)
            return
        }

        pendingText = text
        workerHandler.removeCallbacksAndMessages(null)
        workerHandler.postDelayed({ run(text) }, QUIET_MS)
    }

    /** Field changed or composition ended: drop anything in flight. */
    fun reset() {
        pendingText = null
        lastAnalyzedText = null
        lastVerdict = null
        workerHandler.removeCallbacksAndMessages(null)
    }

    fun shutdown() {
        workerHandler.removeCallbacksAndMessages(null)
        workerHandler.post {
            runCatching { model?.close() }
            model = null
            worker.quitSafely()
        }
    }

    private fun run(text: String) {
        if (text != pendingText) return

        val cached = lastVerdict
        if (cached != null && text == lastAnalyzedText) {
            main.post { if (text == pendingText) onVerdict(text, cached) }
            return
        }

        val loaded = model ?: loadOnce() ?: return
        val verdict = runCatching { loaded.analyze(text) }
            .onFailure { Log.w(TAG, "inference failed; rules continue alone", it) }
            .getOrNull() ?: return

        // Level, rules and timing only — never the text, and never the message. The engine's
        // own output carries no raw user text by design, and a log line is exactly the place
        // that convention would be broken by accident.
        Log.i(
            TAG,
            "verdict L${verdict.level} ${verdict.fired} signals=${verdict.activeSignals.sorted()} " +
                "spans=${verdict.spans.size} ${verdict.tokenCount}tok ${verdict.elapsedMillis}ms",
        )

        lastAnalyzedText = text
        lastVerdict = verdict

        main.post {
            // Checked again on the main thread: the field may have moved on while the model ran,
            // and a warning about text the user has already replaced is worse than none.
            if (text == pendingText) onVerdict(text, verdict)
        }
    }

    private fun loadOnce(): KeyguardModel? {
        if (unavailable || loadStarted && model == null) {
            // A previous attempt failed. Retrying on every pause would spend a 67 MB file copy
            // and a session build per keystroke burst for a problem that will not have fixed
            // itself.
            if (loadStarted && model == null) return null
        }
        loadStarted = true
        val started = System.currentTimeMillis()
        return runCatching { KeyguardModel.load(context) }
            .onSuccess {
                model = it
                Log.i(TAG, "model ready in ${System.currentTimeMillis() - started} ms: ${describe()}")
            }
            .onFailure {
                unavailable = true
                Log.w(TAG, "model unavailable; the rule engine continues alone", it)
            }
            .getOrNull()
    }

    private companion object {
        const val TAG = "KeyguardModel"

        /**
         * Idle time before the model runs.
         *
         * Long enough that a fast typist mid-word does not trigger it, short enough that the
         * second opinion lands before someone reaches for send. §31's benchmark should revisit
         * this once there are real latency numbers to set it against.
         */
        const val QUIET_MS = 350L
    }
}
