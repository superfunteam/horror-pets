package team.superfun.horrorpets.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpriteAnimatorTest {
    private fun spec(durations: List<Int>, loop: Boolean, frames: Int = durations.size) =
        AnimSpec(file = "x.png", frames = frames, durationsMs = durations, loop = loop)

    private val threeByHundred = listOf(100, 100, 100)

    @Test
    fun loopWraps() {
        val s = spec(threeByHundred, loop = true)
        assertEquals(0, frameAt(s, 350))
        assertEquals(0, frameAt(s, 0))
        assertEquals(1, frameAt(s, 100))
        assertEquals(2, frameAt(s, 299))
        assertEquals(0, frameAt(s, 300))
        assertEquals(2, frameAt(s, 1_199))
    }

    @Test
    fun oneShotEndsNull() {
        val s = spec(threeByHundred, loop = false)
        assertNull(frameAt(s, 300))
        assertEquals(2, frameAt(s, 299))
        assertNull(frameAt(s, 10_000))
    }

    @Test
    fun variableDurationsAreHonoured() {
        // The real attack timeline: the wind-up and the recovery are long, the strike is short.
        val s = spec(listOf(300, 140, 180, 75, 140, 360), loop = false)
        assertEquals(0, frameAt(s, 299))
        assertEquals(1, frameAt(s, 300))
        assertEquals(1, frameAt(s, 439))
        assertEquals(2, frameAt(s, 440))
        assertEquals(3, frameAt(s, 620))
        assertEquals(3, frameAt(s, 694))
        assertEquals(4, frameAt(s, 695))
        assertEquals(5, frameAt(s, 835))
        assertEquals(5, frameAt(s, 1_194))
        assertNull(frameAt(s, 1_195))
    }

    @Test
    fun negativeElapsedIsFrameZero() {
        assertEquals(0, frameAt(spec(threeByHundred, loop = true), -1))
        assertEquals(0, frameAt(spec(threeByHundred, loop = false), -1))
        assertEquals(0, frameAt(spec(emptyList(), loop = false), -5))
        assertEquals(0, frameAt(spec(emptyList(), loop = true), Long.MIN_VALUE))
    }

    @Test
    fun emptyDurationsDoNotDivideByZero() {
        assertEquals(0, frameAt(spec(emptyList(), loop = true, frames = 1), 1_000))
        // A one-shot with no timeline has nothing to play: it is already finished.
        assertNull(frameAt(spec(emptyList(), loop = false, frames = 1), 1_000))
        assertNull(frameAt(spec(emptyList(), loop = false, frames = 1), 0))
    }

    @Test
    fun zeroLengthTimelineDoesNotDivideByZero() {
        assertEquals(0, frameAt(spec(listOf(0, 0, 0), loop = true), 500))
        assertNull(frameAt(spec(listOf(0, 0, 0), loop = false), 500))
    }

    @Test
    fun zeroLengthFramesAreSkipped() {
        val s = spec(listOf(100, 0, 100), loop = true)
        assertEquals(0, frameAt(s, 99))
        assertEquals(2, frameAt(s, 100))
        assertEquals(0, frameAt(s, 200))
    }

    @Test
    fun hugeElapsedDoesNotOverflow() {
        val s = spec(threeByHundred, loop = true)
        // Long.MAX_VALUE % 300 == 7, so this is inside frame 0.
        assertEquals(0, frameAt(s, Long.MAX_VALUE))
        assertNull(frameAt(spec(threeByHundred, loop = false), Long.MAX_VALUE))
    }

    @Test
    fun indexNeverExceedsTheStripFrameCount() {
        // Corrupt data: more durations than frames on the strip. Never point past the last cell.
        val s = spec(listOf(100, 100, 100), loop = true, frames = 2)
        assertEquals(1, frameAt(s, 250))
    }
}
