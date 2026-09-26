package com.keyguard.app.family

import com.keyguard.app.input.ComposeOutcome
import com.keyguard.detect.Category
import com.keyguard.detect.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a parent's phone is allowed to interrupt them.
 *
 * This is the only thing in the product that pushes itself in front of someone, so the cases
 * that matter are the ones where it gets that wrong: a backlog dumped on a parent who just
 * signed in, the same warning announced twice, a warning announced never. None of them is
 * reproducible on a device inside a quarter of an hour, which is what the fifteen-minute
 * periodic floor means for testing this by hand.
 */
class ParentAlertsTest {

    private fun event(
        receivedAt: Long,
        severity: Severity = Severity.HIGH,
        category: Category = Category.PII_DISCLOSURE,
    ) = ReportedEvent(
        event = SupervisionEvent(
            // Deliberately unlike `receivedAt`. Every decision here must come from the
            // server's stamp; a test where the two agree would pass with either.
            at = 1L,
            category = category,
            severity = severity,
            outcome = ComposeOutcome.SENT_INFERRED,
            heeded = false,
        ),
        receivedAt = receivedAt,
    )

    private fun child(label: String, vararg events: ReportedEvent) = ChildActivity(
        installId = "install-$label",
        label = label,
        pairedAt = 0,
        lastSeen = 0,
        // Newest first, as the server stores them and as `parseChild` preserves them.
        events = events.sortedByDescending { it.receivedAt },
    )

    @Test
    fun `the first poll establishes the mark and says nothing`() {
        // A parent signing in on a new phone must not be handed a notification about a
        // backlog. Everything before they set the device up is what the dashboard is for.
        val digest = ParentAlerts.digest(
            listOf(child("Ellie", event(300), event(200), event(100))),
            since = 0,
        )
        assertFalse(digest.shouldNotify)
        assertEquals(300, digest.watermark)
    }

    @Test
    fun `a new family's first warning alerts even though the first poll saw nothing`() {
        // The order every real family goes through: pair, poll an empty history, then the
        // child's first warning. Storing 0 after the empty poll made the next one "first" too.
        val quiet = ParentAlerts.digest(listOf(child("Ellie")), since = 0)
        assertFalse(quiet.shouldNotify)

        val next = ParentAlerts.digest(listOf(child("Ellie", event(300))), quiet.watermark)
        assertTrue(next.shouldNotify)
        assertEquals(300, next.watermark)
    }

    @Test
    fun `only events past the mark alert, and the mark then covers them`() {
        val children = listOf(child("Ellie", event(300), event(200), event(100)))

        val first = ParentAlerts.digest(children, since = 150)
        assertTrue(first.shouldNotify)
        assertEquals(2, first.total)
        assertEquals(300, first.watermark)

        // The same fetch again, which is what the next poll sees when nothing new arrived.
        val second = ParentAlerts.digest(children, since = first.watermark)
        assertFalse(second.shouldNotify)
        assertEquals(300, second.watermark)
    }

    @Test
    fun `medium warnings never interrupt, but the mark still moves past them`() {
        // If the mark did not advance over a non-alerting event it would be re-examined on
        // every poll forever - harmless today, and an alert the day the floor is lowered.
        val digest = ParentAlerts.digest(
            listOf(child("Ellie", event(400, Severity.MEDIUM), event(350, Severity.LOW))),
            since = 100,
        )
        assertFalse(digest.shouldNotify)
        assertEquals(400, digest.watermark)
    }

    @Test
    fun `the mark never moves backwards when a child is removed`() {
        // Unpairing deletes that child's history. Without the floor the mark would drop to
        // whatever the remaining children have and replay every event above it.
        val digest = ParentAlerts.digest(listOf(child("Sam", event(120))), since = 900)
        assertEquals(900, digest.watermark)
        assertFalse(digest.shouldNotify)
    }

    @Test
    fun `an empty family leaves the mark exactly where it was`() {
        val digest = ParentAlerts.digest(emptyList(), since = 500)
        assertEquals(500, digest.watermark)
        assertFalse(digest.shouldNotify)
    }

    @Test
    fun `the category named is the newest one, not whichever came first in the list`() {
        val digest = ParentAlerts.digest(
            listOf(
                ChildActivity(
                    installId = "install",
                    label = "Ellie",
                    pairedAt = 0,
                    lastSeen = 0,
                    // Oldest first on purpose: a host that returned them this way would make
                    // the notice name a category the parent is not being alerted about.
                    events = listOf(
                        event(200, category = Category.SUBSTANCE),
                        event(300, category = Category.IN_PERSON_MEETUP),
                    ),
                ),
            ),
            since = 100,
        )
        assertEquals(Category.IN_PERSON_MEETUP, digest.alerting.single().category)
    }

    @Test
    fun `two children each get counted, and the total is across both`() {
        val digest = ParentAlerts.digest(
            listOf(
                child("Ellie", event(300), event(250)),
                child("Sam", event(400)),
                child("Quiet", event(120)),
            ),
            since = 200,
        )
        assertEquals(2, digest.alerting.size)
        assertEquals(3, digest.total)
        assertEquals(400, digest.watermark)
    }

    @Test
    fun `a child device with a clock set far forward cannot mute the family`() {
        // `SupervisionEvent.at` is the child's wall clock and is documented as untrusted. If
        // any decision here read it, one event stamped a year ahead would push the mark past
        // every genuine warning that followed.
        val skewed = ReportedEvent(
            event = SupervisionEvent(
                at = Long.MAX_VALUE / 2,
                category = Category.VIOLENCE_THREAT,
                severity = Severity.HIGH,
                outcome = ComposeOutcome.SENT_INFERRED,
                heeded = false,
            ),
            receivedAt = 250,
        )
        val digest = ParentAlerts.digest(listOf(child("Ellie", skewed)), since = 200)
        assertTrue(digest.shouldNotify)
        assertEquals(250, digest.watermark)
    }
}
