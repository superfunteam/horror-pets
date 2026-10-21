@file:OptIn(ExperimentalCoroutinesApi::class)

package team.superfun.horrorpets.ui

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.data.Clock
import team.superfun.horrorpets.data.SaveStore
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Roll
import team.superfun.horrorpets.fight.ScriptedRandom
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.AwaySummary
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

// A realistic epoch (2027-01-15), safely past Tuning.MIN_VALID_EPOCH_MS.
private const val T0 = 1_800_000_000_000L
private const val HOUR = Tuning.HOUR_MS
private const val DAY = Tuning.DAY_MS
private const val MINUTE = Tuning.MINUTE_MS

private fun gameOf(
    selected: PetId? = PetId.JASON,
    lastSeenMs: Long? = T0,
    vararg pets: PetState,
) = GameState(
    pets = PetId.entries.associateWith { id -> pets.firstOrNull { it.id == id } ?: PetState(id) },
    selected = selected,
    lastSeenMs = lastSeenMs,
)

private fun jason(
    hunger: Double = 20.0,
    funLevel: Double = 80.0,
    strength: Double = 20.0,
    level: Int = 1,
    xp: Int = 0,
    sceneIndex: Int = 0,
    fakeDeathPending: Boolean = false,
) = PetState(PetId.JASON, hunger, funLevel, strength, level, xp, sceneIndex = sceneIndex, fakeDeathPending = fakeDeathPending)

private class FakeClock(var now: Long) : Clock {
    override fun nowMs() = now
}

private class FakeStore(var initial: GameState) : SaveStore {
    val saves = mutableListOf<GameState>()
    var failSaves = false
    var failLoad = false
    var saveAttempts = 0

    val last: GameState get() = saves.last()

    override suspend fun load(): GameState {
        if (failLoad) throw IOException("read failed")
        return initial
    }

    override suspend fun save(s: GameState) {
        saveAttempts++
        if (failSaves) throw IOException("disk full")
        saves += s
    }
}

/** A store whose first save is slow, to prove saves still land in the order the state changed. */
private class SlowFirstSaveStore(private val initial: GameState) : SaveStore {
    val saved = mutableListOf<GameState>()
    private var calls = 0

    override suspend fun load() = initial

    override suspend fun save(s: GameState) {
        val slow = calls++ == 0
        delay(if (slow) 500 else 0)
        saved += s
    }
}

private class Rig(
    initial: GameState,
    rng: Random = Random(7),
    val store: FakeStore = FakeStore(initial),
    val clock: FakeClock = FakeClock(T0),
) {
    val errors = mutableListOf<Throwable>()
    val scope = TestScope(StandardTestDispatcher())
    val vm = GameViewModel(
        store = store,
        clock = clock,
        rng = rng,
        scope = scope,
        feedAnim = { if (it == PetId.FREDDY || it == PetId.GHOSTFACE) Anim.WORK else Anim.REVIEW },
        onError = { errors += it },
    )

    val ui: UiState get() = vm.state.value
    val game: GameState get() = ui.game
    val jason: PetState get() = game.pets.getValue(PetId.JASON)

    fun settle() = scope.advanceUntilIdle()

    fun started(): Rig {
        vm.start()
        settle()
        return this
    }

    fun finishAnim() = vm.onAnimFinished(ui.petAnim!!.token)

    fun finishAllAnims() {
        while (ui.petAnim != null) finishAnim()
    }
}

class GameViewModelTest {

    // ---- start() ----------------------------------------------------------------------------

    @Test
    fun firstLaunchOnboards() {
        val rig = Rig(GameState(selected = null, lastSeenMs = null))
        assertEquals(Screen.Loading, rig.ui.screen)
        rig.started()
        assertEquals(Screen.Switcher(SwitcherMode.ONBOARD), rig.ui.screen)
        assertEquals(listOf("choose_your_killer"), rig.ui.voice)
        assertNull(rig.ui.toast)
        assertEquals(T0, rig.store.last.lastSeenMs)
    }

    @Test
    fun returningGoesHomeWithToast() {
        val rig = Rig(gameOf(lastSeenMs = T0 - (2 * DAY + 4 * HOUR)))
        rig.started()
        assertEquals(Screen.Home, rig.ui.screen)
        assertTrue(rig.ui.toast!!, rig.ui.toast!!.startsWith("Away 2d 4h"))
        assertEquals("Away 2d 4h · Hunger +80", rig.ui.toast)
    }

    @Test
    fun shortAbsenceNoToast() {
        val rig = Rig(gameOf(lastSeenMs = T0 - 5 * MINUTE))
        rig.started()
        assertEquals(Screen.Home, rig.ui.screen)
        assertNull(rig.ui.toast)
    }

    @Test
    fun underAnHourAwayToastIsInMinutes() {
        val rig = Rig(gameOf(lastSeenMs = T0 - 25 * MINUTE))
        rig.started()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals("Away 25m \u00b7 Hunger +2", rig.ui.toast)
    }

