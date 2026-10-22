package team.superfun.horrorpets.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightEvent.Bleed
import team.superfun.horrorpets.fight.FightEvent.Drain
import team.superfun.horrorpets.fight.FightEvent.Hit
import team.superfun.horrorpets.fight.FightEvent.Miss
import team.superfun.horrorpets.fight.FightEvent.SpecialReady
import team.superfun.horrorpets.model.Kit
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.SpecialEffect
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.attackById

/** Learned attacks in a fight: their power, perks and catches (see [resolveTurn]). */
class AttackEngineTest {
    /** [pet]'s fighter with the attack [id] equipped in its slot (strength 64, so a ×1 hit is 16). */
    private fun armed(pet: PetId, id: String, hp: Int = 100, meter: Int = 0, mood: Int = 0): Fighter {
        val a = attackById(id)!!
        val base = Kit.base(pet)
        val kit = when (a.slot) {
            Slot.LIGHT -> base.copy(light = a)
            Slot.HEAVY -> base.copy(heavy = a)
            Slot.SPECIAL -> base.copy(special = a)
        }
        return fighter(id = pet, hp = hp, meter = meter, mood = mood).copy(kit = kit)
    }

    private fun hitOf(r: TurnResult, side: Side = Side.PLAYER) = r.events.filterIsInstance<Hit>().single { it.attacker == side }

