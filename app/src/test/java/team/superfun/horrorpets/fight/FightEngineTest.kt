package team.superfun.horrorpets.fight

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightEvent.Blocked
import team.superfun.horrorpets.fight.FightEvent.Hit
import team.superfun.horrorpets.fight.FightEvent.Interrupted
import team.superfun.horrorpets.fight.FightEvent.Ko
import team.superfun.horrorpets.fight.FightEvent.Miss
import team.superfun.horrorpets.fight.FightEvent.SpecialReady
import team.superfun.horrorpets.game.mood
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.SpecialEffect

/**
 * Numbers used throughout: strength 64 gives base damage 8 + 64/8 = 16, so with neutral variance
 * and no crit a STRIKE does 16, a HEAVY round(28.8) = 29, a counter 8 (16 for Pinhead) and a
 * SPECIAL 40. A crit STRIKE does 28.
 */
class FightEngineTest {
    /** What the default fighter's (Jason's) Machete Frenzy leaves behind. */
    private val jasonsEffect = SpecialEffect.BLEED

    private fun resolve(
        s: FightState,
        player: Move,
        cpu: Move,
        rng: ScriptedRandom = ScriptedRandom(),
    ): TurnResult = resolveTurn(s, player, cpu, rng)

    // ---------------------------------------------------------------- building fighters

    @Test
    fun jasonHasMoreHp() {
        val jason = buildFighter(PetState(PetId.JASON, hunger = 0.0, level = 1))
        val freddy = buildFighter(PetState(PetId.FREDDY, hunger = 0.0, level = 1))
        assertEquals(82, jason.maxHp)
        assertEquals(68, freddy.maxHp)
    }

    @Test
    fun hungerReducesHp() {
        val starving = buildFighter(PetState(PetId.FREDDY, hunger = 100.0, level = 1))
        assertEquals(48, starving.maxHp) // round(68 * 0.7)
        val half = buildFighter(PetState(PetId.FREDDY, hunger = 50.0, level = 1))
        assertEquals(58, half.maxHp) // round(68 * 0.85)
    }

    @Test
    fun hpGrowsWithLevel() {
        assertEquals(60 + 8 * 7, buildFighter(PetState(PetId.PINHEAD, hunger = 0.0, level = 7)).maxHp)
    }

    @Test
    fun buildFighterStartsFullWithAnEmptyMeterAndTheLivePetsMood() {
        val pet = PetState(PetId.GHOSTFACE, hunger = 10.0, funLevel = 60.0, strength = 33.0, level = 3, moodBonus = 5.0)
        val f = buildFighter(pet)
        assertEquals(PetId.GHOSTFACE, f.id)
        assertEquals(3, f.level)
        assertEquals(f.maxHp, f.hp)
        assertEquals(33.0, f.strength, 0.0)
        assertEquals(mood(pet), f.mood)
        assertEquals(0, f.meter)
    }

    @Test
    fun opponentIsBuiltAroundThePlayer() {
        val player = PetState(PetId.JASON, level = 5, strength = 40.0)
        // level offset +1, strength offset -4
        val o = buildOpponent(PetId.FREDDY, player, ScriptedRandom(ints = listOf(1, -4)))
        assertEquals(PetId.FREDDY, o.id)
        assertEquals(6, o.level)
        assertEquals(36.0, o.strength, 0.0)
        assertEquals(50, o.mood)
        assertEquals(0, o.meter)
        assertEquals(o.maxHp, o.hp)
        assertEquals(98, o.maxHp) // round((60 + 8 * 6) * 0.91): level 6, hunger 30
    }

    @Test
    fun opponentLevelNeverDropsBelowOneAndStrengthStaysInRange() {
        val weak = PetState(PetId.JASON, level = 1, strength = 3.0)
        val low = buildOpponent(PetId.PINHEAD, weak, ScriptedRandom(ints = listOf(-1, -10)))
        assertEquals(1, low.level)
        assertEquals(0.0, low.strength, 0.0)

        val strong = PetState(PetId.JASON, level = 1, strength = 97.0)
        val high = buildOpponent(PetId.PINHEAD, strong, ScriptedRandom(ints = listOf(1, 10)))
        assertEquals(2, high.level)
        assertEquals(100.0, high.strength, 0.0)
    }

    @Test
    fun opponentRollsAreWithinTheDocumentedRanges() {
        val player = PetState(PetId.JASON, level = 4, strength = 50.0)
        repeat(500) { seed ->
            val o = buildOpponent(PetId.GHOSTFACE, player, Random(seed))
            assertTrue(o.level in 3..5)
            assertTrue(o.strength in 40.0..60.0)
        }
    }

    @Test
    fun startFightBuildsBothFightersAtTurnOne() {
        val player = PetState(PetId.JASON, hunger = 0.0, level = 2, strength = 30.0)
        val s = startFight(player, PetId.FREDDY, ScriptedRandom(ints = listOf(0, 0)))
        assertEquals(buildFighter(player), s.player)
        assertEquals(PetId.FREDDY, s.cpu.id)
        assertEquals(2, s.cpu.level)
        assertEquals(1, s.turn)
        assertTrue(s.playerHistory.isEmpty())
        assertNull(s.winner)
        assertFalse(s.playerRan)
    }

