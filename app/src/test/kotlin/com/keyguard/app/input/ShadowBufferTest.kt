package com.keyguard.app.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShadowBufferTest {

    @Test
    fun `tracks inserts and deletes`() {
        val buffer = ShadowBuffer()
        buffer.insert("hell")
        buffer.insert("o")
        assertEquals("hello", buffer.text)

        assertEquals(1, buffer.deleteBackward())
        assertEquals("hell", buffer.text)
    }

    @Test
    fun `deleting past the start is a no-op`() {
        val buffer = ShadowBuffer()
        buffer.insert("ab")
        assertEquals(2, buffer.deleteBackward(5))
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.deleteBackward())
    }

    @Test
    fun `agreement with the host is not treated as drift`() {
        val buffer = ShadowBuffer()
        buffer.insert("hello")
        assertFalse(buffer.reconcile("hello"))
        assertEquals("hello", buffer.text)
    }

    @Test
    fun `a truncated host view still counts as agreement`() {
        // Host-side context is often only a tail of the field, so a suffix match must not be
        // mistaken for an external edit - otherwise every selection change looks like a send.
        val buffer = ShadowBuffer()
        buffer.insert("a very long message that the host will only partially report")
        assertFalse(buffer.reconcile("only partially report"))
    }

    @Test
    fun `a host clear is detected as drift and empties the buffer`() {
        val buffer = ShadowBuffer()
        buffer.insert("hello there")

        assertTrue(buffer.reconcile(""), "a cleared host field is an external change")
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `a paste is detected as drift and adopts the host text`() {
        val buffer = ShadowBuffer()
        buffer.insert("hi ")

        assertTrue(buffer.reconcile("hi pasted content"))
        assertEquals("hi pasted content", buffer.text)
    }

    @Test
    fun `buffer stays bounded`() {
        val buffer = ShadowBuffer(maxLength = 16)
        buffer.insert("x".repeat(100))
        assertEquals(16, buffer.length)
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = ShadowBuffer()
        buffer.insert("sensitive")
        buffer.clear()
        assertTrue(buffer.isEmpty)
        assertEquals("", buffer.text)
    }
}
