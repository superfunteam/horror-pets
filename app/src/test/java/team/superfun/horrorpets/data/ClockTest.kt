package team.superfun.horrorpets.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTest {
    private class FakeClock(var now: Long) : Clock {
        override fun nowMs() = now
    }

    @Test
    fun zeroOffsetPassesThrough() {
        val base = FakeClock(1_000L)
        assertEquals(1_000L, OffsetClock(base).nowMs())
    }

    @Test
    fun positiveOffsetMovesIntoTheFuture() {
        val base = FakeClock(1_000L)
        assertEquals(1_500L, OffsetClock(base, 500L).nowMs())
    }

    @Test
    fun negativeOffsetMovesIntoThePast() {
        val base = FakeClock(1_000L)
        assertEquals(400L, OffsetClock(base, -600L).nowMs())
    }

    @Test
    fun offsetCanBeChangedAndBaseKeepsTicking() {
        val base = FakeClock(10_000L)
        val clock = OffsetClock(base)
        clock.offsetMs = 3_600_000L
        assertEquals(3_610_000L, clock.nowMs())
        base.now = 10_250L
        assertEquals(3_610_250L, clock.nowMs())
        clock.offsetMs = 0L
        assertEquals(10_250L, clock.nowMs())
    }

    @Test
    fun wallClockTracksSystemTime() {
        val before = System.currentTimeMillis()
        val t = WallClock.nowMs()
        val after = System.currentTimeMillis()
        assertEquals(true, t in before..after)
    }
}
