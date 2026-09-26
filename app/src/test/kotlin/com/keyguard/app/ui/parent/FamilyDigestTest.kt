package com.keyguard.app.ui.parent

import com.keyguard.app.family.ChildActivity
import com.keyguard.app.family.ReportedEvent
import com.keyguard.app.family.SupervisionEvent
import com.keyguard.app.input.ComposeOutcome
import com.keyguard.app.ui.parent.FamilyDigest.Day
import com.keyguard.app.ui.parent.FamilyDigest.Status
import com.keyguard.detect.Category
import com.keyguard.detect.Severity
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FamilyDigestTest {

    private val zone = ZoneId.of("Europe/London")

    /** 8am on a Wednesday: early enough that "the last 24 hours" and "today" disagree. */
    private val now = LocalDateTime.of(2026, 9, 23, 8, 0).atZone(zone).toInstant().toEpochMilli()

    private fun hoursAgo(hours: Long) = now - TimeUnit.HOURS.toMillis(hours)

    private fun event(
        receivedAt: Long,
        severity: Severity = Severity.MEDIUM,
        deviceClock: Long = receivedAt,
    ) = ReportedEvent(
        SupervisionEvent(
            at = deviceClock,
            category = Category.PII_DISCLOSURE,
            severity = severity,
            outcome = ComposeOutcome.ABANDONED_DELETED,
            heeded = true,
        ),
        receivedAt = receivedAt,
    )

    private fun child(
        id: String = "a",
        lastSeen: Long = hoursAgo(1),
        events: List<ReportedEvent> = emptyList(),
    ) = ChildActivity(id, "Sam", pairedAt = 1, lastSeen = lastSeen, events = events)

    @Test
    fun `a phone that has never reported is not all clear`() {
        assertEquals(Status.NotReporting, FamilyDigest.status(child(lastSeen = 0), now))
    }

    @Test
    fun `a reporting phone with no recent warnings is all clear`() {
        val old = event(now - TimeUnit.DAYS.toMillis(8))
        assertEquals(Status.AllClear, FamilyDigest.status(child(events = listOf(old)), now))
    }

    @Test
    fun `serious warnings outrank the rest and count only the serious ones`() {
        val events = listOf(
            event(hoursAgo(2), Severity.HIGH),
            event(hoursAgo(3), Severity.MEDIUM),
            event(hoursAgo(30), Severity.HIGH),
        )
        assertEquals(Status.Serious(2), FamilyDigest.status(child(events = events), now))
    }

    @Test
    fun `moderate warnings this week are counted`() {
        val events = listOf(event(hoursAgo(2)), event(hoursAgo(50)))
        assertEquals(Status.Warnings(2), FamilyDigest.status(child(events = events), now))
    }

    @Test
    fun `the week is measured in server time, not the child's clock`() {
        // The device claims the event happened a month ago; the server received it an hour
        // ago. A moved clock must not be a way to hide a warning from this week's chip.
        val monthAgo = now - TimeUnit.DAYS.toMillis(30)
        val moved = event(hoursAgo(1), Severity.HIGH, deviceClock = monthAgo)
        assertEquals(Status.Serious(1), FamilyDigest.status(child(events = listOf(moved)), now))
    }

    @Test
    fun `days follow the parent's calendar, not a rolling 24 hours`() {
        // 11pm last night is nine hours ago - inside 24 hours, but yesterday.
        assertEquals(Day.YESTERDAY, FamilyDigest.dayOf(hoursAgo(9), now, zone))
        assertEquals(Day.TODAY, FamilyDigest.dayOf(hoursAgo(7), now, zone))
        assertEquals(Day.EARLIER, FamilyDigest.dayOf(hoursAgo(33), now, zone))
    }

    @Test
    fun `a timestamp from the future is today`() {
        assertEquals(Day.TODAY, FamilyDigest.dayOf(now + TimeUnit.DAYS.toMillis(2), now, zone))
    }

    @Test
    fun `the feed merges children newest first and drops empty days`() {
        val sam = child(id = "sam", events = listOf(event(hoursAgo(1)), event(hoursAgo(40))))
        val alex = child(id = "alex", events = listOf(event(hoursAgo(2))))

        val groups = FamilyDigest.feed(listOf(sam, alex), now, zone)

        assertEquals(listOf(Day.TODAY, Day.EARLIER), groups.map { it.day })
        assertEquals(listOf("sam", "alex"), groups[0].items.map { it.child.installId })
        assertEquals(listOf("sam"), groups[1].items.map { it.child.installId })
    }

    @Test
    fun `a new child is found by install id, not by count`() {
        // One child was unpaired and another joined in the same poll: the count is unchanged,
        // but the arrival is still an arrival.
        val children = listOf(child(id = "b"), child(id = "c"))
        val arrived = FamilyDigest.newlyPaired(setOf("a", "b"), children)
        assertEquals(listOf("c"), arrived.map { it.installId })
    }

    @Test
    fun `avatar colours are stable and in range`() {
        val index = FamilyDigest.avatarIndex("install-123", 5)
        assertEquals(index, FamilyDigest.avatarIndex("install-123", 5))
        assertTrue(index in 0 until 5)
        // hashCode can be negative; floorMod keeps the index in range regardless.
        assertTrue(FamilyDigest.avatarIndex("polygenelubricants", 5) in 0 until 5)
    }

    @Test
    fun `the avatar initial skips leading symbols`() {
        assertEquals("S", FamilyDigest.initial("sam's phone"))
        assertEquals("E", FamilyDigest.initial("  📱 Emu"))
        assertEquals("?", FamilyDigest.initial(""))
    }
}
