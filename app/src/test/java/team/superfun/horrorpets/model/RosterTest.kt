package team.superfun.horrorpets.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightTuning

class RosterTest {
    @Test
    fun everyPetHasExactlyOneEntryInEnumOrder() {
        assertEquals(PetId.entries, Roster.keys.toList())
        for ((id, spec) in Roster) assertEquals(id, spec.id)
        for (id in PetId.entries) assertEquals(id, id.spec.id)
    }

    @Test
    fun theNewKillersComeLastInOrder() {
        assertEquals(listOf(PetId.CHUCKY, PetId.MICHAEL), PetId.entries.drop(4))
    }

    @Test
    fun specialNamesAreTheSignatureMoves() {
        assertEquals(
            listOf("Machete Frenzy", "Dream Claws", "Chains", "Knife Flurry", "Voodoo Stab", "The Shape"),
            PetId.entries.map { it.spec.special.displayName },
        )
    }

    @Test
    fun everyPetHasItsOwnSpecialEffect() {
        assertEquals(PetId.entries.size, PetId.entries.map { it.spec.special }.toSet().size)
    }

    @Test
    fun passivesComeFromFightTuning() {
        assertEquals(FightTuning.JASON_HP_MULT, PetId.JASON.spec.passive.hpMult, 0.0)
        assertEquals(FightTuning.FREDDY_CRIT_BONUS, PetId.FREDDY.spec.passive.critBonus, 0.0)
        assertEquals(FightTuning.PINHEAD_COUNTER_MULT, PetId.PINHEAD.spec.passive.counterMult, 0.0)
        assertEquals(FightTuning.GHOSTFACE_EVADE_BONUS, PetId.GHOSTFACE.spec.passive.evadeBonus, 0.0)
        assertEquals(FightTuning.CHUCKY_STRIKE_MULT, PetId.CHUCKY.spec.passive.strikeMult, 0.0)
        assertEquals(FightTuning.MICHAEL_DAMAGE_TAKEN_MULT, PetId.MICHAEL.spec.passive.damageTakenMult, 0.0)
        assertEquals(1.15, FightTuning.CHUCKY_STRIKE_MULT, 0.0)
        assertEquals(0.85, FightTuning.MICHAEL_DAMAGE_TAKEN_MULT, 0.0)
    }

    @Test
    fun theNewPassivesAreNamed() {
        assertEquals("Scrappy", PetId.CHUCKY.spec.passive.name)
        assertEquals("Relentless", PetId.MICHAEL.spec.passive.name)
        assertTrue(PetId.entries.all { it.spec.passive.name.isNotBlank() })
    }

    @Test
    fun eachPassiveTouchesOnlyItsOwnNumber() {
        val neutral = Passive(name = "x")
        fun Passive.changes(): Int = listOf(
            hpMult != neutral.hpMult,
            critBonus != neutral.critBonus,
            counterMult != neutral.counterMult,
            evadeBonus != neutral.evadeBonus,
            strikeMult != neutral.strikeMult,
            damageTakenMult != neutral.damageTakenMult,
        ).count { it }
        for (id in PetId.entries) assertEquals("$id", 1, id.spec.passive.changes())
        assertEquals(FightTuning.COUNTER_MULT, neutral.counterMult, 0.0)
    }

    @Test
    fun michaelWalksSlowerThanEveryoneElse() {
        assertEquals(0.6f, PetId.MICHAEL.spec.walkSpeed, 0f)
        for (id in PetId.entries - PetId.MICHAEL) assertEquals("$id", 1f, id.spec.walkSpeed, 0f)
    }
}
