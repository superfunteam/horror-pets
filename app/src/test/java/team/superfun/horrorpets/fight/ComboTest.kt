package team.superfun.horrorpets.fight

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.fight.FightEvent.Hit

/** Combos: hits landed turn after turn without being hit build up, and hit harder (see [resolveTurn]). */
class ComboTest {
    private fun hitOf(events: List<FightEvent>, side: Side) = events.filterIsInstance<Hit>().single { it.attacker == side }

    @Test
    fun aCleanHitStartsACombo() {
        // A HEAVY breaks a BLOCK, which does nothing back.
        val r = resolveTurn(stateOf(), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(1, r.state.player.combo)
        assertEquals(0, r.state.cpu.combo)
        assertEquals("the first hit of a combo is a plain hit", 0, hitOf(r.events, Side.PLAYER).combo)
    }

    @Test
    fun eachTurnOfTheComboHitsHarderUpToThreeSteps() {
        // Base 16 x HEAVY 1.8 = 28.8, then +15% per step: 33.12, 37.44, 41.76, and no more after three.
        for ((combo, damage) in listOf(0 to 29, 1 to 33, 2 to 37, 3 to 42, 5 to 42)) {
            val s = stateOf(player = fighter(combo = combo))
            val r = resolveTurn(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
            val hit = hitOf(r.events, Side.PLAYER)
            assertEquals("combo $combo", damage, hit.damage)
            assertEquals(combo, hit.combo)
            assertEquals(combo + 1, r.state.player.combo)
        }
    }

    @Test
    fun beingHitEndsACombo() {
        // A trade: both land, so both are hit, and both combos end.
        val s = stateOf(player = fighter(combo = 2), cpu = fighter(combo = 1))
        val r = resolveTurn(s, Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(0, r.state.player.combo)
        assertEquals(0, r.state.cpu.combo)
    }

    @Test
    fun aWhiffEndsACombo() {
        val s = stateOf(player = fighter(combo = 2))
        val r = resolveTurn(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertEquals(0, r.state.player.combo)
    }

    @Test
    fun aCounterCountsAndTheStrikerItCaughtLosesItsCombo() {
        val s = stateOf(player = fighter(combo = 1), cpu = fighter(combo = 2))
        val r = resolveTurn(s, Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(2, r.state.player.combo)
        assertEquals(0, r.state.cpu.combo)
        assertEquals(1, hitOf(r.events, Side.PLAYER).combo)
    }

    @Test
    fun aQuietTurnKeepsBothCombos() {
        val s = stateOf(player = fighter(combo = 2), cpu = fighter(combo = 1))
        val blocks = resolveTurn(s, Move.BLOCK, Move.BLOCK, ScriptedRandom())
        assertEquals(2, blocks.state.player.combo)
        assertEquals(1, blocks.state.cpu.combo)

        val hurt = s.copy(player = s.player.copy(hp = 50))
        val potion = resolveTurn(hurt, Move.POTION, Move.BLOCK, ScriptedRandom(listOf(0.0)))
        assertEquals("a potion drunk in peace keeps the combo", 2, potion.state.player.combo)
    }

    @Test
    fun aSpecialIsPartOfACombo() {
        val s = stateOf(player = fighter(meter = FightTuning.METER_MAX, combo = 1))
        val r = resolveTurn(s, Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        // 16 x 2.5 x 1.15 = 46
        assertEquals(46, hitOf(r.events, Side.PLAYER).damage)
        assertEquals(2, r.state.player.combo)
    }
}
