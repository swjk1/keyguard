package com.keyguard.app.family

import java.util.concurrent.TimeUnit

/**
 * How long ago something happened, coarsely.
 *
 * Coarse on purpose. A parent screen that reports "4:12:07pm" invites reading a timeline of
 * someone's afternoon, which is a different product from the one being built here — the
 * useful question is whether this is a pattern or a bad day, and minutes-to-days answers it.
 *
 * Pure, and it takes `now` as a parameter rather than reading the clock, so the boundaries are
 * testable and so a device clock that has been moved cannot produce a negative duration on
 * screen.
 */
sealed interface Elapsed {
    data object JustNow : Elapsed
    data class Minutes(val value: Int) : Elapsed
    data class Hours(val value: Int) : Elapsed
    data class Days(val value: Int) : Elapsed

    companion object {
        private val JUST_NOW_MS = TimeUnit.MINUTES.toMillis(1)

        fun between(thenMs: Long, nowMs: Long): Elapsed {
            // A child device's clock is not trusted, so "in the future" collapses to just now
            // rather than showing a negative age.
            val delta = (nowMs - thenMs).coerceAtLeast(0)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
            val hours = TimeUnit.MILLISECONDS.toHours(delta)
            val days = TimeUnit.MILLISECONDS.toDays(delta)

            return when {
                delta < JUST_NOW_MS -> JustNow
                hours < 1 -> Minutes(minutes.toInt())
                days < 1 -> Hours(hours.toInt())
                else -> Days(days.toInt())
            }
        }
    }
}