    // ---------------------------------------------------------------- the move matrix

    @Test
    fun strikeTradesWithStrike() {
        val r = resolve(stateOf(), Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(listOf<FightEvent>(Hit(Side.PLAYER, Move.STRIKE, 16, false), Hit(Side.CPU, Move.STRIKE, 16, false)), r.events)
        assertEquals(84, r.state.player.hp)
        assertEquals(84, r.state.cpu.hp)
    }

    @Test
    fun heavyTradesWithHeavy() {
        val r = resolve(stateOf(), Move.HEAVY, Move.HEAVY, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(listOf<FightEvent>(Hit(Side.PLAYER, Move.HEAVY, 29, false), Hit(Side.CPU, Move.HEAVY, 29, false)), r.events)
        assertEquals(71, r.state.player.hp)
        assertEquals(71, r.state.cpu.hp)
    }

    @Test
    fun bothBlockNothingHappens() {
        val rng = ScriptedRandom()
        val s = stateOf(player = fighter(meter = 50), cpu = fighter(meter = 25))
        val r = resolve(s, Move.BLOCK, Move.BLOCK, rng)
        assertTrue(r.events.isEmpty())
        assertEquals(s.player, r.state.player)
        assertEquals(s.cpu, r.state.cpu)
        assertNull(r.state.winner)
    }

    @Test
    fun strikeInterruptsHeavy() {
        val rng = ScriptedRandom.attacks(Roll()) // only the striker's attack is rolled
        val r = resolve(stateOf(), Move.STRIKE, Move.HEAVY, rng)
        assertEquals(listOf<FightEvent>(Hit(Side.PLAYER, Move.STRIKE, 16, false), Interrupted(Side.CPU)), r.events)
        assertEquals(100, r.state.player.hp)
        assertEquals(84, r.state.cpu.hp)
        assertEquals(0, rng.remainingDoubles)
    }

    @Test
    fun cpuStrikeInterruptsPlayerHeavy() {
        val r = resolve(stateOf(), Move.HEAVY, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf<FightEvent>(Hit(Side.CPU, Move.STRIKE, 16, false), Interrupted(Side.PLAYER)), r.events)
        assertEquals(84, r.state.player.hp)
        assertEquals(100, r.state.cpu.hp)
    }

    @Test
    fun aMissedStrikeStillInterruptsTheHeavy() {
        // The matrix is decided by the moves; dice only decide whether damage lands.
        val r = resolve(stateOf(), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertEquals(listOf<FightEvent>(Miss(Side.PLAYER, Move.STRIKE), Interrupted(Side.CPU)), r.events)
        assertEquals(100, r.state.player.hp)
        assertEquals(100, r.state.cpu.hp)
    }

    @Test
    fun heavyBreaksBlock() {
        val r = resolve(stateOf(), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf<FightEvent>(Hit(Side.PLAYER, Move.HEAVY, 29, false)), r.events) // no Blocked, no counter
        assertEquals(71, r.state.cpu.hp)
        assertEquals(100, r.state.player.hp)
    }

    @Test
    fun cpuHeavyBreaksPlayerBlock() {
        val r = resolve(stateOf(), Move.BLOCK, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf<FightEvent>(Hit(Side.CPU, Move.HEAVY, 29, false)), r.events)
        assertEquals(71, r.state.player.hp)
    }

    @Test
    fun blockCountersStrike() {
        val rng = ScriptedRandom.attacks(Roll()) // the absorbed strike is not rolled, the counter is
        val r = resolve(stateOf(), Move.BLOCK, Move.STRIKE, rng)
        assertEquals(listOf<FightEvent>(Blocked(Side.PLAYER), Hit(Side.PLAYER, Move.STRIKE, 8, false, counter = true)), r.events)
        assertEquals(100, r.state.player.hp)
        assertEquals(92, r.state.cpu.hp)
        assertEquals(0, rng.remainingDoubles)
    }

    @Test
    fun cpuBlockCountersPlayerStrike() {
        val r = resolve(stateOf(), Move.STRIKE, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf<FightEvent>(Blocked(Side.CPU), Hit(Side.CPU, Move.STRIKE, 8, false, counter = true)), r.events)
        assertEquals(92, r.state.player.hp)
        assertEquals(100, r.state.cpu.hp)
    }

    @Test
    fun pinheadCountersAtFullStrength() {
        val s = stateOf(player = fighter(id = PetId.PINHEAD))
        val r = resolve(s, Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf<FightEvent>(Blocked(Side.PLAYER), Hit(Side.PLAYER, Move.STRIKE, 16, false, counter = true)), r.events)
        assertEquals(84, r.state.cpu.hp)
    }

    @Test
    fun counterIsHalfPowerForEveryoneButPinhead() {
        for (id in PetId.entries - PetId.PINHEAD) {
            val r = resolve(stateOf(player = fighter(id = id)), Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
            assertEquals("$id", 92, r.state.cpu.hp)
        }
    }

    @Test
    fun specialIsUnblockableAndResetsMeter() {
        val rng = ScriptedRandom.attacks(Roll())
        val s = stateOf(player = fighter(meter = 100))
        val r = resolve(s, Move.SPECIAL, Move.BLOCK, rng)
        assertEquals(listOf<FightEvent>(Hit(Side.PLAYER, Move.SPECIAL, 40, false, effect = jasonsEffect)), r.events) // block does nothing
        assertEquals(60, r.state.cpu.hp)
        assertEquals(100, r.state.player.hp)
        assertEquals(0, r.state.player.meter)
        assertEquals(0, rng.remainingDoubles)
    }

    @Test
    fun specialAlwaysLandsEvenOnAMissRoll() {
        val s = stateOf(player = fighter(meter = 100))
        val r = resolve(s, Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertEquals(listOf<FightEvent>(Hit(Side.PLAYER, Move.SPECIAL, 40, false, effect = jasonsEffect)), r.events)
    }

    @Test
    fun specialAlsoLandsAgainstGhostface() {
        val s = stateOf(player = fighter(meter = 100), cpu = fighter(id = PetId.GHOSTFACE))
        val r = resolve(s, Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertEquals(listOf<FightEvent>(Hit(Side.PLAYER, Move.SPECIAL, 40, false, effect = jasonsEffect)), r.events)
    }

    @Test
    fun counterNeverMissesEvenAgainstGhostface() {
        val s = stateOf(cpu = fighter(id = PetId.GHOSTFACE))
        val r = resolve(s, Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertEquals(listOf<FightEvent>(Blocked(Side.PLAYER), Hit(Side.PLAYER, Move.STRIKE, 8, false, counter = true)), r.events)
    }

    @Test
    fun specialDoesNotInterruptTheOthersStrikeOrHeavy() {
        val s = stateOf(player = fighter(meter = 100))
        val vsStrike = resolve(s, Move.SPECIAL, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(
            listOf<FightEvent>(Hit(Side.PLAYER, Move.SPECIAL, 40, false, effect = jasonsEffect), Hit(Side.CPU, Move.STRIKE, 16, false)),
            vsStrike.events,
        )
        val vsHeavy = resolve(s, Move.SPECIAL, Move.HEAVY, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(
            listOf<FightEvent>(Hit(Side.PLAYER, Move.SPECIAL, 40, false, effect = jasonsEffect), Hit(Side.CPU, Move.HEAVY, 29, false)),
            vsHeavy.events,
        )
        assertEquals(71, vsHeavy.state.player.hp)
        assertEquals(60, vsHeavy.state.cpu.hp)
    }

    @Test
    fun cpuSpecialWorksTheSameAndResetsItsMeter() {
        val s = stateOf(cpu = fighter(meter = 100))
        val r = resolve(s, Move.BLOCK, Move.SPECIAL, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf<FightEvent>(Hit(Side.CPU, Move.SPECIAL, 40, false, effect = jasonsEffect)), r.events)
        assertEquals(60, r.state.player.hp)
        assertEquals(0, r.state.cpu.meter)
    }

    @Test
    fun specialVsSpecialBothLand() {
        val s = stateOf(player = fighter(meter = 100), cpu = fighter(meter = 100))
        val r = resolve(s, Move.SPECIAL, Move.SPECIAL, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(60, r.state.player.hp)
        assertEquals(60, r.state.cpu.hp)
        assertEquals(0, r.state.player.meter)
        assertEquals(0, r.state.cpu.meter)
    }

    @Test
    fun specialRejectedWithoutFullMeter() {
        val notFull = stateOf(player = fighter(meter = 75), cpu = fighter(meter = 100))
        assertThrows(IllegalArgumentException::class.java) { resolve(notFull, Move.SPECIAL, Move.STRIKE) }

        val cpuNotFull = stateOf(player = fighter(meter = 100), cpu = fighter(meter = 99))
        assertThrows(IllegalArgumentException::class.java) { resolve(cpuNotFull, Move.STRIKE, Move.SPECIAL) }
    }

    @Test
    fun theCpuCannotRun() {
        assertThrows(IllegalArgumentException::class.java) { resolve(stateOf(), Move.STRIKE, Move.RUN) }
    }

    // ---------------------------------------------------------------- dice

    @Test
    fun critMultiplies() {
        val r = resolve(stateOf(), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(crit = 0.0)))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 28, true), r.events.first()) // 16 * 1.75
        assertEquals(72, r.state.cpu.hp)
    }

    @Test
    fun critChanceFollowsMoodAndFreddy() {
        // Mood 0: 5%. Mood 100: 20%. Freddy adds 8 points.
        fun crits(id: PetId, mood: Int, roll: Double): Boolean {
            val s = stateOf(player = fighter(id = id, mood = mood))
            val r = resolve(s, Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(crit = roll), Roll()))
            return (r.events.first() as Hit).crit
        }
        assertTrue(crits(PetId.JASON, 0, 0.049))
        assertFalse(crits(PetId.JASON, 0, 0.051))
        assertTrue(crits(PetId.JASON, 100, 0.199))
        assertFalse(crits(PetId.JASON, 100, 0.201))
        assertTrue(crits(PetId.JASON, 50, 0.124)) // 0.05 + 0.075
        assertFalse(crits(PetId.JASON, 50, 0.126))
        assertFalse(crits(PetId.JASON, 0, 0.12))
        assertTrue(crits(PetId.FREDDY, 0, 0.12)) // 0.05 + 0.08 = 0.13
        assertFalse(crits(PetId.FREDDY, 0, 0.131))
    }

    @Test
    fun counterAndSpecialCanCrit() {
        val counter = resolve(stateOf(), Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll(crit = 0.0)))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 14, true, counter = true), counter.events.last()) // 8 * 1.75
        val special = resolve(stateOf(player = fighter(meter = 100)), Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll(crit = 0.0)))
        assertEquals(Hit(Side.PLAYER, Move.SPECIAL, 70, true, effect = jasonsEffect), special.events.single()) // 40 * 1.75
    }

    @Test
    fun varianceSpansFifteenPercentEitherWay() {
        val low = resolve(stateOf(), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(variance = 0.0)))
        assertEquals(14, (low.events.first() as Hit).damage) // 16 * 0.85 = 13.6
        val high = resolve(stateOf(), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(variance = 1.0)))
        assertEquals(18, (high.events.first() as Hit).damage) // 16 * 1.15 = 18.4
    }

