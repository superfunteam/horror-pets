package team.superfun.horrorpets.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class RewardsTest {
    private fun pet(
        hunger: Double = 20.0,
        fn: Double = 80.0,
        bonus: Double = 0.0,
        level: Int = 1,
        xp: Int = 0,
    ) = PetState(
        PetId.FREDDY,
        hunger = hunger,
        funLevel = fn,
        strength = 37.0,
        level = level,
        xp = xp,
        moodBonus = bonus,
        wins = 4,
        losses = 2,
        sceneIndex = 1,
        fakeDeathPending = true,
    )

    private val won = stateOf(winner = Side.PLAYER)
    private val lost = stateOf(winner = Side.CPU)
    private val ran = stateOf(winner = Side.CPU, playerRan = true)

    @Test
    fun winPaysXpAndMoodAndCostsHunger() {
        val p = pet()
        val r = applyFightResult(p, won)
        assertEquals(35.0, r.pet.hunger, 1e-9)
        assertEquals(15.0, r.pet.moodBonus, 1e-9)
        assertEquals(40, r.pet.xp)
        assertEquals(40, r.xpGained)
        assertEquals(0, r.levelsGained)
        assertEquals(5, r.pet.wins)
        assertEquals(2, r.pet.losses)
        assertEquals(p.funLevel, r.pet.funLevel, 0.0) // a win leaves Fun alone
    }

    @Test
    fun lossPaysSmallXpAndCostsFunAndHunger() {
        val p = pet()
        val r = applyFightResult(p, lost)
        assertEquals(35.0, r.pet.hunger, 1e-9)
        assertEquals(75.0, r.pet.funLevel, 1e-9)
        assertEquals(15, r.pet.xp)
        assertEquals(15, r.xpGained)
        assertEquals(3, r.pet.losses)
        assertEquals(4, r.pet.wins)
        assertEquals(p.moodBonus, r.pet.moodBonus, 0.0) // a loss leaves the mood bonus alone
    }

    @Test
    fun runCostsMoodAndALossButNoXpAndNoHunger() {
        val p = pet(bonus = 5.0)
        val r = applyFightResult(p, ran)
        assertEquals(-5.0, r.pet.moodBonus, 1e-9)
        assertEquals(3, r.pet.losses)
        assertEquals(4, r.pet.wins)
        assertEquals(0, r.xpGained)
        assertEquals(0, r.levelsGained)
        assertEquals(p.xp, r.pet.xp)
        assertEquals(p.hunger, r.pet.hunger, 0.0)
        assertEquals(p.funLevel, r.pet.funLevel, 0.0)
    }

    @Test
    fun aWinCanLevelThePetUp() {
        val r = applyFightResult(pet(xp = 80), won)
        assertEquals(2, r.pet.level)
        assertEquals(20, r.pet.xp)
        assertEquals(1, r.levelsGained)
        assertEquals(40, r.xpGained)
    }

    @Test
    fun aLossCanLevelThePetUpToo() {
        val r = applyFightResult(pet(xp = 90), lost)
        assertEquals(2, r.pet.level)
        assertEquals(5, r.pet.xp)
        assertEquals(1, r.levelsGained)
    }

    @Test
    fun statsClamp() {
        val hungryWin = applyFightResult(pet(hunger = 95.0, bonus = 25.0), won)
        assertEquals(100.0, hungryWin.pet.hunger, 0.0)
        assertEquals(30.0, hungryWin.pet.moodBonus, 0.0)

        val bored = applyFightResult(pet(hunger = 99.0, fn = 3.0), lost)
        assertEquals(100.0, bored.pet.hunger, 0.0)
        assertEquals(0.0, bored.pet.funLevel, 0.0)

        val sulking = applyFightResult(pet(bonus = -25.0), ran)
        assertEquals(-30.0, sulking.pet.moodBonus, 0.0)
    }

    @Test
    fun everythingElseIsUntouched() {
        val p = pet()
        for (s in listOf(won, lost, ran)) {
            val q = applyFightResult(p, s).pet
            assertEquals(p.id, q.id)
            assertEquals(p.strength, q.strength, 0.0)
            assertEquals(p.sceneIndex, q.sceneIndex)
            assertEquals(p.fakeDeathPending, q.fakeDeathPending)
        }
    }

    @Test
    fun rewardsAreTheSameForEveryPet() {
        // Passives only change the fight itself: Chucky, Michael and Leatherface are paid like everyone else.
        val reference = pet()
        for (id in PetId.entries) {
            val p = reference.copy(id = id)
            for (s in listOf(won, lost, ran)) {
                val r = applyFightResult(p, stateOf(player = fighter(id = id), cpu = fighter(id = PetId.JASON), winner = s.winner, playerRan = s.playerRan))
                val expected = applyFightResult(reference, s)
                assertEquals("$id", expected.pet.copy(id = id), r.pet)
                assertEquals("$id", expected.xpGained, r.xpGained)
                assertEquals("$id", expected.levelsGained, r.levelsGained)
            }
        }
    }

    @Test
    fun anUnfinishedFightHasNoResult() {
        assertThrows(IllegalArgumentException::class.java) { applyFightResult(pet(), stateOf()) }
    }
}
