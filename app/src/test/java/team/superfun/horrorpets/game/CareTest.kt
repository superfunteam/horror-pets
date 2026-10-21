package team.superfun.horrorpets.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class CareTest {
    private fun pet(
        hunger: Double = 20.0,
        fn: Double = 80.0,
        strength: Double = 20.0,
        level: Int = 1,
        xp: Int = 0,
        bonus: Double = 0.0,
    ) = PetState(PetId.JASON, hunger = hunger, funLevel = fn, strength = strength, level = level, xp = xp, moodBonus = bonus)

    private fun done(r: CareResult): CareResult.Done = r as CareResult.Done

    @Test
    fun feedLowersHunger() {
        val r = done(perform(CareAction.FEED, pet(hunger = 60.0)))
        assertEquals(25.0, r.pet.hunger, 1e-9)
        assertEquals(0, r.levelsGained)
    }

    @Test
    fun feedGivesNoXpAndChangesNothingElse() {
        val p = pet(hunger = 60.0, xp = 17)
        val r = done(perform(CareAction.FEED, p))
        assertEquals(p.copy(hunger = 25.0), r.pet)
    }

    @Test
    fun feedRefusesWhenFull() {
        assertEquals(CareResult.Refused(RefuseReason.TOO_FULL), perform(CareAction.FEED, pet(hunger = 9.0)))
    }

    @Test
    fun feedAllowedAtRefuseBoundary() {
        assertTrue(perform(CareAction.FEED, pet(hunger = 10.0)) is CareResult.Done)
    }

    @Test
    fun playRaisesFunAndHunger() {
        val r = done(perform(CareAction.PLAY, pet(hunger = 20.0, fn = 50.0)))
        assertEquals(75.0, r.pet.funLevel, 1e-9)
        assertEquals(28.0, r.pet.hunger, 1e-9)
        assertEquals(5.0, r.pet.moodBonus, 1e-9)
        assertEquals(5, r.pet.xp)
        assertEquals(0, r.levelsGained)
    }

    @Test
    fun playMoodBonusClampsAtThirty() {
        val r = done(perform(CareAction.PLAY, pet(bonus = 28.0)))
        assertEquals(30.0, r.pet.moodBonus, 1e-9)
    }

    @Test
    fun trainRaisesStrengthAndHunger() {
        val r = done(perform(CareAction.TRAIN, pet(hunger = 20.0, fn = 80.0, strength = 20.0)))
        assertEquals(26.0, r.pet.strength, 1e-9)
        assertEquals(32.0, r.pet.hunger, 1e-9)
        assertEquals(75.0, r.pet.funLevel, 1e-9)
        assertEquals(10, r.pet.xp)
    }

    @Test
    fun trainRefusesWhenStarving() {
        assertEquals(CareResult.Refused(RefuseReason.TOO_HUNGRY), perform(CareAction.TRAIN, pet(hunger = 85.0)))
    }

    @Test
    fun trainAllowedJustBelowRefuseThreshold() {
        val r = done(perform(CareAction.TRAIN, pet(hunger = 84.9)))
        assertEquals(96.9, r.pet.hunger, 1e-9)
    }

    @Test
    fun trainCanLevelUp() {
        val r = done(perform(CareAction.TRAIN, pet(level = 1, xp = 95)))
        assertEquals(2, r.pet.level)
        assertEquals(5, r.pet.xp)
        assertEquals(1, r.levelsGained)
    }

    @Test
    fun statsClamp() {
        // Feed below zero.
        assertEquals(0.0, done(perform(CareAction.FEED, pet(hunger = 20.0))).pet.hunger, 0.0)
        // Play past 100 on fun and hunger.
        val played = done(perform(CareAction.PLAY, pet(hunger = 98.0, fn = 90.0))).pet
        assertEquals(100.0, played.funLevel, 0.0)
        assertEquals(100.0, played.hunger, 0.0)
        // Train past 100 strength, below 0 fun, past 100 hunger.
        val trained = done(perform(CareAction.TRAIN, pet(hunger = 80.0, fn = 2.0, strength = 98.0))).pet
        assertEquals(100.0, trained.strength, 0.0)
        assertEquals(0.0, trained.funLevel, 0.0)
        assertEquals(92.0, trained.hunger, 1e-9)
    }

    @Test
    fun canFightFalseAt85() {
        assertFalse(canFight(pet(hunger = 85.0)))
        assertFalse(canFight(pet(hunger = 100.0)))
        assertTrue(canFight(pet(hunger = 84.9)))
        assertTrue(canFight(pet(hunger = 0.0)))
    }
}
