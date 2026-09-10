package com.keyguard.detect

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The engine runs on a keystroke cadence on low-end Android hardware. If a scan is not
 * comfortably sub-millisecond on a desktop JVM there is no headroom left for a phone, and
 * typing latency is the one thing users will never forgive in a keyboard.
 *
 * The budget here is deliberately loose (5ms for a 500-char buffer) because CI machines are
 * noisy; the real signal is that a regression to per-term regex scanning would blow past it
 * by orders of magnitude.
 */
class EnginePerformanceTest {

    private val engine = DetectionEngine.withBundledPack()

    private val realisticBuffer = buildString {
        append("hey so i was thinking about what you said earlier and honestly ")
        append("i dont really know how to feel about it. my name is Priya and i ")
        append("live near the park on the north side, we could meet up after ")
        append("school tomorrow if you want. anyway let me know what you think ")
        append("because i have to go soon and my brother needs the laptop back.")
    }.let { it + " ".repeat(maxOf(0, 500 - it.length)) }.take(500)

    @Test
    fun `scan of a 500 char buffer stays within budget`() {
        repeat(WARMUP) { engine.scan(realisticBuffer) }

        val timings = LongArray(SAMPLES) {
            val start = System.nanoTime()
            engine.scan(realisticBuffer)
            System.nanoTime() - start
        }
        timings.sort()

        val medianMicros = timings[SAMPLES / 2] / 1_000.0
        val p95Micros = timings[(SAMPLES * 95) / 100] / 1_000.0
        println("scan(500 chars): median %.1fus p95 %.1fus".format(medianMicros, p95Micros))

        assertTrue(
            medianMicros < BUDGET_MICROS,
            "median %.1fus exceeded budget %.0fus".format(medianMicros, BUDGET_MICROS),
        )
    }

    @Test
    fun `engine construction is the expensive part and is done once`() {
        // Documents why callers must reuse a DetectionEngine rather than build per keystroke.
        val buildStart = System.nanoTime()
        val fresh = DetectionEngine.withBundledPack()
        val buildMicros = (System.nanoTime() - buildStart) / 1_000.0

        repeat(WARMUP) { fresh.scan(realisticBuffer) }
        val scanStart = System.nanoTime()
        fresh.scan(realisticBuffer)
        val scanMicros = (System.nanoTime() - scanStart) / 1_000.0

        println("build %.1fus vs scan %.1fus".format(buildMicros, scanMicros))
        assertTrue(scanMicros < BUDGET_MICROS, "scan after warmup should be fast, was %.1fus".format(scanMicros))
    }

    private companion object {
        const val WARMUP = 500
        const val SAMPLES = 200
        const val BUDGET_MICROS = 5_000.0
    }
}
