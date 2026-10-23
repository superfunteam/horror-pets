package team.superfun.horrorpets.ui

import kotlin.random.Random
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.data.Clock
import team.superfun.horrorpets.data.SaveStore
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Slot

private const val NOW = 1_800_000_000_000L

/** Learning attacks as pets level up, and equipping them from MOVES. */
class AttacksFlowTest {
    private class Game(vararg pets: PetState) {
        val scope = TestScope(StandardTestDispatcher())
        var saved: GameState? = null
        private val initial = GameState(pets = pets.associateBy { it.id }, selected = pets.first().id, lastSeenMs = NOW)
        val vm = GameViewModel(
            store = object : SaveStore {
                override suspend fun load() = initial

                override suspend fun save(s: GameState) {
                    saved = s
                }
            },
            clock = object : Clock {
                override fun nowMs() = NOW
            },
            rng = Random(1),
            scope = scope,
            feedAnim = { Anim.REVIEW },
        )

        val ui: UiState get() = vm.state.value
        val pet: PetState get() = ui.game.pets.getValue(ui.game.selected!!)

        fun start(): Game {
            vm.start()
            scope.advanceUntilIdle()
            vm.onStart()
            scope.advanceUntilIdle()
            return this
        }

        fun finishAnim() = vm.onAnimFinished(ui.petAnim!!.token)
    }

    @Test
    fun aPetPastItsUnlockLevelsIsOfferedEachAttackInTurn() {
        val g = Game(PetState(PetId.JASON, level = 3)).start()
        assertEquals(Screen.Learn(PetId.JASON, "jason_light_1"), g.ui.screen)
        assertTrue("it rings in", "unlock" in g.ui.sfx)

        g.vm.onLearn(learn = true)
        assertEquals("jason_light_1", g.pet.equipped[Slot.LIGHT])
        assertEquals(Screen.Learn(PetId.JASON, "jason_heavy_1"), g.ui.screen)

        g.vm.onLearn(learn = false)
        assertEquals("kept: not equipped, but known", null, g.pet.equipped[Slot.HEAVY])
        assertEquals(setOf("jason_light_1", "jason_heavy_1"), g.pet.known)
        assertEquals(Screen.Home, g.ui.screen)
        g.scope.advanceUntilIdle()
        assertEquals(g.pet, g.saved!!.pets.getValue(PetId.JASON))
    }

    @Test
    fun backPutsTheChoiceOffUntilTheNextLaunch() {
        val g = Game(PetState(PetId.JASON, level = 2)).start()
        g.vm.onLearnLater()
        assertEquals(Screen.Home, g.ui.screen)
        g.vm.onMovesPressed()
        g.vm.onMovesBack()
        assertEquals("not offered again this session", Screen.Home, g.ui.screen)
        assertTrue(g.pet.known.isEmpty())

        val next = Game(g.pet).start()
        assertEquals("but it is next launch", Screen.Learn(PetId.JASON, "jason_light_1"), next.ui.screen)
    }

    @Test
    fun aLevelUpFromTrainingOffersTheAttackOnceItIsOver() {
        val g = Game(PetState(PetId.JASON, level = 1, xp = 95)).start()
        assertEquals(Screen.Home, g.ui.screen)
        g.vm.onCare(CareAction.TRAIN)
        g.vm.onTrainingOver(0)
        assertEquals(2, g.pet.level)
        assertTrue("not before the results card is dismissed", g.ui.screen is Screen.Training)
        g.vm.onTrainingDone()
        assertEquals(Screen.Learn(PetId.JASON, "jason_light_1"), g.ui.screen)
    }

    @Test
    fun aDueFakeDeathComesFirst() {
        val g = Game(PetState(PetId.JASON, level = 2, fakeDeathPending = true)).start()
        assertEquals(Screen.FakeDeath(PetId.JASON), g.ui.screen)
        g.vm.onFakeDeathDone()
        assertEquals(Screen.Learn(PetId.JASON, "jason_light_1"), g.ui.screen)
    }

    @Test
    fun movesEquipsWhatThePetHasUnlocked() {
        val jason = PetState(PetId.JASON, level = 7, known = setOf("jason_light_1", "jason_heavy_1", "jason_special_1", "jason_light_2", "jason_heavy_2"))
        val g = Game(jason).start()
        assertEquals(Screen.Home, g.ui.screen)
        g.vm.onMovesPressed()
        assertEquals(Screen.Moves, g.ui.screen)

        g.vm.onEquip("jason_heavy_2")
        assertEquals("jason_heavy_2", g.pet.equipped[Slot.HEAVY])

        g.vm.onEquip("jason_light_3") // level 10
        assertTrue("refuse" in g.ui.sfx)
        assertEquals(null, g.pet.equipped[Slot.LIGHT])

        g.vm.onEquip("freddy_light_1")
        assertEquals(null, g.pet.equipped[Slot.LIGHT])

        g.vm.onEquip("jason_heavy_0") // back to the starting heavy
        assertEquals(null, g.pet.equipped[Slot.HEAVY])

        g.vm.onMovesBack()
        assertEquals(Screen.Home, g.ui.screen)
    }

    @Test
    fun equippingAnUnlockedAttackItWasNeverOfferedLearnsIt() {
        val g = Game(PetState(PetId.FREDDY, level = 4)).start()
        repeat(3) { g.vm.onLearnLater() } // putting one off goes straight on to the next
        assertEquals(Screen.Home, g.ui.screen)
        g.vm.onMovesPressed()
        g.vm.onEquip("freddy_special_1")
        assertEquals("freddy_special_1", g.pet.equipped[Slot.SPECIAL])
        assertTrue("freddy_special_1" in g.pet.known)
    }
}
