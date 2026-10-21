package team.superfun.horrorpets.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import team.superfun.horrorpets.fight.FightEvent.Heal
import team.superfun.horrorpets.fight.FightEvent.Hit
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

/** The player's potions: two a fight, each healing half to all of their max HP. Damage numbers as in FightEngineTest. */
class PotionTest {
    /** A potion roll, then the CPU's attack dice. */
    private fun dice(potion: Double, vararg cpu: Roll) = ScriptedRandom(listOf(potion) + cpu.flatMap { listOf(it.miss, it.variance, it.crit) })

    @Test
    fun aFightStartsWithTwoPotions() {
        assertEquals(2, FightTuning.POTIONS_PER_FIGHT)
        val s = startFight(PetState(PetId.JASON), PetId.FREDDY, ScriptedRandom(listOf(0.5, 0.5), ints = listOf(0, 0)))
        assertEquals(2, s.playerPotions)
    }

    @Test
    fun theLowestRollHealsHalfTheMaxHp() {
        val s = stateOf(player = fighter(hp = 30, maxHp = 100))
        val r = resolveTurn(s, Move.POTION, Move.BLOCK, dice(0.0))
        assertEquals(listOf(Heal(Side.PLAYER, 50)), r.events)
        assertEquals(80, r.state.player.hp)
        assertEquals(1, r.state.playerPotions)
    }

    @Test
    fun theHighestRollHealsAllOfItButNeverPastFull() {
        val s = stateOf(player = fighter(hp = 30, maxHp = 100))
        val r = resolveTurn(s, Move.POTION, Move.BLOCK, dice(0.999))
        // round(100 * 0.9995) = 100 rolled, but only the 70 missing HP can be restored: that is what it shows.
        assertEquals(listOf(Heal(Side.PLAYER, 70)), r.events)
        assertEquals(100, r.state.player.hp)
    }

    @Test
    fun aMiddleRollHealsInBetween() {
        val s = stateOf(player = fighter(hp = 10, maxHp = 80))
        val r = resolveTurn(s, Move.POTION, Move.BLOCK, dice(0.5))
        assertEquals(listOf(Heal(Side.PLAYER, 60)), r.events) // round(80 * 0.75)
        assertEquals(70, r.state.player.hp)
    }

    @Test
    fun theHealLandsBeforeTheOpponentsHitSoAPotionCanSaveYou() {
        // 10 HP would not survive a 16-damage STRIKE; drinking first, it ends the turn at 10 + 50 - 16.
        val s = stateOf(player = fighter(hp = 10, maxHp = 100))
        val r = resolveTurn(s, Move.POTION, Move.STRIKE, dice(0.0, Roll()))
        assertEquals(listOf(Heal(Side.PLAYER, 50), Hit(Side.CPU, Move.STRIKE, 16, crit = false)), r.events)
        assertEquals(44, r.state.player.hp)
        assertNull(r.state.winner)
    }

    @Test
    fun drinkingIsNotAnAttackSoAHeavyIsNotInterruptedAndABlockDoesNothing() {
        val heavy = resolveTurn(stateOf(player = fighter(hp = 50, maxHp = 100)), Move.POTION, Move.HEAVY, dice(0.0, Roll()))
        assertEquals(listOf(Heal(Side.PLAYER, 50), Hit(Side.CPU, Move.HEAVY, 29, crit = false)), heavy.events)
        assertEquals(100 - 29, heavy.state.player.hp)
        // The CPU's BLOCK meets no STRIKE: no counter, and no dice beyond the potion's.
        val rng = dice(0.0)
        val block = resolveTurn(stateOf(player = fighter(hp = 50, maxHp = 100)), Move.POTION, Move.BLOCK, rng)
        assertEquals(listOf(Heal(Side.PLAYER, 50)), block.events)
        assertEquals(0, rng.remainingDoubles)
    }

    @Test
    fun aPotionLeavesTheMetersAndIsRememberedInTheHistory() {
        val s = stateOf(player = fighter(hp = 40, meter = 50), cpu = fighter(meter = 25))
        val r = resolveTurn(s, Move.POTION, Move.BLOCK, dice(0.0))
        assertEquals(50, r.state.player.meter)
        assertEquals(25, r.state.cpu.meter)
        assertEquals(listOf(Move.POTION), r.state.playerHistory)
        assertEquals(2, r.state.turn)
    }

    @Test
    fun theCpuNeverReadsPotionsAsAHabit() {
        // Only potions so far: no habit, so the CPU goes straight to its weighted pick.
        val s = stateOf(history = listOf(Move.POTION, Move.POTION))
        assertEquals(Move.HEAVY, cpuPick(s, ScriptedRandom(ints = listOf(FightTuning.CPU_WEIGHT_STRIKE))))
    }

    @Test
    fun noPotionsLeftIsRejected() {
        val s = stateOf(player = fighter(hp = 40)).copy(playerPotions = 0)
        assertThrows(IllegalArgumentException::class.java) { resolveTurn(s, Move.POTION, Move.BLOCK, dice(0.0)) }
    }

    @Test
    fun onlyThePlayerDrinks() {
        assertThrows(IllegalArgumentException::class.java) { resolveTurn(stateOf(), Move.STRIKE, Move.POTION, dice(0.0)) }
    }
}
