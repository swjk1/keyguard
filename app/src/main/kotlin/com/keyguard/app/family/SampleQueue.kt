package com.keyguard.app.family

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Activity samples waiting to reach the parent.
 *
 * The same shape as [EventQueue] — append from the keyboard, drain from the app process, drop
 * the oldest when full — and deliberately a separate store rather than a shared one.
 *
 * Two reasons it is not merged into [EventQueue]. First, the capacities have to differ by an
 * order of magnitude in the other direction: an event is one flagged message and a few hundred
 * of those is weeks of history, whereas a sample is *every* message and a chatty afternoon
 * produces more of them than a quiet month produces events. Second, and more importantly, a
 * sample can carry text and an event cannot. Keeping them in different files means a parent
 * unpairing, or a scope dropping back to [ReviewScope.CONCERNING_ONLY], can delete everything
 * that ever held a message without touching the warning history — see [clear], which
 * [SupervisionSync] calls on exactly those transitions.
 *
 * Bounded far more tightly than the event queue in bytes as well as count, because [CAPACITY]
 * samples each carrying up to [ActivitySample.MAX_TEXT] characters is the worst case this file
 * can reach on a device that has been offline for a week.
 */
class SampleQueue(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun append(sample: ActivitySample) {
        write(bounded(read() + sample))
    }

    fun size(): Int = read().size

    /** The next batch to upload. Leaves the queue untouched — see [confirm]. */
    fun peek(max: Int = BATCH): List<ActivitySample> = read().take(max)

    /** Drops [count] samples from the front, after the server has accepted them. */
    fun confirm(count: Int) {
        if (count <= 0) return
        write(read().drop(count))
    }

    /**
     * Throws the queue away.
     *
     * Called on unpairing and on any drop in review scope. A sample collected under a scope
     * that no longer applies has no reader who is entitled to it, and keeping it queued in the
     * hope of a future upload would mean a parent who turned full-text review *off* still
     * received a backlog of their child's messages afterwards.
     */
    fun clear() = prefs.edit().remove(KEY_SAMPLES).apply()

    private fun read(): List<ActivitySample> = deserialize(prefs.getString(KEY_SAMPLES, null))

    private fun write(samples: List<ActivitySample>) =
        prefs.edit().putString(KEY_SAMPLES, serialize(samples)).apply()

    companion object {
        /**
         * Smaller than [EventQueue.CAPACITY] despite covering more messages, because these are
         * the records that can hold text. Two hundred samples at the text cap is roughly 100KB
         * of SharedPreferences, which is already more than that API should be asked to hold.
         */
        const val CAPACITY = 200

        /** One upload carries at most this many, so a backlog cannot become one huge request. */
        const val BATCH = 40

        private const val PREFS = "keyguard_supervision_samples"
        private const val KEY_SAMPLES = "queued"

        /** Keeps the newest [CAPACITY]. Pure, so the eviction rule is testable on its own. */
        fun bounded(samples: List<ActivitySample>): List<ActivitySample> =
            if (samples.size <= CAPACITY) samples else samples.takeLast(CAPACITY)

        fun serialize(samples: List<ActivitySample>): String {
            val array = JSONArray()
            for (sample in samples) array.put(sample.toJson())
            return array.toString()
        }

        /** Rebuilds the queue, skipping anything unreadable. One bad entry costs one line. */
        fun deserialize(stored: String?): List<ActivitySample> {
            if (stored.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? JSONObject)?.let(ActivitySample::fromJson)
            }
        }
    }
}
