package com.keyguard.detect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The privacy gate the plan calls out as non-negotiable.
 *
 * A keyboard that inspects password fields is a credential harvester whatever its intent,
 * and it is also the fastest possible route to a store takedown. This is the one behaviour
 * that must never regress, so it is asserted directly rather than left implicit in the IME.
 */
class FieldPolicyTest {

    private val normalText = FieldPolicy.TYPE_CLASS_TEXT
    private val noFlags = 0

    @Test
    fun `password fields are protected`() {
        val variants = listOf(
            FieldPolicy.TYPE_TEXT_VARIATION_PASSWORD,
            FieldPolicy.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            FieldPolicy.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        for (variation in variants) {
            val inputType = FieldPolicy.TYPE_CLASS_TEXT or variation
            assertTrue(
                FieldPolicy.isProtectedField(inputType, noFlags),
                "text variation 0x${variation.toString(16)} must be protected",
            )
            assertFalse(FieldPolicy.mayScan(inputType, noFlags))
        }
    }

    @Test
    fun `numeric pin fields are protected`() {
        val inputType = FieldPolicy.TYPE_CLASS_NUMBER or FieldPolicy.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(FieldPolicy.isProtectedField(inputType, noFlags))
    }

    @Test
    fun `no personalized learning flag is honored regardless of field type`() {
        // Apps set this to say "do not retain anything from this field". Honoring it is
        // both correct and cheap.
        assertTrue(FieldPolicy.isProtectedField(normalText, FieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }

    @Test
    fun `ordinary message fields are scannable`() {
        assertTrue(FieldPolicy.mayScan(normalText, noFlags))
        // A plain numeric field (quantity, age) is not a PIN field.
        assertTrue(FieldPolicy.mayScan(FieldPolicy.TYPE_CLASS_NUMBER, noFlags))
    }
}
