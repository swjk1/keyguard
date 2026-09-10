package com.keyguard.app

import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.keyguard.app.family.ContentCapture
import com.keyguard.app.family.EventQueue
import com.keyguard.app.family.SampleQueue
import com.keyguard.app.family.SupervisedSettings
import com.keyguard.app.family.Supervision
import com.keyguard.app.family.SupervisionEvent
import com.keyguard.app.input.ComposeOutcome
import com.keyguard.app.input.InputGate
import com.keyguard.app.input.Mutation
import com.keyguard.app.input.SendInference
import com.keyguard.app.input.ShadowBuffer
import com.keyguard.app.keyboard.EmojiPanel
import com.keyguard.app.keyboard.EmojiPanelListener
import com.keyguard.app.keyboard.Highlighter
import com.keyguard.app.keyboard.IdleToolbar
import com.keyguard.app.keyboard.IdleToolbarListener
import com.keyguard.app.keyboard.KeyboardListener
import com.keyguard.app.keyboard.KeyboardView
import com.keyguard.app.keyboard.StripState
import com.keyguard.app.keyboard.SuggestionStrip
import com.keyguard.app.keyboard.SuggestionStripListener
import com.keyguard.app.keyboard.WarningStrip
import com.keyguard.app.keyboard.WarningStripListener
import com.keyguard.app.settings.Intensity
import com.keyguard.app.settings.Settings
import com.keyguard.app.text.AutocorrectState
import com.keyguard.app.text.Predictor
import com.keyguard.app.text.SuggestionSource
import com.keyguard.app.text.UserVocabulary
import com.keyguard.app.text.Vocabulary
import com.keyguard.app.text.WordScanner
import com.keyguard.app.verify.CachedVerdict
import com.keyguard.app.verify.VerdictApplier
import com.keyguard.app.verify.VerifyClient
import com.keyguard.app.verify.VerifyPolicy
import com.keyguard.app.verify.VerifyRequest
import com.keyguard.detect.Category
import com.keyguard.detect.CrisisResources
import com.keyguard.detect.DetectionEngine
import com.keyguard.detect.FieldPolicy
import com.keyguard.detect.Finding
import com.keyguard.detect.RollingContext
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import com.keyguard.detect.VerifyReason
import java.util.Locale

/**
 * The keyboard.
 *
 * Detection runs synchronously on every keystroke. That is affordable because a local scan
 * measures around 100 microseconds for a 500-character buffer, so there is no debounce and
 * no spinner — the warning simply appears as the user types. Debouncing only becomes
 * necessary once the AI verification layer lands, and even then the local result renders
 * immediately and never waits on the network.
 */