    @Test
    fun damageUsesTheAttackersStrengthAndRoundsAfterAllMultipliers() {
        // base 8 + 20/8 = 10.5; heavy 18.9 -> 19; crit heavy 33.075 -> 33.
        val s = stateOf(player = fighter(strength = 20.0))
        val plain = resolve(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(19, (plain.events.single() as Hit).damage)
        val crit = resolve(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(crit = 0.0)))
        assertEquals(33, (crit.events.single() as Hit).damage)
    }

    @Test
    fun aLandedHitDoesAtLeastOneDamage() {
        val s = stateOf(player = fighter(strength = -200.0)) // absurd, but the floor must hold
        val r = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(1, (r.events.first() as Hit).damage)
        assertEquals(99, r.state.cpu.hp)
    }

    @Test
    fun missEmitsMiss() {
        val s = stateOf(player = fighter(meter = 25))
        val r = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertEquals(Miss(Side.PLAYER, Move.STRIKE), r.events.first())
        assertEquals(100, r.state.cpu.hp)
        assertEquals(25, r.state.player.meter) // a miss builds no meter
    }

    @Test
    fun missChanceIsEightPercentForStrikeAndFifteenForHeavy() {
        fun missed(move: Move, roll: Double): Boolean {
            val cpuMove = if (move == Move.STRIKE) Move.HEAVY else Move.BLOCK
            val r = resolve(stateOf(), move, cpuMove, ScriptedRandom.attacks(Roll(miss = roll)))
            return r.events.first() is Miss
        }
        assertTrue(missed(Move.STRIKE, 0.079))
        assertFalse(missed(Move.STRIKE, 0.081))
        assertTrue(missed(Move.HEAVY, 0.149))
        assertFalse(missed(Move.HEAVY, 0.151))
        assertTrue(missed(Move.HEAVY, 0.10)) // a heavy misses where a strike would land
        assertFalse(missed(Move.STRIKE, 0.10))
    }

