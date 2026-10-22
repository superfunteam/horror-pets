package team.superfun.horrorpets.fight

import org.junit.Assert.assertEquals
import org.junit.Test

/** The player's fight stats, tallied by [resolveTurn] for the results screen and the rank. */
class FightStatsTest {
    @Test
    fun aTradeIsDamageDealtAndTaken() {
        val r = resolveTurn(stateOf(), Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(FightStats(damageDealt = 16, damageTaken = 16, hits = 1, bestCombo = 1), r.state.stats)
    }

    @Test
    fun critsSpecialsAndMissesAreCounted() {
        val crit = resolveTurn(stateOf(), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(crit = 0.0)))
        assertEquals(FightStats(damageDealt = 50, hits = 1, crits = 1, bestCombo = 1), crit.state.stats)

        val special = resolveTurn(stateOf(player = fighter(meter = FightTuning.METER_MAX)), Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(1, special.state.stats.specials)

        val miss = resolveTurn(stateOf(), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertEquals(FightStats(misses = 1), miss.state.stats)
    }

    @Test
    fun aBlockedStrikeAndAnInterruptedHeavyWereStopped() {
        val blocked = resolveTurn(stateOf(), Move.STRIKE, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(1, blocked.state.stats.stopped)
        val cut = resolveTurn(stateOf(), Move.HEAVY, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(1, cut.state.stats.stopped)
        val theirs = resolveTurn(stateOf(), Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals("the CPU's stopped strike is not the player's", 0, theirs.state.stats.stopped)
    }

    @Test
    fun bleedsCountForWhoeverCausedThemAndHealsForThePlayer() {
        val s = stateOf(player = fighter(hp = 50, maxHp = 100).copy(bleed = 1), cpu = fighter().copy(bleed = 1))
        val r = resolveTurn(s, Move.POTION, Move.BLOCK, ScriptedRandom(listOf(0.0)))
        // The potion gives back 50 of 100; then each bleeds 6.
        assertEquals(FightStats(damageDealt = 6, damageTaken = 6, healed = 50), r.state.stats)
    }

    @Test
    fun theBestComboIsKeptAfterTheComboEnds() {
        var s = stateOf(cpu = fighter(hp = 300)) // enough to take five hits
        repeat(3) { s = resolveTurn(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll())).state }
        assertEquals(3, s.stats.bestCombo)
        // A trade: the player's fourth hit in a row lands (as COMBO ×4) even though the CPU's ends the combo.
        s = resolveTurn(s, Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll())).state
        assertEquals(0, s.player.combo)
        assertEquals(4, s.stats.bestCombo)
        s = resolveTurn(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll())).state
        assertEquals("a new, shorter run leaves the best alone", 4, s.stats.bestCombo)
    }

    @Test
    fun turnsAndPotionsAreReadOffTheState() {
        val s = stateOf(turn = 7).copy(playerPotions = 0)
        assertEquals(6, s.turnsPlayed)
        assertEquals(2, s.potionsUsed)
    }
}
