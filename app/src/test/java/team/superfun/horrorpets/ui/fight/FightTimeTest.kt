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
        assertTrue(koBlow(hit(Move.HEAVY, damage = 12), defenderHp = 12))
        assertFalse(koBlow(hit(Move.HEAVY, damage = 11), defenderHp = 12))
        assertFalse(koBlow(FightEvent.Miss(Side.PLAYER, Move.STRIKE, dazed = false), defenderHp = 1))
    }

    @Test fun theSoundLandsBeforeTheStopAndTheBlowAfterIt() {
        assertEquals(ImpactSchedule(0, 110, 110), impactSchedule(landMs = 0, stopMs = 110))
        assertEquals(ImpactSchedule(780, 110, 890), impactSchedule(landMs = 780, stopMs = 110))
        assertEquals(ImpactSchedule(0, 0, 0), impactSchedule(landMs = 0, stopMs = 0))
    }

    @Test fun aScaledClockHoldsAtZeroAndCrawlsAtATenth() {
        val c = ScaledClock()
        assertEquals(0L, c.advance(100, 0f))
        assertEquals(10L, c.advance(100, 0.1f))
        assertEquals(110L, c.advance(100, 1f))
        c.advance(5, 0.3f) // 1.5 ms: the half carries
        assertEquals("remainders carry", 113L, c.advance(5, 0.3f))
    }
}
