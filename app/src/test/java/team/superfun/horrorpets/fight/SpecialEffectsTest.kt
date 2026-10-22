package team.superfun.horrorpets.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightEvent.Bleed
import team.superfun.horrorpets.fight.FightEvent.Blocked
import team.superfun.horrorpets.fight.FightEvent.Drain
import team.superfun.horrorpets.fight.FightEvent.Hit
import team.superfun.horrorpets.fight.FightEvent.Interrupted
import team.superfun.horrorpets.fight.FightEvent.Ko
import team.superfun.horrorpets.fight.FightEvent.Miss
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.SpecialEffect
import team.superfun.horrorpets.model.spec

/** What specials leave behind: bleeds, drains and dazes (see [resolveTurn]). */
class SpecialEffectsTest {
    private val full = FightTuning.METER_MAX

    /** The player's [pet] lands its special on a CPU that blocks (which does nothing against it). */
    private fun special(pet: PetId, player: Fighter = fighter(id = pet, meter = full), cpu: Fighter = fighter()) =
        resolveTurn(stateOf(player = player, cpu = cpu), Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll()))

    @Test
    fun everyKillerHasItsEffect() {
        val expected = mapOf(
            PetId.JASON to SpecialEffect.BLEED,
            PetId.FREDDY to SpecialEffect.DRAIN,
            PetId.PINHEAD to SpecialEffect.DAZE,
            PetId.GHOSTFACE to SpecialEffect.BLEED,
            PetId.CHUCKY to SpecialEffect.DAZE,
            PetId.MICHAEL to SpecialEffect.DAZE,
            PetId.LEATHERFACE to SpecialEffect.BLEED,
        )
        assertEquals(PetId.entries.toSet(), expected.keys)
        for ((pet, effect) in expected) {
            assertEquals("$pet", effect, pet.spec.special.effect)
            val hit = special(pet).events.filterIsInstance<Hit>().single()
            assertEquals("$pet's special says what it leaves", effect, hit.effect)
        }
    }

    @Test
    fun onlySpecialsCarryAnEffect() {
        val r = resolveTurn(stateOf(), Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertTrue(r.events.filterIsInstance<Hit>().all { it.effect == null })
    }

    // ---- bleed ----

    @Test
    fun aBleedingSpecialBleedsTheTargetForThreeTurns() {
        val hit = special(PetId.JASON)
        assertEquals(3, hit.state.cpu.bleed)
        assertTrue("no bleed on the turn it lands", hit.events.none { it is Bleed })

        var s = hit.state
        val hp = mutableListOf<Int>()
        repeat(3) { turn ->
            val r = resolveTurn(s, Move.BLOCK, Move.BLOCK, ScriptedRandom())
            // 6% of 100 max HP.
            assertEquals("turn ${turn + 1}", listOf(Bleed(Side.CPU, 6)), r.events)
            assertEquals(2 - turn, r.state.cpu.bleed)
            hp += r.state.cpu.hp
            s = r.state
        }
        assertEquals(listOf(54, 48, 42), hp) // 100 - 40 from the special, then 6 a turn
        assertTrue("then it stops", resolveTurn(s, Move.BLOCK, Move.BLOCK, ScriptedRandom()).events.isEmpty())
    }

    @Test
    fun aNewBleedStartsTheCountAgainAndNeverStacks() {
        val r = special(PetId.LEATHERFACE, cpu = fighter(hp = 100).copy(bleed = 1))
        assertEquals(listOf(6), r.events.filterIsInstance<Bleed>().map { it.damage })
        assertEquals(3, r.state.cpu.bleed)
    }

    @Test
    fun aBleedIsAtLeastOneAndCanFinishAFight() {
        val small = stateOf(cpu = fighter(hp = 3, maxHp = 10).copy(bleed = 1))
        val r = resolveTurn(small, Move.BLOCK, Move.BLOCK, ScriptedRandom())
        // 6% of 10 rounds to 1.
        assertEquals(listOf(Bleed(Side.CPU, 1)), r.events)

        val last = stateOf(cpu = fighter(hp = 4, maxHp = 100).copy(bleed = 2))
        val ko = resolveTurn(last, Move.BLOCK, Move.BLOCK, ScriptedRandom())
        assertEquals(listOf(Bleed(Side.CPU, 4), Ko(Side.CPU)), ko.events)
        assertEquals(0, ko.state.cpu.hp)
        assertEquals(Side.PLAYER, ko.state.winner)
    }

    @Test
    fun bleedingComesAfterTheBlowsAndEndsNoCombo() {
        val s = stateOf(cpu = fighter(combo = 2).copy(bleed = 2))
        val r = resolveTurn(s, Move.BLOCK, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertTrue(r.events.last() is Bleed)
        assertEquals(3, r.state.cpu.combo)
    }

    @Test
    fun aKnockedOutFighterDoesNotBleed() {
        val s = stateOf(cpu = fighter(hp = 10).copy(bleed = 2))
        val r = resolveTurn(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertTrue(r.events.none { it is Bleed })
        assertEquals(listOf(Ko(Side.CPU)), r.events.filterIsInstance<Ko>())
    }

    // ---- drain ----

    @Test
    fun aDrainHealsHalfTheDamageBeforeTheOtherBlowLands() {
        // Freddy at 50/100 lands 40 (16 x 2.5) while the CPU strikes him for 16: +20, -16.
        val s = stateOf(player = fighter(id = PetId.FREDDY, hp = 50, maxHp = 100, meter = full))
        val r = resolveTurn(s, Move.SPECIAL, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(listOf(Drain(Side.PLAYER, 20)), r.events.filterIsInstance<Drain>())
        assertEquals(54, r.state.player.hp)
        assertTrue("the drain follows its hit", r.events.indexOfFirst { it is Drain } == r.events.indexOfFirst { it is Hit } + 1)
    }

    @Test
    fun aDrainNeverHealsPastFullAndSaysNothingAtFull() {
        val nearly = special(PetId.FREDDY, player = fighter(id = PetId.FREDDY, hp = 95, maxHp = 100, meter = full))
        assertEquals(listOf(Drain(Side.PLAYER, 5)), nearly.events.filterIsInstance<Drain>())
        assertEquals(100, nearly.state.player.hp)

        val topped = special(PetId.FREDDY)
        assertTrue(topped.events.none { it is Drain })
        assertEquals(100, topped.state.player.hp)
    }

    @Test
    fun theCpusDrainHealsTheCpu() {
        val s = stateOf(cpu = fighter(id = PetId.FREDDY, hp = 50, maxHp = 100, meter = full))
        val r = resolveTurn(s, Move.BLOCK, Move.SPECIAL, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf(Drain(Side.CPU, 20)), r.events.filterIsInstance<Drain>())
        assertEquals(70, r.state.cpu.hp)
    }

    @Test
    fun aDrainLeavesNoLastingEffect() {
        val r = special(PetId.FREDDY)
        assertEquals(0, r.state.cpu.bleed)
        assertFalse(r.state.cpu.dazed)
    }

    // ---- daze ----

    @Test
    fun aDazingSpecialSpoilsTheTargetsNextTurnOnly() {
        val r = special(PetId.PINHEAD)
        assertTrue(r.state.cpu.dazed)
        assertFalse(r.state.player.dazed)

        // Dazed, the CPU's STRIKE stumbles and draws nothing; the player's BLOCK has nothing to catch.
        val next = resolveTurn(r.state, Move.BLOCK, Move.STRIKE, ScriptedRandom())
        assertEquals(listOf(Miss(Side.CPU, Move.STRIKE, dazed = true)), next.events)
        assertFalse("one turn only", next.state.cpu.dazed)
    }

    @Test
    fun aDazedHeavyStumblesAndIsNotInterrupted() {
        val s = stateOf(cpu = fighter().copy(dazed = true))
        val r = resolveTurn(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 16, crit = false), r.events[0])
        assertEquals(Miss(Side.CPU, Move.HEAVY, dazed = true), r.events[1])
        assertTrue(r.events.none { it is Interrupted })
    }

    @Test
    fun aDazedStrikeCutsNothingOff() {
        val s = stateOf(player = fighter().copy(dazed = true))
        val r = resolveTurn(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(Miss(Side.PLAYER, Move.STRIKE, dazed = true), r.events[0])
        assertEquals(Hit(Side.CPU, Move.HEAVY, 29, crit = false), r.events[1])
        assertTrue(r.events.none { it is Interrupted })
    }

    @Test
    fun aDazedBlockGuardsNothing() {
        val s = stateOf(player = fighter().copy(dazed = true))
        val r = resolveTurn(s, Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf(Hit(Side.CPU, Move.STRIKE, 16, crit = false)), r.events)
        assertTrue(r.events.none { it is Blocked })
        assertEquals(84, r.state.player.hp)
    }

    @Test
    fun aDazedSideCanStillUseItsSpecialOrAPotion() {
        val special = resolveTurn(
            stateOf(player = fighter(meter = full).copy(dazed = true)),
            Move.SPECIAL,
            Move.BLOCK,
            ScriptedRandom.attacks(Roll()),
        )
        assertEquals(Move.SPECIAL, special.events.filterIsInstance<Hit>().single().move)

        val potion = resolveTurn(stateOf(player = fighter(hp = 50, maxHp = 100).copy(dazed = true)), Move.POTION, Move.BLOCK, ScriptedRandom(listOf(0.0)))
        assertEquals(100, potion.state.player.hp)
    }

    @Test
    fun aDazedWhiffEndsTheCombo() {
        val s = stateOf(player = fighter(combo = 2).copy(dazed = true))
        val r = resolveTurn(s, Move.HEAVY, Move.BLOCK, ScriptedRandom())
        assertEquals(0, r.state.player.combo)
        assertNull(r.state.winner)
    }
}
