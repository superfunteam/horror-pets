package team.superfun.horrorpets.ui.home

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When Home's killer says something of its own: every 25–45 s, and only while nothing else is going on. */
class IdleLineTest {
    @Test
    fun theNextLineIsDrawnTwentyFiveToFortyFiveSecondsOn() {
        assertEquals(25_000L, IDLE_LINE_MIN_MS)
        assertEquals(45_000L, IDLE_LINE_MAX_MS)
        val rng = Random(2026)
        val now = 7_000L
        val waits = List(2_000) { nextIdleLineAt(now, rng) - now }
        assertTrue(waits.all { it in IDLE_LINE_MIN_MS..IDLE_LINE_MAX_MS })
        // Random each time, across the whole span: not one fixed wait.
        assertTrue("${waits.min()}", waits.min() < 26_000L)
        assertTrue("${waits.max()}", waits.max() > 44_000L)
        // The same seed draws the same time, so a caller's draws are its rng's.
        assertEquals(nextIdleLineAt(now, Random(5)), nextIdleLineAt(now, Random(5)))
    }

    @Test
    fun aLineIsDueOnlyWhenQuietAndItsTimeHasCome() {
        val at = 40_000L
        assertFalse("not before its time", idleLineDue(nowMs = at - 1, nextAtMs = at, quiet = true))
        assertTrue("the moment it comes", idleLineDue(nowMs = at, nextAtMs = at, quiet = true))
        assertTrue("or any time after", idleLineDue(nowMs = at + 5_000, nextAtMs = at, quiet = true))
        // A gag, a walk, a bubble or sleep: it says nothing, its time come or not.
        assertFalse(idleLineDue(nowMs = at, nextAtMs = at, quiet = false))
        assertFalse(idleLineDue(nowMs = at + 5_000, nextAtMs = at, quiet = false))
        assertFalse(idleLineDue(nowMs = at - 1, nextAtMs = at, quiet = false))
    }
}
