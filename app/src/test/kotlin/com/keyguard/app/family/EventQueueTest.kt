package com.keyguard.app.family

import com.keyguard.app.input.ComposeOutcome
import com.keyguard.detect.Category
import com.keyguard.detect.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bounding and parsing rules only. The SharedPreferences shim around them is three lines
 * and holds no decisions.
 */
class EventQueueTest {

    private fun events(count: Int, from: Long = 0) = (0 until count).map {
        SupervisionEvent(
            at = from + it,
            category = Category.PII_DISCLOSURE,
            severity = Severity.MEDIUM,
            outcome = ComposeOutcome.SENT,
            heeded = false,
        )
    }

    @Test
    fun `a queue under capacity is untouched`() {
        val queued = events(5)
        assertEquals(queued, EventQueue.bounded(queued))
    }

    @Test
    fun `an exactly full queue is untouched`() {
        val queued = events(EventQueue.CAPACITY)
        assertEquals(queued, EventQueue.bounded(queued))
    }

    @Test
    fun `overflow drops the oldest, keeping the recent end a parent is looking at`() {
        val queued = events(EventQueue.CAPACITY + 10)
        val bounded = EventQueue.bounded(queued)

        assertEquals(EventQueue.CAPACITY, bounded.size)
        assertEquals(queued.last(), bounded.last())
        assertEquals(queued[10], bounded.first())
    }

    @Test
    fun `a queue survives a round trip`() {
        val queued = events(3)
        assertEquals(queued, EventQueue.deserialize(EventQueue.serialize(queued)))
    }

    @Test
    fun `an empty or absent store reads as an empty queue`() {
        assertTrue(EventQueue.deserialize(null).isEmpty())
        assertTrue(EventQueue.deserialize("").isEmpty())
        assertTrue(EventQueue.deserialize("[]").isEmpty())
    }

    @Test
    fun `one corrupt entry costs one event, not the whole history`() {
        val stored = """[${events(1).single().toJson()},{"at":1},"garbage"]"""
        assertEquals(1, EventQueue.deserialize(stored).size)
    }

    @Test
    fun `an unparseable store reads as empty rather than throwing`() {
        assertTrue(EventQueue.deserialize("{not json").isEmpty())
    }

    @Test
    fun `a batch is smaller than the queue it drains, so a backlog stays in chunks`() {
        assertTrue(EventQueue.BATCH < EventQueue.CAPACITY)
    }
}
