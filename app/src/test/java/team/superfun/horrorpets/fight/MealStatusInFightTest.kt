package team.superfun.horrorpets.fight

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightEvent.Hit
import team.superfun.horrorpets.fight.FightEvent.Miss
import team.superfun.horrorpets.game.pantry.MealPerk
import team.superfun.horrorpets.game.pantry.MealStatus
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

/**
 * The last meal's status in a fight (the player's, against the CPU): BEEFED UP and a HANGOVER change the damage of
 * every hit, an IRON STOMACH the crit chance, a HANGOVER the miss chance of a STRIKE or HEAVY, a SUGAR RUSH the meter
 * it starts with, and a FAVORITE FEAST the potions. The numbers are [FightEngineTest]'s: a plain STRIKE does 16.
 */
class MealStatusInFightTest {
    private val beefedUp = MealStatus(MealPerk.BEEFED_UP)
    private val sugarRush = MealStatus(MealPerk.SUGAR_RUSH)
    private val ironStomach = MealStatus(MealPerk.IRON_STOMACH)
    private val hangover = MealStatus(MealPerk.HANGOVER)
    private val feast = MealStatus(MealPerk.BEEFED_UP, feast = true)
    private val everyStatus = listOf(beefedUp, sugarRush, ironStomach, hangover, feast, MealStatus(MealPerk.SUGAR_RUSH, feast = true))

    /** The default fighter (Jason, strength 64, mood 0) after a meal that gave it [status]. */
    private fun fed(status: MealStatus?, meter: Int = 0) = fighter(meter = meter).copy(status = status)

    private fun TurnResult.hitBy(side: Side): Hit? = events.filterIsInstance<Hit>().singleOrNull { it.attacker == side }

    private fun TurnResult.missedBy(side: Side): Boolean = events.any { it is Miss && it.attacker == side }

    // ---------------------------------------------------------------- damage