    @Test
    fun exactlyTenMinutesAwayShowsAToast() {
        val rig = Rig(gameOf(lastSeenMs = T0 - 10 * MINUTE))
        rig.started()
        assertNotNull(rig.ui.toast)
    }

    @Test
    fun sevenDaysShowsFakeDeathThenHome() {
        val rig = Rig(gameOf(lastSeenMs = T0 - 7 * DAY))
        rig.started()
        assertEquals(Screen.FakeDeath(PetId.JASON), rig.ui.screen)
        assertNull(rig.ui.toast)
        assertTrue(rig.jason.fakeDeathPending)

        rig.vm.onFakeDeathDone()
        rig.settle()
        assertEquals(Screen.Home, rig.ui.screen)
        assertFalse(rig.jason.fakeDeathPending)
        assertFalse(rig.store.last.pets.getValue(PetId.JASON).fakeDeathPending)
        // Each pet shows its own fake death the first time it is viewed.
        assertTrue(rig.game.pets.getValue(PetId.FREDDY).fakeDeathPending)
    }

    @Test
    fun fakeDeathDoneTwiceIsHarmless() {
        val rig = Rig(gameOf(lastSeenMs = T0 - 7 * DAY)).started()
        rig.vm.onFakeDeathDone()
        rig.vm.onFakeDeathDone()
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun loadFailureStartsAFreshGameInsteadOfCrashing() {
        val store = FakeStore(gameOf()).apply { failLoad = true }
        val rig = Rig(gameOf(), store = store).started()
        assertEquals(Screen.Switcher(SwitcherMode.ONBOARD), rig.ui.screen)
        assertEquals(1, rig.errors.size)
        assertTrue(rig.errors[0] is IOException)
    }

    @Test
    fun startTwiceLoadsOnce() {
        val rig = Rig(gameOf()).started()
        rig.vm.onCare(CareAction.FEED)
        rig.vm.start()
        rig.settle()
        assertTrue(rig.ui.busy)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    // ---- onboarding and the switcher ---------------------------------------------------------

    @Test
    fun onboardingPickSelectsAndGoesHome() {
        val rig = Rig(GameState(selected = null, lastSeenMs = null)).started()
        rig.vm.onSwitcherBack() // ignored while onboarding
        assertEquals(Screen.Switcher(SwitcherMode.ONBOARD), rig.ui.screen)

        rig.vm.onSwitcherPick(PetId.GHOSTFACE)
        rig.settle()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(PetId.GHOSTFACE, rig.game.selected)
        assertEquals(PetId.GHOSTFACE, rig.store.last.selected)
    }

    @Test
    fun selectModeBackReturnsHome() {
        val rig = Rig(gameOf()).started()
        rig.vm.openSwitcher()
        assertEquals(Screen.Switcher(SwitcherMode.SELECT), rig.ui.screen)
        rig.vm.onSwitcherBack()
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun selectingAPendingPetShowsItsFakeDeathOnce() {
        val freddy = PetState(PetId.FREDDY, fakeDeathPending = true)
        val rig = Rig(gameOf(pets = arrayOf(freddy))).started()
        rig.vm.openSwitcher()
        rig.vm.onSwitcherPick(PetId.FREDDY)
        assertEquals(Screen.FakeDeath(PetId.FREDDY), rig.ui.screen)
        assertEquals(PetId.FREDDY, rig.game.selected)

        rig.vm.onFakeDeathDone()
        assertEquals(Screen.Home, rig.ui.screen)
        assertFalse(rig.game.pets.getValue(PetId.FREDDY).fakeDeathPending)
    }

    @Test
    fun switcherPickIsOnlyAcceptedOnTheSwitcher() {
        val rig = Rig(gameOf()).started()
        rig.vm.onSwitcherPick(PetId.FREDDY)
        assertEquals(PetId.JASON, rig.game.selected)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun opponentSwitcherExcludesSelf() {
        val rig = Rig(gameOf()).started()
        rig.vm.onFightPressed()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
        assertFalse(rig.ui.busy)
        // The UI derives the candidates from game.selected: everyone else, so three of four pets.
        val opponents = PetId.entries.filter { it != rig.game.selected }
        assertEquals(3, opponents.size)
        assertFalse(PetId.JASON in opponents)

        rig.vm.onSwitcherPick(PetId.JASON) // never a legal opponent
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)

        rig.vm.onSwitcherPick(PetId.FREDDY)
        assertEquals(Screen.Versus(PetId.FREDDY), rig.ui.screen)
        assertEquals(listOf("jason", "versus", "freddy"), rig.ui.voice)
        assertEquals(PetId.JASON, rig.game.selected)
    }

    @Test
    fun opponentSwitcherBackReturnsHome() {
        val rig = Rig(gameOf()).started()
        rig.vm.onFightPressed()
        rig.vm.onSwitcherBack()
        assertEquals(Screen.Home, rig.ui.screen)
    }

    // ---- care --------------------------------------------------------------------------------

    @Test
    fun feedTwiceWhileBusyAppliesOnce() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.FEED)
        rig.vm.onCare(CareAction.FEED)
        rig.vm.onCare(CareAction.FEED)
        assertEquals(25.0, rig.jason.hunger, 1e-9)
        assertTrue(rig.ui.busy)
        assertEquals(listOf("chomp"), rig.ui.sfx)

        rig.finishAnim()
        assertFalse(rig.ui.busy)
        assertNull(rig.ui.petAnim)
        assertEquals(25.0, rig.jason.hunger, 1e-9)

        rig.settle()
        // One save for start(), one for the single feed.
        assertEquals(25.0, rig.store.last.pets.getValue(PetId.JASON).hunger, 1e-9)
        assertEquals(2, rig.store.saves.size)
    }

    @Test
    fun feedPlaysTheManifestFeedAnimForThatPet() {
        val rig = Rig(gameOf(PetId.FREDDY, T0, PetState(PetId.FREDDY, hunger = 60.0))).started()
        rig.vm.onCare(CareAction.FEED)
        assertEquals(Anim.WORK, rig.ui.petAnim!!.anim)

        val rig2 = Rig(gameOf(pets = arrayOf(jason(hunger = 60.0)))).started()
        rig2.vm.onCare(CareAction.FEED)
        assertEquals(Anim.REVIEW, rig2.ui.petAnim!!.anim)
    }

    @Test
    fun playSequencesJumpThenWave() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 20.0, funLevel = 40.0)))).started()
        rig.vm.onCare(CareAction.PLAY)
        val first = rig.ui.petAnim!!
        assertEquals(Anim.JUMP, first.anim)
        assertTrue(rig.ui.busy)
        // Stats change once, at the start.
        assertEquals(65.0, rig.jason.funLevel, 1e-9)
        assertEquals(28.0, rig.jason.hunger, 1e-9)
        assertEquals(listOf("play"), rig.ui.sfx)

        rig.vm.onAnimFinished(first.token)
        val second = rig.ui.petAnim!!
        assertEquals(Anim.WAVE, second.anim)
        assertNotEquals(first.token, second.token)
        assertTrue("still busy between steps", rig.ui.busy)
        assertEquals(65.0, rig.jason.funLevel, 1e-9)

        // A late repeat of the first step's token must not skip the wave.
        rig.vm.onAnimFinished(first.token)
        assertEquals(second, rig.ui.petAnim)

        rig.vm.onAnimFinished(second.token)
        assertNull(rig.ui.petAnim)
        assertFalse(rig.ui.busy)
        assertEquals(65.0, rig.jason.funLevel, 1e-9)
    }

    @Test
    fun trainPlaysAttackTwiceWithDistinctTokens() {
        val rig = Rig(gameOf()).started()
        rig.vm.onCare(CareAction.TRAIN)
        val first = rig.ui.petAnim!!
        assertEquals(Anim.ATTACK, first.anim)
        assertEquals(26.0, rig.jason.strength, 1e-9)
        assertEquals(listOf("train"), rig.ui.sfx)

        rig.vm.onAnimFinished(first.token)
        val second = rig.ui.petAnim!!
        assertEquals(Anim.ATTACK, second.anim)
        assertNotEquals(first.token, second.token)
        assertTrue(rig.ui.busy)

        rig.vm.onAnimFinished(second.token)
        assertFalse(rig.ui.busy)
        assertEquals(26.0, rig.jason.strength, 1e-9)
    }

    @Test
    fun staleAnimTokenIsIgnored() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 80.0)))).started()
        rig.vm.onCare(CareAction.FEED)
        val token = rig.ui.petAnim!!.token

        rig.vm.onAnimFinished(token + 99)
        rig.vm.onAnimFinished(-1)
        assertTrue(rig.ui.busy)
        assertEquals(token, rig.ui.petAnim!!.token)

        rig.vm.onAnimFinished(token)
        assertFalse(rig.ui.busy)

        // The finished token cannot end the next action.
        rig.vm.onCare(CareAction.PLAY)
        val next = rig.ui.petAnim!!
        assertNotEquals(token, next.token)
        rig.vm.onAnimFinished(token)
        assertEquals(next, rig.ui.petAnim)
        assertTrue(rig.ui.busy)
    }

    @Test
    fun animFinishedWithNothingPlayingIsIgnored() {
        val rig = Rig(gameOf()).started()
        rig.vm.onAnimFinished(1)
        rig.vm.onAnimFinished(0)
        assertEquals(Screen.Home, rig.ui.screen)
        assertFalse(rig.ui.busy)
    }

    @Test
    fun levelUpAddsTheLevelUpSound() {
        val rig = Rig(gameOf(pets = arrayOf(jason(xp = 95)))).started()
        rig.vm.onCare(CareAction.TRAIN)
        assertEquals(2, rig.jason.level)
        assertEquals(listOf("train", "levelup"), rig.ui.sfx)
    }

    @Test
    fun feedingAFullPetIsRefused() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 5.0)))).started()
        val before = rig.jason
        rig.vm.onCare(CareAction.FEED)
        assertEquals(RefuseReason.TOO_FULL, rig.ui.refuse)
        assertEquals(Anim.FAIL, rig.ui.petAnim!!.anim)
        assertEquals(listOf("refuse"), rig.ui.sfx)
        assertTrue(rig.ui.busy)
        assertEquals(before, rig.jason)

        rig.vm.onCare(CareAction.PLAY) // busy: ignored
        assertEquals(before, rig.jason)

        rig.finishAnim()
        assertFalse(rig.ui.busy)
        assertEquals(before, rig.jason)

        // The next real action clears the refusal.
        rig.vm.onCare(CareAction.PLAY)
        assertNull(rig.ui.refuse)
    }

    @Test
    fun trainingAStarvingPetIsRefused() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 90.0)))).started()
        val before = rig.jason
        rig.vm.onCare(CareAction.TRAIN)
        assertEquals(RefuseReason.TOO_HUNGRY, rig.ui.refuse)
        assertEquals(Anim.FAIL, rig.ui.petAnim!!.anim)
        assertEquals(before, rig.jason)
    }

    @Test
    fun anyPressDuringAnActionIsIgnored() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.FEED)
        val token = rig.ui.petAnim!!.token
        val snapshot = rig.ui

        rig.vm.onCare(CareAction.PLAY)
        rig.vm.onCare(CareAction.TRAIN)
        rig.vm.onWalk(1)
        rig.vm.onWalk(-1)
        rig.vm.onFightPressed()
        rig.vm.openSwitcher()
        assertEquals(snapshot, rig.ui)
        assertEquals(token, rig.ui.petAnim!!.token)
    }

    @Test
    fun careIsIgnoredOffTheHomeScreen() {
        val rig = Rig(gameOf()).started()
        rig.vm.openSwitcher()
        val before = rig.ui
        rig.vm.onCare(CareAction.FEED)
        rig.vm.onWalk(1)
        rig.vm.onFightPressed()
        assertEquals(before, rig.ui)
    }

    // ---- walking -----------------------------------------------------------------------------

    @Test
    fun walkWrapsScenes() {
        val rig = Rig(gameOf(pets = arrayOf(jason(sceneIndex = 2)))).started()
        rig.vm.onWalk(1)
        assertEquals(Anim.RUN_RIGHT, rig.ui.petAnim!!.anim)
        assertTrue(rig.ui.busy)
        assertEquals("the scene changes after the run", 2, rig.jason.sceneIndex)

        rig.finishAnim()
        assertFalse(rig.ui.busy)
        assertEquals(0, rig.jason.sceneIndex)
        rig.settle()
        assertEquals(0, rig.store.last.pets.getValue(PetId.JASON).sceneIndex)
    }

    @Test
    fun walkLeftWrapsTheOtherWay() {
        val rig = Rig(gameOf(pets = arrayOf(jason(sceneIndex = 0)))).started()
        rig.vm.onWalk(-1)
        assertEquals(Anim.RUN_LEFT, rig.ui.petAnim!!.anim)
        rig.finishAnim()
        assertEquals(2, rig.jason.sceneIndex)
        assertEquals(Tuning.SCENE_COUNT - 1, rig.jason.sceneIndex)
    }

    @Test
    fun walkMashingMovesOneScene() {
        val rig = Rig(gameOf()).started()
        repeat(5) { rig.vm.onWalk(1) }
        rig.finishAnim()
        assertEquals(1, rig.jason.sceneIndex)
    }

    @Test
    fun walkRejectsBadDirections() {
        val rig = Rig(gameOf()).started()
        rig.vm.onWalk(0)
        rig.vm.onWalk(2)
        assertFalse(rig.ui.busy)
        assertEquals(0, rig.jason.sceneIndex)
    }

    @Test
    fun sceneIsRememberedPerPet() {
        val rig = Rig(gameOf()).started()
        rig.vm.onWalk(1)
        rig.finishAnim()
        rig.vm.openSwitcher()
        rig.vm.onSwitcherPick(PetId.FREDDY)
        assertEquals(1, rig.jason.sceneIndex)
        assertEquals(0, rig.game.pets.getValue(PetId.FREDDY).sceneIndex)
    }

    // ---- fight: entry ------------------------------------------------------------------------

    @Test
    fun fightRefusedWhenStarving() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 90.0)))).started()
        rig.vm.onFightPressed()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(RefuseReason.TOO_HUNGRY, rig.ui.refuse)
        assertEquals(Anim.FAIL, rig.ui.petAnim!!.anim)
        assertTrue(rig.ui.busy)
        assertEquals(listOf("refuse"), rig.ui.sfx)

        rig.vm.onFightPressed() // mashing during the refusal
        assertEquals(Screen.Home, rig.ui.screen)

        rig.finishAnim()
        assertFalse(rig.ui.busy)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    // ---- fight: full flows -------------------------------------------------------------------

    /** A script where Jason (strength 100) trades STRIKEs with a CPU that only ever picks STRIKE. */
    private fun winningScript(): ScriptedRandom {
        val roll = listOf(Roll().miss, Roll().variance, Roll().crit)
        val doubles = buildList {
            addAll(roll + roll) // turn 1: no habit yet, so no habit roll
            repeat(2) { // turns 2 and 3: a habit roll the CPU ignores (0.99 >= 0.4), then both attack
                add(0.99)
                addAll(roll + roll)
            }
        }
        return ScriptedRandom(doubles, ints = listOf(0, 0, 0, 0, 0))
    }

    private fun strongJason() = gameOf(pets = arrayOf(jason(strength = 100.0)))

    private fun Rig.toFight(opponent: PetId = PetId.FREDDY) {
        vm.onFightPressed()
        vm.onSwitcherPick(opponent)
        vm.onVersusDone()
        check(ui.screen is Screen.Fight) { "expected Fight, got ${ui.screen}" }
    }

    @Test
    fun versusDoneStartsTheFight() {
        val rig = Rig(strongJason(), rng = winningScript()).started()
        rig.vm.onFightPressed()
        rig.vm.onSwitcherPick(PetId.FREDDY)
        rig.vm.onVersusDone()
        val fight = rig.ui.screen as Screen.Fight
        assertEquals(PetId.JASON, fight.fight.player.id)
        assertEquals(PetId.FREDDY, fight.fight.cpu.id)
        assertEquals(1, fight.fight.turn)
        assertTrue(fight.lastEvents.isEmpty())
        assertNull(fight.playerMove)
        assertNull(fight.cpuMove)
        assertFalse(rig.ui.busy)

        rig.vm.onVersusDone() // key repeat
        assertEquals(fight, rig.ui.screen)
    }

    @Test
    fun fullFightReachesResultsAndSaves() {
        val rig = Rig(strongJason(), rng = winningScript()).started()
        rig.toFight()
        rig.vm.consumeSounds()

        var turns = 0
        while (rig.ui.screen is Screen.Fight) {
            rig.vm.onMove(Move.STRIKE)
            val fight = rig.ui.screen as Screen.Fight
            assertTrue("a turn's events are on the screen", fight.lastEvents.isNotEmpty())
            assertTrue("the fight screen plays a turn's sounds as it shows them", rig.ui.sfx.isEmpty())
            assertTrue(rig.ui.voice.isEmpty())
            assertEquals(Move.STRIKE, fight.playerMove)
            assertEquals("the scripted CPU only ever strikes", Move.STRIKE, fight.cpuMove)
            assertTrue(rig.ui.busy)
            assertNotNull(rig.ui.petAnim)
            if (fight.fight.winner != null) {
                assertEquals("rewards wait for the animation", 0, rig.jason.wins)
            }
            rig.finishAnim()
            turns++
            check(turns < 20) { "the fight never ended" }
        }
        assertEquals(3, turns)

        val results = rig.ui.screen as Screen.Results
        assertTrue(results.won)
        assertFalse(results.ran)
        assertEquals(40, results.reward.xpGained)
        assertFalse(rig.ui.busy)
        assertNull(rig.ui.petAnim)
        assertEquals(1, rig.jason.wins)
        assertEquals(0, rig.jason.losses)
        assertEquals(results.reward.pet, rig.jason)
        assertEquals(35.0, rig.jason.hunger, 1e-9)

        // Only the result is announced here; the hits and the KO were the fight screen's to play.
        assertTrue(rig.ui.sfx.isEmpty())
        assertEquals(listOf("you_win"), rig.ui.voice)

        rig.settle()
        assertEquals(1, rig.store.last.pets.getValue(PetId.JASON).wins)

        rig.vm.onResultsDone()
        assertEquals(Screen.Home, rig.ui.screen)
        rig.vm.onResultsDone() // key repeat
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun losingAFightRecordsALoss() {
        // The CPU always HEAVYs (40 <= roll < 70) and Jason always BLOCKs, which does nothing against it.
        val heavy = listOf(Roll().miss, Roll().variance, Roll().crit)
        val doubles = buildList {
            addAll(heavy)
            repeat(2) {
                add(0.99)
                addAll(heavy)
            }
        }
        val rig = Rig(strongJason(), rng = ScriptedRandom(doubles, ints = listOf(0, 0, 40, 40, 40))).started()
        rig.toFight()
        rig.vm.consumeSounds()

        var turns = 0
        while (rig.ui.screen is Screen.Fight) {
            rig.vm.onMove(Move.BLOCK)
            val fight = rig.ui.screen as Screen.Fight
            assertEquals(Move.BLOCK, fight.playerMove)
            assertEquals(Move.HEAVY, fight.cpuMove)
            rig.finishAnim()
            check(++turns < 20)
        }
        assertEquals(3, turns)
        val results = rig.ui.screen as Screen.Results
        assertFalse(results.won)
        assertFalse(results.ran)
        assertEquals(15, results.reward.xpGained)
        assertEquals(1, rig.jason.losses)
        assertEquals(0, rig.jason.wins)
        assertEquals(35.0, rig.jason.hunger, 1e-9)
        assertEquals(75.0, rig.jason.funLevel, 1e-9)
        assertTrue(rig.ui.sfx.isEmpty())
        assertEquals(listOf("you_lose"), rig.ui.voice)
        rig.settle()
        assertEquals(1, rig.store.last.pets.getValue(PetId.JASON).losses)
    }

    @Test
    fun aWinThatLevelsThePetQueuesLevelupWithTheResult() {
        // 90 xp + the win's 40 crosses level 2's 100.
        val rig = Rig(gameOf(pets = arrayOf(jason(strength = 100.0, xp = 90))), rng = winningScript()).started()
        rig.toFight()
        rig.vm.consumeSounds()

        var turns = 0
        while (rig.ui.screen is Screen.Fight) {
            rig.vm.onMove(Move.STRIKE)
            assertTrue("no levelup before the results", "levelup" !in rig.ui.sfx)
            rig.finishAnim()
            check(++turns < 20)
        }
        val results = rig.ui.screen as Screen.Results
        assertTrue(results.won)
        assertEquals(1, results.reward.levelsGained)
        assertEquals(2, rig.jason.level)
        assertEquals(30, rig.jason.xp)
        assertEquals(listOf("levelup"), rig.ui.sfx)
        assertEquals(listOf("you_win"), rig.ui.voice)
    }

    @Test
    fun runningAwayEndsTheFightAsALoss() {
        val rig = Rig(strongJason(), rng = ScriptedRandom(ints = listOf(0, 0))).started()
        rig.toFight()
        rig.vm.consumeSounds()
        val before = rig.jason

        rig.vm.onMove(Move.RUN)
        val fight = rig.ui.screen as Screen.Fight
        assertEquals(Side.CPU, fight.fight.winner)
        assertTrue(fight.fight.playerRan)
        assertTrue(fight.lastEvents.isEmpty())
        assertEquals(Move.RUN, fight.playerMove)
        assertNull("the CPU never gets to pick when the player runs", fight.cpuMove)
        assertTrue(rig.ui.busy)
        assertNotNull(rig.ui.petAnim)
        assertEquals(before, rig.jason)

        rig.finishAnim()
        val results = rig.ui.screen as Screen.Results
        assertTrue(results.ran)
        assertFalse(results.won)
        assertEquals(1, rig.jason.losses)
        assertEquals(before.hunger, rig.jason.hunger, 1e-9)
        assertEquals(-10.0, rig.jason.moodBonus, 1e-9)
        assertTrue(rig.ui.voice.isEmpty())
    }

    @Test
    fun aTurnQueuesNoSoundsEvenForACrit() {
        // Turn 1: Jason's strike crits, Freddy's strike lands.
        val crit = Roll(crit = 0.0)
        val plain = Roll()
        val rng = ScriptedRandom(
            listOf(crit.miss, crit.variance, crit.crit, plain.miss, plain.variance, plain.crit),
            ints = listOf(0, 0, 0),
        )
        val rig = Rig(strongJason(), rng = rng).started()
        rig.toFight()
        rig.vm.consumeSounds()

        rig.vm.onMove(Move.STRIKE)
        val fight = rig.ui.screen as Screen.Fight
        assertTrue(fight.lastEvents.any { it is FightEvent.Hit && it.crit })
        assertTrue("hit, crit and the rest are played by the fight screen", rig.ui.sfx.isEmpty())
        assertTrue("so is the announcer's critical", rig.ui.voice.isEmpty())
    }

    @Test
    fun movesDuringATurnAnimationAreIgnored() {
        val rig = Rig(strongJason(), rng = winningScript()).started()
        rig.toFight()

        rig.vm.onMove(Move.STRIKE)
        val afterFirst = rig.ui
        rig.vm.onMove(Move.STRIKE)
        rig.vm.onMove(Move.HEAVY)
        rig.vm.onMove(Move.RUN)
        assertEquals(afterFirst, rig.ui)
        val fight = (rig.ui.screen as Screen.Fight).fight
        assertEquals(2, fight.turn)
        assertEquals(listOf(Move.STRIKE), fight.playerHistory)

        rig.finishAnim()
        assertFalse(rig.ui.busy)
        assertTrue(rig.ui.screen is Screen.Fight)
    }

    @Test
    fun specialWithoutAFullMeterIsIgnored() {
        val rig = Rig(strongJason(), rng = ScriptedRandom(ints = listOf(0, 0))).started()
        rig.toFight()
        val before = rig.ui
        rig.vm.onMove(Move.SPECIAL)
        assertEquals(before, rig.ui)
    }

    @Test
    fun movesOutsideAFightAreIgnored() {
        val rig = Rig(gameOf()).started()
        rig.vm.onMove(Move.STRIKE)
        assertEquals(Screen.Home, rig.ui.screen)
        assertFalse(rig.ui.busy)
    }

    // ---- time passing ------------------------------------------------------------------------

    @Test
    fun tickDecaysWhileOpen() {
        val rig = Rig(gameOf()).started()
        rig.clock.now += 3 * HOUR
        rig.vm.tick()
        assertEquals(32.0, rig.jason.hunger, 1e-9)
        assertEquals(71.0, rig.jason.funLevel, 1e-9)
        assertEquals(Screen.Home, rig.ui.screen)
        assertNull(rig.ui.toast)
        rig.settle()
        assertEquals(32.0, rig.store.last.pets.getValue(PetId.JASON).hunger, 1e-9)
        assertEquals(rig.clock.now, rig.store.last.lastSeenMs)
    }

    @Test
    fun hourlyTicksAddUpToTheSameDecay() {
        val rig = Rig(gameOf()).started()
        repeat(3) {
            rig.clock.now += HOUR
            rig.vm.tick()
        }
        assertEquals(32.0, rig.jason.hunger, 1e-9)
    }

    @Test
    fun tickThatChangesNothingDoesNotSave() {
        val rig = Rig(gameOf()).started()
        val saves = rig.store.saves.size
        rig.vm.tick() // the clock has not moved
        rig.settle()
        assertEquals(saves, rig.store.saves.size)
    }

    @Test
    fun tickAndResumeBeforeTheSaveIsLoadedDoNothing() {
        val rig = Rig(gameOf(lastSeenMs = T0 - 2 * HOUR))
        rig.vm.tick()
        rig.vm.onResume()
        rig.settle()
        assertEquals(Screen.Loading, rig.ui.screen)
        assertTrue("an unloaded game must never overwrite the save", rig.store.saves.isEmpty())
    }

    @Test
    fun resumeAfterWeekTriggersFakeDeath() {
        val rig = Rig(gameOf()).started()
        assertEquals(Screen.Home, rig.ui.screen)
        rig.clock.now += 7 * DAY
        rig.vm.onResume()
        assertEquals(Screen.FakeDeath(PetId.JASON), rig.ui.screen)
        assertTrue(rig.jason.fakeDeathPending)
        assertNull(rig.ui.toast)
        assertEquals(90.0, rig.jason.hunger, 1e-9)
        rig.settle()
        assertTrue(rig.store.last.pets.getValue(PetId.JASON).fakeDeathPending)
    }

    @Test
    fun resumeAfterAShortNapJustDecays() {
        val rig = Rig(gameOf()).started()
        rig.clock.now += 5 * HOUR
        rig.vm.onResume()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(40.0, rig.jason.hunger, 1e-9)
        assertNull(rig.ui.toast)
    }

    @Test
    fun fakeDeathFromATickWaitsForTheActionToFinish() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.FEED)
        rig.clock.now += 7 * DAY
        rig.vm.tick()
        assertEquals("not mid-animation", Screen.Home, rig.ui.screen)
        assertTrue(rig.ui.busy)

        rig.finishAnim()
        assertEquals(Screen.FakeDeath(PetId.JASON), rig.ui.screen)
        assertFalse(rig.ui.busy)
    }

    @Test
    fun fakeDeathFromATickWaitsOffTheHomeScreenUntilHome() {
        val rig = Rig(gameOf()).started()
        rig.vm.openSwitcher()
        rig.clock.now += 7 * DAY
        rig.vm.tick()
        assertEquals(Screen.Switcher(SwitcherMode.SELECT), rig.ui.screen)

        rig.vm.onSwitcherBack()
        assertEquals(Screen.FakeDeath(PetId.JASON), rig.ui.screen)
    }

    // ---- saving ------------------------------------------------------------------------------

    @Test
    fun saveFailureDoesNotCrashOrStopTheGame() {
        val store = FakeStore(gameOf(pets = arrayOf(jason(hunger = 60.0)))).apply { failSaves = true }
        val rig = Rig(gameOf(), store = store).started()
        assertEquals(Screen.Home, rig.ui.screen)

        rig.vm.onCare(CareAction.FEED)
        rig.finishAnim()
        rig.clock.now += HOUR
        rig.vm.tick()
        rig.vm.onWalk(1)
        rig.finishAnim()
        rig.settle()

        // The game kept running from memory, every failure was reported, and it kept trying to save.
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(1, rig.jason.sceneIndex)
        assertTrue(rig.errors.isNotEmpty())
        assertTrue(rig.errors.all { it is IOException })
        assertEquals(store.saveAttempts, rig.errors.size)
        assertTrue(store.saveAttempts >= 4)

        // And it recovers once the disk does.
        store.failSaves = false
        rig.vm.onCare(CareAction.PLAY)
        rig.settle()
        assertEquals(rig.game, store.last)
    }

    @Test
    fun everyStateChangeIsSaved() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.FEED)
        rig.finishAnim()
        rig.vm.onWalk(1)
        rig.finishAnim()
        rig.settle()
        assertEquals(rig.game, rig.store.last)
    }

    @Test
    fun savesLandInTheOrderTheStateChanged() {
        val store = SlowFirstSaveStore(gameOf(pets = arrayOf(jason(hunger = 60.0))))
        val scope = TestScope(StandardTestDispatcher())
        val vm = GameViewModel(store, FakeClock(T0), Random(1), scope, { Anim.REVIEW })
        vm.start()
        scope.runCurrent() // the first save is now in flight and slow
        vm.onCare(CareAction.FEED) // its successor must wait for it
        scope.advanceUntilIdle()
        assertEquals(listOf(60.0, 25.0), store.saved.map { it.pets.getValue(PetId.JASON).hunger })
        assertEquals(vm.state.value.game, store.saved.last())
    }

    // ---- sounds and toast --------------------------------------------------------------------

    @Test
    fun consumeSoundsClearsBothQueues() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.FEED)
        rig.finishAnim()
        rig.vm.onFightPressed()
        rig.vm.onSwitcherPick(PetId.GHOSTFACE)
        // Unconsumed sounds pile up in order.
        assertEquals(listOf("chomp"), rig.ui.sfx)
        assertEquals(listOf("jason", "versus", "ghostface"), rig.ui.voice)

        rig.vm.consumeSounds()
        assertTrue(rig.ui.sfx.isEmpty())
        assertTrue(rig.ui.voice.isEmpty())
    }

    @Test
    fun consumeToastClearsIt() {
        val rig = Rig(gameOf(lastSeenMs = T0 - 3 * HOUR)).started()
        assertNotNull(rig.ui.toast)
        rig.vm.consumeToast()
        assertNull(rig.ui.toast)
    }
}

