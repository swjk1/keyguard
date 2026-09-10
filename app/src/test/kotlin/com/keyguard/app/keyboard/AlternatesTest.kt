package com.keyguard.app.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlternatesTest {

    @Test
    fun `top row letters offer their digit first`() {
        // The digit is by far the most common reason to long-press the top row, so it has to
        // be the option under the finger when the popup opens.
        val expected = mapOf(
            "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
            "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        )
        for ((key, digit) in expected) {
            assertEquals(digit, Alternates.forKey(key).first(), "key '$key'")
        }
    }

    @Test
    fun `digits are omitted when disabled`() {
        // Pointless on the symbols plane, where the digits are already on screen.
        assertFalse(Alternates.forKey("q", includeDigits = false).contains("1"))
        assertTrue(Alternates.forKey("q", includeDigits = true).contains("1"))
    }

    @Test
    fun `accented letters are offered`() {
        assertTrue(Alternates.forKey("e").containsAll(listOf("é", "è", "ê")))
        assertTrue(Alternates.forKey("n").contains("ñ"))
        assertTrue(Alternates.forKey("c").contains("ç"))
        assertTrue(Alternates.forKey("a").contains("á"))
    }

    @Test
    fun `keys with no alternates report none`() {
        // 'd' has neither a top-row digit nor a common accent.
        assertTrue(Alternates.forKey("d").isEmpty())
        assertFalse(Alternates.hasAlternates("d"))
    }

    @Test
    fun `currency alternates hang off the dollar key`() {
        assertTrue(Alternates.forKey("$").containsAll(listOf("€", "£", "¥")))
    }

    @Test
    fun `shift state is applied to letter alternates`() {
        assertEquals("É", Alternates.applyCase("é", upper = true))
        assertEquals("é", Alternates.applyCase("é", upper = false))
    }

    @Test
    fun `case folding leaves non letters untouched`() {
        // Uppercasing a digit or a currency symbol must be a no-op, not a corruption.
        for (symbol in listOf("1", "€", "…", "°")) {
            assertEquals(symbol, Alternates.applyCase(symbol, upper = true))
        }
    }

    @Test
    fun `no alternate list contains duplicates`() {
        val keys = ('a'..'z').map { it.toString() } +
            listOf(".", ",", "?", "!", "-", "'", "\"", "$", "/", "(", ")", "0", "1", "2", "3")

        for (key in keys) {
            val alternates = Alternates.forKey(key)
            assertEquals(
                alternates.size,
                alternates.distinct().size,
                "key '$key' offers a duplicate alternate: $alternates",
            )
        }
    }

    @Test
    fun `no alternate is blank or multi character`() {
        // A blank entry would render an invisible option; a multi-glyph entry would not fit
        // the fixed-width popup cells.
        val keys = ('a'..'z').map { it.toString() } + listOf(".", ",", "$", "1")
        for (key in keys) {
            for (alternate in Alternates.forKey(key)) {
                assertTrue(alternate.isNotBlank(), "blank alternate on '$key'")
                assertTrue(
                    alternate.codePointCount(0, alternate.length) == 1,
                    "alternate '$alternate' on '$key' is not a single character",
                )
            }
        }
    }
}
