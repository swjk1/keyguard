package com.keyguard.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A dead crisis number is the worst failure mode this product has, so the invariant asserted
 * hardest here is that **no input path returns nothing usable**.
 */
class CrisisResourcesTest {

    @Test
    fun `known regions resolve to their own helpline`() {
        assertEquals("tel:988", CrisisResources.forRegion("US").dial)
        assertEquals("tel:116123", CrisisResources.forRegion("GB").dial)
        assertEquals("tel:131114", CrisisResources.forRegion("AU").dial)
    }

    @Test
    fun `region codes are case and whitespace insensitive`() {
        val expected = CrisisResources.forRegion("GB")
        assertEquals(expected, CrisisResources.forRegion("gb"))
        assertEquals(expected, CrisisResources.forRegion(" Gb "))
    }

    @Test
    fun `unknown regions fall back to a directory rather than a dead number`() {
        // The original bug was shipping tel:988 worldwide. Anything unrecognised must reach a
        // resource that actually works, not a disconnected line.
        for (unknown in listOf("XX", "ZZ", "KP")) {
            val resource = CrisisResources.forRegion(unknown)
            assertEquals(CrisisResources.FALLBACK, resource, "region $unknown")
            assertTrue(resource.web.startsWith("https://"))
        }
    }

    @Test
    fun `malformed input falls back instead of throwing`() {
        for (bad in listOf(null, "", "   ", "U", "USA", "1", "!!")) {
            val resource = CrisisResources.forRegion(bad)
            assertEquals(CrisisResources.FALLBACK, resource, "input '$bad'")
        }
    }

    @Test
    fun `every resource has a usable primary uri`() {
        val all = CrisisResources.supportedRegions.map { CrisisResources.forRegion(it) } +
            CrisisResources.FALLBACK

        for (resource in all) {
            assertNotNull(resource.primaryUri)
            assertTrue(
                resource.primaryUri.startsWith("tel:") || resource.primaryUri.startsWith("https://"),
                "unusable primary uri '${resource.primaryUri}' for ${resource.label}",
            )
            assertTrue(resource.label.isNotBlank(), "every resource needs a describable label")
            assertTrue(resource.web.startsWith("https://"), "web fallback must be https")
        }
    }

    @Test
    fun `dial numbers contain no formatting that would break the dialer`() {
        for (region in CrisisResources.supportedRegions) {
            val dial = CrisisResources.forRegion(region).dial ?: continue
            val digits = dial.removePrefix("tel:")
            assertTrue(digits.isNotEmpty(), "$region has an empty number")
            assertTrue(
                digits.all { it.isDigit() || it == '+' },
                "$region number '$digits' contains spaces or punctuation the dialer may reject",
            )
        }
    }

    @Test
    fun `fallback is never a phone number`() {
        // A global fallback cannot name a number, because no number is global.
        assertFalse(CrisisResources.FALLBACK.dial != null)
        assertEquals(CrisisResources.FALLBACK.web, CrisisResources.FALLBACK.primaryUri)
    }
}
