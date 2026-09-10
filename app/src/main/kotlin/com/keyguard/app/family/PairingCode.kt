package com.keyguard.app.family

/**
 * The code a parent reads out and a child types in.
 *
 * Codes are minted on the server, never here — a client-generated pairing code would let a
 * device claim a family it was never given. This side only cleans up what someone typed and
 * decides whether it is worth spending a round trip on.
 *
 * The alphabet is Crockford base32: no `I`, `L`, `O` or `U`. The first three are the ones
 * people misread across a kitchen table, and `U` is dropped so a random code cannot spell
 * something a child has to read aloud to their parent. [normalize] then maps the confusions
 * back rather than rejecting them, so a child who types `O` for zero gets in instead of
 * getting an error they cannot act on.
 *
 * Pure and Android-free, like the rest of the logic worth testing.
 */
object PairingCode {

    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 8
    private const val GROUP = 4

    /**
     * Canonical form of arbitrary user input: upper case, separators dropped, look-alikes
     * folded. Returns whatever it can — [isValid] decides if the result is usable.
     */
    fun normalize(raw: String): String = buildString {
        for (character in raw.uppercase()) {
            when (character) {
                'O' -> append('0')
                'I', 'L' -> append('1')
                else -> if (character in ALPHABET) append(character)
            }
        }
    }

    fun isValid(normalized: String): Boolean =
        normalized.length == LENGTH && normalized.all { it in ALPHABET }

    /** `A1B2-C3D4`. Split for reading aloud; the hyphen is decoration and [normalize] drops it. */
    fun format(normalized: String): String =
        normalized.chunked(GROUP).joinToString("-")
}
