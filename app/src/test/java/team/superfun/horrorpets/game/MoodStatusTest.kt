package team.superfun.horrorpets.game

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class MoodStatusTest {
    private fun pet(hunger: Double = 20.0, fn: Double = 80.0, bonus: Double = 0.0) =
        PetState(PetId.JASON, hunger = hunger, funLevel = fn, moodBonus = bonus)

    // ---- mood ----

    @Test
    fun defaultPetMoodIs80() {
        // 0.4 * (100 - 20) + 0.6 * 80 = 32 + 48
        assertEquals(80, mood(pet(hunger = 20.0, fn = 80.0, bonus = 0.0)))
    }

    @Test
    fun moodAddsBonusAndRounds() {
        // 0.4 * 50 + 0.6 * 50 + 5 = 55
        assertEquals(55, mood(pet(hunger = 50.0, fn = 50.0, bonus = 5.0)))
        // 0.4 * 63 + 0.6 * 41 = 25.2 + 24.6 = 49.8 -> 50
        assertEquals(50, mood(pet(hunger = 37.0, fn = 41.0)))
    }

    @Test
    fun moodClampsTo0And100() {
        assertEquals(100, mood(pet(hunger = 0.0, fn = 100.0, bonus = 30.0)))
        assertEquals(0, mood(pet(hunger = 100.0, fn = 0.0, bonus = -30.0)))
    }

    // ---- status ----

    @Test
    fun contentWhenHealthy() {
        assertEquals(StatusTag.CONTENT, statusOf(pet(hunger = 20.0, fn = 80.0)))
    }

    @Test
    fun boredWhenFunIsLow() {
        // hunger 20 -> mood = 32 + 0.6*30 = 50, so not grumpy.
        assertEquals(StatusTag.BORED, statusOf(pet(hunger = 20.0, fn = 30.0)))
        assertEquals(StatusTag.CONTENT, statusOf(pet(hunger = 20.0, fn = 31.0)))
    }

    @Test
    fun hungryAtSixty() {
        // fun 100 keeps mood well above 30: 0.4*40 + 60 = 76
        assertEquals(StatusTag.HUNGRY, statusOf(pet(hunger = 60.0, fn = 100.0)))
        assertEquals(StatusTag.CONTENT, statusOf(pet(hunger = 59.9, fn = 100.0)))
    }

    @Test
    fun starvingAtEightyFive() {
        assertEquals(StatusTag.STARVING, statusOf(pet(hunger = 85.0, fn = 100.0)))
        assertEquals(StatusTag.HUNGRY, statusOf(pet(hunger = 84.9, fn = 100.0)))
    }

    @Test
    fun grumpyWhenMoodBelowThirty() {
        // hunger 50, fun 20 -> 0.4*50 + 0.6*20 = 32 -> not grumpy (and BORED, fun <= 30)
        assertEquals(StatusTag.BORED, statusOf(pet(hunger = 50.0, fn = 20.0)))
        // hunger 55, fun 10 -> 18 + 6 = 24 -> GRUMPY (hunger < 60 so not HUNGRY)
        assertEquals(StatusTag.GRUMPY, statusOf(pet(hunger = 55.0, fn = 10.0)))
        // mood of exactly 30 is not grumpy: 0.4*50 + 0.6*0 + 10 = 30 (and fun 0 makes it BORED)
        assertEquals(StatusTag.BORED, statusOf(pet(hunger = 50.0, fn = 0.0, bonus = 10.0)))
    }

    @Test
    fun starvingBeatsEverything() {
        assertEquals(StatusTag.STARVING, statusOf(pet(hunger = 90.0, fn = 0.0)))
    }

    @Test
    fun grumpyBeatsHungryAndBored() {
        // hunger 70, fun 0 -> mood 12: grumpy outranks hungry (and bored).
        assertEquals(StatusTag.GRUMPY, statusOf(pet(hunger = 70.0, fn = 0.0)))
    }

    @Test
    fun hungryBeatsBored() {
        // hunger 65, fun 30 -> mood 14 + 18 = 32 (not grumpy); hungry outranks bored.
        assertEquals(StatusTag.HUNGRY, statusOf(pet(hunger = 65.0, fn = 30.0)))
    }

    @Test
    fun asleepRanksUnderStarvingAndOverTheRest() {
        // STARVING > ASLEEP > GRUMPY > HUNGRY > BORED > CONTENT: a starving killer is never left to sleep it off.
        assertEquals(StatusTag.STARVING, statusOf(pet(hunger = 90.0, fn = 0.0), asleep = true))
        // Each of these is what the killer would be awake (see the tests above).
        assertEquals(StatusTag.ASLEEP, statusOf(pet(hunger = 70.0, fn = 0.0), asleep = true)) // grumpy
        assertEquals(StatusTag.ASLEEP, statusOf(pet(hunger = 65.0, fn = 30.0), asleep = true)) // hungry
        assertEquals(StatusTag.ASLEEP, statusOf(pet(hunger = 20.0, fn = 30.0), asleep = true)) // bored
        assertEquals(StatusTag.ASLEEP, statusOf(pet(hunger = 20.0, fn = 80.0), asleep = true)) // content
        // Awake unless told otherwise: the switcher's chips never say ASLEEP.
        assertEquals(StatusTag.CONTENT, statusOf(pet(hunger = 20.0, fn = 80.0)))
    }
}