    @Test
    fun beefedUpHitsHarderAndTheCpuHitsAsEver() {
        val r = resolveTurn(stateOf(player = fed(beefedUp)), Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(18, r.hitBy(Side.PLAYER)!!.damage) // round(16 * 1.15 = 18.4)
        assertEquals((16 * FightTuning.PERK_DAMAGE_UP).roundToInt(), r.hitBy(Side.PLAYER)!!.damage)
        assertEquals("the CPU's blow is its own", 16, r.hitBy(Side.CPU)!!.damage)
        assertEquals("the status lasts the whole fight", beefedUp, r.state.player.status)
    }

    @Test
    fun beefedUpRaisesTheSameSeededStrike() {
        for (seed in 0 until 20) {
            val plain = resolveTurn(stateOf(), Move.STRIKE, Move.STRIKE, Random(seed)).hitBy(Side.PLAYER)
            val beefed = resolveTurn(stateOf(player = fed(beefedUp)), Move.STRIKE, Move.STRIKE, Random(seed)).hitBy(Side.PLAYER)
            assertEquals("seed $seed: the same dice land or miss alike", plain == null, beefed == null)
            if (plain == null || beefed == null) continue
            assertEquals("seed $seed", plain.crit, beefed.crit)
            assertTrue("seed $seed: ${beefed.damage} after ${plain.damage}", beefed.damage > plain.damage)
            // The plain damage is already rounded, so this is round(x * 1.15) give or take the rounding.
            assertTrue("seed $seed: ${beefed.damage} after ${plain.damage}", abs(beefed.damage - (plain.damage * FightTuning.PERK_DAMAGE_UP).roundToInt()) <= 1)
        }
    }

    @Test
    fun aHangoverHitsSofterRoundedOnceAtTheEnd() {
        val strike = resolveTurn(stateOf(player = fed(hangover)), Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(14, strike.hitBy(Side.PLAYER)!!.damage) // round(16 * 0.9 = 14.4)
        assertEquals("the CPU's blow is its own", 16, strike.hitBy(Side.CPU)!!.damage)
        val crit = resolveTurn(stateOf(player = fed(hangover)), Move.HEAVY, Move.BLOCK, ScriptedRandom.attacks(Roll(crit = 0.0)))
        assertEquals(Hit(Side.PLAYER, Move.HEAVY, 45, true), crit.hitBy(Side.PLAYER)) // round(16 * 1.8 * 1.75 * 0.9 = 45.36)
    }

    // ---------------------------------------------------------------- misses

    @Test
    fun aHangoverMissesAStrikeOrAHeavyThatWouldHaveLandedOnTheSameDice() {
        // A miss roll of 0.2 is over a plain STRIKE's (0.08) and HEAVY's (0.15) miss chance, under a hungover one's.
        for (move in listOf(Move.STRIKE, Move.HEAVY)) {
            val plainDice = ScriptedRandom.attacks(Roll(miss = 0.2), Roll())
            val plain = resolveTurn(stateOf(), move, move, plainDice)
            val sickDice = ScriptedRandom.attacks(Roll(miss = 0.2), Roll())
            val sick = resolveTurn(stateOf(player = fed(hangover)), move, move, sickDice)
            assertFalse("$move", plain.missedBy(Side.PLAYER))
            assertEquals("$move", Miss(Side.PLAYER, move), sick.events.first())
            assertEquals("$move: the same dice, all of them", 0 to 0, plainDice.remainingDoubles to sickDice.remainingDoubles)
        }
    }

    @Test
    fun aHangoverNeverSpoilsACounterOrASpecial() {
        // Even a miss roll of 0.0 lands them, a little softer.
        val counter = resolveTurn(stateOf(player = fed(hangover)), Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 7, false, counter = true), counter.hitBy(Side.PLAYER)) // round(8 * 0.9)
        val special = resolveTurn(stateOf(player = fed(hangover, meter = FightTuning.METER_MAX)), Move.SPECIAL, Move.BLOCK, ScriptedRandom.attacks(Roll(miss = 0.0)))
        assertFalse(special.missedBy(Side.PLAYER))
        assertEquals(36, special.hitBy(Side.PLAYER)!!.damage) // round(40 * 0.9)
    }

    @Test
    fun aHangoverMissesAbout15PercentMoreOften() {
        fun misses(status: MealStatus?, move: Move) = (0 until 2000).count { seed ->
            resolveTurn(stateOf(player = fed(status)), move, move, Random(seed)).missedBy(Side.PLAYER)
        }
        for (move in listOf(Move.STRIKE, Move.HEAVY)) {
            // 0.15 more of 2000 swings: about 300 more misses.
            val more = misses(hangover, move) - misses(null, move)
            assertTrue("$move: $more more misses / 2000", more in 250..350)
        }
    }

    // ---------------------------------------------------------------- crits

    @Test
    fun anIronStomachCritsOnARollThatWouldNotHave() {
        // A crit roll of 0.1 is over the plain crit chance at mood 0 (0.05), under an iron stomach's (0.15).
        val plain = resolveTurn(stateOf(), Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(crit = 0.1), Roll()))
        val iron = resolveTurn(stateOf(player = fed(ironStomach)), Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(crit = 0.1), Roll()))
        assertFalse(plain.hitBy(Side.PLAYER)!!.crit)
        assertEquals(Hit(Side.PLAYER, Move.STRIKE, 28, true), iron.hitBy(Side.PLAYER))
        assertFalse("the CPU's crit chance is its own", iron.hitBy(Side.CPU)!!.crit)
    }

    @Test
    fun anIronStomachCritsAbout10PercentMoreOften() {
        fun critShare(status: MealStatus?): Double {
            val hits = (0 until 2000).mapNotNull { seed ->
                resolveTurn(stateOf(player = fed(status)), Move.STRIKE, Move.STRIKE, Random(seed)).hitBy(Side.PLAYER)
            }
            return hits.count { it.crit }.toDouble() / hits.size
        }
        val more = critShare(ironStomach) - critShare(null)
        assertEquals("crit share up by $more", FightTuning.PERK_CRIT_BONUS, more, 0.02)
    }

    // ---------------------------------------------------------------- the meter, the potions

    @Test
    fun aSugarRushStartsTheMeterHalfFull() {
        val pet = PetState(PetId.JASON)
        val rushed = buildFighter(pet, sugarRush)
        assertEquals(50, rushed.meter)
        assertEquals(sugarRush, rushed.status)
        assertEquals("a sugary feast too", 50, buildFighter(pet, MealStatus(MealPerk.SUGAR_RUSH, feast = true)).meter)
        for (other in listOf(beefedUp, ironStomach, hangover, feast, null)) {
            assertEquals("$other", 0, buildFighter(pet, other).meter)
        }
        assertEquals("and nothing else changes", buildFighter(pet), rushed.copy(meter = 0, status = null))
    }

    @Test
    fun aFavoriteFeastBringsAThirdPotion() {
        val pet = PetState(PetId.JASON)
        val s = startFight(pet, PetId.FREDDY, ScriptedRandom(ints = listOf(0, 0)), feast)
        assertEquals(3, s.playerPotions)
        assertEquals("a feast is its flavor's perk too", feast, s.player.status)
        assertEquals("nothing drunk yet", 0, s.potionsUsed)
        for (other in listOf(beefedUp, sugarRush, ironStomach, hangover, null)) {
            assertEquals("$other", FightTuning.POTIONS_PER_FIGHT, startFight(pet, PetId.FREDDY, ScriptedRandom(ints = listOf(0, 0)), other).playerPotions)
        }
        // One drunk leaves two, and counts as one drunk (for the battle rank).
        val drunk = resolveTurn(s.copy(player = s.player.copy(hp = 1)), Move.POTION, Move.BLOCK, ScriptedRandom(listOf(0.5))).state
        assertEquals(2, drunk.playerPotions)
        assertEquals(1, drunk.potionsUsed)
    }

    @Test
    fun theCpuNeverCarriesAStatus() {
        val pet = PetState(PetId.JASON)
        val opponent = buildOpponent(PetId.FREDDY, pet, ScriptedRandom(ints = listOf(0, 0)))
        for (status in everyStatus) {
            val s = startFight(pet, PetId.FREDDY, ScriptedRandom(ints = listOf(0, 0)), status)
            assertEquals("$status", opponent, s.cpu)
            assertNull("$status", s.cpu.status)
            assertEquals("$status", 0, s.cpuPotions)
        }
    }

    // ---------------------------------------------------------------- the chips

    @Test
    fun theChipsSayWhatTheFightDoes() {
        fun percent(share: Double) = (share * 100).roundToInt()
        assertEquals("+${percent(FightTuning.PERK_DAMAGE_UP - 1)}% damage", MealPerk.BEEFED_UP.meaning)
        assertEquals("+${percent(FightTuning.PERK_CRIT_BONUS)}% crit chance", MealPerk.IRON_STOMACH.meaning)
        assertEquals("Meter starts half full", MealPerk.SUGAR_RUSH.meaning)
        assertEquals("half full", FightTuning.METER_MAX / 2, FightTuning.PERK_START_METER)
        assertEquals("Misses more, hits softer", MealPerk.HANGOVER.meaning)
        assertTrue("misses more", FightTuning.PERK_MISS_BONUS > 0.0)
        assertTrue("hits softer", FightTuning.PERK_DAMAGE_DOWN < 1.0)
        assertEquals("a feast's third potion", FightTuning.POTIONS_PER_FIGHT + 1, FightTuning.PERK_FEAST_POTIONS)
    }
}
