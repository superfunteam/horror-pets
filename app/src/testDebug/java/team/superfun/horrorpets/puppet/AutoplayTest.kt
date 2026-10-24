package team.superfun.horrorpets.puppet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.fighter
import team.superfun.horrorpets.fight.stateOf

class AutoplayTest {
    /** A fight on [turn] with the player at [hp]/100, its meter at [meter] and [potions] left. */
    private fun fight(hp: Int = 100, meter: Int = 0, potions: Int = FightTuning.POTIONS_PER_FIGHT, turn: Int = 1): FightState =
        stateOf(player = fighter(hp = hp, maxHp = 100, meter = meter), turn = turn).copy(playerPotions = potions)

    private fun pick(f: FightState): Move = autoplayPick(f).move

    @Test
    fun aFullMeterGoesFirst() {
        assertEquals(Move.SPECIAL, pick(fight(meter = FightTuning.METER_MAX)))
        assertEquals(Move.SPECIAL, pick(fight(meter = FightTuning.METER_MAX, hp = 10)))
        assertEquals(Move.STRIKE, pick(fight(meter = FightTuning.METER_MAX - 1)))
    }

    @Test
    fun aPotionUnderThirtyPercentWhileAnyAreLeft() {
        assertEquals(Move.POTION, pick(fight(hp = 29)))
        assertEquals(Move.POTION, pick(fight(hp = 1, potions = 1)))
        // At 30% it fights on, and with none left it has to.
        assertEquals(Move.STRIKE, pick(fight(hp = 30)))
        assertEquals(Move.STRIKE, pick(fight(hp = 10, potions = 0)))
    }

    @Test
    fun withoutASpecialOrAPotionAutoplayAlternatesStrikeAndHeavy() {
        val first = fight(turn = 1) // an empty meter and full HP
        assertEquals(Move.STRIKE, autoplayPick(first).move)
        assertEquals(Move.HEAVY, autoplayPick(first.copy(turn = 2)).move)
        assertEquals(listOf(Move.STRIKE, Move.HEAVY, Move.STRIKE, Move.HEAVY), (1..4).map { pick(fight(turn = it)) })
    }

    @Test
    fun itNeverPicksWhatTheFightWouldRefuse() {
        for (hp in listOf(1, 29, 30, 100)) for (meter in listOf(0, 99, 100)) for (potions in 0..2) {
            for (turn in 1..2) {
                val f = fight(hp, meter, potions, turn)
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
