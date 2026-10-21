package team.superfun.horrorpets.fight

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.PetId

class CpuBrainTest {
    private val full = fighter(meter = FightTuning.METER_MAX)

    private fun picks(s: FightState, n: Int = 1000, seed: Int = 7): List<Move> {
        val rng = Random(seed)
        return List(n) { cpuPick(s, rng) }
    }

    @Test
    fun neverRuns() {
        val states = listOf(
            stateOf(),
            stateOf(cpu = full),
            stateOf(history = List(10) { Move.STRIKE }),
            stateOf(cpu = full, history = listOf(Move.SPECIAL, Move.RUN, Move.BLOCK)),
        )
        for (s in states) {
            assertFalse(picks(s).contains(Move.RUN))
        }
    }

    @Test
    fun theNewKillersNeverRunNorSpecialWithoutAFullMeter() {
        for (id in listOf(PetId.CHUCKY, PetId.MICHAEL)) {
            for (meter in listOf(0, 25, 50, 75)) {
                val s = stateOf(player = fighter(id = id), cpu = fighter(id = id, meter = meter), history = listOf(Move.STRIKE, Move.HEAVY))
                val moves = picks(s)
                assertFalse("$id meter $meter", moves.contains(Move.RUN) || moves.contains(Move.SPECIAL))
            }
            val full = picks(stateOf(cpu = fighter(id = id, meter = FightTuning.METER_MAX)))
            assertFalse("$id full meter", full.contains(Move.RUN))
            assertTrue("$id full meter", full.contains(Move.SPECIAL))
        }
    }

    @Test
    fun neverSpecialUnlessTheMeterIsFull() {
        for (meter in listOf(0, 25, 50, 75)) {
            assertFalse("meter $meter", picks(stateOf(cpu = fighter(meter = meter))).contains(Move.SPECIAL))
        }
    }

    @Test
    fun prefersSpecialWhenFull() {
        val special = picks(stateOf(cpu = full)).count { it == Move.SPECIAL }
        assertTrue("special picked $special / 1000", special >= 600)
    }

    @Test
    fun aFullMeterStillLeavesRoomForOtherMoves() {
        val others = picks(stateOf(cpu = full)).filter { it != Move.SPECIAL }
        assertTrue(others.toSet().containsAll(listOf(Move.STRIKE, Move.HEAVY, Move.BLOCK)))
    }

    @Test
    fun countersMostUsed() {
        val s = stateOf(history = List(6) { Move.STRIKE })
        val block = picks(s).count { it == Move.BLOCK }
        assertTrue("block picked $block / 1000", block >= 400)
    }

    @Test
    fun countersEachMoveWithTheRightAnswer() {
        // Counter roll 0.0 < 0.4 always counters.
        val cases = mapOf(Move.STRIKE to Move.BLOCK, Move.HEAVY to Move.STRIKE, Move.BLOCK to Move.HEAVY)
        for ((habit, answer) in cases) {
            val s = stateOf(history = listOf(habit, habit))
            assertEquals("vs $habit", answer, cpuPick(s, ScriptedRandom(doubles = listOf(0.0))))
        }
    }

    @Test
    fun countersTheMostFrequentMoveNotTheLatest() {
        val s = stateOf(history = listOf(Move.HEAVY, Move.HEAVY, Move.STRIKE))
        assertEquals(Move.STRIKE, cpuPick(s, ScriptedRandom(doubles = listOf(0.0)))) // answers HEAVY
    }

    @Test
    fun aTieInUsageGoesToTheMoreRecentMove() {
        val s = stateOf(history = listOf(Move.STRIKE, Move.HEAVY))
        assertEquals(Move.STRIKE, cpuPick(s, ScriptedRandom(doubles = listOf(0.0)))) // answers HEAVY
        val flipped = stateOf(history = listOf(Move.HEAVY, Move.STRIKE))
        assertEquals(Move.BLOCK, cpuPick(flipped, ScriptedRandom(doubles = listOf(0.0)))) // answers STRIKE
    }

    @Test
    fun specialAndRunInTheHistoryAreNotCountered() {
        // Only SPECIAL in the history: nothing to counter, so no counter roll is drawn and weights decide.
        val s = stateOf(history = listOf(Move.SPECIAL, Move.SPECIAL))
        val rng = ScriptedRandom(ints = listOf(95))
        assertEquals(Move.BLOCK, cpuPick(s, rng))
        assertEquals(0, rng.remainingInts)
    }

    @Test
    fun anEmptyHistoryDrawsNoCounterRoll() {
        val rng = ScriptedRandom(ints = listOf(0)) // no doubles scripted: a stray draw would throw
        assertEquals(Move.STRIKE, cpuPick(stateOf(), rng))
    }

    @Test
    fun fullMeterRollsForSpecialFirst() {
        // 0.69 < 0.7: special. 0.71 falls through to the weights (no history, so no counter roll).
        assertEquals(Move.SPECIAL, cpuPick(stateOf(cpu = full), ScriptedRandom(doubles = listOf(0.69))))
        assertEquals(Move.HEAVY, cpuPick(stateOf(cpu = full), ScriptedRandom(doubles = listOf(0.71), ints = listOf(40))))
    }

    @Test
    fun counterRollComesAfterTheSpecialRoll() {
        val s = stateOf(cpu = full, history = listOf(Move.STRIKE))
        // Special roll fails (0.9), counter roll succeeds (0.1): STRIKE habit -> BLOCK.
        assertEquals(Move.BLOCK, cpuPick(s, ScriptedRandom(doubles = listOf(0.9, 0.1))))
        // Counter roll fails (0.5), so the weights decide.
        assertEquals(Move.STRIKE, cpuPick(s, ScriptedRandom(doubles = listOf(0.9, 0.5), ints = listOf(10))))
    }

    @Test
    fun weightsAreFortyThirtyThirty() {
        val expected = mapOf(0 to Move.STRIKE, 39 to Move.STRIKE, 40 to Move.HEAVY, 69 to Move.HEAVY, 70 to Move.BLOCK, 99 to Move.BLOCK)
        for ((roll, move) in expected) {
            assertEquals("roll $roll", move, cpuPick(stateOf(), ScriptedRandom(ints = listOf(roll))))
        }
    }

    @Test
    fun withNoHabitAndNoSpecialThePicksFollowTheWeights() {
        val all = picks(stateOf(), n = 10_000)
        val strike = all.count { it == Move.STRIKE }
        val heavy = all.count { it == Move.HEAVY }
        val block = all.count { it == Move.BLOCK }
        assertEquals(10_000, strike + heavy + block)
        assertTrue("strike $strike", strike in 3_700..4_300)
        assertTrue("heavy $heavy", heavy in 2_700..3_300)
        assertTrue("block $block", block in 2_700..3_300)
    }

    @Test
    fun theSameSeedGivesTheSamePicks() {
        val s = stateOf(cpu = full, history = listOf(Move.HEAVY, Move.STRIKE))
        assertEquals(picks(s, seed = 3), picks(s, seed = 3))
    }
}