class AwayToastTest {
    private fun away(ms: Long, stat: String?, delta: Int) = AwaySummary(ms, stat, delta, emptySet())

    @Test
    fun showsDaysHoursAndTheBiggestChange() {
        assertEquals(
            "Away 2d 4h \u00b7 Fun \u221238",
            awayToast(away(2 * DAY + 4 * HOUR, "Fun", -38)),
        )
    }

    @Test
    fun omitsTheDayPartWhenUnderADay() {
        assertEquals("Away 4h \u00b7 Fun \u22126", awayToast(away(4 * HOUR, "Fun", -6)))
    }

    @Test
    fun positiveChangesUsePlus() {
        assertEquals("Away 5h \u00b7 Hunger +20", awayToast(away(5 * HOUR, "Hunger", 20)))
    }

    @Test
    fun negativeSignIsTheRealMinusNotAHyphen() {
        val toast = awayToast(away(4 * HOUR, "Fun", -6))!!
        assertTrue('\u2212' in toast)
        assertFalse('-' in toast)
    }

    @Test
    fun withoutAStatShowsJustTheTime() {
        assertEquals("Away 1d 2h", awayToast(away(DAY + 2 * HOUR, null, 0)))
        assertEquals("Away 3h", awayToast(away(3 * HOUR, null, 0)))
    }

