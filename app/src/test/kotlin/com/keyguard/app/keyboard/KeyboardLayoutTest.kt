package com.keyguard.app.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Alignment is a property of the layout data, so it can be asserted rather than eyeballed.
 *
 * The first build shipped a middle row of 9 keys each at weight 1.0, stretched across the
 * full width. That made every key in it wider than the 10-key row above and visibly knocked
 * the columns out of line. A row-weight assertion catches that class of mistake immediately.
 */
class KeyboardLayoutTest {

    @Test
    fun `every row in every plane sums to the same width`() {
        for (plane in Plane.entries) {
            KeyboardLayout.rowsFor(plane).forEachIndexed { index, row ->
                assertEquals(
                    KeyboardLayout.ROW_UNITS,
                    KeyboardLayout.rowWeight(row),
                    absoluteTolerance = 0.001f,
                    "plane $plane row $index must sum to ${KeyboardLayout.ROW_UNITS} " +
                        "or its keys will not align with the other rows",
                )
            }
        }
    }

    @Test
    fun `every plane has four rows`() {
        for (plane in Plane.entries) {
            assertEquals(4, KeyboardLayout.rowsFor(plane).size, "plane $plane")
        }
    }

    @Test
    fun `the letter middle row is indented rather than stretched`() {
        val middleRow = KeyboardLayout.rowsFor(Plane.LETTERS)[1]
        assertTrue(middleRow.first() is Key.Spacer, "middle row must start with a spacer")
        assertTrue(middleRow.last() is Key.Spacer, "middle row must end with a spacer")

        val letters = middleRow.filterIsInstance<Key.Character>()
        assertEquals(9, letters.size)
        assertEquals("asdfghjkl", letters.joinToString("") { it.lower })
        assertTrue(
            letters.all { it.weight == 1f },
            "letters must stay a standard key wide so they line up with the row above",
        )
    }

    @Test
    fun `letter rows are the standard qwerty arrangement`() {
        val rows = KeyboardLayout.rowsFor(Plane.LETTERS)
        assertEquals(
            "qwertyuiop",
            rows[0].filterIsInstance<Key.Character>().joinToString("") { it.lower },
        )
        assertEquals(
            "zxcvbnm",
            rows[2].filterIsInstance<Key.Character>().joinToString("") { it.lower },
        )
    }

    @Test
    fun `symbol planes return to letters rather than deeper into symbols`() {
        // A user who reaches a symbol plane must be able to get back with one tap; sending
        // them further in was a real bug in the first pass.
        for (plane in listOf(Plane.SYMBOLS, Plane.MORE_SYMBOLS)) {
            val bottomRow = KeyboardLayout.rowsFor(plane).last()
            val switch = bottomRow.filterIsInstance<Key.SwitchPlane>().single()
            assertEquals(Plane.LETTERS, switch.target, "plane $plane bottom row")
            assertEquals("ABC", switch.label)
        }
    }

    @Test
    fun `letters plane offers a route into symbols`() {
        val switch = KeyboardLayout.rowsFor(Plane.LETTERS).last()
            .filterIsInstance<Key.SwitchPlane>().single()
        assertEquals(Plane.SYMBOLS, switch.target)
    }

    @Test
    fun `every plane can reach the next input method`() {
        // Guideline 4.4.1 requires a keyboard to provide a way to move to the next one.
        for (plane in Plane.entries) {
            assertTrue(
                KeyboardLayout.rowsFor(plane).any { row -> row.contains(Key.NextKeyboard) },
                "plane $plane must expose a next-keyboard key",
            )
        }
    }

    @Test
    fun `every plane has backspace enter and space`() {
        for (plane in Plane.entries) {
            val keys = KeyboardLayout.rowsFor(plane).flatten()
            assertTrue(keys.contains(Key.Backspace), "plane $plane needs backspace")
            assertTrue(keys.contains(Key.Enter), "plane $plane needs enter")
            assertTrue(keys.contains(Key.Space), "plane $plane needs space")
        }
    }

    @Test
    fun `no duplicate characters within a plane`() {
        for (plane in Plane.entries) {
            val chars = KeyboardLayout.rowsFor(plane).flatten()
                .filterIsInstance<Key.Character>()
                .map { it.lower }
            val duplicates = chars.groupBy { it }.filterValues { it.size > 1 }.keys
            // "," and "." appear on the bottom row of every plane by design; nothing else
            // should be reachable twice from the same plane.
            assertTrue(
                duplicates.all { it == "," || it == "." },
                "plane $plane has unexpected duplicate keys: $duplicates",
            )
        }
    }
}