    @Test
    fun ghostfaceIsHarderToHit() {
        val roll = Roll(miss = 0.10)
        val vsJason = resolve(stateOf(), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(roll))
        assertTrue(vsJason.events.first() is Hit)
        val vsGhostface = resolve(stateOf(cpu = fighter(id = PetId.GHOSTFACE)), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(roll))
        assertEquals(Miss(Side.PLAYER, Move.STRIKE), vsGhostface.events.first())
    }

    @Test
    fun ghostfaceEvadeBonusAppliesToHeavyToo() {
        val ghost = stateOf(cpu = fighter(id = PetId.GHOSTFACE))
        // Heavy: 0.15 + 0.07 = 0.22.
        val missed = resolve(ghost, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.21)))
        assertEquals(Miss(Side.PLAYER, Move.HEAVY), missed.events.single())
        val landed = resolve(ghost, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.23)))
        assertTrue(landed.events.single() is Hit)
    }

    @Test
    fun ghostfaceEvadesOnTheCpuSideAndOnlyWhenDefending() {
        // The CPU strikes a Ghostface player: 0.08 + 0.07 = 0.15, so 0.10 misses.
        val ghostPlayer = stateOf(player = fighter(id = PetId.GHOSTFACE))
        val r = resolve(ghostPlayer, Move.HEAVY, Move.STRIKE, ScriptedRandom.attacks(Roll(miss = 0.10)))
        assertEquals(listOf<FightEvent>(Miss(Side.CPU, Move.STRIKE), Interrupted(Side.PLAYER)), r.events)

        // Ghostface attacking someone else has no bonus: the same 0.10 roll lands on Jason.
        val ghostAttacks = resolve(ghostPlayer, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(miss = 0.10)))
        assertTrue(ghostAttacks.events.first() is Hit)
    }

    // ---------------------------------------------------------------- roll order

    @Test
    fun theWholeScriptIsConsumedPlayerFirstThenCpu() {
        // Player rolls variance 0.0 (x0.85 = 14), the CPU rolls variance 1.0 (x1.15 = 18).
        val rng = ScriptedRandom.attacks(Roll(variance = 0.0), Roll(variance = 1.0))
        val r = resolve(stateOf(), Move.STRIKE, Move.STRIKE, rng)
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 14, false), r.events[0])
        assertEquals(Hit(Side.CPU, Move.STRIKE, 18, false), r.events[1])
        assertEquals(0, rng.remainingDoubles)
    }

    @Test
    fun anAttackDrawsMissThenVarianceThenCrit() {
        // miss 0.5 (no miss), variance 1.0 (x1.15), crit 0.0 (crit): 16 * 1.15 * 1.75 = 32.2
        val rng = ScriptedRandom(listOf(0.5, 1.0, 0.0))
        val r = resolve(stateOf(), Move.STRIKE, Move.HEAVY, rng)
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 32, true), r.events.first())
        assertEquals(0, rng.remainingDoubles)
    }

    @Test
    fun missedAttacksStillDrawAllThreeDice() {
        val rng = ScriptedRandom.attacks(Roll(miss = 0.0), Roll(variance = 1.0))
        val r = resolve(stateOf(), Move.STRIKE, Move.STRIKE, rng)
        assertEquals(Miss(Side.PLAYER, Move.STRIKE), r.events[0])
        assertEquals(Hit(Side.CPU, Move.STRIKE, 18, false), r.events[1]) // its dice are the second three
        assertEquals(0, rng.remainingDoubles)
    }

    @Test
    fun theSameSeedGivesTheSameTurn() {
        val a = resolveTurn(stateOf(), Move.HEAVY, Move.HEAVY, Random(42))
        val b = resolveTurn(stateOf(), Move.HEAVY, Move.HEAVY, Random(42))
        assertEquals(a, b)
    }

    // ---------------------------------------------------------------- meter

    @Test
    fun meterFillsAndSignalsReady() {
        var s = stateOf(player = fighter(hp = 1000), cpu = fighter(hp = 1000))
        val ready = mutableListOf<Boolean>()
        repeat(4) {
            // Strike beats heavy, so only the player lands.
            val r = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
            ready += r.events.contains(SpecialReady(Side.PLAYER))
            s = r.state
        }
        assertEquals(listOf(false, false, false, true), ready)
        assertEquals(100, s.player.meter)
        assertEquals(0, s.cpu.meter)
    }

    @Test
    fun meterCapsAtOneHundredAndReadyIsOnlySignalledOnce() {
        val s = stateOf(player = fighter(meter = 100))
        val r = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(100, r.state.player.meter)
        assertFalse(r.events.any { it is SpecialReady })
    }

    @Test
    fun counterHitsBuildMeterToo() {
        val s = stateOf(player = fighter(meter = 75))
        val r = resolve(s, Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(
            listOf<FightEvent>(Blocked(Side.PLAYER), Hit(Side.PLAYER, Move.STRIKE, 8, false, counter = true), SpecialReady(Side.PLAYER)),
            r.events,
        )
        assertEquals(100, r.state.player.meter)
    }

    @Test
    fun theCpuMeterFillsToo() {
        val s = stateOf(cpu = fighter(meter = 75))
        val r = resolve(s, Move.HEAVY, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(listOf<FightEvent>(Hit(Side.CPU, Move.STRIKE, 16, false), Interrupted(Side.PLAYER), SpecialReady(Side.CPU)), r.events)
        assertEquals(100, r.state.cpu.meter)
    }

    @Test
    fun aSpecialHitDoesNotRefillTheMeter() {
        val s = stateOf(player = fighter(meter = 100))
        val r = resolve(s, Move.SPECIAL, Move.HEAVY, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(0, r.state.player.meter)
        assertEquals(25, r.state.cpu.meter)
    }

    // ---------------------------------------------------------------- bookkeeping

    @Test
    fun aTurnAdvancesTheCounterAndRecordsThePlayersMove() {
        val s = stateOf(turn = 3, history = listOf(Move.STRIKE, Move.BLOCK))
        val r = resolve(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(4, r.state.turn)
        assertEquals(listOf(Move.STRIKE, Move.BLOCK, Move.HEAVY), r.state.playerHistory)
        assertEquals(listOf(Move.STRIKE, Move.BLOCK), s.playerHistory) // input untouched
    }

    @Test
    fun hpNeverGoesBelowZero() {
        val s = stateOf(cpu = fighter(hp = 5, maxHp = 100))
        val r = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(0, r.state.cpu.hp)
    }

    // ---------------------------------------------------------------- KO

    @Test
    fun koEndsTheFightAndNamesTheWinner() {
        val s = stateOf(cpu = fighter(hp = 16, maxHp = 100))
        val r = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(Side.PLAYER, r.state.winner)
        assertEquals(0, r.state.cpu.hp)
        assertEquals(
            listOf<FightEvent>(Hit(Side.PLAYER, Move.STRIKE, 16, false), Interrupted(Side.CPU), Ko(Side.CPU)),
            r.events,
        )
    }

    @Test
    fun theCpuCanWinToo() {
        val s = stateOf(player = fighter(hp = 1, maxHp = 100))
        val r = resolve(s, Move.BLOCK, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(Side.CPU, r.state.winner)
        assertEquals(Ko(Side.PLAYER), r.events.last())
    }

    @Test
    fun aCounterCanFinishTheFight() {
        val s = stateOf(cpu = fighter(hp = 8, maxHp = 100))
        val r = resolve(s, Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(Side.PLAYER, r.state.winner)
    }

    @Test
    fun doubleKoGoesToBiggerHitter() {
        // Player hits for 16, the CPU for 12 (strength 32): both are down, the player dealt more.
        val s = stateOf(player = fighter(hp = 10, maxHp = 100), cpu = fighter(hp = 10, maxHp = 100, strength = 32.0))
        val r = resolve(s, Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(0, r.state.player.hp)
        assertEquals(0, r.state.cpu.hp)
        assertEquals(Side.PLAYER, r.state.winner)
        assertEquals(Ko(Side.CPU), r.events.last())
        assertEquals(1, r.events.count { it is Ko })

        // Swap the strengths and the CPU is the bigger hitter.
        val swapped = stateOf(player = fighter(hp = 10, maxHp = 100, strength = 32.0), cpu = fighter(hp = 10, maxHp = 100))
        val r2 = resolve(swapped, Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(Side.CPU, r2.state.winner)
        assertEquals(Ko(Side.PLAYER), r2.events.last())
    }

    @Test
    fun doubleKoTieGoesToPlayer() {
        val s = stateOf(player = fighter(hp = 10, maxHp = 100), cpu = fighter(hp = 10, maxHp = 100))
        val r = resolve(s, Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(Side.PLAYER, r.state.winner)
        assertEquals(Ko(Side.CPU), r.events.last())
    }

    @Test
    fun overkillDoesNotCountTowardsTheBiggerHitterTiebreak() {
        // Damage dealt is the roll, not the hp that was left: 16 vs 12 even though both targets had 1 hp.
        val s = stateOf(player = fighter(hp = 1, maxHp = 100, strength = 32.0), cpu = fighter(hp = 1, maxHp = 100))
        val r = resolve(s, Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(Side.CPU, r.state.winner)
    }

    @Test
    fun noFurtherTurnsAfterTheFightIsOver() {
        val over = stateOf(winner = Side.PLAYER)
        assertThrows(IllegalStateException::class.java) { resolve(over, Move.STRIKE, Move.STRIKE) }
        val ran = stateOf(winner = Side.CPU, playerRan = true)
        assertThrows(IllegalStateException::class.java) { resolve(ran, Move.RUN, Move.STRIKE) }
    }

    // ---------------------------------------------------------------- run

    @Test
    fun runForfeits() {
        val rng = ScriptedRandom() // running rolls nothing
        val s = stateOf(player = fighter(meter = 50), turn = 4, history = listOf(Move.STRIKE))
        val r = resolve(s, Move.RUN, Move.HEAVY, rng)
        assertEquals(Side.CPU, r.state.winner)
        assertTrue(r.state.playerRan)
        assertTrue(r.events.isEmpty())
        assertEquals(s.player, r.state.player)
        assertEquals(s.cpu, r.state.cpu)
        assertEquals(4, r.state.turn)
        assertEquals(listOf(Move.STRIKE), r.state.playerHistory)
    }

    // ---------------------------------------------------------------- Chucky and Michael

    @Test
    fun theNewKillersHavePlainHp() {
        for (id in listOf(PetId.CHUCKY, PetId.MICHAEL)) {
            assertEquals("$id", 68, buildFighter(PetState(id, hunger = 0.0, level = 1)).maxHp)
        }
    }

    @Test
    fun chuckyStrikesFifteenPercentHarder() {
        // Scrappy: 16 * 1.15 = 18.4.
        val s = stateOf(player = fighter(id = PetId.CHUCKY))
        val r = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 18, false), r.events.first())
        assertEquals(82, r.state.cpu.hp)
    }

    @Test
    fun chuckysStrikeBonusStacksWithCritsAndVariance() {
        val s = stateOf(player = fighter(id = PetId.CHUCKY))
        val crit = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(crit = 0.0)))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 32, true), crit.events.first()) // 16 * 1.15 * 1.75 = 32.2
        val high = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(variance = 1.0)))
        assertEquals(21, (high.events.first() as Hit).damage) // 16 * 1.15 * 1.15 = 21.16
    }

    @Test
    fun chuckysBonusIsForHisStrikeOnly() {
        val chucky = fighter(id = PetId.CHUCKY, meter = 100)
        val heavy = resolve(stateOf(player = chucky), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(29, (heavy.events.single() as Hit).damage)
        val counter = resolve(stateOf(player = chucky), Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 8, false, counter = true), counter.events.last())
        val special = resolve(stateOf(player = chucky), Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(40, (special.events.single() as Hit).damage)
    }

    @Test
    fun chuckyStrikesHarderOnTheCpuSideToo() {
        val s = stateOf(cpu = fighter(id = PetId.CHUCKY))
        val r = resolve(s, Move.HEAVY, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.CPU, Move.STRIKE, 18, false), r.events.first())
    }

    @Test
    fun nobodyElseGetsTheStrikeBonus() {
        for (id in PetId.entries - PetId.CHUCKY) {
            val r = resolve(stateOf(player = fighter(id = id)), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
            assertEquals("$id", 16, (r.events.first() as Hit).damage)
        }
    }

    @Test
    fun michaelTakesFifteenPercentLessFromEverything() {
        val michael = fighter(id = PetId.MICHAEL)
        val strike = resolve(stateOf(cpu = michael), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 14, false), strike.events.first()) // 16 * 0.85 = 13.6
        assertEquals(86, strike.state.cpu.hp)
        val heavy = resolve(stateOf(cpu = michael), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(24, (heavy.events.single() as Hit).damage) // 28.8 * 0.85 = 24.48
        val counter = resolve(stateOf(cpu = michael), Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 7, false, counter = true), counter.events.last()) // 8 * 0.85 = 6.8
        val special = resolve(stateOf(player = fighter(meter = 100), cpu = michael), Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(34, (special.events.single() as Hit).damage) // 40 * 0.85
    }

    @Test
    fun michaelsReductionAppliesAfterTheCrit() {
        // 16 * 1.75 = 28, then * 0.85 = 23.8 -> 24, rounded once at the end.
        val r = resolve(stateOf(cpu = fighter(id = PetId.MICHAEL)), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(crit = 0.0)))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 24, true), r.events.first())
    }

    @Test
    fun michaelStillTakesAtLeastOneDamage() {
        val s = stateOf(player = fighter(strength = -200.0), cpu = fighter(id = PetId.MICHAEL))
        val r = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(1, (r.events.first() as Hit).damage)
        assertEquals(99, r.state.cpu.hp)
    }

    @Test
    fun michaelAsThePlayerResistsTheCpusHits() {
        val r = resolve(stateOf(player = fighter(id = PetId.MICHAEL)), Move.HEAVY, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.CPU, Move.STRIKE, 14, false), r.events.first())
        assertEquals(86, r.state.player.hp)
    }

    @Test
    fun michaelHitsNormally() {
        val r = resolve(stateOf(player = fighter(id = PetId.MICHAEL)), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(16, (r.events.first() as Hit).damage)
    }

    @Test
    fun nobodyElseResistsDamage() {
        for (id in PetId.entries - PetId.MICHAEL) {
            val r = resolve(stateOf(cpu = fighter(id = id)), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
            assertEquals("$id", 16, (r.events.first() as Hit).damage)
        }
    }

    @Test
    fun chuckyStabbingMichaelAppliesBothAndRoundsOnce() {
        // 16 * 1.15 * 0.85 = 15.64 -> 16 (rounding 18 then scaling would give 15).
        val s = stateOf(player = fighter(id = PetId.CHUCKY), cpu = fighter(id = PetId.MICHAEL))
        val r = resolve(s, Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(16, (r.events.first() as Hit).damage)
    }

    @Test
    fun theNewKillersMissAndCritLikeAnyoneElse() {
        for (id in listOf(PetId.CHUCKY, PetId.MICHAEL)) {
            // 0.08 strike miss with no evade bonus either way; 5% crit at mood 0 with no crit bonus.
            val hitThem = resolve(stateOf(cpu = fighter(id = id)), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(miss = 0.081)))
            assertTrue("$id", hitThem.events.first() is Hit)
            val critRoll = resolve(stateOf(player = fighter(id = id)), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll(crit = 0.051)))
            assertFalse("$id", (critRoll.events.first() as Hit).crit)
        }
    }

    // ---------------------------------------------------------------- Leatherface

    @Test
    fun leatherfaceHasPlainHp() {
        assertEquals(68, buildFighter(PetState(PetId.LEATHERFACE, hunger = 0.0, level = 1)).maxHp)
    }

    @Test
    fun leatherfacesHeavyHitsTwentyPercentHarder() {
        // Chainsaw: 16 * 1.8 * 1.2 = 34.56.
        val s = stateOf(player = fighter(id = PetId.LEATHERFACE))
        val r = resolve(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.PLAYER, Move.HEAVY, 35, false), r.events.single())
        assertEquals(65, r.state.cpu.hp)
    }

    @Test
    fun leatherfacesHeavyBonusAppliesBeforeTheCritAndStacksWithVariance() {
        val s = stateOf(player = fighter(id = PetId.LEATHERFACE))
        val crit = resolve(s, Move.HEAVY, Move.HEAVY, ScriptedRandom.attacks(Roll(crit = 0.0), Roll()))
        assertEquals(Hit(Side.PLAYER, Move.HEAVY, 60, true), crit.events.first()) // 16 * 1.8 * 1.2 * 1.75 = 60.48
        val high = resolve(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(variance = 1.0)))
        assertEquals(40, (high.events.single() as Hit).damage) // 16 * 1.15 * 1.8 * 1.2 = 39.744
        val low = resolve(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(variance = 0.0)))
        assertEquals(29, (low.events.single() as Hit).damage) // 16 * 0.85 * 1.8 * 1.2 = 29.376
    }

    @Test
    fun leatherfacesBonusIsForHisHeavyOnly() {
        val leatherface = fighter(id = PetId.LEATHERFACE, meter = 100)
        val strike = resolve(stateOf(player = leatherface), Move.STRIKE, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(16, (strike.events.first() as Hit).damage)
        val counter = resolve(stateOf(player = leatherface), Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 8, false, counter = true), counter.events.last())
        val special = resolve(stateOf(player = leatherface), Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(40, (special.events.single() as Hit).damage)
    }

    @Test
    fun leatherfaceHeavyHitsHarderOnTheCpuSideToo() {
        val s = stateOf(cpu = fighter(id = PetId.LEATHERFACE))
        val r = resolve(s, Move.BLOCK, Move.HEAVY, ScriptedRandom.attacks(Roll()))
        assertEquals(Hit(Side.CPU, Move.HEAVY, 35, false), r.events.single())
        assertEquals(65, r.state.player.hp)
    }

    @Test
    fun leatherfaceTakesHeaviesLikeAnyoneElse() {
        // His passive is his own swing: a HEAVY aimed at him does the usual 29.
        val r = resolve(stateOf(cpu = fighter(id = PetId.LEATHERFACE)), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(29, (r.events.single() as Hit).damage)
    }

    @Test
    fun leatherfaceStillDoesAtLeastOneDamage() {
        val s = stateOf(player = fighter(id = PetId.LEATHERFACE, strength = -200.0))
        val r = resolve(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(1, (r.events.single() as Hit).damage)
    }

    @Test
    fun nobodyElseGetsTheHeavyBonus() {
        for (id in PetId.entries - PetId.LEATHERFACE - PetId.JAWS) {
            val r = resolve(stateOf(player = fighter(id = id)), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
            assertEquals("$id", 29, (r.events.single() as Hit).damage)
        }
    }

    @Test
    fun jawsApexHeavyHitsHarderToo() {
        // 16 * 1.8 * 1.15 = 33.12 -> 33, against everyone else's 29 (and Leatherface's 35).
        val r = resolve(stateOf(player = fighter(id = PetId.JAWS)), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(33, (r.events.single() as Hit).damage)
    }

    @Test
    fun leatherfaceSawingIntoMichaelAppliesBothAndRoundsOnce() {
        // 16 * 1.8 * 1.2 * 0.85 = 29.376 -> 29 (rounding 35 first and then scaling would give 30).
        val s = stateOf(player = fighter(id = PetId.LEATHERFACE), cpu = fighter(id = PetId.MICHAEL))
        val r = resolve(s, Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll()))
        assertEquals(29, (r.events.single() as Hit).damage)
    }

    @Test
    fun leatherfaceMissesAndCritsLikeAnyoneElse() {
        // A HEAVY at him misses on 0.15 like anyone's; his own HEAVY misses on 0.15 and crits on 5% at mood 0.
        val hitHim = resolve(stateOf(cpu = fighter(id = PetId.LEATHERFACE)), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.151)))
        assertTrue(hitHim.events.single() is Hit)
        val missed = resolve(stateOf(player = fighter(id = PetId.LEATHERFACE)), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.149)))
        assertEquals(Miss(Side.PLAYER, Move.HEAVY), missed.events.single())
        val critRoll = resolve(stateOf(player = fighter(id = PetId.LEATHERFACE)), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(crit = 0.051)))
        assertFalse((critRoll.events.single() as Hit).crit)
    }

    // ---------------------------------------------------------------- whole fights

    @Test
    fun randomFightsAlwaysFinishWithSaneState() {
        repeat(300) { seed ->
            val rng = Random(seed)
            val ids = PetId.entries
            val pet = PetState(ids[seed % ids.size], hunger = (seed % 7) * 10.0, strength = (seed % 11) * 9.0, level = 1 + seed % 5)
            var s = startFight(pet, ids[(seed / 4) % ids.size], rng)
            var turns = 0
            while (s.winner == null) {
                assertTrue("fight $seed ran away", turns++ < 500)
                val legal = Move.entries.filter {
                    it != Move.RUN &&
                        (it != Move.SPECIAL || s.player.meter == FightTuning.METER_MAX) &&
                        (it != Move.POTION || s.playerPotions > 0)
                }
                val r = resolveTurn(s, legal[rng.nextInt(legal.size)], cpuPick(s, rng), rng)
                s = r.state
                for (f in listOf(s.player, s.cpu)) {
                    assertTrue(f.hp in 0..f.maxHp)
                    assertTrue(f.meter in 0..FightTuning.METER_MAX)
                }
                assertTrue(s.playerPotions in 0..FightTuning.POTIONS_PER_FIGHT)
                assertEquals(s.winner != null, r.events.any { it is Ko })
            }
            val loser = if (s.winner == Side.PLAYER) s.cpu else s.player
            assertEquals(0, loser.hp)
        }
    }
}
