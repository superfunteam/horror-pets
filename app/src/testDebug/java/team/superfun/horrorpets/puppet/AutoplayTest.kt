package team.superfun.horrorpets.puppet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import team.superfun.horrorpets.fight.CpuPlan
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.fighter
import team.superfun.horrorpets.fight.stateOf

class AutoplayTest {
    /** A fight on [turn] with the player at [hp]/100, its meter at [meter] and [potions] left, the CPU telling [tell]. */
    private fun fight(hp: Int = 100, meter: Int = 0, potions: Int = FightTuning.POTIONS_PER_FIGHT, tell: Move? = null, turn: Int = 1): FightState =
        stateOf(player = fighter(hp = hp, maxHp = 100, meter = meter), turn = turn)
            .copy(playerPotions = potions, cpuPlan = tell?.let { CpuPlan(it, it) })

    private fun pick(f: FightState): Move = autoplayPick(f).move

    @Test
    fun aFullMeterGoesFirst() {
        assertEquals(Move.SPECIAL, pick(fight(meter = FightTuning.METER_MAX)))
        assertEquals(Move.SPECIAL, pick(fight(meter = FightTuning.METER_MAX, hp = 10, tell = Move.HEAVY)))
        assertEquals(Move.BLOCK, pick(fight(meter = FightTuning.METER_MAX - 1, tell = Move.STRIKE)))
    }

    @Test
    fun aPotionUnderThirtyPercentWhileAnyAreLeft() {
        assertEquals(Move.POTION, pick(fight(hp = 29, tell = Move.STRIKE)))
        assertEquals(Move.POTION, pick(fight(hp = 1, potions = 1)))
        // At 30% it fights on, and with none left it has to.
        assertEquals(Move.BLOCK, pick(fight(hp = 30, tell = Move.STRIKE)))
        assertEquals(Move.BLOCK, pick(fight(hp = 10, potions = 0, tell = Move.STRIKE)))
    }

    @Test
    fun theTellIsAnswered() {
        assertEquals(Move.BLOCK, pick(fight(tell = Move.STRIKE)))
        assertEquals(Move.STRIKE, pick(fight(tell = Move.HEAVY)))
        assertEquals(Move.HEAVY, pick(fight(tell = Move.BLOCK)))
    }

    @Test
    fun theTellIsWhatIsAnsweredNotTheMoveBehindAFeint() {
        val feint = fight().copy(cpuPlan = CpuPlan(move = Move.STRIKE, tell = Move.BLOCK))
        assertEquals(Move.HEAVY, pick(feint))
    }

    @Test
    fun withNothingToAnswerItAlternatesStrikeAndHeavy() {
        // No tell (a PvP opponent's), or a SPECIAL, which has no answer.
        for (tell in listOf(null, Move.SPECIAL)) {
            assertEquals(listOf(Move.STRIKE, Move.HEAVY, Move.STRIKE, Move.HEAVY), (1..4).map { pick(fight(tell = tell, turn = it)) })
        }
    }

    @Test
    fun itNeverPicksWhatTheFightWouldRefuse() {
        for (hp in listOf(1, 29, 30, 100)) for (meter in listOf(0, 99, 100)) for (potions in 0..2) {
            for (tell in listOf(null) + Move.entries) for (turn in 1..2) {
                val f = fight(hp, meter, potions, tell, turn)
                val p = autoplayPick(f)
                assertNull("$p in $f", pickRefusal(f, p.move))
            }
        }
    }

    @Test
    fun refusalsFollowTheGamesRules() {
        assertNull(pickRefusal(fight(), Move.STRIKE))
        assertNull(pickRefusal(fight(), Move.RUN))
        assertNotNull(pickRefusal(fight(meter = 99), Move.SPECIAL))
        assertNull(pickRefusal(fight(meter = FightTuning.METER_MAX), Move.SPECIAL))
        assertNotNull(pickRefusal(fight(hp = 50, potions = 0), Move.POTION))
        assertNotNull(pickRefusal(fight(hp = 100), Move.POTION))
        assertNull(pickRefusal(fight(hp = 99), Move.POTION))
        assertNotNull(pickRefusal(fight().copy(winner = Side.PLAYER), Move.STRIKE))
    }
}