    @Test
    fun wholeDaysKeepTheZeroHours() {
        assertEquals("Away 3d 0h \u00b7 Hunger +80", awayToast(away(3 * DAY, "Hunger", 80)))
    }

    @Test
    fun partialHoursAreFlooredAndDaysCarry() {
        assertEquals("Away 1d 23h \u00b7 Fun \u22121", awayToast(away(2 * DAY - 1, "Fun", -1)))
    }

    @Test
    fun underAnHourShowsMinutes() {
        assertEquals("Away 25m \u00b7 Hunger +2", awayToast(away(25 * MINUTE, "Hunger", 2)))
        assertEquals("Away 10m \u00b7 Fun \u22121", awayToast(away(10 * MINUTE, "Fun", -1)))
        assertEquals("Away 59m", awayToast(away(HOUR - 1, null, 0)))
    }

    @Test
    fun fromOneHourOnItStaysInHours() {
        assertEquals("Away 1h \u00b7 Fun \u22123", awayToast(away(HOUR, "Fun", -3)))
        assertEquals("Away 1h", awayToast(away(HOUR + 59 * MINUTE, null, 0)))
    }

    @Test
    fun noToastUnderTenMinutes() {
        assertNull(awayToast(away(Tuning.TOAST_MIN_AWAY_MS - 1, "Fun", -1)))
        assertNull(awayToast(away(0, null, 0)))
        assertNotNull(awayToast(away(Tuning.TOAST_MIN_AWAY_MS, "Fun", -1)))
    }
}
