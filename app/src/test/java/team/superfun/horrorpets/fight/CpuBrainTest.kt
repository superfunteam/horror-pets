package team.superfun.horrorpets.fight

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        for (id in listOf(PetId.CHUCKY, PetId.MICHAEL, PetId.LEATHERFACE)) {
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

    // ---- plans and tells ----

    @Test
    fun aPlanShowsItsMoveUnlessItFeints() {
        // No habit, empty meter: the weighted pick (0 is STRIKE), then the feint roll against 0.25.
        assertEquals(CpuPlan(Move.STRIKE, Move.STRIKE), cpuPlan(stateOf(), ScriptedRandom(doubles = listOf(0.25), ints = listOf(0))))
        assertEquals(CpuPlan(Move.STRIKE, Move.BLOCK), cpuPlan(stateOf(), ScriptedRandom(doubles = listOf(0.24), ints = listOf(0))))
    }

    @Test
    fun aFeintShowsTheMoveThatAnswersTheRealOne() {
        // Each feint baits the answer the real move beats: BLOCK baits a HEAVY (a STRIKE interrupts it), STRIKE
        // baits a BLOCK (a HEAVY breaks it), HEAVY baits a STRIKE (a BLOCK counters it).
        val weightRolls = mapOf(Move.STRIKE to 0, Move.HEAVY to 40, Move.BLOCK to 70)
        val shown = mapOf(Move.STRIKE to Move.BLOCK, Move.HEAVY to Move.STRIKE, Move.BLOCK to Move.HEAVY)
        for ((move, roll) in weightRolls) {
            assertEquals(CpuPlan(move, shown.getValue(move)), cpuPlan(stateOf(), ScriptedRandom(doubles = listOf(0.0), ints = listOf(roll))))
        }
    }

    @Test
    fun aSpecialIsNeverAFeintAndRollsNoFeint() {
        val rng = ScriptedRandom(doubles = listOf(0.0))
        assertEquals(CpuPlan(Move.SPECIAL, Move.SPECIAL), cpuPlan(stateOf(cpu = full), rng))
        assertEquals(0, rng.remainingDoubles)
    }

    @Test
    fun ghostfaceFeintsMoreOften() {
        val roll = 0.3 // over everyone's 0.25, under Ghostface's 0.45
        assertEquals(Move.STRIKE, cpuPlan(stateOf(cpu = fighter(id = PetId.JASON)), ScriptedRandom(doubles = listOf(roll), ints = listOf(0))).tell)
        assertEquals(Move.BLOCK, cpuPlan(stateOf(cpu = fighter(id = PetId.GHOSTFACE)), ScriptedRandom(doubles = listOf(roll), ints = listOf(0))).tell)
    }

    @Test
    fun mostTellsAreTrue() {
        for ((id, range) in listOf(PetId.JASON to 2_200..2_800, PetId.GHOSTFACE to 4_200..4_800)) {
            val rng = Random(11)
            val s = stateOf(cpu = fighter(id = id))
            val feints = List(10_000) { cpuPlan(s, rng) }.count { it.tell != it.move }
            assertTrue("$id feinted $feints / 10000", feints in range)
        }
    }

    @Test
    fun theCpuPlansOnlyWhileTheFightIsOn() {
        assertEquals(CpuPlan(Move.STRIKE, Move.STRIKE), planCpu(stateOf(), ScriptedRandom(doubles = listOf(0.9), ints = listOf(0))).cpuPlan)
        val over = stateOf(winner = Side.PLAYER).copy(cpuPlan = CpuPlan(Move.HEAVY, Move.HEAVY))
        assertNull(planCpu(over, ScriptedRandom()).cpuPlan)
    }

    @Test
    fun theSameSeedGivesTheSamePicks() {
        val s = stateOf(cpu = full, history = listOf(Move.HEAVY, Move.STRIKE))
        assertEquals(picks(s, seed = 3), picks(s, seed = 3))
    }
}
