package team.superfun.horrorpets.game.training

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganDropTest {
    /** The first [n] drops of a game dealt by [rng], as the game deals them. */
    private fun schedule(rng: Random, n: Int): List<Drop> {
        val drops = ArrayList<Drop>(n)
        for (i in 0 until n) drops += nextDrop(drops.lastOrNull(), i, rng)
        return drops
    }

    /** How many of [drops] are in the air at [t]: shown at the top already, not yet at the catch line. */
    private fun inAir(drops: List<Drop>, t: Long): Int = drops.count { it.landMs - it.fallMs <= t && t < it.landMs }

    @Test
    fun dropsSpeedUpAndCrowdIn() {
        // Falling faster, down to 0.9 s from the top to the catch line (from the 19th drop).
        assertEquals(1_200L, fallMs(0))
        assertEquals(1_182L, fallMs(1))
        assertEquals(1_032L, fallMs(10))
        assertEquals(914L, fallMs(18))
        assertEquals(900L, fallMs(19))
        assertEquals(900L, fallMs(200))
        // Dropping more often, down to one every 0.42 s (from the 25th).
        assertEquals(1_500L, landGapMs(0))
        assertEquals(1_425L, landGapMs(1))
        assertEquals(898L, landGapMs(10))
        assertEquals(438L, landGapMs(24))
        assertEquals(420L, landGapMs(25))
        assertEquals(420L, landGapMs(200))
        for (i in 1..200) {
            assertTrue("fall $i", fallMs(i) <= fallMs(i - 1) && fallMs(i) >= 900L)
            assertTrue("gap $i", landGapMs(i) <= landGapMs(i - 1) && landGapMs(i) >= 420L)
        }
        // At first each falls for no longer than the gap before it lands, so it shows once the last has come down...
        for (i in 1..6) assertTrue("drop $i", fallMs(i) <= landGapMs(i))
        assertTrue(fallMs(7) > landGapMs(7))
        // ...and the falls shrink more slowly than the gaps, so the organs crowd in more and more (rounding aside).
        for (i in 2..200) {
            val ratio = fallMs(i) / landGapMs(i).toFloat()
            assertTrue("drop $i", ratio >= fallMs(i - 1) / landGapMs(i - 1).toFloat() - 0.01f)
        }

        val drops = schedule(Random(7), 60)
        assertEquals(FIRST_LAND_MS, drops[0].landMs)
        assertEquals(fallMs(0), drops[0].fallMs)
        for (i in 1 until drops.size) {
            assertEquals("drop $i lands a gap after the last", drops[i - 1].landMs + landGapMs(i), drops[i].landMs)
            assertEquals(fallMs(i), drops[i].fallMs)
            // Each shows at the top after the one before it: they come in order.
            assertTrue("drop $i", drops[i].landMs - drops[i].fallMs > drops[i - 1].landMs - drops[i - 1].fallMs)
        }

        /** The fewest and the most in the air at once from [fromMs] until [untilMs]. */
        fun crowd(fromMs: Long, untilMs: Long): IntRange {
            val counts = (fromMs until untilMs).map { inAir(drops, it) }
            return counts.min()..counts.max()
        }
        // The first six come one at a time, a beat with none in the air between some of them...
        assertEquals(0..1, crowd(0L, drops[5].landMs))
        // ...then they overlap: up to two at once by drops 10 to 15...
        assertEquals(1..2, crowd(drops[9].landMs, drops[15].landMs))
        // ...and after 25 drops never fewer than two, at times three.
        assertEquals(2..3, crowd(drops[24].landMs, drops[50].landMs))
    }

    @Test
    fun theFirstOrganShowsAtTheTopABeatIn() {
        // It falls for less than the time to its landing: not already part way down as the game begins, but showing at
        // the top 0.4 s in.
        assertEquals(400L, FIRST_LAND_MS - fallMs(0))
        for (seed in 0 until 200) {
            val first = nextDrop(null, 0, Random(seed))
            assertEquals(FIRST_LAND_MS, first.landMs)
            assertEquals(400L, first.landMs - first.fallMs)
        }
    }

    @Test
    fun everyDropIsReachableFromTheLast() {
        val eps = 1e-4f
        for (seed in 0 until 2_000) {
            val drops = schedule(Random(seed), 120)
            // The first comes down 1.6 s in, within a run from the middle, where the killer starts, from when it shows.
            val first = drops[0]
            assertEquals(FIRST_LAND_MS, first.landMs)
            val firstReach = RUN_SPEED * first.fallMs / 1_000f - 0.05f
            assertTrue("seed $seed: first at ${first.x}", abs(first.x - START_X) <= firstReach + eps)
            for (i in drops.indices) {
                val drop = drops[i]
                assertTrue("seed $seed drop $i at ${drop.x}", drop.x in DROP_RANGE)
                if (i == 0) continue
                val prev = drops[i - 1]
                // The time to run for it: from the last landing, or from when it shows if that is later.
                val runS = minOf(drop.landMs - prev.landMs, drop.fallMs) / 1_000f
                val reach = RUN_SPEED * runS - 0.05f
                assertTrue("seed $seed drop $i: ${prev.x} to ${drop.x} in $runS s", abs(drop.x - prev.x) <= reach + eps)
            }
        }
    }

    @Test
    fun organsVary() {
        val seen = IntArray(ORGAN_KINDS)
        for (seed in 0 until 200) {
            val drops = schedule(Random(seed), 60)
            for (i in drops.indices) {
                val organ = drops[i].organ
                assertTrue("seed $seed drop $i: organ $organ", organ in 0 until ORGAN_KINDS)
                seen[organ]++
                if (i > 0) assertNotEquals("seed $seed drop $i: the same organ twice running", drops[i - 1].organ, organ)
            }
        }
        // Every organ falls, about as often as the others.
        val total = seen.sum()
        for (organ in 0 until ORGAN_KINDS) {
            val share = seen[organ] / total.toFloat()
            assertTrue("organ $organ: $share", share > 0.12f && share < 0.21f)
        }
        // And they land all over the range, not bunched in the middle.
        val xs = (0 until 200).flatMap { seed -> schedule(Random(seed), 60).map { it.x } }
        assertTrue(xs.count { it < 0.3f } > xs.size / 10)
        assertTrue(xs.count { it > 0.7f } > xs.size / 10)
    }

    @Test
    fun catchWindow() {
        assertTrue(caught(0.5f, 0.5f))
        assertTrue(caught(0.5f, 0.44f))
        assertTrue(caught(0.5f, 0.56f))
        assertTrue("right at the edge", caught(0f, CATCH_HALF))
        assertTrue("either side", caught(CATCH_HALF, 0f))
        assertFalse(caught(0.5f, 0.42f))
        assertFalse(caught(0.5f, 0.58f))
        assertFalse(caught(0.1f, 0.9f))
    }

    @Test
    fun keyHoldFollowsTheLatestHeldKeyAndIgnoresRepeats() {
        val hold = KeyHold()
        assertEquals(0, hold.direction)
        hold.key(TrainingKey.RIGHT, down = true)
        assertEquals(1, hold.direction)
        // A held key repeats as more downs: still running right.
        repeat(5) { hold.key(TrainingKey.RIGHT, down = true) }
        assertEquals(1, hold.direction)
        // Left pressed while Right is held: the latest wins.
        hold.key(TrainingKey.LEFT, down = true)
        assertEquals(-1, hold.direction)
        // Right still repeating under it changes nothing: a repeat isn't a new press.
        repeat(5) { hold.key(TrainingKey.RIGHT, down = true) }
        assertEquals(-1, hold.direction)
        // Left let go: back to Right, still held.
        hold.key(TrainingKey.LEFT, down = false)
        assertEquals(1, hold.direction)
        hold.key(TrainingKey.RIGHT, down = false)
        assertEquals(0, hold.direction)

        // Up, Down and OK don't steer, and a key let go that wasn't held does nothing.
        for (key in listOf(TrainingKey.UP, TrainingKey.DOWN, TrainingKey.OK)) hold.key(key, down = true)
        assertEquals(0, hold.direction)
        hold.key(TrainingKey.LEFT, down = true)
        hold.key(TrainingKey.RIGHT, down = false)
        hold.key(TrainingKey.UP, down = false)
        assertEquals(-1, hold.direction)
        // Left let go once is let go: a second up, then Right down and up, leave nothing held.
        hold.key(TrainingKey.LEFT, down = false)
        hold.key(TrainingKey.LEFT, down = false)
        hold.key(TrainingKey.RIGHT, down = true)
        hold.key(TrainingKey.RIGHT, down = false)
        assertEquals(0, hold.direction)

        // Cleared (the app went away with a key held, its key-up lost): nothing is held until a key goes down again,
        // and the lost key's repeats, if it is still held, pick it up again.
        hold.key(TrainingKey.LEFT, down = true)
        hold.key(TrainingKey.RIGHT, down = true)
        hold.clear()
        assertEquals(0, hold.direction)
        hold.key(TrainingKey.RIGHT, down = false)
        assertEquals(0, hold.direction)
        hold.key(TrainingKey.LEFT, down = true)
        assertEquals(-1, hold.direction)
    }
}
