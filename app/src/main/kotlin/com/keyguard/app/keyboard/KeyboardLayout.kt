package com.keyguard.app.keyboard

/**
 * A single key.
 *
 * Weights are in key units where 1.0 is a standard letter key. **Every row must total
 * [KeyboardLayout.ROW_UNITS]**, which is what makes keys line up vertically between rows.
 * Getting this wrong is immediately visible as a misaligned keyboard: a row of 9 keys
 * stretched to full width sits noticeably wider than the 10-key row above it.
 */
sealed class Key {
    open val weight: Float get() = 1f

    data class Character(val lower: String, val upper: String = lower.uppercase()) : Key()

    /**
     * Invisible filler. The standard QWERTY middle row has 9 keys against the top row's 10,
     * so it is indented by half a key on each side rather than stretched.
     */
    data class Spacer(override val weight: Float = 0.5f) : Key()

    data object Shift : Key() {
        override val weight: Float get() = 1.5f
    }

    data object Backspace : Key() {
        override val weight: Float get() = 1.5f
    }

    data object Space : Key() {
        override val weight: Float get() = 4f
    }

    data object Enter : Key() {
        override val weight: Float get() = 1.5f
    }

    data class SwitchPlane(val target: Plane, val label: String) : Key() {
        override val weight: Float get() = 1.5f
    }

    /**
     * Hands off to the next input method. Guideline 4.4.1 requires a keyboard to "provide a
     * method for progressing to the next keyboard", and Android users expect it too.
     */
    data object NextKeyboard : Key() {
        override val weight: Float get() = 1f
    }
}

enum class Plane { LETTERS, SYMBOLS, MORE_SYMBOLS }

typealias KeyRow = List<Key>

object KeyboardLayout {

    /** Total weight every row must sum to. */
    const val ROW_UNITS: Float = 10f

    private fun chars(spec: String): KeyRow = spec.split(" ").map { Key.Character(it) }

    /** The bottom row is identical across planes apart from its plane-switch key. */
    private fun bottomRow(switchTo: Plane, label: String): KeyRow = listOf(
        Key.SwitchPlane(switchTo, label),
        Key.NextKeyboard,
        Key.Character(","),
        Key.Space,
        Key.Character("."),
        Key.Enter,
    )

    /** The upper three rows of each plane. The bottom row is appended by [rowsFor]. */
    private val letters: List<KeyRow> = listOf(
        // 10 keys
        chars("q w e r t y u i o p"),
        // 0.5 + 9 + 0.5 — the half-key indent that makes this row align with the one above
        listOf(Key.Spacer()) + chars("a s d f g h j k l") + listOf(Key.Spacer()),
        // 1.5 + 7 + 1.5
        listOf(Key.Shift) + chars("z x c v b n m") + listOf(Key.Backspace),
    )

    private val symbols: List<KeyRow> = listOf(
        chars("1 2 3 4 5 6 7 8 9 0"),
        chars("- / : ; ( ) $ & @ \""),
        listOf(Key.SwitchPlane(Plane.MORE_SYMBOLS, "=\\<")) +
            chars("% * . , ? ! '") +
            listOf(Key.Backspace),
    )

    private val moreSymbols: List<KeyRow> = listOf(
        chars("~ ` | • √ π ÷ × ¶ ∆"),
        chars("£ ¢ € ¥ ^ ° = { } \\"),
        listOf(Key.SwitchPlane(Plane.SYMBOLS, "?123")) +
            chars("© ® ™ [ ] < >") +
            listOf(Key.Backspace),
    )

    fun rowsFor(plane: Plane): List<KeyRow> {
        val upper = when (plane) {
            Plane.LETTERS -> letters
            Plane.SYMBOLS -> symbols
            Plane.MORE_SYMBOLS -> moreSymbols
        }
        // From a symbol plane the bottom-left key returns to letters, not deeper into symbols.
        val bottom = when (plane) {
            Plane.LETTERS -> bottomRow(Plane.SYMBOLS, "?123")
            Plane.SYMBOLS, Plane.MORE_SYMBOLS -> bottomRow(Plane.LETTERS, "ABC")
        }
        return upper + listOf(bottom)
    }

    /** Row weights, exposed so a test can assert alignment rather than trusting the eye. */
    fun rowWeight(row: KeyRow): Float = row.sumOf { it.weight.toDouble() }.toFloat()
}
