package team.superfun.horrorpets.game.training

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HideAndSeekTest {
    @Test
    fun moreSwapsEachRoundUpToACap() {
        assertEquals(3, shuffleFor(1, Random(1)).swaps.size)
        assertEquals(4, shuffleFor(2, Random(2)).swaps.size)
        assertEquals(10, shuffleFor(8, Random(8)).swaps.size) // at par
        assertEquals(13, shuffleFor(11, Random(11)).swaps.size)
        assertEquals(14, shuffleFor(12, Random(12)).swaps.size)
        assertEquals(14, shuffleFor(13, Random(13)).swaps.size)
        assertEquals(14, shuffleFor(100, Random(100)).swaps.size)
        // One more each round until the cap, whatever the dealing.
        for (seed in 0 until 50) {
            val rng = Random(seed)
            var last = shuffleFor(1, rng).swaps.size
            for (round in 2..20) {
                val swaps = shuffleFor(round, rng).swaps.size
                assertEquals("seed $seed round $round", if (last < 14) last + 1 else 14, swaps)
                last = swaps
            }
        }
    }

    @Test
    fun swapsSpeedUpToAFloor() {
        fun swapMs(round: Int) = shuffleFor(round, Random(round)).swapMs
        assertEquals(600L, swapMs(1))
        assertEquals(528L, swapMs(2))
        assertEquals(465L, swapMs(3))
        assertEquals(245L, swapMs(8)) // at par
        assertEquals(190L, swapMs(10))
        assertEquals(170L, swapMs(11)) // 167 would be under the floor
        assertEquals(170L, swapMs(50))
        var last = swapMs(1)
        for (round in 2..40) {
            val ms = swapMs(round)
            assertTrue("round $round: $ms over $last", ms <= last)
            if (last > 170L) assertTrue("round $round didn't speed up", ms < last)
            assertTrue("round $round: $ms under the floor", ms >= 170L)
            last = ms
        }
    }

    @Test
    fun theMateFollowsItsStone() {
        // No swaps: it stays where it hid.
        for (at in 0..2) assertEquals(at, mateAfter(Shuffle(at, emptyList(), 600L)))
        // Its stone moves with each swap it is in, and stays put through a swap it isn't.
        assertEquals(2, mateAfter(Shuffle(0, listOf(0 to 2), 600L)))
        assertEquals(0, mateAfter(Shuffle(0, listOf(1 to 2), 600L)))
        assertEquals(1, mateAfter(Shuffle(2, listOf(0 to 2, 1 to 0), 600L)))
        assertEquals(2, mateAfter(Shuffle(1, listOf(1 to 0, 2 to 1, 0 to 2), 600L)))
        // Either way round: (a, b) and (b, a) swap the same two stones.
        assertEquals(1, mateAfter(Shuffle(0, listOf(1 to 0), 600L)))
        // The same pair twice undoes it.
        assertEquals(1, mateAfter(Shuffle(1, listOf(0 to 1, 1 to 0), 600L)))
        // Played out swap by swap, three stones each with its own mark, the mate's mark ends where mateAfter says.
        for (seed in 0 until 200) {
            val shuffle = shuffleFor(1 + seed % 15, Random(seed))
            val stones = intArrayOf(0, 1, 2) // stones[position] = the stone there, by where it started
            for ((a, b) in shuffle.swaps) stones[a] = stones[b].also { stones[b] = stones[a] }
            assertEquals("seed $seed", stones.indexOf(shuffle.hideAt), mateAfter(shuffle))
        }
    }

    @Test
    fun designsCycleEveryRound() {
        assertEquals(0, tombDesign(1))
        assertEquals(1, tombDesign(2))
        assertEquals(2, tombDesign(3))
        assertEquals(0, tombDesign(4))
        assertEquals(1, tombDesign(5))
        for (round in 1..30) {
            assertTrue(tombDesign(round) in 0..2)
            assertTrue("round $round has the last round's design", tombDesign(round) != tombDesign(round + 1))
        }
    }

    @Test
    fun noSwapIsANoOp() {
        val pairs = mutableSetOf<Pair<Int, Int>>()
        val hides = mutableSetOf<Int>()
        for (seed in 0 until 500) {
            val rng = Random(seed)
            for (round in 1..14) {
                val shuffle = shuffleFor(round, rng)
                assertTrue(shuffle.hideAt in 0..2)
                hides += shuffle.hideAt
                for ((a, b) in shuffle.swaps) {
                    assertTrue("seed $seed round $round: $a with itself", a != b)
                    assertTrue("seed $seed round $round: ($a, $b) off the row", a in 0..2 && b in 0..2)
                    pairs += a to b
                }
            }
        }
        // Every stone hides, and every pair swaps, either way round (which one passes in front).
        assertEquals(setOf(0, 1, 2), hides)
        assertEquals(6, pairs.size)
    }
}
