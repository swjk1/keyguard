package com.keyguard.app.text

/**
 * Finds the word the user is currently typing.
 *
 * Pure and Android-free so the boundary rules — which are fiddlier than they look — can be
 * tested without a device.
 */
object WordScanner {

    /** A located word and its span within the buffer. */
    data class Word(val text: String, val start: Int, val end: Int) {
        val isEmpty: Boolean get() = text.isEmpty()
    }

    /**
     * Characters that can appear inside a word. Apostrophes are included so "don't" and
     * "it's" are treated as single words rather than two fragments — correcting "dont" to
     * "don't" is one of the most common corrections there is.
     */
    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’'

    /**
     * The word ending at the cursor, i.e. at the end of [buffer].
     *
     * Returns an empty word when the buffer ends on a separator, which is the signal that the
     * user has just finished a word rather than being partway through one.
     */
    fun currentWord(buffer: String): Word {
        var start = buffer.length
        while (start > 0 && isWordChar(buffer[start - 1])) start--
        return Word(buffer.substring(start), start, buffer.length)
    }

    /**
     * The word immediately before a just-typed separator.
     *
     * Used when the user presses space or punctuation: at that moment the word to correct is
     * behind the separator, not at the cursor.
     */
    fun wordBeforeSeparator(buffer: String): Word {
        var end = buffer.length
        // Skip any trailing separators the user just typed.
        while (end > 0 && !isWordChar(buffer[end - 1])) end--
        if (end == 0) return Word("", 0, 0)

        var start = end
        while (start > 0 && isWordChar(buffer[start - 1])) start--
        return Word(buffer.substring(start, end), start, end)
    }

    /** True when [text] ends a word, i.e. typing it should commit any pending correction. */
    fun isWordTerminator(text: String): Boolean {
        if (text.isEmpty()) return false
        return text.all { !isWordChar(it) }
    }

    /**
     * Whether a word is worth offering corrections for.
     *
     * Skips very short words (too many equally plausible candidates to be useful), anything
     * containing digits, and fully-uppercase words, which are usually acronyms rather than
     * typos.
     */
    fun isCorrectable(word: String): Boolean {
        if (word.length < MIN_CORRECTABLE_LENGTH) return false
        if (word.any { it.isDigit() }) return false
        if (word.length > MAX_CORRECTABLE_LENGTH) return false
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return !(letters.length > 1 && letters.all { it.isUpperCase() })
    }

    /**
     * Copies the capitalisation pattern of [source] onto [replacement], so correcting
     * "Teh" yields "The" rather than "the".
     */
    fun matchCase(source: String, replacement: String): String = when {
        source.isEmpty() || replacement.isEmpty() -> replacement
        source.all { !it.isLetter() || it.isUpperCase() } && source.count { it.isLetter() } > 1 ->
            replacement.uppercase()
        source.first().isUpperCase() ->
            replacement.replaceFirstChar { it.uppercaseChar() }
        else -> replacement
    }

    const val MIN_CORRECTABLE_LENGTH = 3
    const val MAX_CORRECTABLE_LENGTH = 24
}
