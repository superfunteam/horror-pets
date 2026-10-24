package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side

class FightTimeTest {
    private fun hit(move: Move, crit: Boolean = false, counter: Boolean = false, damage: Int = 10) =
        FightEvent.Hit(Side.PLAYER, move, damage, crit, counter, combo = 0, effect = null, shattered = 0)

    @Test fun aStrikeStopsShortAndTheBigOnesStopLonger() {
        assertEquals(FightTuning.HIT_STOP_STRIKE_MS, hitStopFor(hit(Move.STRIKE)))
        assertEquals(FightTuning.HIT_STOP_HEAVY_MS, hitStopFor(hit(Move.HEAVY)))
        assertEquals(FightTuning.HIT_STOP_HEAVY_MS, hitStopFor(hit(Move.STRIKE, crit = true)))
        assertEquals(FightTuning.HIT_STOP_HEAVY_MS, hitStopFor(hit(Move.STRIKE, counter = true)))
        assertEquals(FightTuning.HIT_STOP_HEAVY_MS, hitStopFor(hit(Move.SPECIAL)))
        assertEquals(0L, hitStopFor(FightEvent.Miss(Side.PLAYER, Move.STRIKE, dazed = false)))
        assertEquals(0L, hitStopFor(FightEvent.Blocked(Side.PLAYER)))
    }

    @Test fun theBlowThatEmptiesTheBarIsTheKnockout() {
        assertTrue(koBlow(hit(Move.HEAVY, damage = 12), defenderHp = 12, attacker = Side.PLAYER, winner = Side.PLAYER))
        assertFalse(koBlow(hit(Move.HEAVY, damage = 11), defenderHp = 12, attacker = Side.PLAYER, winner = Side.PLAYER))
        val miss = FightEvent.Miss(Side.PLAYER, Move.STRIKE, dazed = false)
        assertFalse(koBlow(miss, defenderHp = 1, attacker = Side.PLAYER, winner = Side.PLAYER))
    }

    @Test fun onlyTheWinnersBlowIsTheKnockout() {
        // A heal later in the turn fills the emptied bar again: nobody wins, so there is no knockout.
        assertFalse(koBlow(hit(Move.STRIKE, damage = 20), defenderHp = 12, attacker = Side.PLAYER, winner = null))
        // A double K.O. the CPU wins (it dealt more): the player's lethal-looking blow is not the knockout, the
        // CPU's is.
        assertFalse(koBlow(hit(Move.STRIKE, damage = 20), defenderHp = 12, attacker = Side.PLAYER, winner = Side.CPU))
        val cpuBlow = FightEvent.Hit(Side.CPU, Move.HEAVY, damage = 30, crit = false)
        assertTrue(koBlow(cpuBlow, defenderHp = 25, attacker = Side.CPU, winner = Side.CPU))
    }

    @Test fun theSoundLandsBeforeTheStopAndTheBlowAfterIt() {
        assertEquals(ImpactSchedule(0, 110, 110), impactSchedule(landMs = 0, stopMs = 110))
        assertEquals(ImpactSchedule(780, 110, 890), impactSchedule(landMs = 780, stopMs = 110))
        assertEquals(ImpactSchedule(0, 0, 0), impactSchedule(landMs = 0, stopMs = 0))
    }
}
