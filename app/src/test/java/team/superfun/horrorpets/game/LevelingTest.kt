package team.superfun.horrorpets.game

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class LevelingTest {
    private fun pet(level: Int = 1, xp: Int = 0) = PetState(PetId.FREDDY, level = level, xp = xp)

    @Test
    fun smallGainDoesNotLevel() {
        val r = addXp(pet(level = 1, xp = 10), 20)
        assertEquals(1, r.pet.level)
        assertEquals(30, r.pet.xp)
        assertEquals(0, r.levelsGained)
    }

    @Test
    fun crossingOneThresholdLevelsUp() {
        val r = addXp(pet(level = 1, xp = 90), 20)
        assertEquals(2, r.pet.level)
        assertEquals(10, r.pet.xp)
        assertEquals(1, r.levelsGained)
    }

    @Test
    fun exactThresholdLevelsUpWithZeroLeft() {
        val r = addXp(pet(level = 1, xp = 90), 10)
        assertEquals(2, r.pet.level)
        assertEquals(0, r.pet.xp)
        assertEquals(1, r.levelsGained)
    }

    @Test
    fun multipleLevelsInOneGrant() {
        // 100 for 1->2, 200 for 2->3: 350 - 100 - 200 = 50 left.
        val r = addXp(pet(level = 1, xp = 0), 350)
        assertEquals(3, r.pet.level)
        assertEquals(50, r.pet.xp)
        assertEquals(2, r.levelsGained)
    }

    @Test
    fun costGrowsWithLevel() {
        assertEquals(100, xpToNext(1))
        assertEquals(200, xpToNext(2))
        assertEquals(500, xpToNext(5))
        // Level 2 needs 200, so 150 total is not enough.
        val r = addXp(pet(level = 2, xp = 50), 100)
        assertEquals(2, r.pet.level)
        assertEquals(150, r.pet.xp)
        assertEquals(0, r.levelsGained)
    }

    @Test
    fun zeroOrNegativeAmountChangesNothing() {
        val p = pet(level = 3, xp = 120)
        assertEquals(XpResult(p, 0), addXp(p, 0))
        assertEquals(XpResult(p, 0), addXp(p, -50))
    }

    @Test
    fun otherFieldsPreserved() {
        val p = PetState(PetId.PINHEAD, hunger = 33.0, funLevel = 44.0, strength = 55.0, wins = 4, losses = 1)
        val r = addXp(p, 100)
        assertEquals(p.copy(level = 2, xp = 0), r.pet)
    }
}
