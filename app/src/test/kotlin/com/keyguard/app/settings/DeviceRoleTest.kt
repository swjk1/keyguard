package com.keyguard.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The role lock.
 *
 * One of these tests is a security property rather than a preference, and it is the reason the
 * rules are pure rather than living inside the activity: a supervised child must not be able to
 * leave supervision by picking a different role. That would be the unpair button the product
 * deliberately never built, reintroduced through the back door.
 */
class DeviceRoleTest {

    @Test
    fun `a supervised device cannot change role`() {
        assertFalse(
            RolePolicy.mayChange(
                current = DeviceRole.CHILD,
                isSupervised = true,
                hasParentSession = false,
            ),
        )
    }

    @Test
    fun `supervision locks the role whatever the stored value claims`() {
        // Checked before the current role is even consulted, so a corrupted or hand-edited
        // preference is not an escape hatch.
        for (role in DeviceRole.entries) {
            assertEquals(
                RolePolicy.Verdict.LockedBySupervision,
                RolePolicy.verdict(role, isSupervised = true, hasParentSession = false),
                "supervision did not lock $role",
            )
        }
    }

    @Test
    fun `a signed-in parent must sign out first`() {
        // Not a security boundary in the same way — a parent can sign out — but it stops the
        // dashboard being swapped for the child side while a session is live, which would leave
        // an authenticated account reachable from a device now claiming to be a child's.
        assertEquals(
            RolePolicy.Verdict.LockedByParentSession,
            RolePolicy.verdict(DeviceRole.PARENT, isSupervised = false, hasParentSession = true),
        )
    }

    @Test
    fun `a parent with no session may change freely`() {
        assertTrue(
            RolePolicy.mayChange(
                current = DeviceRole.PARENT,
                isSupervised = false,
                hasParentSession = false,
            ),
        )
    }

    @Test
    fun `an unset device may choose either side`() {
        assertTrue(
            RolePolicy.mayChange(
                current = DeviceRole.UNSET,
                isSupervised = false,
                hasParentSession = false,
            ),
        )
    }

    @Test
    fun `an unrecognised stored role is unset rather than a guess`() {
        // Falling back to CHILD or PARENT would silently route someone into half a product.
        // UNSET asks, which is the only honest answer to an unreadable value.
        assertEquals(DeviceRole.UNSET, DeviceRole.fromName("GUARDIAN"))
        assertEquals(DeviceRole.UNSET, DeviceRole.fromName(null))
    }
}
