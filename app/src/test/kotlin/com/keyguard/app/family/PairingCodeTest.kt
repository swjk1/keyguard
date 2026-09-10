package com.keyguard.app.family

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingCodeTest {

    @Test
    fun `hyphens and spaces are not part of the code`() {
        assertEquals("A1B2C3D4", PairingCode.normalize("a1b2-c3d4"))
        assertEquals("A1B2C3D4", PairingCode.normalize(" A1B2 C3D4 "))
    }

    @Test
    fun `look-alike characters are folded rather than rejected`() {
        // A child reading their parent's screen types O for zero and I for one.
        assertEquals("0123", PairingCode.normalize("OI23"))
        assertEquals("1", PairingCode.normalize("L"))
    }

    @Test
    fun `characters outside the alphabet are dropped`() {
        assertEquals("ABC", PairingCode.normalize("A/B?C!"))
    }

    @Test
    fun `only a full-length code is worth a round trip`() {
        assertTrue(PairingCode.isValid("A1B2C3D4"))
        assertFalse(PairingCode.isValid("A1B2C3D"))
        assertFalse(PairingCode.isValid("A1B2C3D45"))
        assertFalse(PairingCode.isValid(""))
    }

    @Test
    fun `the ambiguous letters are absent from the alphabet entirely`() {
        for (excluded in listOf('I', 'L', 'O', 'U')) {
            assertFalse(excluded in PairingCode.ALPHABET, "$excluded should not be mintable")
        }
    }

    @Test
    fun `formatting is reversible by normalize`() {
        val code = "9XYZ7KMN"
        assertEquals("9XYZ-7KMN", PairingCode.format(code))
        assertEquals(code, PairingCode.normalize(PairingCode.format(code)))
    }
}
