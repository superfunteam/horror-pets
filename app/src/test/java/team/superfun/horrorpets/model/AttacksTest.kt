package team.superfun.horrorpets.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightTuning

/** The learnable attacks: who learns what, when, and how hard it hits (docs/superpowers/specs/2026-10-22-learnable-attacks-design.md). */
class AttacksTest {
    @Test
    fun everyKillerHasFourTiersInEachSlotAtTheUnlockLevels() {
        assertEquals(PetId.entries.size * 3 * 4, Attacks.size)
        for (pet in PetId.entries) {
            for (slot in Slot.entries) {
                val tiers = attacksOf(pet, slot)
                assertEquals("$pet $slot", listOf(0, 1, 2, 3), tiers.map { it.tier })
                assertEquals("$pet $slot", UNLOCK_LEVELS.getValue(slot), tiers.map { it.level })
                assertEquals("$pet $slot", ATTACK_POWER.getValue(slot), tiers.map { it.power })
            }
        }
        assertEquals(listOf(1, 2, 6, 10), UNLOCK_LEVELS[Slot.LIGHT])
        assertEquals(listOf(1, 3, 7, 12), UNLOCK_LEVELS[Slot.HEAVY])
        assertEquals(listOf(1, 4, 8, 14), UNLOCK_LEVELS[Slot.SPECIAL])
    }

    @Test
    fun theStartingAttacksHitAsTheGameAlwaysHas() {
        for (pet in PetId.entries) {
            val kit = Kit.base(pet)
            assertEquals(FightTuning.STRIKE_MULT, kit.light.power, 0.0)
            assertEquals(FightTuning.HEAVY_MULT, kit.heavy.power, 0.0)
            assertEquals(FightTuning.SPECIAL_MULT, kit.special.power, 0.0)
            assertTrue(kit.light.traits.isEmpty() && kit.heavy.traits.isEmpty())
            assertEquals("$pet's special keeps its name", pet.spec.special.displayName, kit.special.name)
            assertEquals(specialTraits(pet.spec.special.effect, 0), kit.special.traits)
        }
        assertEquals(listOf(Trait.Bleed(3, 0.06)), Kit.base(PetId.JASON).special.traits)
        assertEquals(listOf(Trait.Drain(0.5)), Kit.base(PetId.FREDDY).special.traits)
        assertEquals(listOf(Trait.Daze(1.0)), Kit.base(PetId.PINHEAD).special.traits)
    }

    @Test
    fun tierOneIsAPlainUpgradeAndTiersTwoAndThreeHaveAPerkAndACatch() {
        for (a in Attacks.filter { it.slot != Slot.SPECIAL }) {
            when (a.tier) {
                0, 1 -> assertTrue("${a.name} has no traits", a.traits.isEmpty())
                else -> {
                    assertEquals("${a.name}: a perk and a catch", 2, a.traits.size)
                    val catches = a.traits.filter { (it is Trait.Accuracy && it.change < 0) || (it is Trait.Meter && it.gain < FightTuning.METER_PER_HIT) }
                    assertEquals("${a.name}: one catch", 1, catches.size)
                }
            }
        }
    }

    @Test
    fun specialsLeaveMoreAsTheyTierUp() {
        assertEquals(
            listOf(Trait.Bleed(3, 0.06), Trait.Bleed(3, 0.07), Trait.Bleed(4, 0.07), Trait.Bleed(4, 0.09)),
            attacksOf(PetId.LEATHERFACE, Slot.SPECIAL).map { it.traits.single() },
        )
        assertEquals(listOf(0.5, 0.6, 0.7, 0.85), attacksOf(PetId.FREDDY, Slot.SPECIAL).map { it.drain })
        val chucky = attacksOf(PetId.CHUCKY, Slot.SPECIAL)
        assertTrue(chucky.all { it.daze == 1.0 })
        assertEquals(listOf(0, 25, 50, FightTuning.METER_MAX), chucky.map { it.shatter })
    }

    @Test
    fun namesAreUniqueAndFitTheCallouts() {
        assertEquals(Attacks.size, Attacks.map { it.name }.toSet().size)
        assertEquals(Attacks.size, Attacks.map { it.id }.toSet().size)
        // The longest, "Welcome to Prime Time" and friends: every screen that shows a name is laid out for this.
        assertTrue(Attacks.maxOf { it.name.length } <= LONGEST_ATTACK_NAME)
        assertEquals("jason_heavy_2", attacksOf(PetId.JASON, Slot.HEAVY)[2].id)
        assertEquals("Crystal Lake Crush", attackById("jason_heavy_2")?.name)
        assertNull(attackById("jason_heavy_9"))
    }

    @Test
    fun aCpuFightsWithTheBestItHasAtItsLevel() {
        assertEquals(Kit.base(PetId.MICHAEL), Kit.best(PetId.MICHAEL, 1))
        val seven = Kit.best(PetId.MICHAEL, 7)
        assertEquals(listOf(2, 2, 1), listOf(seven.light.tier, seven.heavy.tier, seven.special.tier))
        val top = Kit.best(PetId.MICHAEL, 99)
        assertEquals(listOf(3, 3, 3), listOf(top.light.tier, top.heavy.tier, top.special.tier))
    }

    @Test
    fun aPetFightsWithWhatItHasEquippedIfItMayUseIt() {
        val pet = PetState(PetId.JASON, level = 7, known = setOf("jason_light_1", "jason_heavy_2"))
        assertEquals(Kit.base(PetId.JASON), kitOf(pet))
        val armed = pet.copy(equipped = mapOf(Slot.LIGHT to "jason_light_1", Slot.HEAVY to "jason_heavy_2"))
        assertEquals("Machete Swipe", kitOf(armed).light.name)
        assertEquals("Crystal Lake Crush", kitOf(armed).heavy.name)
        // Not learned, too high a level, another killer's, the wrong slot, or no such attack: the starting one.
        for (bad in listOf("jason_light_2", "jason_light_3", "freddy_light_1", "jason_heavy_1", "nope")) {
            assertEquals(bad, Kit.base(PetId.JASON).light, kitOf(pet.copy(level = 7, equipped = mapOf(Slot.LIGHT to bad))).light)
        }
        assertEquals("a starting attack is always usable", "Machete Jab", kitOf(armed.copy(equipped = mapOf(Slot.LIGHT to "jason_light_0"))).light.name)
    }

    @Test
    fun theLearnQueueIsEveryUnlockedAttackNotYetOfferedInLevelOrder() {
        assertTrue(pendingAttacks(PetState(PetId.JASON, level = 1)).isEmpty())
        val seven = PetState(PetId.JASON, level = 7)
        assertEquals(
            listOf("jason_light_1", "jason_heavy_1", "jason_special_1", "jason_light_2", "jason_heavy_2"),
            pendingAttacks(seven).map { it.id },
        )
        val answered = seven.copy(known = setOf("jason_light_1", "jason_heavy_1", "jason_special_1"))
        assertEquals(listOf("jason_light_2", "jason_heavy_2"), pendingAttacks(answered).map { it.id })
    }
}
