package com.keyguard.app.family

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Events waiting to reach the parent.
 *
 * The keyboard writes here and stops. Nothing about a warning should hold up a keystroke, and
 * the upload deliberately happens elsewhere, in the app process — see [SupervisionSync] for
 * why that separation is load-bearing rather than tidy.
 *
 * Persisted, because the alternative is a queue that dies with the IME process and a parent
 * who sees a quiet afternoon that was not quiet. Bounded, because a device that cannot reach
 * the network for a week must not grow this file without limit.
 *
 * The bounding rule is *drop the oldest*: a parent looking at a backlog wants the recent end
 * of it. At [CAPACITY] events that only bites after several hundred flagged messages with no
 * connectivity at all, since one event is one composed message, not one keystroke.
 */
class EventQueue(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun append(event: SupervisionEvent) {
        write(bounded(read() + event))
    }

    fun size(): Int = read().size

    /** The next batch to upload. Leaves the queue untouched — see [confirm]. */
    fun peek(max: Int = BATCH): List<SupervisionEvent> = read().take(max)

    /**
     * Drops [count] events from the front, after the server has accepted them.
     *
     * Peek-then-confirm rather than take-then-restore because the failure that matters is a
     * batch lost between a successful upload and a crash. Events the server already has get
     * re-sent, and the ingest route dedupes; the other ordering loses them outright.
     */
    fun confirm(count: Int) {
        if (count <= 0) return
        write(read().drop(count))
    }

    fun clear() = prefs.edit().remove(KEY_EVENTS).apply()

    private fun read(): List<SupervisionEvent> =
        deserialize(prefs.getString(KEY_EVENTS, null))

    private fun write(events: List<SupervisionEvent>) =
        prefs.edit().putString(KEY_EVENTS, serialize(events)).apply()

    companion object {
        const val CAPACITY = 200

        /** One upload carries at most this many, so a backlog cannot become one huge request. */
        const val BATCH = 50

        private const val PREFS = "keyguard_supervision_events"
        private const val KEY_EVENTS = "queued"

        /** Keeps the newest [CAPACITY]. Pure, so the eviction rule is testable on its own. */
        fun bounded(events: List<SupervisionEvent>): List<SupervisionEvent> =
            if (events.size <= CAPACITY) events else events.takeLast(CAPACITY)

        fun serialize(events: List<SupervisionEvent>): String {
            val array = JSONArray()
            for (event in events) array.put(event.toJson())
            return array.toString()
        }

        /**
         * Rebuilds the queue, skipping anything unreadable.
         *
         * A single corrupt entry drops that entry, not the file. Losing one event costs a
         * parent one line; throwing away the queue on a parse error would let one bad write
         * silently erase a week of a child's history, which is the worse of the two.
         */
        fun deserialize(stored: String?): List<SupervisionEvent> {
            if (stored.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? JSONObject)?.let(SupervisionEvent::fromJson)
            }
        }
    }
}