    @Test
    fun aLearnedAttackHitsWithItsPower() {
        // Machete Swipe ×1.15 against a BLOCK-less HEAVY trade... a STRIKE into a HEAVY interrupts it: 16 × 1.15 = 18.4.
        val r = resolveTurn(stateOf(player = armed(PetId.JASON, "jason_light_1")), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(18, hitOf(r).damage)
        // Sleeping Bag Slam ×2.0 breaks a block: 32.
        val heavy = resolveTurn(stateOf(player = armed(PetId.JASON, "jason_heavy_1")), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(32, hitOf(heavy).damage)
        // Ki Ki Ma Ma ×3.0: 48.
        val special = resolveTurn(
            stateOf(player = armed(PetId.JASON, "jason_special_2", meter = FightTuning.METER_MAX)),
            Move.SPECIAL,
            Move.BLOCK,
            ScriptedRandom.attacks(Roll()),
        )
        assertEquals(48, hitOf(special).damage)
    }

    @Test
    fun theKillersPassiveStillMultipliesItsSlot() {
        // Leatherface's heavies are +20%: Texas Chainsaw is 16 × 2.45 × 1.2 = 47.04. Its 30% daze rolls a fourth die.
        val r = resolveTurn(
            stateOf(player = armed(PetId.LEATHERFACE, "leatherface_heavy_3")),
            Move.HEAVY,
            Move.BLOCK,
            ScriptedRandom(listOf(Roll.NO_MISS, Roll.NEUTRAL, Roll.NO_CRIT, 0.99)),
        )
        assertEquals(47, hitOf(r).damage)
    }

    @Test
    fun anAccuracyCatchMissesMore() {
        // Lakeside Hack: a STRIKE's 8% plus 4%.
        val hack = armed(PetId.JASON, "jason_light_2")
        assertTrue(resolveTurn(stateOf(player = hack), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(miss = 0.119))).events.first() is Miss)
        assertTrue(resolveTurn(stateOf(player = hack), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(miss = 0.12))).events.first() is Hit)
        // Pin Prick is more precise: 8% − 5%.
        val prick = armed(PetId.PINHEAD, "pinhead_light_2")
        assertTrue(resolveTurn(stateOf(player = prick), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(miss = 0.05))).events.first() is Hit)
    }

    @Test
    fun aCritPerkCritsMore() {
        // Freddy at mood 0: 5% + his 8% + Nightmare Scratch's 10%.
        val scratch = armed(PetId.FREDDY, "freddy_light_2")
        assertTrue(hitOf(resolveTurn(stateOf(player = scratch), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(crit = 0.229)))).crit)
        assertFalse(hitOf(resolveTurn(stateOf(player = scratch), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(crit = 0.23)))).crit)
    }

    @Test
    fun meterPerksFillTheSpecialFasterOrSlower() {
        var s = stateOf(player = armed(PetId.GHOSTFACE, "ghostface_light_2"), cpu = fighter(hp = 500))
        val meters = mutableListOf<Int>()
        repeat(3) {
            val r = resolveTurn(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
            meters += r.state.player.meter
            if (it == 2) assertTrue("ready on the third hit", r.events.any { e -> e is SpecialReady && e.side == Side.PLAYER })
            s = r.state
        }
        assertEquals(listOf(34, 68, 100), meters)

        val slow = resolveTurn(stateOf(player = armed(PetId.FREDDY, "freddy_light_2")), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(20, slow.state.player.meter)
    }

    @Test
    fun aBleedPerkBleedsTheTarget() {
        val r = resolveTurn(stateOf(player = armed(PetId.JASON, "jason_light_3")), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(SpecialEffect.BLEED, hitOf(r).effect)
        assertEquals(2, r.state.cpu.bleed)
        assertEquals(FightTuning.BLEED_SHARE, r.state.cpu.bleedShare, 0.0)
    }

    @Test
    fun aDazeChanceRollsAFourthDieOnlyWhenItLands() {
        val crush = armed(PetId.JASON, "jason_heavy_2") // 25%
        val dazed = resolveTurn(stateOf(player = crush), Move.HEAVY, Move.BLOCK, ScriptedRandom(listOf(Roll.NO_MISS, Roll.NEUTRAL, Roll.NO_CRIT, 0.249)))
        assertTrue(dazed.state.cpu.dazed)
        assertEquals(SpecialEffect.DAZE, hitOf(dazed).effect)
        val shrugged = resolveTurn(stateOf(player = crush), Move.HEAVY, Move.BLOCK, ScriptedRandom(listOf(Roll.NO_MISS, Roll.NEUTRAL, Roll.NO_CRIT, 0.25)))
        assertFalse(shrugged.state.cpu.dazed)
        assertEquals(null, hitOf(shrugged).effect)
        // A miss rolls no fourth die (the script would run out if it did).
        val missed = resolveTurn(stateOf(player = crush), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertFalse(missed.state.cpu.dazed)
    }

    @Test
    fun aDrainPerkHealsTheAttacker() {
        // Dream Demon Rake: 16 × 1.45 = 23.2, a quarter back is 6.
        val rake = armed(PetId.FREDDY, "freddy_light_3", hp = 50).copy(maxHp = 100)
        val r = resolveTurn(stateOf(player = rake), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf(Drain(Side.PLAYER, 6)), r.events.filterIsInstance<Drain>())
        assertEquals(56, r.state.player.hp)
    }

    @Test
    fun aShatterKnocksTheTargetsMeterBack() {
        val agony = armed(PetId.PINHEAD, "pinhead_light_3")
        val r = resolveTurn(stateOf(player = agony, cpu = fighter(meter = 50)), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(25, hitOf(r).shattered)
        assertEquals(25, r.state.cpu.meter)
        val low = resolveTurn(stateOf(player = agony, cpu = fighter(meter = 10)), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals("never more than it had", 10, hitOf(low).shattered)
        assertEquals(0, low.state.cpu.meter)
    }

    @Test
    fun aMeterShatteredAsItFillsIsNotAnnounced() {
        // Pinhead's Lament Configuration (daze, shatter 25) lands as the CPU's strike fills its meter from 75.
        val pinhead = armed(PetId.PINHEAD, "pinhead_special_1", meter = FightTuning.METER_MAX)
        val r = resolveTurn(stateOf(player = pinhead, cpu = fighter(meter = 75)), Move.SPECIAL, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(75, r.state.cpu.meter)
        assertTrue(r.events.none { it is SpecialReady })
        assertTrue(r.state.cpu.dazed)
    }

    @Test
    fun theTopSpecialsHitHardestAndLeaveTheMost() {
        val massacre = armed(PetId.LEATHERFACE, "leatherface_special_3", meter = FightTuning.METER_MAX)
        val r = resolveTurn(stateOf(player = massacre), Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(54, hitOf(r).damage) // 16 × 3.4
        assertEquals(4, r.state.cpu.bleed)
        assertEquals(0.09, r.state.cpu.bleedShare, 1e-9)
        val next = resolveTurn(r.state, Move.BLOCK, Move.BLOCK, ScriptedRandom())
        assertEquals(listOf(Bleed(Side.CPU, 9)), next.events) // 9% of 100

        val friends = armed(PetId.CHUCKY, "chucky_special_3", meter = FightTuning.METER_MAX)
        val emptied = resolveTurn(stateOf(player = friends, cpu = fighter(meter = 75)), Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(0, emptied.state.cpu.meter)
        assertEquals(75, hitOf(emptied).shattered)
    }

    @Test
    fun aWeakerBleedNeverCutsAStrongerOneShort() {
        val s = stateOf(player = armed(PetId.JASON, "jason_light_3"), cpu = fighter().copy(bleed = 4, bleedShare = 0.09))
        val r = resolveTurn(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(3, r.state.cpu.bleed) // its own 4 less a turn beats the new 2
        assertEquals(0.09, r.state.cpu.bleedShare, 1e-9)
    }

    @Test
    fun aCounterIsTheSameWhateverTheKit() {
        val hack = armed(PetId.JASON, "jason_light_3")
        val r = resolveTurn(stateOf(player = hack), Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        val counter = hitOf(r)
        assertTrue(counter.counter)
        assertEquals(8, counter.damage) // 16 × 0.5, no ×1.45, no bleed
        assertEquals(null, counter.effect)
        assertEquals(25, r.state.player.meter)
    }

    @Test
    fun fightersComeWithTheirAttacks() {
        val pet = PetState(PetId.JASON, level = 7, known = setOf("jason_heavy_2"), equipped = mapOf(Slot.HEAVY to "jason_heavy_2"))
        assertEquals("Crystal Lake Crush", buildFighter(pet).kit.heavy.name)
        assertEquals("Machete Jab", buildFighter(pet).kit.light.name)
        // A CPU fights with the best it has at its level: level 12 (11 + 1) has every tier 3 heavy.
        val cpu = buildOpponent(PetId.LEATHERFACE, PetState(PetId.JASON, level = 11), ScriptedRandom(ints = listOf(1, 0)))
        assertEquals(12, cpu.level)
        assertEquals("Texas Chainsaw", cpu.kit.heavy.name)
        assertEquals("Butcher's Cut", cpu.kit.light.name)
        assertEquals("Family Dinner", cpu.kit.special.name)
    }
}
