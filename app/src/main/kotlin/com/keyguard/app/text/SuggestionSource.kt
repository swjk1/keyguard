package com.keyguard.app.text

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager

/**
 * Supplies correction candidates for a word.
 *
 * Uses the **platform spell checker** rather than a bundled dictionary. That is a deliberate
 * trade: it means no multi-megabyte word list in the APK (which matters a great deal on iOS,
 * where the extension memory ceiling is around 30-50MB), it inherits whatever language the user
 * has configured, and it stays current without shipping updates. The cost is that it may be
 * absent or disabled, so callers must tolerate getting nothing back.
 *
 * Results arrive asynchronously on the main thread.
 */
class SuggestionSource(context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var session: SpellCheckerSession? = null

    /** The word the in-flight request was for, so a stale response can be discarded. */
    private var pendingWord: String? = null
    private var callback: ((String, List<String>) -> Unit)? = null

    val isAvailable: Boolean get() = session != null

    init {
        val manager = context.getSystemService(TextServicesManager::class.java)
        session = runCatching {
            manager?.newSpellCheckerSession(
                null,
                null,
                object : SpellCheckerSession.SpellCheckerSessionListener {
                    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
                        deliver(results?.flatMap(::extract).orEmpty())
                    }

                    override fun onGetSentenceSuggestions(
                        results: Array<out SentenceSuggestionsInfo>?,
                    ) {
                        val words = results.orEmpty().flatMap { sentence ->
                            (0 until sentence.suggestionsCount).flatMap { index ->
                                extract(sentence.getSuggestionsInfoAt(index))
                            }
                        }
                        deliver(words)
                    }
                },
                true,
            )
        }.getOrNull()
    }

    fun setCallback(onSuggestions: (word: String, candidates: List<String>) -> Unit) {
        callback = onSuggestions
    }

    /** Requests candidates for [word]. Silently does nothing when no spell checker exists. */
    fun request(word: String) {
        val active = session ?: return
        pendingWord = word
        runCatching {
            active.getSentenceSuggestions(arrayOf(TextInfo(word)), CANDIDATES_PER_WORD)
        }
    }

    fun cancel() {
        pendingWord = null
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        callback = null
        pendingWord = null
    }

    private fun extract(info: SuggestionsInfo): List<String> =
        (0 until info.suggestionsCount).mapNotNull { runCatching { info.getSuggestionAt(it) }.getOrNull() }

    private fun deliver(words: List<String>) {
        val word = pendingWord ?: return
        handler.post { callback?.invoke(word, words) }
    }

    private companion object {
        /** Ask for more than are shown, since re-ranking discards some. */
        const val CANDIDATES_PER_WORD = 6
    }
}
