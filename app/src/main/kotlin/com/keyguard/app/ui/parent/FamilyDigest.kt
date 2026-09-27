package com.keyguard.app.ui.parent

import com.keyguard.app.family.ChildActivity
import com.keyguard.app.family.ReportedEvent
import com.keyguard.detect.Severity
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The decisions behind the parent's Family and Activity screens, kept apart from the views.
 *
 * Two questions the screens ask over and over - "is this child fine?" and "which day did this
 * happen on?" - have edge cases a parent would notice immediately if they came out wrong: a
 * phone that has never reported shown as "All clear", a warning from 11pm filed under Today
 * the next morning, a child's moved clock putting an old event at the top of the feed. They
 * are pure functions of the overview and a clock, so they are checked on the JVM.
 *
 * Everything is measured in the server's receipt time ([ReportedEvent.receivedAt]), never the
 * child device's own clock, for the reason [com.keyguard.app.family.SupervisionEvent] gives:
 * the child's clock is untrusted and can be moved.
 */
object FamilyDigest {

    /** The window a status chip summarises. "This week" means the last seven days. */
    val WEEK_MS: Long = TimeUnit.DAYS.toMillis(7)

    /** What a child's status chip says. Exactly one of these per child. */
    sealed interface Status {
        /** Paired, but the phone has never checked in. Not "All clear": nothing is known. */
        data object NotReporting : Status

        /** Reporting, and no warnings in the last week. */
        data object AllClear : Status

        /** Warnings this week, none of them serious. */
        data class Warnings(val count: Int) : Status

        /** At least one serious warning this week. [count] is the serious ones only. */
        data class Serious(val count: Int) : Status
    }

    /**
     * Which chip a child gets.
     *
     * Serious outranks everything, including a phone that has gone quiet since: a serious
     * warning reported this week is still the most useful thing to put in front of a parent.
     * A phone that has never reported and has no events gets the neutral chip, because "All
     * clear" about a device that has told us nothing would be a claim the product cannot back.
     */
    fun status(child: ChildActivity, now: Long): Status {
        val thisWeek = child.events.filter { now - it.receivedAt < WEEK_MS }
        val serious = thisWeek.count { it.event.severity == Severity.HIGH }
        return when {
            serious > 0 -> Status.Serious(serious)
            thisWeek.isNotEmpty() -> Status.Warnings(thisWeek.size)
            child.lastSeen == 0L && child.events.isEmpty() -> Status.NotReporting
            else -> Status.AllClear
        }
    }

    /** The most recent warning, by receipt time, or null for a quiet child. */
    fun latest(child: ChildActivity): ReportedEvent? = child.events.maxByOrNull { it.receivedAt }

    /** The feed's three buckets. */
    enum class Day { TODAY, YESTERDAY, EARLIER }

    /** One line in the Activity feed: a warning, and whose phone it came from. */
    data class FeedItem(val child: ChildActivity, val reported: ReportedEvent)

    data class FeedGroup(val day: Day, val items: List<FeedItem>)

    /**
     * Calendar day, in the parent's time zone, rather than "within 24 hours".
     *
     * A parent reading "Yesterday" at 8am means the day before today on their own clock. A
     * rolling 24-hour window would file last night's 11pm warning under Today, which is the
     * kind of small wrongness that makes a parent stop trusting the rest of the screen.
     */
    fun dayOf(timestamp: Long, now: Long, zone: ZoneId): Day {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return when {
            // A timestamp from the future (server and phone clocks disagreeing) is today, not
            // a fourth bucket - the same rule Elapsed applies to "in the future".
            !date.isBefore(today) -> Day.TODAY
            date == today.minusDays(1) -> Day.YESTERDAY
            else -> Day.EARLIER
        }
    }

    /**
     * Every child's warnings, newest first, grouped by day. Empty groups are left out, so a
     * quiet day does not render a heading over nothing.
     */
    fun feed(children: List<ChildActivity>, now: Long, zone: ZoneId): List<FeedGroup> {
        val items = children
            .flatMap { child -> child.events.map { FeedItem(child, it) } }
            .sortedByDescending { it.reported.receivedAt }
        return items
            .groupBy { dayOf(it.reported.receivedAt, now, zone) }
            .toSortedMap()
            .map { (day, dayItems) -> FeedGroup(day, dayItems) }
    }

    /**
     * Children who were not in [baseline], in overview order.
     *
     * How the Add child screen notices that the code it is showing has just been used. By
     * install id rather than by count, so that another caregiver unpairing a different child
     * at the same moment cannot cancel out the arrival.
     */
    fun newlyPaired(baseline: Set<String>, children: List<ChildActivity>): List<ChildActivity> =
        children.filter { it.installId !in baseline }

    /**
     * A stable index into the avatar palette, so a child keeps one colour everywhere and
     * across launches. Keyed on the install id rather than the label, because two children
     * named "Phone" should still look different.
     */
    fun avatarIndex(installId: String, paletteSize: Int): Int =
        Math.floorMod(installId.hashCode(), paletteSize)

    /** The letter on a child's avatar: the first letter or digit of their label. */
    fun initial(label: String): String =
        label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
}
