package com.keyguard.app.keyboard

/**
 * Long-press alternates for each key.
 *
 * The single biggest daily-use gap versus a stock keyboard. Without these, typing a name with
 * an accent or a digit means switching planes, and users who hit that friction go straight
 * back to the keyboard they came from — at which point the detection engine protects nobody.
 *
 * The first entry of each list is the primary alternate, shown when the user long-presses and
 * releases without moving.
 */
object Alternates {

    /**
     * Digits on the top letter row, matching the stock layout so muscle memory transfers.
     * These are merged into [forKey] rather than listed separately.
     */
    private val topRowDigits = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
    )

    private val letterAccents = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "c" to listOf("ç", "ć", "č"),
        "e" to listOf("é", "è", "ê", "ë", "ē", "ė", "ę"),
        "i" to listOf("î", "ï", "í", "ī", "į", "ì"),
        "l" to listOf("ł"),
        "n" to listOf("ñ", "ń"),
        "o" to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
        "s" to listOf("ß", "ś", "š"),
        "u" to listOf("û", "ü", "ù", "ú", "ū"),
        "y" to listOf("ÿ"),
        "z" to listOf("ž", "ź", "ż"),
    )

    private val punctuationAlternates = mapOf(
        "." to listOf(",", "?", "!", "…", ":", ";", "'", "\""),
        "," to listOf("'", "\"", ":", ";", "-", "_", "(", ")"),
        "?" to listOf("¿", "!", "."),
        "!" to listOf("¡", "?", "."),
        "-" to listOf("–", "—", "_", "•"),
        "'" to listOf("‘", "’", "\""),
        "\"" to listOf("“", "”", "'"),
        "$" to listOf("€", "£", "¥", "¢", "₹", "₽"),
        "/" to listOf("\\"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "0" to listOf("°", "∅"),
        "1" to listOf("¹", "½", "⅓"),
        "2" to listOf("²"),
        "3" to listOf("³", "¾"),
    )

    /**
     * Alternates for [lowercaseKey], or an empty list when the key has none.
     *
     * @param includeDigits whether top-row digits should be offered. True on the letters
     *   plane, where they save a plane switch; pointless on the symbols plane.
     */
    fun forKey(lowercaseKey: String, includeDigits: Boolean = true): List<String> {
        val digit = if (includeDigits) topRowDigits[lowercaseKey] else null
        val accents = letterAccents[lowercaseKey].orEmpty()
        val punctuation = punctuationAlternates[lowercaseKey].orEmpty()

        // Digit first: on the top row it is by far the most common reason to long-press.
        return buildList {
            digit?.let { add(it) }
            addAll(accents)
            addAll(punctuation)
        }
    }

    fun hasAlternates(lowercaseKey: String, includeDigits: Boolean = true): Boolean =
        forKey(lowercaseKey, includeDigits).isNotEmpty()

    /** Applies the keyboard's shift state to a chosen alternate. */
    fun applyCase(alternate: String, upper: Boolean): String =
        if (upper) alternate.uppercase() else alternate
}
