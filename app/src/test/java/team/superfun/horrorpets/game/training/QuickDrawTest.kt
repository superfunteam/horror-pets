package team.superfun.horrorpets.game.training

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickDrawTest {
    @Test
    fun waitsAreUnpredictable() {
        val rng = Random(2026)
        val waits = List(10_000) { drawWaitMs(rng) }
        assertTrue("all in 700..2200: ${waits.min()}..${waits.max()}", waits.all { it in 700L..2200L })
        // Spread over the whole range, not bunched: both ends come up, and each fifth of it gets about its share.
        assertTrue("shortest ${waits.min()}", waits.min() < 720L)
        assertTrue("longest ${waits.max()}", waits.max() > 2180L)
        val fifths = waits.groupingBy { ((it - 700L) * 5 / 1501L).toInt() }.eachCount()
        assertEquals((0 until 5).toSet(), fifths.keys)
        for ((fifth, n) in fifths) assertTrue("fifth $fifth has $n of 10000", n in 1_700..2_300)
        assertTrue("${waits.toSet().size} different waits", waits.toSet().size > 1_000)
        // Two waits running are not the same wait.
        val pairs = waits.zipWithNext().count { (a, b) -> a == b }
        assertTrue("$pairs repeats", pairs < 50)
    }

    @Test
    fun windowShrinksToAFloor() {
        assertEquals(1_500L, drawWindowMs(0))
        assertEquals(1_380L, drawWindowMs(1))
        assertEquals(1_270L, drawWindowMs(2))
        assertEquals(429L, drawWindowMs(15)) // at par
        assertEquals(363L, drawWindowMs(17))
        assertEquals(350L, drawWindowMs(18))
        assertEquals(350L, drawWindowMs(100))
        var last = drawWindowMs(0)
        for (streak in 1..60) {
            val window = drawWindowMs(streak)
            assertTrue("streak $streak: $window over $last", window <= last)
            if (last > 350L) assertTrue("streak $streak didn't shrink", window < last)
            assertTrue("streak $streak: $window under the floor", window >= 350L)
            last = window
        }
    }

    @Test
    fun neverTheSameCallThreeTimes() {
        for (seed in 0 until 500) {
            val rng = Random(seed)
            val calls = mutableListOf<DrawCall>()
            repeat(200) { calls += nextCall(calls, rng) }
            calls.windowed(3).forEachIndexed { i, (a, b, c) ->
                assertTrue("seed $seed: $a three times from call $i", !(a == b && b == c))
            }
        }
        // After the same one twice, never that one; after it once, it may come again (twice running is fair).
        for (call in DrawCall.entries) {
            val twice = List(500) { nextCall(listOf(DrawCall.CENTER, call, call), Random(it)) }
            assertTrue("$call a third time", call !in twice)
            assertEquals(DrawCall.entries.toSet() - call, twice.toSet())
            val once = List(500) { nextCall(listOf(call), Random(it)) }
            assertTrue("$call never twice", call in once)
        }
        // Only the last two count: an older pair doesn't stop it.
        val older = List(500) { nextCall(listOf(DrawCall.UP, DrawCall.UP, DrawCall.LEFT), Random(it)) }
        assertTrue(DrawCall.UP in older)
    }

    @Test
    fun everyCallComesUp() {
        // From the very first call, and about evenly: no part is rare or favoured.
        assertEquals(DrawCall.entries.toSet(), List(500) { nextCall(emptyList(), Random(it)) }.toSet())
        val rng = Random(7)
        val calls = mutableListOf<DrawCall>()
        repeat(10_000) { calls += nextCall(calls, rng) }
        val counts = calls.groupingBy { it }.eachCount()
        assertEquals(DrawCall.entries.toSet(), counts.keys)
        for ((call, n) in counts) assertTrue("$call came up $n times of 10000", n in 1_700..2_300)
    }

    @Test
    fun judging() {
        val window = 1_000L
        // The called part in time.
        assertEquals(DrawOutcome.HIT, judgeDraw(DrawCall.LEFT, DrawCall.LEFT, sinceCallMs = 240L, windowMs = window))
        assertEquals(DrawOutcome.HIT, judgeDraw(DrawCall.CENTER, DrawCall.CENTER, sinceCallMs = 0L, windowMs = window))
        // Exactly at the window's end still counts.
        assertEquals(DrawOutcome.HIT, judgeDraw(DrawCall.UP, DrawCall.UP, sinceCallMs = window, windowMs = window))
        // Another part, however fast.
        assertEquals(DrawOutcome.WRONG, judgeDraw(DrawCall.LEFT, DrawCall.RIGHT, sinceCallMs = 240L, windowMs = window))
        assertEquals(DrawOutcome.WRONG, judgeDraw(DrawCall.CENTER, DrawCall.DOWN, sinceCallMs = 0L, windowMs = window))
        assertEquals(DrawOutcome.WRONG, judgeDraw(DrawCall.UP, DrawCall.CENTER, sinceCallMs = window, windowMs = window))
        // Before anything is called: any part at all.
        for (part in DrawCall.entries) {
            assertEquals(DrawOutcome.EARLY, judgeDraw(called = null, pressed = part, sinceCallMs = 0L, windowMs = window))
        }
        // No press by the window's end, or the right part a moment after it.
        assertEquals(DrawOutcome.LATE, judgeDraw(DrawCall.DOWN, pressed = null, sinceCallMs = window + 1, windowMs = window))
        assertEquals(DrawOutcome.LATE, judgeDraw(DrawCall.DOWN, DrawCall.DOWN, sinceCallMs = window + 1, windowMs = window))
        assertEquals(DrawOutcome.LATE, judgeDraw(DrawCall.DOWN, DrawCall.UP, sinceCallMs = window + 50, windowMs = window))
    }

    @Test
    fun keysMatchTheirParts() {
        assertEquals(TrainingKey.UP, DrawCall.UP.key())
        assertEquals(TrainingKey.DOWN, DrawCall.DOWN.key())
        assertEquals(TrainingKey.LEFT, DrawCall.LEFT.key())
        assertEquals(TrainingKey.RIGHT, DrawCall.RIGHT.key())
        assertEquals(TrainingKey.OK, DrawCall.CENTER.key())
        for (call in DrawCall.entries) assertEquals(call, drawCallOf(call.key()))
    }

    @Test
    fun tapsFindTheirPart() {
        val center = 40f
        val outer = 100f

        /** The part a tap [r] out from the centre at [degrees] (0 right, clockwise, y down) lands on. */
        fun at(degrees: Double, r: Float): DrawCall? {
            val a = Math.toRadians(degrees)
            return drawPartAt(r * cos(a).toFloat(), r * sin(a).toFloat(), center, outer)
        }
        // The centre button, by radius, whichever way.
        for (deg in 0 until 360 step 15) assertEquals("$deg°", DrawCall.CENTER, at(deg.toDouble(), 39f))
        assertEquals(DrawCall.CENTER, drawPartAt(0f, 0f, center, outer))
        // The wedges, each a quarter centred on its direction, from the button out to the ring's edge.
        for (r in listOf(41f, 70f, 99f)) {
            assertEquals(DrawCall.RIGHT, at(0.0, r))
            assertEquals(DrawCall.DOWN, at(90.0, r))
            assertEquals(DrawCall.LEFT, at(180.0, r))
            assertEquals(DrawCall.UP, at(270.0, r))
            assertEquals(DrawCall.RIGHT, at(44.0, r))
            assertEquals(DrawCall.DOWN, at(46.0, r))
            assertEquals(DrawCall.DOWN, at(134.0, r))
            assertEquals(DrawCall.LEFT, at(136.0, r))
            assertEquals(DrawCall.LEFT, at(224.0, r))
            assertEquals(DrawCall.UP, at(226.0, r))
            assertEquals(DrawCall.UP, at(314.0, r))
            assertEquals(DrawCall.RIGHT, at(316.0, r))
        }
        // Outside the ring: nothing.
        assertNull(at(0.0, 101f))
        assertNull(at(225.0, 180f))
        assertNotEquals(null, at(225.0, 99.9f))
    }
}
