package team.superfun.horrorpets.ui.training

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClockTest {
    private val start = 5_000_000_000L // frame times are the frame clock's nanoseconds, from wherever it began

    private fun ms(n: Long): Long = n * 1_000_000L

    @Test
    fun timeAdvancesWithFrames() {
        val clock = GameClock()
        clock.tick(start, paused = false) // the first frame only starts the count
        assertEquals(0L, clock.nowMs)
        for (k in 1..10) clock.tick(start + ms(16L * k), paused = false)
        assertEquals(160L, clock.nowMs)
    }

    @Test
    fun partFramesAddUp() {
        val clock = GameClock()
        // Sixty frames of a 60 Hz display (16.67 ms each) are one second, not sixty times 16 ms.
        for (k in 0..60) clock.tick(start + 1_000_000_000L * k / 60, paused = false)
        assertEquals(1_000L, clock.nowMs)
    }

    @Test
    fun noTimePassesWhilePaused() {
        val clock = GameClock()
        clock.tick(start, paused = false)
        clock.tick(start + ms(16), paused = false)
        for (k in 2..300) clock.tick(start + ms(16L * k), paused = true)
        assertEquals(16L, clock.nowMs)
    }

    @Test
    fun theFirstFrameAfterAPauseAddsNothing() {
        val clock = GameClock()
        clock.tick(start, paused = false)
        clock.tick(start + ms(16), paused = false)
        clock.tick(start + ms(32), paused = true)
        // Back from the pause five seconds later: that gap is not game time, however long it was.
        clock.tick(start + ms(5_032), paused = false)
        assertEquals(16L, clock.nowMs)
        clock.tick(start + ms(5_048), paused = false)
        assertEquals(32L, clock.nowMs)
    }

    @Test
    fun itSaysWhenItIsPaused() {
        val clock = GameClock()
        assertFalse(clock.paused)
        clock.tick(start, paused = false)
        assertFalse(clock.paused)
        // Paused from the frame that says so until the frame that says it isn't, however long between.
        clock.tick(start + ms(16), paused = true)
        assertTrue(clock.paused)
        clock.tick(start + ms(32), paused = true)
        assertTrue(clock.paused)
        clock.tick(start + ms(3_000), paused = false)
        assertFalse(clock.paused)
        clock.tick(start + ms(3_016), paused = false)
        assertFalse(clock.paused)
        assertEquals(16L, clock.nowMs)
    }

    @Test
    fun aHugeFrameGapIsCapped() {
        val clock = GameClock()
        clock.tick(start, paused = false)
        clock.tick(start + ms(2_000), paused = false) // a two-second hitch
        assertEquals(100L, clock.nowMs)
        clock.tick(start + ms(2_016), paused = false)
        assertEquals(116L, clock.nowMs)
    }

    @Test
    fun aFrameTimeGoingBackwardsAddsNothing() {
        val clock = GameClock()
        clock.tick(start, paused = false)
        clock.tick(start + ms(16), paused = false)
        clock.tick(start + ms(8), paused = false)
        assertEquals(16L, clock.nowMs)
    }

    @Test
    fun waitingResumesOnlyAfterThatMuchGameTime() = runTest {
        val clock = GameClock()
        var done = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            clock.waitMs(50)
            done = true
        }
        clock.tick(start, paused = false)
        clock.tick(start + ms(40), paused = false)
        yield()
        assertFalse(done)
        // Paused for a long while: still not done.
        clock.tick(start + ms(56), paused = true)
        clock.tick(start + ms(3_000), paused = true)
        yield()
        assertFalse(done)
        clock.tick(start + ms(3_016), paused = false)
        clock.tick(start + ms(3_026), paused = false)
        yield()
        assertTrue(done)
    }

    @Test
    fun waitingNoTimeReturnsAtOnce() = runTest {
        val clock = GameClock()
        clock.waitMs(0) // no frame needed
        clock.waitMs(-5)
    }
}
