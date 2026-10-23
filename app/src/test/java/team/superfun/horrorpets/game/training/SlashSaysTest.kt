package team.superfun.horrorpets.game.training

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashSaysTest {
    /** A round laid out by hand: [slots] for its dots, slashed in [order]. */
    private fun round(slots: List<Int>, order: List<Int>) = SlashRound(round = slots.size - 1, slots = slots, order = order)

    /** The dots' places on the ring the players see (x radius [SLASH_RING_ASPECT] times the y radius), around (0, 0). */
    private fun dotsOf(round: SlashRound): List<Pair<Float, Float>> =
        round.slots.map { slotPosition(it, cx = 0f, cy = 0f, rx = SLASH_RING_ASPECT * 100f, ry = 100f) }

    @Test
    fun roundsGrowToEightDots() {
        assertEquals(2, slashDots(1))
        assertEquals(3, slashDots(2))
        assertEquals(8, slashDots(7))
        assertEquals(8, slashDots(8))
        assertEquals(8, slashDots(50))
        for (r in 1..12) assertEquals(slashDots(r), nextSlashRound(r, Random(r)).slots.size)
    }

    @Test
    fun slotsAreDistinctAndOrderIsAPermutation() {
        for (seed in 0 until 1000) {
            val rng = Random(seed)
            for (r in 1..12) {
                val round = nextSlashRound(r, rng)
                assertEquals(r, round.round)
                assertEquals(slashDots(r), round.slots.size)
                assertEquals("distinct slots, seed $seed round $r", round.slots.size, round.slots.toSet().size)
                assertTrue("slots on the ring, seed $seed round $r", round.slots.all { it in 0 until SLASH_SLOTS })
                assertEquals("every dot once, seed $seed round $r", round.slots.indices.toList(), round.order.sorted())
            }
        }
    }

    @Test
    fun layoutsChangeEveryRound() {
        // Each round is laid out afresh, not the last one plus a dot (as in Simon): the dots move and the order is new.
        // Played as each round's slots in slash order, a round rarely starts with the one before it, and over many
        // games every slot comes up in every place of the order.
        var carriedOver = 0
        var pairs = 0
        val seenAt = Array(SLASH_SLOTS) { mutableSetOf<Int>() } // the places in the order each slot was slashed at
        for (seed in 0 until 500) {
            val rng = Random(seed)
            var last: List<Int>? = null
            for (r in 1..12) {
                val round = nextSlashRound(r, rng)
                val played = round.order.map { round.slots[it] }
                played.forEachIndexed { place, slot -> seenAt[slot] += place }
                last?.let { before ->
                    pairs++
                    if (played.take(before.size) == before) carriedOver++
                }
                last = played
            }
        }
        assertTrue("$carriedOver of $pairs rounds began with the round before", carriedOver * 100 < pairs)
        for (slot in 0 until SLASH_SLOTS) assertEquals("slot $slot", (0 until SLASH_SLOTS).toSet(), seenAt[slot])
        // And the slots taken change too: the 28 ways to place round 1's two dots all come up.
        val firstRounds = (0 until 2000).map { nextSlashRound(1, Random(it)).slots.toSet() }.toSet()
        assertEquals(28, firstRounds.size)
    }

    @Test
    fun showSpeedsUpToAFloor() {
        assertEquals(520L to 180L, slashShowMs(1))
        assertEquals(468L to 162L, slashShowMs(2))
        var last = slashShowMs(1)
        for (r in 2..40) {
            val (lit, gap) = slashShowMs(r)
            assertTrue("lit slowed at round $r", lit <= last.first)
            assertTrue("gap slowed at round $r", gap <= last.second)
            assertTrue("lit under the floor at round $r", lit >= 220L)
            assertTrue("gap under the floor at round $r", gap >= 80L)
            last = lit to gap
        }
        assertEquals(220L to 80L, slashShowMs(10))
        assertEquals(220L to 80L, slashShowMs(40))
    }

    @Test
    fun aTurnTakesTheOrderThenEnds() {
        val turn = SlashTurn(round(slots = listOf(1, 4, 6), order = listOf(2, 0, 1)))
        assertEquals(0, turn.slashed)
        assertEquals(2, turn.due)
        assertEquals(SlashStep.RIGHT, turn.slash(2))
        assertEquals(SlashStep.RIGHT, turn.slash(0))
        assertEquals(listOf(2, 0), turn.path)
        assertEquals(2, turn.slashed)
        assertEquals(1, turn.due)
        assertFalse(turn.over)
        assertEquals(SlashStep.ROUND_DONE, turn.slash(1))
        assertEquals(3, turn.slashed)
        assertTrue(turn.over)
        assertNull(turn.due)
        assertEquals(listOf(2, 0, 1), turn.path)
        // A done turn takes nothing more.
        assertEquals(SlashStep.IGNORED, turn.slash(1))
    }

    @Test
    fun aWrongDotEndsIt() {
        val turn = SlashTurn(round(slots = listOf(0, 3, 5, 7), order = listOf(3, 1, 0, 2)))
        assertEquals(SlashStep.RIGHT, turn.slash(3))
        assertEquals(SlashStep.WRONG, turn.slash(0))
        assertTrue(turn.over)
        assertEquals(1, turn.slashed)
        assertEquals("the one that was due, to flash", 1, turn.due)
        // Over: even the right dot is too late now.
        assertEquals(SlashStep.IGNORED, turn.slash(1))
        assertEquals(SlashStep.IGNORED, turn.slash(2))
        assertEquals(1, turn.slashed)

        // Wrong from the very first dot.
        val first = SlashTurn(round(slots = listOf(2, 6), order = listOf(1, 0)))
        assertEquals(SlashStep.WRONG, first.slash(0))
        assertEquals(0, first.slashed)
        assertEquals(1, first.due)
    }

    @Test
    fun reslashingIsIgnored() {
        val turn = SlashTurn(round(slots = listOf(0, 2, 4), order = listOf(0, 1, 2)))
        assertEquals(SlashStep.RIGHT, turn.slash(0))
        // A swipe going back over the dot it just slashed, or an earlier one, does nothing.
        assertEquals(SlashStep.IGNORED, turn.slash(0))
        assertEquals(SlashStep.RIGHT, turn.slash(1))
        assertEquals(SlashStep.IGNORED, turn.slash(0))
        assertEquals(SlashStep.IGNORED, turn.slash(1))
        assertEquals(2, turn.slashed)
        assertFalse(turn.over)
        assertEquals(SlashStep.ROUND_DONE, turn.slash(2))
    }

    @Test
    fun slotsKeepClearOfThePetsBody() {
        // Under the chest is the pet's body and legs: no slot between 30° and 150° (y down, so that's below).
        assertEquals(SLASH_SLOTS, (0 until SLASH_SLOTS).map(::slotAngle).toSet().size)
        for (slot in 0 until SLASH_SLOTS) {
            val a = slotAngle(slot)
            assertTrue("slot $slot at $a", a in 0f..360f)
            assertFalse("slot $slot at $a is under the pet", a > 30f && a < 150f)
        }
        // A slot sits on the ring at its angle: 0 is right, 90 is down.
        val (x, y) = slotPosition(7, cx = 10f, cy = 20f, rx = 150f, ry = 100f)
        assertEquals(10f + 150f * cos(Math.toRadians(30.0)).toFloat(), x, 1e-3f)
        assertEquals(20f + 100f * sin(Math.toRadians(30.0)).toFloat(), y, 1e-3f)
    }

    @Test
    fun aimingPicksTheBestDotThatWay() {
        val dots = listOf(10f to 0f, 0f to -10f, -30f to 0f, 25f to 20f)
        val chest = 0f to 0f
        assertEquals(0, aimFrom(chest, dots, TrainingKey.RIGHT, skip = null))
        assertEquals(1, aimFrom(chest, dots, TrainingKey.UP, skip = null))
        assertEquals(2, aimFrom(chest, dots, TrainingKey.LEFT, skip = null))
        // Down: only (25, 20) is within 75° of straight down (51° off it).
        assertEquals(3, aimFrom(chest, dots, TrainingKey.DOWN, skip = null))
        // The aimed dot is skipped: from it, right goes on to the next one that way.
        assertEquals(3, aimFrom(dots[0], dots, TrainingKey.RIGHT, skip = 0))
        // Nothing that way at all.
        assertNull(aimFrom(dots[2], dots, TrainingKey.LEFT, skip = 2))
        assertNull(aimFrom(chest, emptyList(), TrainingKey.UP, skip = null))
        // Nearer but well off to the side loses to farther but straight ahead: cost is distance x (1 + 2 x angle/75°).
        val choice = listOf(10f to -10f, 35f to 0f) // 45° off at 14.1 (cost 31.1), straight on at 35 (cost 35)
        assertEquals(0, aimFrom(chest, choice, TrainingKey.RIGHT, skip = null))
        val farther = listOf(10f to -10f, 30f to 0f) // straight on at 30 (cost 30) wins now
        assertEquals(1, aimFrom(chest, farther, TrainingKey.RIGHT, skip = null))
        // Past 75° off is not that way.
        assertNull(aimFrom(chest, listOf(1f to -10f), TrainingKey.RIGHT, skip = null))
    }

    @Test
    fun everyDotIsReachableWithTheArrows() {
        val directions = listOf(TrainingKey.UP, TrainingKey.DOWN, TrainingKey.LEFT, TrainingKey.RIGHT)
        val chest = 0f to 0f
        val rng = Random(2026)
        // 2000 rounds as the game deals them, then every set of slots a round can have (the order doesn't matter here).
        val dealt = List(2000) { n -> nextSlashRound(1 + n % 12, rng) }
        val everyLayout = (0 until (1 shl SLASH_SLOTS)).map { mask -> (0 until SLASH_SLOTS).filter { mask shr it and 1 == 1 } }
            .filter { it.size >= 2 }
            .map { round(slots = it, order = it.indices.toList()) }
        assertEquals(247, everyLayout.size)
        for (round in dealt + everyLayout) {
            val dots = dotsOf(round)

            /** Every dot the arrows can get to from [start] (null: the chest), aiming on and on. */
            fun reach(start: Int?): Set<Int> {
                val seen = mutableSetOf<Int>()
                val queue = ArrayDeque<Int?>().apply { add(start) }
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    val from = at?.let { dots[it] } ?: chest
                    for (dir in directions) {
                        val next = aimFrom(from, dots, dir, skip = at) ?: continue
                        if (seen.add(next)) queue.add(next)
                    }
                }
                return seen
            }

            val all = round.slots.indices.toSet()
            assertEquals("from the chest, slots ${round.slots}", all, reach(null))
            for (dot in round.slots.indices) {
                assertEquals("from dot $dot (slot ${round.slots[dot]}), slots ${round.slots}", all - dot, reach(dot) - dot)
            }
        }
    }
}