class KeyguardInputMethodService :
    InputMethodService(),
    KeyboardListener,
    WarningStripListener,
    IdleToolbarListener,
    EmojiPanelListener,
    SuggestionStripListener {

    private lateinit var engine: DetectionEngine
    private lateinit var settings: Settings

    /**
     * Settings as the parent's policy leaves them. Read through this everywhere rather than
     * from [settings] directly, or a supervised floor would apply in some parts of the
     * keyboard and not others.
     */
    private lateinit var effective: SupervisedSettings

    /**
     * The keyboard's only involvement in parent reporting: it appends here and stops. The
     * upload happens in the app process — see [SupervisionSync].
     */
    private var eventQueue: EventQueue? = null

    /**
     * The report side of the same arrangement. Allocated on the same condition as
     * [eventQueue] — supervised only — so an unpaired device has nowhere to write a sample
     * even if a future edit dropped the scope check at the call site.
     */
    private var sampleQueue: SampleQueue? = null
    private lateinit var supervision: Supervision
    private lateinit var outcomeLog: OutcomeLog

    private val buffer = ShadowBuffer()
    private val sendInference = SendInference()
    private var context = RollingContext()

    private var keyboardView: KeyboardView? = null
    private var warningStrip: WarningStrip? = null
    private var idleToolbar: IdleToolbar? = null
    private var emojiPanel: EmojiPanel? = null
    private var suggestionStrip: SuggestionStrip? = null
    private var emojiVisible = false

    private var suggestionSource: SuggestionSource? = null
    private var predictor: Predictor? = null
    private var userVocabulary: UserVocabulary? = null
    private val autocorrect = AutocorrectState()

    /** What the strip is currently offering. */
    private var suggestions: Predictor.Suggestions = Predictor.Suggestions.EMPTY

    private var scanResult: ScanResult = ScanResult.EMPTY

    /** The unrefined local result, kept so a verdict is always applied to a clean baseline. */
    private var localScanResult: ScanResult = ScanResult.EMPTY

    private var verifyClient: VerifyClient? = null
    private val verifyPolicy = VerifyPolicy()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** A model-suggested safer rewrite, when verification returned one. */
    private var pendingRewrite: String? = null

    private var expanded = false
    private var dismissed = false
    private var awaitingSendConfirmation = false

    /**
     * Whether the keys are currently refusing text. High-severity findings stop input until
     * the user resolves them or presses Ignore; see [InputGate] for why backspace, the
     * keyboard switcher, and the crisis path are exempt.
     */
    private val inputGate = InputGate()

    /** True when the focused field must never be scanned. */
    private var fieldProtected = false

    /**
     * Absolute cursor position in the host editor, from the last selection update, or -1 when
     * unknown. Needed to delete a flagged span safely: our buffer offsets are relative to the
     * buffer, and `deleteSurroundingText` works relative to the cursor, so translating between
     * them requires knowing where the cursor actually is.
     */
    private var selectionStart = -1
    private var selectionEnd = -1

    /** The editor action this field wants from the Enter key, if any. */
    private var editorAction = EditorInfo.IME_ACTION_NONE

    override fun onCreate() {
        super.onCreate()
        engine = DetectionEngine.withBundledPack()
        settings = Settings(this)
        supervision = Supervision(this)
        effective = SupervisedSettings(settings, supervision)
        outcomeLog = OutcomeLog(this)
        suggestionSource = SuggestionSource(this).apply {
            setCallback(::onSuggestionsReady)
        }
        // Parsing the vocabulary is not free, so it happens once here rather than per field.
        val vocabulary = runCatching {
            Vocabulary.load(
                assets.open(VOCAB_WORDS_ASSET),
                assets.open(VOCAB_BIGRAMS_ASSET),
            )
        }.getOrNull()
        userVocabulary = UserVocabulary(this)
        predictor = vocabulary?.let { Predictor(it, userVocabulary!!) }
        val endpoint = getString(R.string.verify_base_url)
        // No endpoint configured means no client, and therefore no possibility of a request.
        verifyClient = if (endpoint.isBlank()) null else VerifyClient(this, endpoint)
        // Allocated only while supervised, so an unpaired device has nowhere to write even if
        // a future edit forgot the guard at the call site.
        eventQueue = if (supervision.isSupervised) EventQueue(this) else null
        sampleQueue = if (supervision.isSupervised) SampleQueue(this) else null
    }

    override fun onDestroy() {
        userVocabulary?.flush()
        suggestionSource?.close()
        suggestionSource = null
        verifyClient?.shutdown()
        verifyClient = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val appearance = settings.appearance
        val strip = WarningStrip(this, this, appearance)
        val suggestionsView = SuggestionStrip(this, this, appearance)
        val toolbar = IdleToolbar(this, this)
        val keys = KeyboardView(this, this, appearance)
        val emoji = EmojiPanel(this, this)
        warningStrip = strip
        suggestionStrip = suggestionsView
        idleToolbar = toolbar
        keyboardView = keys
        emojiPanel = emoji
        emoji.visibility = View.GONE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.keyboard_background))
            // Warning, suggestions, and toolbar all share one row; exactly one is ever
            // visible. Priority is warning > suggestions > toolbar, so a safety message is
            // never pushed aside by a spelling hint or a utility button.
            addView(
                strip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                suggestionsView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                toolbar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                keys,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    keys.desiredHeightPx(),
                ),
            )
            addView(
                emoji,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    keys.desiredHeightPx(),
                ),
            )
        }

        // targetSdk 35+ puts windows edge to edge, so the bottom row can otherwise sit under
        // the navigation bar or gesture area. Pushing the keys up by the reported inset is
        // what keeps the last row reachable.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom,
            )
            keys.applyBottomInset(bottom)
            // The emoji panel needs the same inset, or its bottom row sits lower than the
            // keyboard's and crowds the system's keyboard-dismiss control.
            emoji.applyBottomInset(bottom)
            syncKeyboardHeight()
            insets
        }
        return root
    }

    /**
     * Re-reads sizing. The input view is created once and reused, so without this a user who
     * adjusts the sliders and comes back would still see the old geometry.
     */
    private fun applyAppearance() {
        val appearance = settings.appearance
        keyboardView?.updateAppearance(appearance)
        keyboardView?.hapticsEnabled = settings.hapticsEnabled
        warningStrip?.updateAppearance(appearance)
        suggestionStrip?.updateAppearance(appearance)
        syncKeyboardHeight()
    }

    private fun syncKeyboardHeight() {
        val keys = keyboardView ?: return
        val desired = keys.desiredHeightPx()
        val params = keys.layoutParams ?: return
        if (params.height != desired) {
            params.height = desired
            keys.layoutParams = params
            keys.requestLayout()
        }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)

        // Hard privacy gate. A keyboard that inspects password fields is a credential
        // harvester regardless of intent, so this is checked before anything else happens.
        fieldProtected = info?.let {
            FieldPolicy.isProtectedField(it.inputType, it.imeOptions)
        } ?: false

        editorAction = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE

        // Context must never cross fields, apps, or conversations.
        buffer.clear()
        sendInference.reset()
        context = RollingContext()
        resetWarningState()
        keyboardView?.resetForNewField()
        // A new field should always start on the keys, never on a stale emoji panel.
        setEmojiVisible(false)
        applyAppearance()
        renderStrip()
    }

    override fun onFinishInput() {
        val outcome = sendInference.onFinishInput()
        if (outcome != null) recordOutcome(outcome)
        buffer.clear()
        context.clear()
        resetWarningState()
        renderStrip()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        if (fieldProtected) return

        // Reconcile against the host. Disagreement means something other than our keys
        // changed the field — including the host clearing its input on send, which is the
        // signal the whole send inference rests on.
        val hostText = currentInputConnection?.getTextBeforeCursor(ShadowBuffer.DEFAULT_MAX_LENGTH, 0)
        val drifted = buffer.reconcile(hostText)
        if (drifted) {
            val outcome = sendInference.record(Mutation.EXTERNAL, buffer.length)
            if (outcome != null) recordOutcome(outcome)
            rescan()
        }
    }

    // region KeyboardListener

    override fun onText(text: String) {
        // Every route text can take into the field converges here — character keys, space,
        // long-press alternates, and emoji — so this one guard is what holds the block.
        // Checked before the autocorrect commit as well: a blocked keystroke must not be able
        // to finalise a pending correction as a side effect.
        if (inputGate.isBlocked) return

        // A word terminator is the moment to commit a pending correction, and it has to happen
        // before the terminator itself is inserted so offsets still line up.
        if (!fieldProtected && WordScanner.isWordTerminator(text)) {
            applyPendingAutocorrect()
        }

        currentInputConnection?.commitText(text, 1)
        if (fieldProtected) return

        buffer.insert(text)
        sendInference.record(Mutation.INSERT, buffer.length)
        dismissed = false

        // Typing on accepts any correction already applied; it is no longer undoable.
        if (!WordScanner.isWordTerminator(text)) autocorrect.invalidate()

        rescan()
        requestSuggestions()
    }

    override fun onBackspace() {
        val connection = currentInputConnection ?: return

        // Backspace immediately after an automatic correction undoes it rather than deleting a
        // character. An autocorrect that cannot be taken back is the behaviour that makes
        // people switch the feature off entirely.
        if (!fieldProtected && revertAutocorrect()) return

        val selection = connection.getSelectedText(0)
        if (!selection.isNullOrEmpty()) {
            // Select-all-then-delete arrives here. It is a deliberate deletion, and being
            // able to see it is what makes "sent" versus "abandoned" decidable at all.
            connection.commitText("", 1)
            buffer.clear()
        } else {
            connection.deleteSurroundingText(1, 0)
            buffer.deleteBackward(1)
        }

        if (fieldProtected) return
        val outcome = sendInference.record(Mutation.OUR_DELETE, buffer.length)
        if (outcome != null) recordOutcome(outcome)
        rescan()
        requestSuggestions()
    }

    override fun onEnter() {
        // A block outranks the confirm gate. Pressing Ignore lifts both at once — it sets
        // `dismissed`, which is what shouldGateSend() reads — so the user is asked once, not
        // handed a second dialog immediately after clearing the first.
        if (inputGate.isBlocked) return

        // The Enter gate. This only works for fields whose send action runs through our
        // key; an in-app send button (Instagram, Discord) cannot be intercepted by any IME,
        // which is an accepted limitation rather than a missing permission.
        if (!fieldProtected && shouldGateSend() && !awaitingSendConfirmation) {
            awaitingSendConfirmation = true
            renderStrip()
            return
        }
        awaitingSendConfirmation = false

        val connection = currentInputConnection ?: return
        if (editorAction != EditorInfo.IME_ACTION_NONE &&
            editorAction != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            connection.performEditorAction(editorAction)
            if (!fieldProtected) {
                val outcome = sendInference.record(Mutation.ACTION_KEY, buffer.length)
                buffer.clear()
                if (outcome != null) recordOutcome(outcome)
                resetWarningState()
                rescan()
            }
        } else {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            if (!fieldProtected) {
                buffer.insert("\n")
                sendInference.record(Mutation.INSERT, buffer.length)
                rescan()
            }
        }
    }

    override fun onNextKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    // endregion

    // region WarningStripListener

    override fun onToggleExpanded() {
        expanded = !expanded
        renderStrip()
    }

    override fun onDismiss() {
        // The explicit override a high-severity block requires. Unlike `dismissed`, this one
        // survives the next keystroke — see InputGate.
        //
        // It can now be refused: a parent's OverrideLevel may have withheld the right to
        // dismiss this severity. A refusal must leave `dismissed` alone as well, or the strip
        // would hide itself while the keys stayed blocked — a dead keyboard with nothing on
        // screen explaining it, which is the worst state this component can reach.
        val blocked = inputGate.isBlocked
        if (blocked && !inputGate.acknowledge()) {
            renderStrip()
            return
        }
        if (!blocked && !effective.overrideLevel.mayDismiss(scanResult.maxSeverity)) {
            // Not blocking, but still not dismissible — a medium-severity warning under
            // OverrideLevel.NONE. The strip stays up; Remove it is the way past it.
            renderStrip()
            return
        }

        // Records a false-positive signal in a later milestone. For now it just gets out of
        // the way for the rest of this composition.
        dismissed = true
        awaitingSendConfirmation = false
        renderStrip()
    }

    override fun onRewriteRequested() {
        // A model-supplied rewrite replaces the whole sentence when one is available; otherwise
        // fall back to deleting the flagged span, which is the useful local-only equivalent.
        pendingRewrite?.let { rewrite ->
            applyRewrite(rewrite)
            return
        }

        val finding = topFinding() ?: return
        val connection = currentInputConnection ?: return
        val text = buffer.text
        if (finding.end > text.length || finding.length <= 0) return

        // The first version assumed the cursor sat at the end of the buffer and deleted
        // backwards from there, which corrupted the field whenever it did not. Instead move
        // the cursor to the end of the span and delete exactly the span.
        //
        // Our buffer mirrors the text *before* the cursor, so buffer offset `length` maps to
        // the cursor. Any earlier offset is that many characters back from it.
        if (selectionStart < 0 || selectionStart != selectionEnd) return
        val absoluteSpanEnd = selectionStart - (text.length - finding.end)
        if (absoluteSpanEnd - finding.length < 0) return

        connection.beginBatchEdit()
        try {
            connection.setSelection(absoluteSpanEnd, absoluteSpanEnd)
            connection.deleteSurroundingText(finding.length, 0)
        } finally {
            connection.endBatchEdit()
        }

        buffer.clear()
        buffer.insert(text.removeRange(finding.start, finding.end))
        sendInference.record(Mutation.INSERT, buffer.length)
        expanded = false
        rescan()
    }

    override fun onCrisisHelpTapped() {
        // Opening a crisis line is the one case worth leaving the keyboard for.
        val resource = crisisResource()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resource.primaryUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // If no dialer or browser can handle it, fall back to the web directory rather than
        // silently doing nothing — a dead end here is the failure that matters most.
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

    /**
     * The helpline for this device's region. Resolved per call rather than cached so a user
     * who travels or changes region gets the right number.
     */
    private fun crisisResource(): CrisisResources.Resource {
        val region = resources.configuration.locales.get(0)?.country
            ?: Locale.getDefault().country
        return CrisisResources.forRegion(region)
    }

    override fun onConfirmSend() {
        awaitingSendConfirmation = false
        dismissed = true
        onEnter()
    }

    override fun onCancelSend() {
        awaitingSendConfirmation = false
        renderStrip()
    }

    // endregion

    // region autocorrect

    override fun onSuggestionPicked(word: String) {
        // The suggestion strip loses the shared row to the warning, so this is unreachable
        // while blocked. Guarded anyway, since `replaceWord` writes to the field directly and
        // does not pass through onText.
        if (inputGate.isBlocked) return

        val current = WordScanner.currentWord(buffer.text)

        if (current.isEmpty) {
            // A prediction: nothing to replace, so insert the word plus a trailing space, which
            // is what makes tapping predictions repeatedly feel fluid.
            learnWord(word)
            onText("$word ")
            return
        }

        replaceWord(current, WordScanner.matchCase(current.text, word), revertible = false)
        learnWord(word)
        clearSuggestions()
        rescan()
        requestSuggestions()
    }

    /** Records a committed word, and the pair it followed, so suggestions improve with use. */
    private fun learnWord(word: String) {
        val vocab = userVocabulary ?: return
        val text = buffer.text
        val previous = WordScanner.wordBeforeSeparator(text).text
        vocab.learn(word, previous.ifEmpty { null })
    }

    /**
     * Recomputes the strip.
     *
     * Runs synchronously from the local vocabulary so predictions and completions appear on the
     * same keystroke that produced them — a suggestion strip that lags is worse than none. The
     * spell checker is asked in parallel and refines the correction case when it answers.
     */
    private fun requestSuggestions() {
        if (fieldProtected || !settings.autocorrectEnabled) {
            clearSuggestions()
            renderStrip()
            return
        }
        val engine = predictor ?: return

        val prefix = WordScanner.currentWord(buffer.text)
        val previous = if (prefix.isEmpty) {
            WordScanner.wordBeforeSeparator(buffer.text).text
        } else {
            WordScanner.wordBeforeSeparator(buffer.text.substring(0, prefix.start)).text
        }

        suggestions = engine.suggest(previous.ifEmpty { null }, prefix.text)
        renderStrip()

        // Only a possible typo is worth a spell-checker round trip.
        if (prefix.text.isNotEmpty() && WordScanner.isCorrectable(prefix.text)) {
            suggestionSource?.request(prefix.text)
        }
    }

    /**
     * Spell-checker candidates arriving asynchronously.
     *
     * Discarded when the word has moved on, since the user keeps typing while a request is in
     * flight and a verdict on stale text would replace the wrong word.
     */
    private fun onSuggestionsReady(word: String, candidates: List<String>) {
        if (fieldProtected || !settings.autocorrectEnabled) return
        val engine = predictor ?: return
        val current = WordScanner.currentWord(buffer.text)
        if (!current.text.equals(word, ignoreCase = true)) return
        if (candidates.isEmpty()) return

        val previous = WordScanner.wordBeforeSeparator(buffer.text.substring(0, current.start)).text
        suggestions = engine.suggest(previous.ifEmpty { null }, current.text, candidates)
        renderStrip()
    }

    /**
     * Applies the pending correction, if the top candidate was confident enough. Called when a
     * word terminator is typed.
     */
    private fun applyPendingAutocorrect() {
        val current = WordScanner.currentWord(buffer.text)
        if (current.isEmpty) return

        // Learn what the user actually typed before any correction replaces it. Typing a word
        // twice teaches the dictionary, which is how it stops correcting names.
        val previous = WordScanner.wordBeforeSeparator(buffer.text.substring(0, current.start)).text
        userVocabulary?.learn(current.text, previous.ifEmpty { null })

        if (!settings.autocorrectEnabled || suggestions.autoApplyIndex < 0) {
            clearSuggestions()
            return
        }
        val replacement = suggestions.items.getOrNull(suggestions.autoApplyIndex) ?: return
        if (current.text.equals(replacement, ignoreCase = true)) return

        replaceWord(current, replacement, revertible = true)
        clearSuggestions()
    }

    /** Undoes the last automatic correction. @return true when something was reverted. */
    private fun revertAutocorrect(): Boolean {
        val pending = autocorrect.revertible ?: return false
        val text = buffer.text
        // The replacement must still be sitting at the end of the buffer, possibly followed by
        // the terminator that triggered it. Anything else means the user has moved on.
        val trailing = text.length - pending.end
        if (trailing < 0 || trailing > 1) {
            autocorrect.invalidate()
            return false
        }
        if (!text.startsWith(pending.replacement, pending.start)) {
            autocorrect.invalidate()
            return false
        }

        val connection = currentInputConnection ?: return false
        autocorrect.consumeForRevert()

        val suffix = text.substring(pending.end)
        connection.beginBatchEdit()
        try {
            connection.deleteSurroundingText(pending.replacement.length + suffix.length, 0)
            connection.commitText(pending.original + suffix, 1)
        } finally {
            connection.endBatchEdit()
        }

        buffer.clear()
        buffer.insert(text.substring(0, pending.start) + pending.original + suffix)
        sendInference.record(Mutation.INSERT, buffer.length)
        rescan()
        return true
    }

    /** Swaps [word] for [replacement] in both the host field and our buffer. */
    private fun replaceWord(word: WordScanner.Word, replacement: String, revertible: Boolean) {
        val connection = currentInputConnection ?: return
        val text = buffer.text
        val trailing = text.length - word.end
        if (trailing < 0) return

        connection.beginBatchEdit()
        try {
            connection.deleteSurroundingText(word.text.length + trailing, 0)
            connection.commitText(replacement + text.substring(word.end), 1)
        } finally {
            connection.endBatchEdit()
        }

        buffer.clear()
        buffer.insert(text.substring(0, word.start) + replacement + text.substring(word.end))
        sendInference.record(Mutation.INSERT, buffer.length)

        if (revertible) {
            autocorrect.recordApplied(word.text, replacement, word.start)
        } else {
            autocorrect.invalidate()
        }
    }

    /** Replaces the whole composed message with a model-suggested safer version. */
    private fun applyRewrite(rewrite: String) {
        val connection = currentInputConnection ?: return
        val existing = buffer.text
        if (existing.isEmpty() || rewrite.isBlank()) return

        connection.beginBatchEdit()
        try {
            connection.deleteSurroundingText(existing.length, 0)
            connection.commitText(rewrite, 1)
        } finally {
            connection.endBatchEdit()
        }

        buffer.clear()
        buffer.insert(rewrite)
        sendInference.record(Mutation.INSERT, buffer.length)
        pendingRewrite = null
        expanded = false
        rescan()
    }

    private fun clearSuggestions() {
        suggestions = Predictor.Suggestions.EMPTY
        suggestionSource?.cancel()
    }

    // endregion

    // region IdleToolbarListener

    override fun onEmojiRequested() = setEmojiVisible(true)

    override fun onEmojiClose() = setEmojiVisible(false)

    override fun onEmoji(emoji: String) = onText(emoji)

    override fun onEmojiBackspace() = onBackspace()

    override fun onPasteRequested() {
        // Paste is the one path that can add a lot of text at once, so it is the last one that
        // should be exempt from a block.
        if (inputGate.isBlocked) return

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val pasted = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?: return

        currentInputConnection?.commitText(pasted, 1)
        if (fieldProtected) return

        // Pasted text has to be scanned like anything else — arguably more so, since a pasted
        // address or card number is exactly the sort of thing this exists to catch.
        buffer.insert(pasted)
        sendInference.record(Mutation.INSERT, buffer.length)
        dismissed = false
        rescan()
    }

    override fun onCursorLeft() = moveCursor(-1)

    override fun onCursorRight() = moveCursor(1)

    override fun onSelectAll() {
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    private fun moveCursor(delta: Int) {
        val connection = currentInputConnection ?: return
        val target = (selectionStart + delta).coerceAtLeast(0)
        if (selectionStart < 0) return
        connection.setSelection(target, target)
    }

    private fun setEmojiVisible(visible: Boolean) {
        emojiVisible = visible
        emojiPanel?.visibility = if (visible) View.VISIBLE else View.GONE
        keyboardView?.visibility = if (visible) View.GONE else View.VISIBLE
        renderStrip()
    }

    /** Enables paste only when the clipboard actually holds something. */
    private fun refreshPasteAvailability() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val available = clipboard?.hasPrimaryClip() == true
        idleToolbar?.setPasteAvailable(available)
    }

    // endregion

    private fun rescan() {
        if (fieldProtected) {
            scanResult = ScanResult.EMPTY
            localScanResult = ScanResult.EMPTY
            inputGate.update(
            scanResult,
            fieldProtected,
            effective.blocksAtHighSeverity,
            effective.overrideLevel,
        )
            renderStrip()
            return
        }

        val now = System.currentTimeMillis()
        val local = engine.scan(buffer.text, context, now)
        localScanResult = local

        // The local verdict renders immediately and unconditionally. Verification only ever
        // refines what is already on screen, so there is nothing to wait for and no spinner.
        scanResult = local
        // Blocking follows the local verdict for the same reason the strip does: waiting on a
        // network call to decide whether the keys work would be the worst possible place to
        // introduce a spinner.
        inputGate.update(
            scanResult,
            fieldProtected,
            effective.blocksAtHighSeverity,
            effective.overrideLevel,
        )
        renderStrip()

        maybeVerify(local, now)
    }

    /**
     * Asks the model for a second opinion, when the policy says it is worth the call.
     *
     * Every early return here is a call not made, which is a cost not incurred — the policy
     * class holds the reasoning and is unit-tested for exactly that.
     */
    private fun maybeVerify(local: ScanResult, nowMs: Long) {
        // Through the policy, so a parent who forced verification on gets it and one who
        // forced it off cannot be overridden from the child's settings screen.
        if (!effective.aiVerificationEnabled) {
            trace("skip: toggle off")
            return
        }
        val client = verifyClient ?: run {
            trace("skip: no endpoint configured")
            return
        }

        val request = VerifyRequest.from(
            buffer = buffer.text,
            findings = local.findings,
            context = context.contextSnapshot(),
            escalate = local.verifyReason == VerifyReason.ESCALATE,
        ) ?: run {
            trace("skip: nothing eligible to send")
            return
        }

        // A cached verdict costs nothing and applies instantly.
        verifyPolicy.cached(request)?.let { cached ->
            trace("cache hit, applying without a call")
            applyVerdict(request, cached)
            return
        }

        val decision = verifyPolicy.decide(local, buffer.length, nowMs)
        if (decision != VerifyPolicy.Decision.SEND) {
            trace("declined: $decision (used ${verifyPolicy.callsUsedToday} today)")
            return
        }

        verifyPolicy.onCallStarted(nowMs, buffer.length)
        val requestedForText = buffer.text
        val sentAtMs = System.currentTimeMillis()
        trace(
            "sending ${request.findings.size} finding(s) " +
                "[${request.findings.joinToString(",") { it.ruleId }}] escalate=${request.escalate}",
        )
        client.verify(request) { verdict ->
            mainHandler.post {
                val elapsed = System.currentTimeMillis() - sentAtMs
                if (verdict == null) {
                    trace("no verdict after ${elapsed}ms — keeping local result")
                    verifyPolicy.onCallFailed()
                    return@post
                }
                verifyPolicy.onCallFinished(request, verdict)
                // Discard a verdict for text the user has already moved past. While testing,
                // this is why a verdict can vanish: keep typing and the reply is thrown away.
                if (buffer.text == requestedForText) {
                    trace("verdict in ${elapsed}ms")
                    applyVerdict(request, verdict)
                } else {
                    trace("verdict in ${elapsed}ms, discarded — text moved on")
                }
            }
        }
    }

    /**
     * Debug trace for the verification path, which is otherwise invisible from the device.
     * `adb logcat -s KeyguardVerify` shows the whole decision chain.
     *
     * Never logs buffer contents or finding text — only rule ids, severities, and timings.
     * The no-raw-text invariant holds in debug builds too, since that is exactly where a
     * stray log line would be written and then forgotten.
     */
    private fun trace(message: String) {
        if (BuildConfig.DEBUG) Log.d(VERIFY_TAG, message)
    }

    private fun applyVerdict(request: VerifyRequest, verdict: CachedVerdict) {
        val refined = VerdictApplier.apply(localScanResult.findings, verdict)
        val maxSeverity = refined.maxOfOrNull { it.severity } ?: Severity.NONE

        trace(
            "applied: ${localScanResult.findings.size} local finding(s) " +
                "(max ${localScanResult.maxSeverity}) → ${refined.size} refined (max $maxSeverity)",
        )

        scanResult = localScanResult.copy(
            findings = refined,
            maxSeverity = maxSeverity,
            // A verified scan needs no further verification.
            eligibleForVerification = false,
            verifyReason = null,
            // Not simply "is there a self-harm finding". A verdict that says the message is
            // about someone *else's* distress stands the crisis card down, which is the one
            // case the local engine is structurally unable to see. VerdictApplier fails toward
            // showing it — see requiresCrisis.
            requiresCrisisResponse = VerdictApplier.requiresCrisis(refined, verdict),
        )
        pendingRewrite = verdict.suggestedRewrite
        // Verification can downgrade a finding — that is most of its value — and a downgrade
        // out of HIGH has to give the keys back, not just repaint the strip.
        inputGate.update(
            scanResult,
            fieldProtected,
            effective.blocksAtHighSeverity,
            effective.overrideLevel,
        )
        renderStrip()
    }

    private fun renderStrip() {
        val strip = warningStrip ?: return
        val state = resolveStripState()

        strip.render(state)
        // Dimming and refusal are driven from the same place, so the keys never look live
        // while they are inert, or the reverse.
        keyboardView?.setInputBlocked(inputGate.isBlocked)

        // One row, three possible occupants, strict priority: warning > suggestions > toolbar.
        // A spelling hint must never displace a safety warning.
        val warningVisible = state != StripState.Hidden
        val showSuggestions = !warningVisible && !emojiVisible && !suggestions.isEmpty

        suggestionStrip?.render(
            if (showSuggestions) suggestions else Predictor.Suggestions.EMPTY,
        )

        val showToolbar = !warningVisible && !showSuggestions
        idleToolbar?.visibility = if (showToolbar) View.VISIBLE else View.GONE
        if (showToolbar) refreshPasteAvailability()
    }

    private fun resolveStripState(): StripState {
        if (fieldProtected || scanResult.findings.isEmpty()) return StripState.Hidden

        // Checked before every other state. Previously the crisis branch came first, which
        // made its own Ignore button a no-op and the message undismissable — the worst
        // possible pairing with a figurative-language false positive.
        if (dismissed) return StripState.Hidden

        if (scanResult.requiresCrisisResponse) {
            // Never framed as a privacy warning, and never as a reporting threat.
            return StripState.Crisis(
                message = getString(R.string.crisis_summary),
                resourceLabel = crisisResource().label,
            )
        }

        if (awaitingSendConfirmation) {
            return StripState.ConfirmSend(
                preview = highlightedPreview(),
                summary = topFinding()?.message.orEmpty(),
            )
        }

        val finding = topFinding()
        // Severity 0 renders nothing by design; it exists to route an uncertain case to AI
        // verification, not to interrupt the user.
        if (finding == null || finding.severity == Severity.NONE) return StripState.Hidden

        val blocking = inputGate.isBlocked
        return StripState.Warning(
            summary = finding.message,
            preview = highlightedPreview(),
            detail = detailFor(finding),
            severity = finding.severity,
            // Blocking forces the strip open regardless of intensity. At Subtle the strip
            // would otherwise stay collapsed and hide both exits, which would leave the user
            // holding a dead keyboard with no visible way out.
            expanded = blocking || expanded || autoExpands(finding.severity),
            blocking = blocking,
            dismissible = effective.overrideLevel.mayDismiss(finding.severity),
        )
    }

    /** The user's text echoed back with the flagged spans highlighted in red. */
    private fun highlightedPreview(): CharSequence = Highlighter.highlight(
        text = buffer.text,
        findings = scanResult.findings,
        mediumColor = getColor(R.color.warn_medium),
        highColor = getColor(R.color.warn_high),
        onHighlightColor = getColor(R.color.warn_text),
    )

    /** Highest-severity finding, preferring the earliest span on a tie. */
    private fun topFinding(): Finding? = scanResult.findings
        .maxWithOrNull(compareBy({ it.severity.level }, { -it.start }))

    private fun detailFor(finding: Finding): String {
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

    /** Intensity resolves the underline-versus-popup question as one comprehensible setting. */
    private fun autoExpands(severity: Severity): Boolean = when (effective.intensity) {
        Intensity.SUBTLE -> false
        Intensity.STANDARD -> severity == Severity.HIGH
        Intensity.INSISTENT -> severity.level >= Severity.MEDIUM.level
    }

    private fun shouldGateSend(): Boolean {
        if (dismissed || scanResult.requiresCrisisResponse) return false
        val max = scanResult.maxSeverity
        return when (effective.intensity) {
            Intensity.SUBTLE -> false
            Intensity.STANDARD -> max == Severity.HIGH
            Intensity.INSISTENT -> max.level >= Severity.MEDIUM.level
        }
    }

    private fun resetWarningState() {
        scanResult = ScanResult.EMPTY
        localScanResult = ScanResult.EMPTY
        pendingRewrite = null
        expanded = false
        dismissed = false
        awaitingSendConfirmation = false
        inputGate.reset()
        clearSuggestions()
        autocorrect.clear()
    }

    private companion object {
        const val VOCAB_WORDS_ASSET = "vocab/words.txt"
        const val VOCAB_BIGRAMS_ASSET = "vocab/bigrams.txt"
        const val VERIFY_TAG = "KeyguardVerify"
    }

    private fun recordOutcome(outcome: ComposeOutcome) {
        // Sampled before the guard below, because that guard is about *warnings* and this is
        // not one. A report whose only inputs were flagged messages could describe a bad
        // afternoon and nothing else, which is the opposite of what a parent asked for.
        reportActivity(outcome, buffer.text)

        // Nothing was shown, so there is no outcome to attribute to a warning.
        if (scanResult.maxSeverity == Severity.NONE) return

        val heeded = outcome == ComposeOutcome.ABANDONED_DELETED &&
            scanResult.maxSeverity.level >= Severity.MEDIUM.level
        outcomeLog.record(scanResult.maxSeverity, outcome, heeded)
        reportToParent(outcome, heeded)
    }

    /**
     * Queues the warning for the parent, on a supervised device only.
     *
     * This is the whole of the keyboard's involvement in parent reporting: an append to a
     * local queue, on the same thread, taking no longer than the counter write above it. The
     * upload lives in [com.keyguard.app.family.SupervisionSync], driven from the app process —
     * on iOS a keyboard extension may not do that at all, and building it the other way now
     * would mean discovering at port time that the sync lives somewhere it can never live.
     *
     * Crisis findings are included, on an explicit product decision. Note what that does *not*
     * change: [ScanResult.requiresCrisisResponse] still forbids saying anything about
     * reporting in the moment, so the crisis strip stays supportive and silent on the subject.
     * A young person is told about this at pairing, on the supervision screen, and by the
     * standing notification — not while they are mid-sentence about hurting themselves.
     */
    private fun reportToParent(outcome: ComposeOutcome, heeded: Boolean) {
        val queue = eventQueue ?: return
        val category = SupervisionEvent.dominantCategory(scanResult) ?: return

        queue.append(
            SupervisionEvent(
                at = System.currentTimeMillis(),
                category = category,
                severity = scanResult.maxSeverity,
                outcome = outcome,
                heeded = heeded,
            ),
        )
    }

    /**
     * Queues the message itself, at whatever depth the parent's review scope permits.
     *
     * Kept separate from [reportToParent] rather than folded into it, because the two are
     * gated on different things and fire on different messages. An event exists only when
     * something was flagged; a sample exists for ordinary messages too, which is the entire
     * point of it — a report built only from warnings can describe a bad afternoon and nothing
     * else.
     *
     * There is no scope check written out here on purpose. [ContentCapture] is the gate, and a
     * second copy of the condition at this call site would be a second place for the two to
     * disagree about what a family agreed to.
     */
    private fun reportActivity(outcome: ComposeOutcome, text: String) {
        val queue = sampleQueue ?: return
        if (!effective.reportsEnabled) return

        ContentCapture.sample(
            scope = effective.reviewScope,
            summary = engine.summarize(text),
            outcome = outcome,
            atMs = System.currentTimeMillis(),
            flagged = scanResult.maxSeverity != Severity.NONE,
            text = text,
            protectedField = fieldProtected,
        )?.let(queue::append)
    }
}
