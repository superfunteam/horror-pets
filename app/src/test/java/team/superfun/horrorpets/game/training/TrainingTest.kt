package team.superfun.horrorpets.game.training

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.CareResult
import team.superfun.horrorpets.game.perform
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.TrainingGame

class TrainingTest {
    private fun pet(
        strength: Double = 20.0,
        hunger: Double = 20.0,
        fn: Double = 80.0,
        level: Int = 1,
        xp: Int = 0,
        best: Map<String, Int> = emptyMap(),
    ) = PetState(PetId.JASON, hunger = hunger, funLevel = fn, strength = strength, level = level, xp = xp, trainingBest = best)

    private fun stable(vararg ids: PetId, selected: PetId = ids.first()) =
        GameState(pets = ids.associateWith { PetState(it) }, selected = selected)

    @Test
    fun eligibleGamesNeedAMateForQuickDrawAndHideAndSeek() {
        assertEquals(listOf(TrainingGame.SLASH_SAYS, TrainingGame.ORGAN_DROP), eligibleGames(false))
        assertEquals(TrainingGame.entries, eligibleGames(true))
    }

    @Test
    fun pickNeverRepeatsTheLastGameWhenThereIsAChoice() {
        for (eligible in listOf(eligibleGames(true), eligibleGames(false))) {
            for (last in eligible) {
                val rng = Random(7)
                repeat(500) { assertNotEquals(last, pickTrainingGame(eligible, last, rng)) }
            }
        }
        // A last game that is no longer eligible (the stable shrank) rules nothing out.
        repeat(20) { assertEquals(TrainingGame.SLASH_SAYS, pickTrainingGame(listOf(TrainingGame.SLASH_SAYS), TrainingGame.QUICK_DRAW, Random(it))) }
        // With nothing else to pick, the one game comes up again even when it was the last.
        val only = listOf(TrainingGame.ORGAN_DROP)
        repeat(20) { assertEquals(TrainingGame.ORGAN_DROP, pickTrainingGame(only, TrainingGame.ORGAN_DROP, Random(it))) }
        // Over a long run of picks, each following the last, every eligible game shows up.
        for (eligible in listOf(eligibleGames(true), eligibleGames(false))) {
            val rng = Random(11)
            var last: TrainingGame? = null
            val seen = mutableSetOf<TrainingGame>()
            repeat(2000) {
                val next = pickTrainingGame(eligible, last, rng)
                assertTrue(next in eligible)
                seen += next
                last = next
            }
            assertEquals(eligible.toSet(), seen)
        }
    }

    @Test
    fun pickMateIsAnotherStablePet() {
        assertNull(pickMate(stable(PetId.JASON), Random(1)))
        assertNull(pickMate(GameState(), Random(1)))
        val game = stable(PetId.JASON, PetId.FREDDY, PetId.PINHEAD, PetId.MICHAEL, selected = PetId.FREDDY)
        val rng = Random(3)
        val seen = mutableSetOf<PetId>()
        repeat(300) { seen += pickMate(game, rng)!! }
        assertEquals(setOf(PetId.JASON, PetId.PINHEAD, PetId.MICHAEL), seen)
    }

    @Test
    fun multiplierRunsFromOneToThreeAtPar() {
        assertEquals(1.0, trainingMultiplier(TrainingGame.SLASH_SAYS, 0), 0.0)
        assertEquals(2.0, trainingMultiplier(TrainingGame.SLASH_SAYS, 4), 0.0)
        assertEquals(3.0, trainingMultiplier(TrainingGame.SLASH_SAYS, 8), 0.0)
        assertEquals(3.0, trainingMultiplier(TrainingGame.SLASH_SAYS, 99), 0.0)
        assertEquals(2.0, trainingMultiplier(TrainingGame.ORGAN_DROP, 15), 0.0)
        assertEquals(1.0, trainingMultiplier(TrainingGame.SLASH_SAYS, -3), 0.0)
    }

    @Test
    fun scoreZeroPaysExactlyTodaysTrain() {
        val p = pet(strength = 40.0, hunger = 33.0, fn = 55.0, level = 2, xp = 195, best = mapOf("QUICK_DRAW" to 9))
        val today = perform(CareAction.TRAIN, p) as CareResult.Done
        for (game in TrainingGame.entries) {
            val out = applyTraining(p, game, 0)
            assertEquals(today.pet, out.pet)
            assertEquals(today.levelsGained, out.levelsGained)
            assertEquals(6.0, out.strengthGained, 1e-9)
            assertEquals(10, out.xpGained)
            assertFalse(out.newBest)
        }
        // A pet with no best yet: a nothing score is not a best either, so the save stays as it was.
        val fresh = applyTraining(pet(), TrainingGame.SLASH_SAYS, 0)
        assertTrue(fresh.pet.trainingBest.isEmpty())
        assertEquals(0, fresh.best)
        assertFalse(fresh.newBest)
    }

