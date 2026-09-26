package com.keyguard.app.family

import com.keyguard.detect.Category
import com.keyguard.detect.Severity

/**
 * Which reported warnings are worth waking a parent for, and what the notice should say.
 *
 * Until this existed the parent side was pull-only: a child's device queued an event, a
 * fifteen-minute job uploaded it, and then it sat on the server until the parent happened to
 * open the app and its dashboard happened to fetch. That is a defensible way to show a weekly
 * report and a poor way to deliver the one thing the product exists for. "We will tell you if
 * something serious happens" was not true; "you can come and look" was.
 *
 * ### Why a watermark rather than a diff of what is on screen
 *
 * The parent device is the wrong place to compute "new since last time" from the dashboard's
 * state, because the dashboard is not always there — the job runs whether or not anything is
 * open. So the only durable thing is a high-water mark, and the only field it can safely be
 * measured in is the *server's* receipt time. [SupervisionEvent.at] is the child device's wall
 * clock, which that class documents as untrusted and movable; a child who set their clock
 * forward once would push the watermark past every genuine event that followed. `receivedAt`
 * is stamped by a machine the child does not control, and it is already what the parent view
 * orders by.
 *
 * ### The first run deliberately notifies about nothing
 *
 * A parent signing in on a new phone would otherwise be handed a notification summarising
 * however much history the family already had. [digest] treats a zero watermark as "establish
 * the mark, say nothing", so the first alert a parent ever receives describes something that
 * happened after they set the device up. Everything before that is what the dashboard is for.
 *
 * ### Only high severity interrupts
 *
 * A notification per medium warning would be several a day for an ordinary teenager, and a
 * parent who swipes the notification away without reading it has been trained to ignore the
 * one that mattered. So the floor is [Severity.HIGH] and the rest lives in the dashboard.
 * [Digest.alerting] holds only those; [Digest.watermark] advances past everything seen, so a
 * lower-severity event is never re-examined and can never become an alert later.
 *
 * Pure, so the interesting cases — first run, a clock-skewed event, several children at once —
 * are checked on the JVM rather than by pairing two phones and waiting a quarter of an hour.
 */
object ParentAlerts {

    /** The severity floor that earns an interruption. See the class note. */
    val ALERT_FLOOR: Severity = Severity.HIGH

    /**
     * One child's contribution to an alert, named the way a parent named it.
     *
     * Carries the label rather than the install id because the notification is read by a
     * person. It carries no text, no host app and no message content — the same rule
     * [SupervisionEvent] states, restated here because this is the other place in the product
     * where a record is deliberately put in front of someone who did not write it.
     */
    data class Alerting(
        val childLabel: String,
        val count: Int,
        /** The category of the newest alerting event for this child. */
        val category: Category,
    )

    /**
     * What the caller should do about a fetch.
     *
     * [watermark] is always the value to store, alert or no alert — recording it is how a
     * quiet poll stops the next one from re-reading the same events.
     */
    data class Digest(
        val watermark: Long,
        val alerting: List<Alerting>,
    ) {
        val total: Int get() = alerting.sumOf { it.count }
        val shouldNotify: Boolean get() = alerting.isNotEmpty()
    }

    /**
     * @param children the overview as fetched, each child's events newest first.
     * @param since the stored watermark, or 0 on a device that has never polled.
     */
    fun digest(children: List<ChildActivity>, since: Long): Digest {
        val highest = children
            .flatMap { child -> child.events.map { it.receivedAt } }
            .maxOrNull()
            ?: since

        // Never moves backwards. A child removed from the family takes their events with them,
        // which would otherwise drop the mark below events the parent has already been told
        // about and replay them all on the next poll.
        val watermark = maxOf(since, highest)

        // First ever poll. Establish the mark in silence; see the class note.
        if (since <= 0L) return Digest(watermark, emptyList())

        val alerting = children.mapNotNull { child ->
            val fresh = child.events.filter {
                it.receivedAt > since && it.event.severity.level >= ALERT_FLOOR.level
            }
            if (fresh.isEmpty()) return@mapNotNull null
            Alerting(
                childLabel = child.label,
                count = fresh.size,
                // Newest first is how the server stores them and how `parseChild` preserves
                // them, so the head is the most recent. Taken by receipt time anyway rather
                // than trusting the order, since one wrong assumption here names the wrong
                // category on the one notice a parent actually reads.
                category = fresh.maxBy { it.receivedAt }.event.category,
            )
        }

        return Digest(watermark, alerting)
    }
}
