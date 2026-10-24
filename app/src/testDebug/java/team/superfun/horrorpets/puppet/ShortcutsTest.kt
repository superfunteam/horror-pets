package team.superfun.horrorpets.puppet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.fighter
import team.superfun.horrorpets.fight.stateOf
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.challengerDue
import team.superfun.horrorpets.model.kitOf
import team.superfun.horrorpets.model.pendingAttacks

class ShortcutsTest {
    private val freddy = PetState(PetId.FREDDY, hunger = 55.0, level = 6, wins = 3)

    @Test
    fun unlockAllKeepsTheStableAndAddsTheRestAtTheirDefaults() {
        val game = GameState(pets = mapOf(PetId.FREDDY to freddy), selected = PetId.FREDDY).withAllUnlocked()
        assertEquals(PetId.entries.filterNot { it.secret }.toSet(), game.pets.keys)
        assertEquals(freddy, game.pets[PetId.FREDDY])
        assertEquals(PetState(PetId.PINHEAD), game.pets[PetId.PINHEAD])
        assertEquals(PetId.FREDDY, game.selected)
        // A new game gets someone to play.
        assertEquals(PetId.JASON, GameState().withAllUnlocked().selected)
    }

    @Test
    fun unlockAllLeavesTheSecretKillerSoTheNextFightBringsHimOn() {
        val game = GameState(pets = mapOf(PetId.FREDDY to freddy), selected = PetId.FREDDY).withAllUnlocked()
        assertFalse(PetId.JAWS in game.pets)
        assertEquals(PetId.JAWS, game.challengerDue())
        // The stable stays in roster order, and one already in it (the puppet's `pet jaws`) stays.
        assertEquals(PetId.entries.filterNot { it.secret }, game.pets.keys.toList())
        val withHim = game.withActive(PetId.JAWS).withAllUnlocked()
        assertEquals(PetId.entries, withHim.pets.keys.toList())
        assertEquals(PetId.JAWS, withHim.selected)
    }

    @Test
    fun aPetIsUnlockedAsItIsMadeActive() {
        val game = GameState(pets = mapOf(PetId.FREDDY to freddy), selected = PetId.FREDDY)
        val pinhead = game.withActive(PetId.PINHEAD)
        assertEquals(PetId.PINHEAD, pinhead.selected)
        assertEquals(PetState(PetId.PINHEAD), pinhead.pets[PetId.PINHEAD])
        assertEquals(freddy, pinhead.pets[PetId.FREDDY])
        // One already in the stable keeps its state.
        assertEquals(freddy, pinhead.withActive(PetId.FREDDY).pets[PetId.FREDDY])
    }

    @Test
    fun aLevelIsHeldToTheGamesAndStartsWithNoXp() {
        assertEquals(14, freddy.copy(xp = 250).atLevel(14).level)
        assertEquals(0, freddy.copy(xp = 250).atLevel(14).xp)
        assertEquals(Tuning.MAX_LEVEL, freddy.atLevel(500).level)
        assertEquals(1, freddy.atLevel(0).level)
    }

    @Test
    fun learnAllLearnsWhatTheLevelUnlocksAndEquipsTheBest() {
        val pet = PetState(PetId.FREDDY, level = 14).withEverythingLearned()
        assertEquals(emptyList<Any>(), pendingAttacks(pet))
        assertEquals(9, pet.known.size)
        val kit = kitOf(pet)
        for (slot in Slot.entries) assertEquals("$slot", 3, kit.of(slot).tier)

        // Level 7: light tier 2 (level 6), heavy tier 2 (level 7), special tier 1 (level 4).
        val mid = kitOf(PetState(PetId.FREDDY, level = 7).withEverythingLearned())
        assertEquals(listOf(2, 2, 1), Slot.entries.map { mid.of(it).tier })
    }

    @Test
    fun learnAllAtLevelOneLeavesTheStartingAttacks() {
        val pet = PetState(PetId.JASON).withEverythingLearned()
        assertTrue(pet.known.isEmpty())
        assertTrue(pet.equipped.isEmpty())
    }

    @Test
    fun meterFullFillsOnlyThePlayers() {
        val f = stateOf(player = fighter(meter = 20), cpu = fighter(meter = 40)).withFullMeter()
        assertEquals(FightTuning.METER_MAX, f.player.meter)
        assertEquals(40, f.cpu.meter)
    }
}