    @Test
    fun aHigherScorePaysMore() {
        val p = pet(strength = 20.0)
        for (game in TrainingGame.entries) {
            val none = applyTraining(p, game, 0)
            val atPar = applyTraining(p, game, game.par)
            assertEquals(none.strengthGained * 3, atPar.strengthGained, 1e-9)
            assertEquals(none.xpGained * 3, atPar.xpGained)
            assertEquals(20.0 + atPar.strengthGained, atPar.pet.strength, 1e-9)
            // Past par pays no more.
            assertEquals(atPar.strengthGained, applyTraining(p, game, game.par * 4).strengthGained, 1e-9)
        }
        // Hunger and fun cost the same however well it went.
        val atPar = applyTraining(p, TrainingGame.SLASH_SAYS, 8).pet
        val none = applyTraining(p, TrainingGame.SLASH_SAYS, 0).pet
        assertEquals(none.hunger, atPar.hunger, 0.0)
        assertEquals(none.funLevel, atPar.funLevel, 0.0)
    }

    @Test
    fun xpCarriesIntoLevelsAndRounds() {
        // 3x at par is 30 xp: 80 + 30 crosses the 100 for level 1 with 10 over.
        val out = applyTraining(pet(xp = 80), TrainingGame.SLASH_SAYS, 8)
        assertEquals(30, out.xpGained)
        assertEquals(1, out.levelsGained)
        assertEquals(2, out.pet.level)
        assertEquals(10, out.pet.xp)
        // 1 + 2 * 1 / 8 = 1.25, so 12.5 xp rounds to 13.
        assertEquals(13, applyTraining(pet(), TrainingGame.SLASH_SAYS, 1).xpGained)
    }

    @Test
    fun negativeScoresCountAsZero() {
        val p = pet()
        assertEquals(applyTraining(p, TrainingGame.QUICK_DRAW, 0), applyTraining(p, TrainingGame.QUICK_DRAW, -5))
    }

    @Test
    fun strengthGainedReportsTheClampedChange() {
        val out = applyTraining(pet(strength = 99.0), TrainingGame.SLASH_SAYS, 8)
        assertEquals(1.0, out.strengthGained, 1e-9)
        assertEquals(100.0, out.pet.strength, 0.0)
        assertEquals(0.0, applyTraining(pet(strength = 100.0), TrainingGame.SLASH_SAYS, 8).strengthGained, 0.0)
    }

    @Test
    fun hungerAndFunStayInBounds() {
        val out = applyTraining(pet(hunger = 84.0, fn = 2.0), TrainingGame.SLASH_SAYS, 8).pet
        assertEquals(96.0, out.hunger, 1e-9)
        assertEquals(0.0, out.funLevel, 0.0)
        assertEquals(100.0, applyTraining(pet(hunger = 95.0), TrainingGame.SLASH_SAYS, 8).pet.hunger, 0.0)
    }

    @Test
    fun bestIsKeptPerGame() {
        val p = pet(best = mapOf("QUICK_DRAW" to 9))
        assertEquals(0, p.bestAt(TrainingGame.SLASH_SAYS))
        assertEquals(9, p.bestAt(TrainingGame.QUICK_DRAW))

        val first = applyTraining(p, TrainingGame.SLASH_SAYS, 5)
        assertTrue(first.newBest)
        assertEquals(5, first.best)
        assertEquals(5, first.pet.bestAt(TrainingGame.SLASH_SAYS))
        // Another game's best is untouched.
        assertEquals(9, first.pet.bestAt(TrainingGame.QUICK_DRAW))

        val lower = applyTraining(first.pet, TrainingGame.SLASH_SAYS, 3)
        assertFalse(lower.newBest)
        assertEquals(5, lower.best)
        assertEquals(first.pet.trainingBest, lower.pet.trainingBest)

        val tie = applyTraining(first.pet, TrainingGame.SLASH_SAYS, 5)
        assertFalse(tie.newBest)

        val higher = applyTraining(lower.pet, TrainingGame.SLASH_SAYS, 7)
        assertTrue(higher.newBest)
        assertEquals(7, higher.best)
        assertEquals(mapOf("QUICK_DRAW" to 9, "SLASH_SAYS" to 7), higher.pet.trainingBest)
    }
}
