package com.keyguard.app.family

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class ElapsedTest {

    private val now = 1_700_000_000_000

    private fun ago(amount: Long, unit: TimeUnit) = Elapsed.between(now - unit.toMillis(amount), now)

    @Test
    fun `under a minute is just now`() {
        assertEquals(Elapsed.JustNow, Elapsed.between(now, now))
        assertEquals(Elapsed.JustNow, ago(59, TimeUnit.SECONDS))
    }

    @Test
    fun `minutes up to an hour`() {
        assertEquals(Elapsed.Minutes(1), ago(60, TimeUnit.SECONDS))
        assertEquals(Elapsed.Minutes(59), ago(59, TimeUnit.MINUTES))
    }

    @Test
    fun `hours up to a day`() {
        assertEquals(Elapsed.Hours(1), ago(60, TimeUnit.MINUTES))
        assertEquals(Elapsed.Hours(23), ago(23, TimeUnit.HOURS))
    }

    @Test
    fun `a day and beyond`() {
        assertEquals(Elapsed.Days(1), ago(24, TimeUnit.HOURS))
        assertEquals(Elapsed.Days(30), ago(30, TimeUnit.DAYS))
    }

    @Test
    fun `a timestamp from the future reads as just now, not as a negative age`() {
        // A child device's clock is not trusted, and "-3 h ago" on a parent's screen reads as
        // a bug in the product rather than as a clock that has been moved.
        assertEquals(Elapsed.JustNow, Elapsed.between(now + TimeUnit.HOURS.toMillis(3), now))
    }
}
