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
import team.superfun.horrorpets.data.SaveCodec
import team.superfun.horrorpets.data.SaveStore
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Roll
import team.superfun.horrorpets.fight.ScriptedRandom
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.applyElapsed
import team.superfun.horrorpets.game.training.FIGHT_PREREQUISITE_NAMES
import team.superfun.horrorpets.game.pantry.Reaction
import team.superfun.horrorpets.game.pantry.feed
import team.superfun.horrorpets.game.training.applyTraining
import team.superfun.horrorpets.model.AwaySummary
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PantryItem.BATTER
import team.superfun.horrorpets.model.PantryItem.BRAIN
import team.superfun.horrorpets.model.PantryItem.BURNT_TO_A_CRISP
import team.superfun.horrorpets.model.PantryItem.CAMPFIRE
import team.superfun.horrorpets.model.PantryItem.CAMPFIRE_BREAD
import team.superfun.horrorpets.model.PantryItem.CAMP_CRYSTAL_FISH_FRY
import team.superfun.horrorpets.model.PantryItem.CAULDRON
import team.superfun.horrorpets.model.PantryItem.CHEESE
import team.superfun.horrorpets.model.PantryItem.EGG
import team.superfun.horrorpets.model.PantryItem.FLOUR
import team.superfun.horrorpets.model.PantryItem.FRIED_EGG
import team.superfun.horrorpets.model.PantryItem.GRILL
import team.superfun.horrorpets.model.PantryItem.KITCHEN_FIRE
import team.superfun.horrorpets.model.PantryItem.PILE_OF_SLOP
import team.superfun.horrorpets.model.PantryItem.POT
import team.superfun.horrorpets.model.PantryItem.RAW_STEAK
import team.superfun.horrorpets.model.PantryItem.STEAK
import team.superfun.horrorpets.model.PantryItem.SUN
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Starters
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.model.challengerDue
import team.superfun.horrorpets.model.isVisible
import team.superfun.horrorpets.model.pantryItems
import team.superfun.horrorpets.musicTrackFor
import team.superfun.horrorpets.ui.fight.nearlyDone
import team.superfun.horrorpets.ui.switcher.lineup

// A realistic epoch (2027-01-15), safely past Tuning.MIN_VALID_EPOCH_MS.
private const val T0 = 1_800_000_000_000L
private const val HOUR = Tuning.HOUR_MS
private const val DAY = Tuning.DAY_MS
private const val MINUTE = Tuning.MINUTE_MS

/**
 * The training games a new player has to finish before their first fight (see mustTrainBeforeFighting). The fixtures
 * below have all done them, so FIGHT is open unless a test builds a new player's stable to see it shut.
 */
private val TRAINED: Set<String> = FIGHT_PREREQUISITE_NAMES

/**
 * A shelf that already has everything a visit tops up to (two ways to cook, four foods), so opening the pantry leaves
 * it as it is and the tests can count on every index.
 */
private val SHELF: List<PantryItem> = listOf(GRILL, RAW_STEAK, EGG, CAMPFIRE, FLOUR, CHEESE)

private fun gameOf(
    selected: PetId? = PetId.JASON,
    lastSeenMs: Long? = T0,
    vararg pets: PetState,
) = GameState(
    pets = PetId.entries.associateWith { id -> pets.firstOrNull { it.id == id } ?: PetState(id, trainedGames = TRAINED) },
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
) = PetState(
    PetId.JASON, hunger, funLevel, strength, level, xp,
    sceneIndex = sceneIndex, fakeDeathPending = fakeDeathPending, trainedGames = TRAINED,
)

private class FakeClock(var now: Long) : Clock {
    override fun nowMs() = now
}

private class FakeStore(var initial: GameState) : SaveStore {
    val saves = mutableListOf<GameState>()
    var failSaves = false

    /** Every load fails. */
    var failLoad = false

    /** This many of the next loads fail, then they work again. */
    var failNextLoads = 0
    var loadAttempts = 0
    var saveAttempts = 0

    val last: GameState get() = saves.last()

    override suspend fun load(): GameState {
        loadAttempts++
        if (failLoad) throw IOException("read failed")
        if (failNextLoads > 0) {
            failNextLoads--
            throw IOException("transient read failure")
        }
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
    clockFloorMs: Long = Tuning.MIN_VALID_EPOCH_MS,
    pickRng: Random = Random.Default,
) {
    val errors = mutableListOf<Throwable>()
    val scope = TestScope(StandardTestDispatcher())
    val vm = GameViewModel(
        store = store,
        clock = clock,
        rng = rng,
        scope = scope,
        // As the real manifests have it: only Leatherface ships a dance.
        canDance = { it == PetId.LEATHERFACE },
        onError = { errors += it },
        clockFloorMs = clockFloorMs,
        pickRng = pickRng,
    )

    val ui: UiState get() = vm.state.value
    val game: GameState get() = ui.game
    val jason: PetState get() = game.pets.getValue(PetId.JASON)

    fun settle() = scope.advanceUntilIdle()

    /** Loaded: the save is read and the title screen is up. */
    fun loaded(): Rig {
        vm.start()
        settle()
        return this
    }

    /** Loaded and past the title (Start pressed), at Home or the starter pick, as a player gets there. */
    fun started(): Rig {
        loaded()
        vm.onStart()
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

    // ---- the title screen and starting over ----------------------------------------------------

    @Test
    fun theTitleComesFirstAndStartGoesHome() {
        val rig = Rig(gameOf(lastSeenMs = T0 - (2 * DAY + 4 * HOUR))).loaded()
        assertEquals(Screen.Title, rig.ui.screen)
        assertNull("the welcome back waits for Home", rig.ui.toast)

        rig.vm.onStart()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals("Away 2d 4h · Hunger +80", rig.ui.toast)
        rig.vm.onStart() // key repeat
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun startOnANewGameGoesToTheStarterPick() {
        val rig = Rig(GameState()).loaded()
        assertEquals(Screen.Title, rig.ui.screen)
        assertTrue(rig.ui.voice.isEmpty())
        rig.vm.onStart()
        assertEquals(Screen.Switcher(SwitcherMode.ONBOARD), rig.ui.screen)
        assertEquals(listOf("choose_your_killer"), rig.ui.voice)
    }

    @Test
    fun startIsOnlyAcceptedOnTheTitle() {
        val rig = Rig(gameOf()).started()
        rig.vm.onFightPressed()
        val before = rig.ui
        rig.vm.onStart()
        assertEquals(before, rig.ui)
    }

    @Test
    fun anOldSaveStartsOver() {
        // v0.3.0 had every killer unlocked; the stable starts everyone over with one starter.
        val v030 = """{"version":1,"selected":"LEATHERFACE","lastSeenMs":${T0 - DAY},"pets":{
            "JASON":{"id":"JASON","level":3,"xp":120},"LEATHERFACE":{"id":"LEATHERFACE","level":2}}}"""
        val rig = Rig(SaveCodec.decode(v030)).started()
        assertEquals(Screen.Switcher(SwitcherMode.ONBOARD), rig.ui.screen)
        assertTrue(rig.game.pets.isEmpty())
        rig.vm.onSwitcherPick(PetId.CHUCKY)
        rig.settle()
        assertEquals(mapOf(PetId.CHUCKY to PetState(PetId.CHUCKY)), rig.store.last.pets)
        assertEquals(2, rig.store.last.version)
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
    fun aLoadThatFailsOnceIsRetriedAndTheGameSavesNormally() {
        val store = FakeStore(gameOf(lastSeenMs = T0 - 3 * HOUR)).apply { failNextLoads = 1 }
        val rig = Rig(gameOf(), store = store).started()
        assertEquals(2, store.loadAttempts)
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(PetId.JASON, rig.game.selected)
        assertNotNull("the real save loaded, so it is a normal welcome back", rig.ui.toast)
        assertTrue("a load that recovered is not an error", rig.errors.isEmpty())
        // Saving works as usual.
        assertEquals(rig.game, store.last)
        rig.vm.onCare(CareAction.PLAY)
        rig.settle()
        assertEquals(rig.game, store.last)
    }

    @Test
    fun aLoadThatKeepsFailingPlaysFreshInMemoryAndNeverSaves() {
        val store = FakeStore(gameOf()).apply { failLoad = true }
        val rig = Rig(gameOf(), store = store).started()
        assertEquals("retried once", 2, store.loadAttempts)
        assertEquals(Screen.Switcher(SwitcherMode.ONBOARD), rig.ui.screen)
        assertEquals(1, rig.errors.size)
        assertTrue(rig.errors[0] is IOException)

        // The session plays on from defaults, but nothing reaches the store: the real save must
        // survive for the next launch.
        rig.vm.onSwitcherPick(PetId.FREDDY)
        rig.vm.onCare(CareAction.PLAY)
        rig.finishAllAnims()
        rig.clock.now += 2 * HOUR
        rig.vm.tick()
        rig.vm.onResume()
        rig.settle()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(PetId.FREDDY, rig.game.selected)
        assertEquals(0, store.saveAttempts)
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun startTwiceLoadsOnce() {
        val rig = Rig(gameOf()).started()
        rig.vm.onCare(CareAction.PLAY)
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
        // The starter is the whole stable: the rest are to be unlocked.
        assertEquals(mapOf(PetId.GHOSTFACE to PetState(PetId.GHOSTFACE)), rig.game.pets)
    }

    @Test
    fun theStableOpensAndClosesOverHome() {
        val rig = Rig(gameOf()).started()
        rig.vm.openStable()
        assertTrue(rig.ui.stableOpen)
        assertEquals(Screen.Home, rig.ui.screen)
        rig.vm.closeStable()
        assertFalse(rig.ui.stableOpen)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun selectingAPendingPetShowsItsFakeDeathOnce() {
        val freddy = PetState(PetId.FREDDY, fakeDeathPending = true)
        val rig = Rig(gameOf(pets = arrayOf(freddy))).started()
        rig.vm.openStable()
        rig.vm.onStablePick(PetId.FREDDY)
        assertFalse(rig.ui.stableOpen)
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
        // The UI derives the candidates from the game: every killer it may see but the one fighting. This stable has
        // the whole roster (the secret killer included, so he is visible), so seven of eight.
        val opponents = lineup(SwitcherMode.OPPONENT, rig.game)
        assertEquals(PetId.entries - PetId.JASON, opponents)
        assertEquals(7, opponents.size)
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

    @Test
    fun backOnTheVersusCardReturnsToTheOpponentSwitcher() {
        val rig = Rig(gameOf()).started()
        val savesBefore = rig.store.saves.size
        rig.vm.onFightPressed()
        rig.vm.onSwitcherPick(PetId.FREDDY)
        assertEquals(Screen.Versus(PetId.FREDDY), rig.ui.screen)
        val game = rig.game

        rig.vm.onVersusBack()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
        assertEquals("no fight happened, so nothing is paid or lost", game, rig.game)
        assertFalse(rig.ui.busy)

        // A second Back, or the card's timeline ending as it fades out, changes nothing.
        rig.vm.onVersusBack()
        rig.vm.onVersusDone()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)

        // Another opponent can be picked straight away, and Back from there still goes Home.
        rig.vm.onSwitcherPick(PetId.PINHEAD)
        assertEquals(Screen.Versus(PetId.PINHEAD), rig.ui.screen)
        rig.vm.onVersusBack()
        rig.vm.onSwitcherBack()
        assertEquals(Screen.Home, rig.ui.screen)
        rig.settle()
        assertEquals(savesBefore, rig.store.saves.size)
    }

    @Test
    fun versusBackIsIgnoredOffTheVersusCard() {
        val rig = Rig(gameOf()).started()
        rig.vm.onVersusBack()
        assertEquals(Screen.Home, rig.ui.screen)
        rig.vm.onFightPressed()
        rig.vm.onVersusBack()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
    }

    // ---- care --------------------------------------------------------------------------------

    @Test
    fun playTwiceWhileBusyAppliesOnce() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 20.0, funLevel = 40.0)))).started()
        rig.vm.onCare(CareAction.PLAY)
        rig.vm.onCare(CareAction.PLAY)
        rig.vm.onCare(CareAction.PLAY)
        assertEquals(65.0, rig.jason.funLevel, 1e-9)
        assertEquals(28.0, rig.jason.hunger, 1e-9)
        assertTrue(rig.ui.busy)
        assertEquals(listOf("hand_whoosh"), rig.ui.sfx)

        rig.finishAllAnims()
        assertFalse(rig.ui.busy)
        assertNull(rig.ui.petAnim)
        assertEquals(65.0, rig.jason.funLevel, 1e-9)

        rig.settle()
        // One save for start(), one for the single play.
        assertEquals(65.0, rig.store.last.pets.getValue(PetId.JASON).funLevel, 1e-9)
        assertEquals(2, rig.store.saves.size)
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
        assertEquals(listOf("hand_whoosh"), rig.ui.sfx)

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
    fun playOnAPetWithADanceIsOneDance() {
        val leatherface = PetState(PetId.LEATHERFACE, hunger = 20.0, funLevel = 40.0)
        val rig = Rig(gameOf(selected = PetId.LEATHERFACE, pets = arrayOf(leatherface))).started()
        rig.vm.onCare(CareAction.PLAY)
        val dance = rig.ui.petAnim!!
        assertEquals(Anim.DANCE, dance.anim)
        assertTrue(rig.ui.busy)
        // The same care as anyone's PLAY, applied once at the start, with the same sound.
        val pet = rig.game.pets.getValue(PetId.LEATHERFACE)
        assertEquals(65.0, pet.funLevel, 1e-9)
        assertEquals(28.0, pet.hunger, 1e-9)
        assertEquals(listOf("hand_whoosh"), rig.ui.sfx)

        // One request, one report: the dance's finish ends the action.
        rig.vm.onAnimFinished(dance.token)
        assertNull(rig.ui.petAnim)
        assertFalse(rig.ui.busy)
        // A late repeat of its token is ignored.
        rig.vm.onAnimFinished(dance.token)
        assertNull(rig.ui.petAnim)
        assertFalse(rig.ui.busy)
        assertEquals(65.0, rig.game.pets.getValue(PetId.LEATHERFACE).funLevel, 1e-9)
    }

    @Test
    fun playOnEveryPetWithoutADanceStillJumpsThenWaves() {
        for (id in PetId.entries - PetId.LEATHERFACE) {
            val rig = Rig(gameOf(selected = id, pets = arrayOf(PetState(id, funLevel = 40.0)))).started()
            rig.vm.onCare(CareAction.PLAY)
            assertEquals("$id", Anim.JUMP, rig.ui.petAnim!!.anim)
            rig.finishAnim()
            assertEquals("$id", Anim.WAVE, rig.ui.petAnim!!.anim)
            rig.finishAnim()
            assertFalse("$id", rig.ui.busy)
        }
    }

    @Test
    fun feedAndTrainOpenTheirScreensForADancerToo() {
        val rig = Rig(gameOf(selected = PetId.LEATHERFACE, pets = arrayOf(PetState(PetId.LEATHERFACE, hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.FEED)
        assertTrue(rig.ui.screen is Screen.Pantry)
        assertNull("the kitchen plays the meal, not Home", rig.ui.petAnim)
        rig.vm.onPantryBack((rig.ui.screen as Screen.Pantry).session)
        rig.vm.onCare(CareAction.TRAIN)
        assertTrue(rig.ui.screen is Screen.Training)
        assertNull("no dance, no swing: the games are the training", rig.ui.petAnim)
    }

    @Test
    fun trainOpensTrainingInsteadOfPayingAtOnce() {
        val rig = Rig(gameOf()).started()
        val before = rig.jason
        val saves = rig.store.saves.size
        rig.vm.onCare(CareAction.TRAIN)

        val training = rig.ui.screen as Screen.Training
        assertTrue(training.game in training.options)
        assertNull(training.result)
        assertEquals("nothing is paid until a game ends", before, rig.jason)
        assertFalse(rig.ui.busy)
        assertNull(rig.ui.petAnim)
        assertNull(rig.ui.refuse)
        rig.settle()
        assertEquals("and nothing is saved for opening it", saves, rig.store.saves.size)
    }

    @Test
    fun staleAnimTokenIsIgnored() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 80.0)))).started()
        rig.vm.onCare(CareAction.PLAY)
        val token = rig.ui.petAnim!!.token

        rig.vm.onAnimFinished(token + 99)
        rig.vm.onAnimFinished(-1)
        assertTrue(rig.ui.busy)
        assertEquals(token, rig.ui.petAnim!!.token)

        rig.vm.onAnimFinished(token)
        val second = rig.ui.petAnim!!
        assertNotEquals(token, second.token)
        rig.vm.onAnimFinished(token) // the finished token cannot end the play's wave
        assertEquals(second, rig.ui.petAnim)
        rig.vm.onAnimFinished(second.token)
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
    fun aLevelUpFromTrainingAddsTheLevelUpSound() {
        val rig = Rig(gameOf(pets = arrayOf(jason(xp = 95)))).started()
        rig.vm.onCare(CareAction.TRAIN)
        assertFalse("not yet: the game has not paid", "levelup" in rig.ui.sfx)
        rig.vm.onTrainingOver(rig.session(), 0)
        assertEquals(2, rig.jason.level)
        assertEquals(listOf("levelup"), rig.ui.sfx)
    }

    @Test
    fun tryAgainPlaysTheSameGameWithoutTheReel() {
        val rig = Rig(gameOf()).started()
        rig.vm.onCare(CareAction.TRAIN)
        val first = rig.ui.screen as Screen.Training
        assertFalse(first.again)
        rig.vm.onTrainingAgain(first.session)
        assertEquals("not before the game has paid", first, rig.ui.screen)
        rig.vm.onTrainingOver(first.session, 3)
        rig.vm.onTrainingAgain(first.session + 1)
        assertNotNull("another session's press changes nothing", (rig.ui.screen as Screen.Training).result)
        rig.vm.onTrainingAgain(first.session)
        val again = rig.ui.screen as Screen.Training
        assertEquals(first.game, again.game)
        assertEquals(first.mate, again.mate)
        assertNotEquals(first.session, again.session)
        assertNull(again.result)
        assertTrue(again.again)
        val strength = rig.jason.strength
        rig.vm.onTrainingOver(again.session, 4)
        assertTrue("the retry pays like any game", rig.jason.strength > strength)
    }

    @Test
    fun tryAgainIsRefusedOnceTooHungry() {
        // One game's appetite takes the pet over the line.
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = Tuning.TRAIN_REFUSE_AT - Tuning.TRAIN_HUNGER + 1)))).started()
        rig.vm.onCare(CareAction.TRAIN)
        val session = (rig.ui.screen as Screen.Training).session
        rig.vm.onTrainingOver(session, 2)
        rig.vm.onTrainingAgain(session)
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(RefuseReason.TOO_HUNGRY, rig.ui.refuse)
    }

    @Test
    fun noLevelUpNoLevelUpSound() {
        val rig = Rig(gameOf()).started()
        rig.vm.onCare(CareAction.TRAIN)
        rig.vm.onTrainingOver(rig.session(), 5)
        assertEquals(1, rig.jason.level)
        assertTrue(rig.ui.sfx.isEmpty())
    }

    @Test
    fun anyPressDuringAnActionIsIgnored() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.PLAY)
        val token = rig.ui.petAnim!!.token
        val snapshot = rig.ui

        rig.vm.onCare(CareAction.PLAY)
        rig.vm.onCare(CareAction.FEED)
        rig.vm.onCare(CareAction.TRAIN)
        rig.vm.onWalk(1)
        rig.vm.onWalk(-1)
        rig.vm.onFightPressed()
        rig.vm.openStable()
        assertEquals(snapshot, rig.ui)
        assertEquals(token, rig.ui.petAnim!!.token)
    }

    @Test
    fun careIsIgnoredOffTheHomeScreen() {
        val rig = Rig(gameOf()).started()
        rig.vm.onFightPressed()
        val before = rig.ui
        rig.vm.onCare(CareAction.FEED)
        rig.vm.onWalk(1)
        rig.vm.onFightPressed()
        assertEquals(before, rig.ui)
    }

    // ---- training ----------------------------------------------------------------------------

    /** A stable of just [pet]: nobody to be a mate, so only the solo games are on offer. */
    private fun loneStable(pet: PetState = jason()) = GameState(pets = mapOf(pet.id to pet), selected = pet.id, lastSeenMs = T0)

    /** The training session on screen, or -1 (no session's) off the training screen. */
    private fun Rig.session(): Long = (ui.screen as? Screen.Training)?.session ?: -1L

    /** Opens training on [game], as the puppet does, whatever the pick would have been. */
    private fun Rig.trainAt(game: TrainingGame): Screen.Training {
        vm.debugNextTraining = game
        vm.onCare(CareAction.TRAIN)
        return ui.screen as Screen.Training
    }

    @Test
    fun trainingWithoutAMateOffersOnlySoloGames() {
        val rig = Rig(loneStable()).started()
        repeat(10) {
            rig.vm.onCare(CareAction.TRAIN)
            val training = rig.ui.screen as Screen.Training
            assertEquals(listOf(TrainingGame.SLASH_SAYS, TrainingGame.ORGAN_DROP), training.options)
            assertTrue(training.game in training.options)
            assertNull(training.mate)
            rig.vm.onTrainingQuit(rig.session())
        }
    }

    @Test
    fun trainingWithAMateMayPickAnyGame() {
        val rig = Rig(gameOf(), pickRng = Random(3)).started()
        val seen = mutableSetOf<TrainingGame>()
        repeat(40) {
            rig.vm.onCare(CareAction.TRAIN)
            val training = rig.ui.screen as Screen.Training
            assertEquals(TrainingGame.entries, training.options)
            seen += training.game
            if (training.game.needsMate) {
                val mate = training.mate
                assertNotNull("${training.game} needs a mate", mate)
                assertNotEquals("the mate is another killer", PetId.JASON, mate)
                assertTrue("from the stable", mate in rig.game.pets)
            } else {
                assertNull("${training.game} is played alone", training.mate)
            }
            rig.vm.onTrainingQuit(rig.session())
        }
        assertEquals(TrainingGame.entries.toSet(), seen)
    }

    @Test
    fun trainingNeverOpensTheSameGameTwiceInARow() {
        for (stable in listOf(gameOf(), loneStable())) {
            val rig = Rig(stable).started()
            var last: TrainingGame? = null
            repeat(30) {
                rig.vm.onCare(CareAction.TRAIN)
                val picked = (rig.ui.screen as Screen.Training).game
                assertNotEquals(last, picked)
                last = picked
                rig.vm.onTrainingQuit(rig.session())
            }
        }
    }

    @Test
    fun aForcedGameOpensOnceAndOnlyWhenItIsOnOffer() {
        val rig = Rig(gameOf()).started()
        for (game in TrainingGame.entries) {
            val training = rig.trainAt(game)
            assertEquals(game, training.game)
            assertEquals("a mate game has its mate", game.needsMate, training.mate != null)
            assertNull("used up", rig.vm.debugNextTraining)
            rig.vm.onTrainingQuit(rig.session())
        }

        val alone = Rig(loneStable()).started()
        val training = alone.trainAt(TrainingGame.QUICK_DRAW)
        assertTrue("nobody to draw against: another game", training.game in training.options)
        assertNull(alone.vm.debugNextTraining)
    }

    @Test
    fun starvingPetStillRefusesTraining() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 90.0)))).started()
        val before = rig.jason
        rig.vm.debugNextTraining = TrainingGame.ORGAN_DROP
        rig.vm.onCare(CareAction.TRAIN)
        assertEquals(RefuseReason.TOO_HUNGRY, rig.ui.refuse)
        assertEquals(Anim.FAIL, rig.ui.petAnim!!.anim)
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(before, rig.jason)
        assertNull("a refused train uses the forced game up too", rig.vm.debugNextTraining)
    }

    @Test
    fun trainingOverPaysOnceAndShowsTheResult() {
        val rig = Rig(gameOf()).started()
        val before = rig.jason
        rig.trainAt(TrainingGame.SLASH_SAYS)
        rig.vm.onTrainingOver(rig.session(), 8)

        // Par is 8, so the full 3x: strength +18 and xp +30, and hunger and fun as TRAIN has always moved them.
        val paid = rig.jason
        assertEquals(38.0, paid.strength, 1e-9)
        assertEquals(30, paid.xp)
        assertEquals(32.0, paid.hunger, 1e-9)
        assertEquals(75.0, paid.funLevel, 1e-9)
        // The pay, and the game marked finished (whatever the pet had finished before).
        val paidFor = applyTraining(before, TrainingGame.SLASH_SAYS, 8).pet
        assertEquals(paidFor.copy(trainedGames = before.trainedGames + TrainingGame.SLASH_SAYS.name), paid)
        assertEquals(mapOf("SLASH_SAYS" to 8), paid.trainingBest)
        val result = (rig.ui.screen as Screen.Training).result
        assertEquals(TrainingResult(score = 8, best = 8, newBest = true, strength = 18.0, xp = 30, levelsGained = 0), result)
        assertFalse(rig.ui.busy)
        rig.settle()
        assertEquals(rig.game, rig.store.last)

        val card = rig.ui
        val saves = rig.store.saves.size
        rig.vm.onTrainingOver(rig.session(), 8)
        rig.vm.onTrainingOver(rig.session(), 30)
        rig.settle()
        assertEquals("a second report pays nothing", card, rig.ui)
        assertEquals(saves, rig.store.saves.size)
    }

    @Test
    fun aLowerScoreLeavesTheBestAlone() {
        val rig = Rig(gameOf()).started()
        rig.trainAt(TrainingGame.SLASH_SAYS)
        rig.vm.onTrainingOver(rig.session(), 8)
        rig.vm.onTrainingDone(rig.session())

        rig.trainAt(TrainingGame.SLASH_SAYS)
        rig.vm.onTrainingOver(rig.session(), 5)
        val result = (rig.ui.screen as Screen.Training).result!!
        assertEquals(5, result.score)
        assertEquals(8, result.best)
        assertFalse(result.newBest)
        assertEquals(mapOf("SLASH_SAYS" to 8), rig.jason.trainingBest)
    }

    @Test
    fun trainingPaysThePetAsItIsWhenTheGameEnds() {
        val rig = Rig(gameOf()).started()
        rig.trainAt(TrainingGame.ORGAN_DROP)
        rig.clock.now += 2 * HOUR
        rig.vm.tick()
        assertTrue("time passing does not end it", rig.ui.screen is Screen.Training)
        val ticked = rig.jason
        assertNotEquals(jason(), ticked)

        rig.vm.onTrainingOver(rig.session(), 0)
        val paidFor = applyTraining(ticked, TrainingGame.ORGAN_DROP, 0).pet
        assertEquals(paidFor.copy(trainedGames = ticked.trainedGames + TrainingGame.ORGAN_DROP.name), rig.jason)
    }

    @Test
    fun aTrainingGameMakesItsOwnKeySoundsUntilItsCard() {
        val rig = Rig(gameOf()).started()
        assertFalse("Home ticks", rig.ui.screen.ownsKeySounds)
        rig.vm.onCare(CareAction.TRAIN)
        assertTrue("the reel and the game", rig.ui.screen.ownsKeySounds)
        rig.vm.onTrainingOver(rig.session(), 0)
        assertFalse("the card ticks like the others", rig.ui.screen.ownsKeySounds)
    }

    @Test
    fun everyTrainIsASessionOfItsOwnToItsCard() {
        val rig = Rig(gameOf()).started()
        rig.vm.onCare(CareAction.TRAIN)
        val first = rig.session()
        rig.vm.onTrainingOver(first, 3)
        assertEquals("the card is the same session", first, rig.session())
        rig.vm.onTrainingDone(first)

        val seen = mutableSetOf(first)
        repeat(5) {
            rig.vm.onCare(CareAction.TRAIN)
            assertTrue("a new session each time", seen.add(rig.session()))
            rig.vm.onTrainingQuit(rig.session())
        }
    }

    @Test
    fun aSessionNeverActsOnTheNextOne() {
        // The last session reporting late (its game, its card's OK, Back), after its card, Home and TRAIN again within
        // a frame, as the debug puppet goes: the session on screen must not hear it.
        val rig = Rig(gameOf()).started()
        rig.trainAt(TrainingGame.SLASH_SAYS)
        val old = rig.session()
        rig.vm.onTrainingOver(old, 4)
        rig.vm.onTrainingDone(old)
        rig.trainAt(TrainingGame.ORGAN_DROP)
        val now = rig.session()
        val reel = rig.ui
        rig.vm.onTrainingOver(old, 30)
        rig.vm.onTrainingQuit(old)
        rig.vm.onTrainingDone(old)
        assertEquals("nothing paid, nothing left", reel, rig.ui)

        rig.vm.onTrainingOver(now, 2)
        val card = rig.ui
        assertEquals(2, (card.screen as Screen.Training).result!!.score)
        rig.vm.onTrainingOver(old, 30)
        rig.vm.onTrainingDone(old)
        assertEquals("nor its card", card, rig.ui)
        rig.vm.onTrainingDone(now)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun trainingMessagesOffTheTrainingScreenAreIgnored() {
        val rig = Rig(gameOf()).started()
        val before = rig.ui
        rig.vm.onTrainingOver(rig.session(), 8)
        rig.vm.onTrainingDone(rig.session())
        rig.vm.onTrainingQuit(rig.session())
        assertEquals(before, rig.ui)
    }

    @Test
    fun trainingDoneGoesHomeOnce() {
        val rig = Rig(gameOf()).started()
        rig.vm.onCare(CareAction.TRAIN)
        val reel = rig.ui
        rig.vm.onTrainingDone(rig.session())
        assertEquals("nothing to dismiss yet", reel, rig.ui)

        rig.vm.onTrainingOver(rig.session(), 3)
        rig.vm.onTrainingDone(rig.session())
        assertEquals(Screen.Home, rig.ui.screen)
        val home = rig.ui
        rig.vm.onTrainingDone(rig.session())
        assertEquals(home, rig.ui)
    }

    @Test
    fun quittingDuringTheReelGivesNothing() {
        val rig = Rig(gameOf()).started()
        val before = rig.jason
        rig.settle()
        val saves = rig.store.saves.size

        rig.vm.onCare(CareAction.TRAIN)
        rig.vm.onTrainingQuit(rig.session())
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(before, rig.jason)
        rig.settle()
        assertEquals(saves, rig.store.saves.size)
        val home = rig.ui
        rig.vm.onTrainingQuit(rig.session())
        assertEquals(home, rig.ui)

        // Once the game has paid, Back no longer leaves for free: the card is dismissed with OK.
        rig.vm.onCare(CareAction.TRAIN)
        rig.vm.onTrainingOver(rig.session(), 2)
        val card = rig.ui
        rig.vm.onTrainingQuit(rig.session())
        assertEquals(card, rig.ui)
    }

    @Test
    fun aLevelUpFromTrainingOffersTheNewAttackAfterTheCard() {
        val rig = Rig(gameOf(pets = arrayOf(jason(xp = 95)))).started()
        rig.vm.onCare(CareAction.TRAIN)
        rig.vm.onTrainingOver(rig.session(), 0)
        assertEquals(1, (rig.ui.screen as Screen.Training).result!!.levelsGained)
        assertTrue("the card comes first", rig.ui.screen is Screen.Training)

        rig.vm.onTrainingDone(rig.session())
        assertEquals(Screen.Learn(PetId.JASON, "jason_light_1"), rig.ui.screen)
        assertTrue("unlock" in rig.ui.sfx)
    }

    // ---- the pantry --------------------------------------------------------------------------

    /** A rig started on a Jason (not too full to eat unless [pet] says so) with [shelf] on the pantry shelf. */
    private fun pantryRig(shelf: List<PantryItem> = SHELF, pet: PetState = jason()): Rig =
        Rig(gameOf(pets = arrayOf(pet)).copy(pantry = shelf.map { it.id }), pickRng = Random(1)).started()

    /** The pantry on screen. */
    private val Rig.visit: Screen.Pantry get() = ui.screen as Screen.Pantry

    /** The shelf as items, in shelf order. */
    private val Rig.shelf: List<PantryItem> get() = game.pantryItems()

    /** FEED on Home: the pantry opens. */
    private fun Rig.openPantry(): Screen.Pantry {
        vm.onCare(CareAction.FEED)
        return visit
    }

    /** Picks [indices] off the shelf one after another, in the pantry on screen. */
    private fun Rig.pick(vararg indices: Int) {
        val session = visit.session
        indices.forEach { vm.onPantryPick(session, it) }
    }

    @Test
    fun feedOpensThePantry() {
        val rig = Rig(gameOf(), pickRng = Random(5)).started()
        val before = rig.jason
        assertTrue(rig.game.pantry.isEmpty())
        rig.vm.onCare(CareAction.FEED)

        val visit = rig.visit
        assertEquals(emptyList<Int>(), visit.board)
        assertNull(visit.cook)
        assertNull(visit.fed)
        assertEquals(0L, visit.refusedCook)
        assertEquals(0L, visit.tooFull)
        // An empty pantry starts with a handful, the cooks and foods every visit needs.
        assertEquals(Tuning.PANTRY_PAIRS + Tuning.PANTRY_START_FOODS, rig.shelf.size)
        assertTrue(rig.shelf.count { it.raw } >= Tuning.PANTRY_MIN_RAW)
        assertEquals(before, rig.jason)
        assertFalse("opening the pantry is not an action", rig.ui.busy)
        assertNull("the kitchen plays the meal, not Home", rig.ui.petAnim)
        assertNull(rig.ui.refuse)
        assertTrue("and food is not dropped", rig.ui.sfx.isEmpty())
        rig.settle()
        assertEquals(rig.game, rig.store.last)
        assertEquals(rig.shelf.map { it.id }, rig.store.last.pantry)
    }

    @Test
    fun openingTopsAThinShelfUpAndKeepsWhatIsOnIt() {
        val rig = pantryRig(listOf(EGG, PILE_OF_SLOP))
        rig.vm.onCare(CareAction.FEED)
        assertEquals(listOf(EGG, PILE_OF_SLOP), rig.shelf.take(2))
        assertTrue(rig.shelf.count { it.raw } >= Tuning.PANTRY_MIN_RAW)
        assertTrue(rig.shelf.any { it.kind == ItemKind.METHOD })
        rig.settle()
        assertEquals(rig.game.pantry, rig.store.last.pantry)
    }

    @Test
    fun aStockedShelfOpensAsItWasAndSavesNothing() {
        val rig = pantryRig()
        rig.settle()
        val saves = rig.store.saves.size
        rig.vm.onCare(CareAction.FEED)
        rig.settle()
        assertEquals(SHELF, rig.shelf)
        assertEquals(saves, rig.store.saves.size)
    }

    @Test
    fun pickTogglesTheBoard() {
        val rig = pantryRig()
        val session = rig.openPantry().session
        rig.settle()
        val saves = rig.store.saves.size
        val opened = rig.ui

        rig.vm.onPantryPick(session, 0)
        assertEquals(listOf(0), rig.visit.board)
        rig.vm.onPantryPick(session, 2)
        assertEquals(listOf(0, 2), rig.visit.board)
        rig.vm.onPantryPick(session, 0)
        assertEquals("taken off the board again", listOf(2), rig.visit.board)
        rig.vm.onPantryPick(session, 0)
        assertEquals("the board keeps the order they were placed in", listOf(2, 0), rig.visit.board)
        rig.vm.onPantryPick(session, 2)
        rig.vm.onPantryPick(session, 0)
        assertEquals(emptyList<Int>(), rig.visit.board)

        assertEquals("a pick moves nothing but the board", opened, rig.ui)
        rig.settle()
        assertEquals("and the board is not saved", saves, rig.store.saves.size)
    }

    @Test
    fun picksOffTheShelfAreIgnored() {
        val rig = pantryRig()
        val session = rig.openPantry().session
        val opened = rig.ui
        rig.vm.onPantryPick(session, -1)
        rig.vm.onPantryPick(session, SHELF.size)
        rig.vm.onPantryPick(session, Int.MAX_VALUE)
        assertEquals(opened, rig.ui)
    }

    @Test
    fun aThirdPickIsIgnored() {
        val rig = pantryRig()
        rig.openPantry()
        rig.pick(0, 2)
        val full = rig.ui
        rig.pick(3)
        assertEquals(listOf(0, 2), rig.visit.board)
        assertEquals(full, rig.ui)

        // Taking one off makes room for another.
        rig.pick(0, 3)
        assertEquals(listOf(2, 3), rig.visit.board)
    }

    @Test
    fun cookUsesBothAndShelvesTheResult() {
        // Grill + Egg make a Fried Egg, whichever was put on the board first; it goes where the first of them was.
        for (order in listOf(listOf(0, 2), listOf(2, 0))) {
            val rig = pantryRig()
            rig.openPantry()
            rig.pick(*order.toIntArray())
            val before = rig.jason
            rig.vm.onPantryCook(rig.visit.session)

            val expected = listOf(FRIED_EGG, RAW_STEAK, CAMPFIRE, FLOUR, CHEESE)
            assertEquals("$order", expected, rig.shelf)
            val cook = rig.visit.cook!!
            assertEquals(FRIED_EGG, cook.result)
            assertEquals("the inputs, in the order they were placed", order.map { SHELF[it] }, cook.inputs)
            assertEquals(0, cook.at)
            assertEquals(emptyList<Int>(), rig.visit.board)
            assertEquals(listOf("cook_grill"), rig.ui.sfx)
            assertEquals("cooking feeds no one", before, rig.jason)
            assertFalse(rig.ui.busy)
            assertNull(rig.ui.petAnim)
            rig.settle()
            assertEquals("every cook is saved at once", rig.game, rig.store.last)
            assertEquals(expected.map { it.id }, rig.store.last.pantry)
        }
    }

    @Test
    fun theResultTakesTheSmallerIndexWhateverTheOrder() {
        // Flour (4) + Campfire (3): Campfire Bread where the Campfire stood, the later items sliding up.
        val near = pantryRig()
        near.openPantry()
        near.pick(4, 3)
        near.vm.onPantryCook(near.visit.session)
        assertEquals(listOf(GRILL, RAW_STEAK, EGG, CAMPFIRE_BREAD, CHEESE), near.shelf)
        assertEquals(3, near.visit.cook!!.at)

        // Cheese (5) + Raw Steak (1) don't go: a Pile of Slop where the steak was, far from the other input.
        val far = pantryRig()
        far.openPantry()
        far.pick(5, 1)
        far.vm.onPantryCook(far.visit.session)
        assertEquals(listOf(GRILL, PILE_OF_SLOP, EGG, CAMPFIRE, FLOUR), far.shelf)
        assertEquals(1, far.visit.cook!!.at)
    }

    @Test
    fun aMixPlaysNoSoundOfItsOwn() {
        val rig = pantryRig()
        rig.openPantry()
        rig.pick(4, 2) // Flour + Egg: two foods, no way to cook
        rig.vm.onPantryCook(rig.visit.session)
        assertEquals(BATTER, rig.visit.cook!!.result)
        assertEquals("the poof is the screen's, as it shows it", emptyList<String>(), rig.ui.sfx)
    }

    @Test
    fun aCookWithAWayToCookPlaysThatWaysSound() {
        for (method in listOf(GRILL, CAMPFIRE, POT, CAULDRON, SUN)) {
            val rig = pantryRig(listOf(RAW_STEAK, method, EGG, FLOUR, CHEESE, STEAK))
            rig.openPantry()
            rig.pick(2, 1) // the way to cook doesn't have to be placed first
            rig.vm.onPantryCook(rig.visit.session)
            assertEquals("${method.id} + Egg", listOf("cook_${method.id}"), rig.ui.sfx)
        }

        // A way to cook on a finished dish still has the way's sound, though what comes out is Burnt to a Crisp.
        val burnt = pantryRig(listOf(RAW_STEAK, GRILL, EGG, FLOUR, CHEESE, STEAK))
        burnt.openPantry()
        burnt.pick(5, 1)
        burnt.vm.onPantryCook(burnt.visit.session)
        assertEquals(BURNT_TO_A_CRISP, burnt.visit.cook!!.result)
        assertEquals(listOf("cook_grill"), burnt.ui.sfx)
    }

    @Test
    fun twoWaysToCookPlayTheSoundOfTheFirstOnTheBoard() {
        val rig = pantryRig()
        rig.openPantry()
        rig.pick(3, 0) // Campfire, then Grill
        rig.vm.onPantryCook(rig.visit.session)
        assertEquals(KITCHEN_FIRE, rig.visit.cook!!.result)
        assertEquals(listOf("cook_campfire"), rig.ui.sfx)
    }

    @Test
    fun cookTwiceCooksOnce() {
        val rig = pantryRig()
        val session = rig.openPantry().session
        rig.pick(0, 2)
        rig.vm.onPantryCook(session)
        val cooked = rig.ui
        rig.settle()
        val saves = rig.store.saves.size

        rig.vm.onPantryCook(session) // the same press again, as a held OK or a double tap would
        rig.vm.onPantryCook(session)
        assertEquals(cooked, rig.ui)
        assertEquals("the inputs are used up once", listOf(FRIED_EGG, RAW_STEAK, CAMPFIRE, FLOUR, CHEESE), rig.shelf)
        assertEquals(listOf("cook_grill"), rig.ui.sfx)
        rig.settle()
        assertEquals(saves, rig.store.saves.size)
    }

    @Test
    fun feedMoreIsAnotherVisitWhileTheKillerCouldEatMore() {
        val rig = pantryRig(pet = jason(hunger = 70.0))
        val first = rig.openPantry().session
        rig.vm.onPantryMore(first)
        assertEquals("not before it has eaten", first, rig.visit.session)
        rig.pick(1)
        rig.vm.onPantryFeed(first)
        val fed = rig.visit.fed!!
        assertEquals(70.0, fed.hungerBefore, 0.001)
        assertTrue(fed.hungerAfter < 70.0)
        rig.vm.onPantryMore(first + 1)
        assertNotNull("another session's press changes nothing", rig.visit.fed)
        rig.vm.onPantryMore(first)
        assertNotEquals(first, rig.visit.session)
        assertNull("a fresh visit", rig.visit.fed)
        assertEquals(emptyList<Int>(), rig.visit.board)
    }

    @Test
    fun feedMoreAfterALevelUpOffersTheNewAttackFirst() {
        val rig = pantryRig(SHELF + STEAK, pet = jason(xp = 95, hunger = 70.0))
        val session = rig.openPantry().session
        rig.pick(SHELF.size)
        rig.vm.onPantryFeed(session)
        assertTrue(rig.visit.fed!!.levelsGained > 0)
        rig.vm.onPantryMore(session)
        assertEquals(Screen.Learn(PetId.JASON, "jason_light_1"), rig.ui.screen)
    }

    @Test
    fun feedMoreRestocksAnEmptiedShelf() {
        val rig = pantryRig(listOf(RAW_STEAK), pet = jason(hunger = 70.0))
        val session = rig.openPantry().session
        rig.pick(rig.shelf.indexOf(RAW_STEAK))
        rig.vm.onPantryFeed(session)
        rig.vm.onPantryMore(session)
        assertNotEquals(session, rig.visit.session)
        assertTrue("stocked for the new visit", rig.shelf.size >= Tuning.PANTRY_MIN_RAW)
    }

    @Test
    fun feedMoreGoesHomeOnceTheKillerIsFull() {
        val rig = pantryRig(pet = jason(hunger = Tuning.FEED_REFUSE_BELOW + 1))
        val session = rig.openPantry().session
        rig.pick(1)
        rig.vm.onPantryFeed(session)
        assertTrue(rig.jason.hunger < Tuning.FEED_REFUSE_BELOW)
        rig.vm.onPantryMore(session)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun aDishCookedIsARecipeFoundAndSlopIsNot() {
        val rig = pantryRig()
        val session = rig.openPantry().session
        assertTrue(rig.game.recipesFound.isEmpty())
        rig.pick(0, 2) // Grill + Egg: a fried egg
        rig.vm.onPantryCook(session)
        assertEquals(setOf(FRIED_EGG.id), rig.game.recipesFound)
        rig.pick(2, 0) // Campfire + the fried egg: burnt to a crisp, which is no recipe
        rig.vm.onPantryCook(session)
        assertEquals(BURNT_TO_A_CRISP, rig.shelf[0])
        assertEquals(setOf(FRIED_EGG.id), rig.game.recipesFound)
        rig.settle()
        assertEquals("saved with the game", setOf(FRIED_EGG.id), rig.store.last.recipesFound)
    }

    @Test
    fun cookNeedsTwoOnTheBoardAndFeedNeedsOne() {
        val rig = pantryRig()
        val session = rig.openPantry().session
        val empty = rig.ui
        rig.vm.onPantryCook(session)
        rig.vm.onPantryFeed(session)
        assertEquals("nothing on the board", empty, rig.ui)

        rig.pick(0)
        val one = rig.ui
        rig.vm.onPantryCook(session)
        assertEquals("one is nothing to cook", one, rig.ui)

        rig.pick(2)
        val two = rig.ui
        rig.vm.onPantryFeed(session)
        assertEquals("and two are not a meal", two, rig.ui)
        assertEquals(SHELF, rig.shelf)
    }

    @Test
    fun aBotchOnTheBoardRefusesTheCook() {
        val rig = pantryRig(listOf(GRILL, EGG, PILE_OF_SLOP, FLOUR, CHEESE, RAW_STEAK))
        val session = rig.openPantry().session
        rig.pick(2, 1) // the Slop and the Egg
        rig.settle()
        val saves = rig.store.saves.size
        val before = rig.ui
        val shelf = rig.shelf

        rig.vm.onPantryCook(session)
        assertEquals("nothing is used", shelf, rig.shelf)
        assertEquals("the board stays as it was", listOf(2, 1), rig.visit.board)
        assertNull(rig.visit.cook)
        val first = rig.visit.refusedCook
        assertNotEquals("the screen can tell it was refused", 0L, first)
        assertEquals(listOf("pantry_squelch"), rig.ui.sfx)
        assertEquals(before.game, rig.ui.game)
        assertFalse(rig.ui.busy)
        rig.settle()
        assertEquals("a refusal is not saved", saves, rig.store.saves.size)

        // Every refusal is a new one, so the screen shakes the board each time.
        rig.vm.onPantryCook(session)
        assertNotEquals(first, rig.visit.refusedCook)
        assertEquals(listOf("pantry_squelch", "pantry_squelch"), rig.ui.sfx)

        // The board is still usable: swap the Slop for the Grill and the Egg cooks.
        rig.pick(2, 0)
        rig.vm.onPantryCook(session)
        assertEquals(FRIED_EGG, rig.visit.cook!!.result)
        assertEquals(listOf(PILE_OF_SLOP, FLOUR, CHEESE, RAW_STEAK), rig.shelf.drop(1))
    }

    @Test
    fun twoBotchesCookIntoOnePileOfSlop() {
        // Slop with slop squashes down to one pile: two places become one, shown and saved like any cook, and it pays
        // nothing, as no cook does.
        val rig = pantryRig(listOf(GRILL, KITCHEN_FIRE, EGG, PILE_OF_SLOP, FLOUR, CHEESE, RAW_STEAK))
        val session = rig.openPantry().session
        val before = rig.jason
        rig.pick(3, 1) // the Slop and the Kitchen Fire
        rig.vm.onPantryCook(session)

        val expected = listOf(GRILL, PILE_OF_SLOP, EGG, FLOUR, CHEESE, RAW_STEAK)
        assertEquals("both used up, the slop where the earlier one was", expected, rig.shelf)
        val cook = rig.visit.cook!!
        assertEquals(PILE_OF_SLOP, cook.result)
        assertEquals(listOf(PILE_OF_SLOP, KITCHEN_FIRE), cook.inputs)
        assertEquals(1, cook.at)
        assertEquals(0L, rig.visit.refusedCook)
        assertEquals(emptyList<Int>(), rig.visit.board)
        assertEquals("no way to cook, so no cooking sound: the show's squelch is the screen's", emptyList<String>(), rig.ui.sfx)
        assertEquals("cooking slop feeds no one and moves no mood", before, rig.jason)
        rig.settle()
        assertEquals(expected.map { it.id }, rig.store.last.pantry)
    }

    @Test
    fun feedPaysAndShowsTheReaction() {
        // Jason loves savory: a Steak is a loved snack.
        val rig = pantryRig(SHELF + STEAK, pet = jason(hunger = 60.0))
        val before = rig.jason
        val session = rig.openPantry().session
        rig.pick(SHELF.size)
        rig.settle()
        val saves = rig.store.saves.size
        rig.vm.onPantryFeed(session)

        val paid = feed(before, STEAK, rig.clock.now)
        assertEquals(paid.pet, rig.jason)
        assertEquals("the meal is remembered, at the clock's time", STEAK.id to rig.clock.now, rig.jason.lastMeal to rig.jason.lastMealAtMs)
        assertEquals(25.0, rig.jason.hunger, 1e-9)
        assertEquals(10, rig.jason.xp)
        assertEquals(SHELF, rig.shelf)
        val fed = rig.visit.fed!!
        assertEquals(STEAK, fed.item)
        assertEquals(Reaction.LOVED, fed.reaction)
        assertEquals(paid.line, fed.line)
        assertEquals(10, fed.xp)
        assertEquals(0, fed.levelsGained)
        assertEquals("what it gave, as paid: a loved snack's strength doubled", 6.0, fed.strength, 1e-9)
        assertEquals(0.0, fed.funLevel, 1e-9)
        assertEquals("and the mood a loved dish lifts", 10.0, fed.mood, 1e-9)
        assertEquals(paid.strengthGained, fed.strength, 0.0)
        assertEquals(paid.moodGained, fed.mood, 0.0)
        assertEquals(emptyList<Int>(), rig.visit.board)
        assertTrue("the kitchen plays the eating sounds", rig.ui.sfx.isEmpty())
        assertFalse(rig.ui.busy)
        rig.settle()
        assertEquals("paid and saved the moment it is fed", rig.game, rig.store.last)
        assertEquals(saves + 1, rig.store.saves.size)
        assertEquals(SHELF.map { it.id }, rig.store.last.pantry)
    }

    @Test
    fun onceFedNothingButDoneAndBackActs() {
        val rig = pantryRig(SHELF + STEAK)
        val session = rig.openPantry().session
        rig.pick(SHELF.size)
        rig.vm.onPantryFeed(session)
        rig.settle()
        val fed = rig.ui
        val saves = rig.store.saves.size

        rig.vm.onPantryPick(session, 0)
        rig.vm.onPantryPick(session, 2)
        rig.vm.onPantryCook(session)
        rig.vm.onPantryFeed(session)
        assertEquals(fed, rig.ui)
        rig.pick(0)
        rig.vm.onPantryFeed(session) // a second meal for the same visit
        assertEquals(fed, rig.ui)
        rig.settle()
        assertEquals(saves, rig.store.saves.size)

        rig.vm.onPantryDone(session)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun aFullPetCanCookButNotFeed() {
        val rig = pantryRig(pet = jason(hunger = 5.0))
        val session = rig.openPantry().session

        // Cooking needs no appetite.
        rig.pick(0, 2)
        rig.vm.onPantryCook(session)
        assertEquals(FRIED_EGG, rig.visit.cook!!.result)
        rig.vm.consumeSounds()
        rig.settle()
        val saves = rig.store.saves.size
        val cooked = rig.ui
        val pet = rig.jason

        // Feeding is turned away: nothing eaten, nothing paid, the board kept; the screen is told with a fresh token.
        rig.pick(0)
        val onBoard = rig.visit
        rig.vm.onPantryFeed(session)
        val first = rig.visit.tooFull
        assertNotEquals(0L, first)
        assertEquals("only the token changed: the board is kept", onBoard.copy(tooFull = first), rig.visit)
        assertEquals(listOf("refuse"), rig.ui.sfx)
        assertNull(rig.visit.fed)
        assertEquals(pet, rig.jason)
        assertEquals(cooked.game, rig.ui.game)
        assertFalse("no Home animation that nothing here would finish", rig.ui.busy)
        assertNull(rig.ui.petAnim)
        assertNull(rig.ui.refuse)
        rig.settle()
        assertEquals(saves, rig.store.saves.size)

        rig.vm.onPantryFeed(session)
        assertNotEquals(first, rig.visit.tooFull)
        assertEquals(listOf("refuse", "refuse"), rig.ui.sfx)
    }

    @Test
    fun aPetJustAtTheLimitCanStillEat() {
        val atLimit = pantryRig(pet = jason(hunger = Tuning.FEED_REFUSE_BELOW))
        atLimit.openPantry()
        atLimit.pick(1)
        atLimit.vm.onPantryFeed(atLimit.visit.session)
        assertNotNull(atLimit.visit.fed)
        assertEquals(0L, atLimit.visit.tooFull)

        val below = pantryRig(pet = jason(hunger = Tuning.FEED_REFUSE_BELOW - 0.01))
        below.openPantry()
        below.pick(1)
        below.vm.onPantryFeed(below.visit.session)
        assertNull(below.visit.fed)
        assertNotEquals(0L, below.visit.tooFull)
    }

    @Test
    fun doneGoesHome() {
        val rig = pantryRig(SHELF + STEAK)
        val session = rig.openPantry().session
        rig.pick(SHELF.size)
        rig.vm.onPantryFeed(session)
        rig.vm.onPantryDone(session)
        assertEquals(Screen.Home, rig.ui.screen)
        assertNull(rig.ui.refuse)
        assertFalse(rig.ui.busy)

        val home = rig.ui
        rig.vm.onPantryDone(session)
        rig.vm.onPantryBack(session)
        assertEquals("a second Done finds no pantry", home, rig.ui)
    }

    @Test
    fun aShelfFullOfSlopMakesRoomAndIsSaved() {
        // A full shelf of botches can't take its top-up's minimums: its oldest botches go to make room, and the
        // shelf that changed is saved.
        val slop = List(Tuning.PANTRY_MAX) { PILE_OF_SLOP }
        val rig = pantryRig(slop)
        rig.settle()
        val saves = rig.store.saves.size
        rig.openPantry()
        assertEquals(Tuning.PANTRY_MAX, rig.shelf.size)
        assertEquals("only the four oldest botches went", List(Tuning.PANTRY_MAX - 4) { PILE_OF_SLOP }, rig.shelf.take(Tuning.PANTRY_MAX - 4))
        assertTrue(rig.shelf.drop(Tuning.PANTRY_MAX - 4).all { it.raw })
        rig.settle()
        assertEquals(saves + 1, rig.store.saves.size)
        assertEquals(rig.shelf.map { it.id }, rig.store.last.pantry)
    }

    @Test
    fun doneWaitsForTheMeal() {
        // Done is the reaction's: before the killer has eaten it does nothing, so a FEED tapped twice (the second tap
        // landing as the reaction's OK) can never skip the reaction. Back still leaves at any time.
        val rig = pantryRig(SHELF + STEAK)
        val session = rig.openPantry().session
        rig.pick(SHELF.size)
        val before = rig.ui
        rig.vm.onPantryDone(session)
        assertEquals("nothing eaten yet: still in the kitchen, the board as it was", before, rig.ui)

        rig.pick(0, 2)
        rig.vm.onPantryCook(session)
        val cooked = rig.ui
        rig.vm.onPantryDone(session)
        assertEquals("a cook is not a meal", cooked, rig.ui)

        rig.vm.onPantryBack(session)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun backLeavesWithTheShelfKept() {
        val rig = pantryRig()
        val first = rig.openPantry().session
        rig.pick(0, 2)
        rig.settle()
        val saves = rig.store.saves.size
        val pet = rig.jason

        rig.vm.onPantryBack(first)
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals("what was on the board is back where it was", SHELF, rig.shelf)
        assertEquals(pet, rig.jason)
        rig.settle()
        assertEquals(saves, rig.store.saves.size)

        // The next visit starts with a clean board.
        val second = rig.openPantry()
        assertNotEquals(first, second.session)
        assertEquals(emptyList<Int>(), second.board)
        assertEquals(SHELF, rig.shelf)
    }

    @Test
    fun backAfterFeedingStillGoesHome() {
        val rig = pantryRig(SHELF + STEAK)
        val session = rig.openPantry().session
        rig.pick(SHELF.size)
        rig.vm.onPantryFeed(session)
        rig.vm.onPantryBack(session)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun everyVisitIsASessionOfItsOwn() {
        val rig = pantryRig()
        val seen = mutableSetOf<Long>()
        repeat(5) {
            assertTrue("a new session each time", seen.add(rig.openPantry().session))
            rig.vm.onPantryBack(rig.visit.session)
        }
    }

    @Test
    fun aStaleSessionCannotCookOrFeed() {
        // FEED, Back and FEED again within a frame, as the debug puppet goes: the last visit reporting late must not
        // act on this one.
        val rig = pantryRig(SHELF + STEAK)
        val old = rig.openPantry().session
        rig.pick(0, 2)
        rig.vm.onPantryBack(old)
        val now = rig.openPantry().session
        rig.pick(0, 2)
        val board = rig.ui

        rig.vm.onPantryCook(old)
        rig.vm.onPantryFeed(old)
        rig.vm.onPantryPick(old, 3)
        rig.vm.onPantryDone(old)
        rig.vm.onPantryBack(old)
        rig.vm.onPantryCook(now + 1)
        rig.vm.onPantryCook(-1)
        assertEquals("the shelf, the board and the screen are as they were", board, rig.ui)
        assertEquals(SHELF + STEAK, rig.shelf)

        rig.vm.onPantryPick(now, 2) // this one's board is still its own
        rig.vm.onPantryFeed(old)
        assertEquals(listOf(0), rig.visit.board)

        rig.vm.onPantryPick(now, 2)
        rig.vm.onPantryCook(now)
        assertEquals(FRIED_EGG, rig.visit.cook!!.result)
    }

    @Test
    fun pantryMessagesOffThePantryAreIgnored() {
        val rig = Rig(gameOf()).started()
        val before = rig.ui
        rig.vm.onPantryPick(1, 0)
        rig.vm.onPantryCook(1)
        rig.vm.onPantryFeed(1)
        rig.vm.onPantryDone(1)
        rig.vm.onPantryBack(1)
        assertEquals(before, rig.ui)
    }

    @Test
    fun aLevelUpFromFeedingOffersTheNewAttack() {
        val rig = pantryRig(SHELF + STEAK, pet = jason(xp = 95))
        val session = rig.openPantry().session
        rig.pick(SHELF.size)
        rig.vm.onPantryFeed(session)
        assertEquals(2, rig.jason.level)
        assertEquals(1, rig.visit.fed!!.levelsGained)
        assertTrue("the reaction comes first", rig.ui.screen is Screen.Pantry)

        rig.vm.onPantryDone(session)
        assertEquals(Screen.Learn(PetId.JASON, "jason_light_1"), rig.ui.screen)
        assertTrue("unlock" in rig.ui.sfx)
        rig.settle()
        assertEquals(2, rig.store.last.pets.getValue(PetId.JASON).level)
    }

    @Test
    fun theFavoriteIsRemembered() {
        val rig = pantryRig(SHELF + CAMP_CRYSTAL_FISH_FRY, pet = jason(hunger = 60.0))
        assertFalse(rig.jason.favoriteFound)
        val session = rig.openPantry().session
        rig.pick(SHELF.size)
        rig.vm.onPantryFeed(session)

        assertEquals(Reaction.FAVORITE, rig.visit.fed!!.reaction)
        assertTrue(rig.jason.favoriteFound)
        assertTrue("and only Jason's", rig.game.pets.filterKeys { it != PetId.JASON }.values.none { it.favoriteFound })
        rig.settle()
        assertTrue("saved with the meal", rig.store.last.pets.getValue(PetId.JASON).favoriteFound)
    }

    @Test
    fun aPantryMakesItsOwnKeySounds() {
        val rig = pantryRig(SHELF + STEAK)
        assertFalse("Home ticks", rig.ui.screen.ownsKeySounds)
        val session = rig.openPantry().session
        assertTrue(rig.ui.screen.ownsKeySounds)
        rig.pick(SHELF.size)
        rig.vm.onPantryFeed(session)
        assertTrue("the reaction too", rig.ui.screen.ownsKeySounds)
        rig.vm.onPantryDone(session)
        assertFalse(rig.ui.screen.ownsKeySounds)
    }

    @Test
    fun feedIsIgnoredWhileTheStableIsOpenOrAnActionPlays() {
        val rig = pantryRig()
        rig.vm.onCare(CareAction.PLAY)
        val busy = rig.ui
        rig.vm.onCare(CareAction.FEED)
        assertEquals(busy, rig.ui)
        rig.finishAllAnims()

        rig.vm.openStable()
        val stable = rig.ui
        rig.vm.onCare(CareAction.FEED)
        assertEquals(stable, rig.ui)
    }

    @Test
    fun aTickKeepsThePantryOnScreen() {
        val rig = pantryRig()
        val session = rig.openPantry().session
        rig.pick(0, 2)
        val hunger = rig.jason.hunger
        rig.clock.now += 3 * HOUR
        rig.vm.tick()
        assertTrue("time passed", rig.jason.hunger > hunger)
        assertEquals(session, rig.visit.session)
        assertEquals(listOf(0, 2), rig.visit.board)
        assertEquals(SHELF, rig.shelf)
    }

    // ---- the pantry: the puppet's way in -------------------------------------------------------

    @Test
    fun debugStockAddsToTheSavedPantry() {
        val rig = pantryRig()
        rig.vm.debugStock(listOf(SUN, SUN, BRAIN))
        assertEquals(SHELF + listOf(SUN, SUN, BRAIN), rig.shelf)
        assertEquals("on Home still", Screen.Home, rig.ui.screen)
        rig.settle()
        assertEquals(rig.game, rig.store.last)
        assertEquals((SHELF + listOf(SUN, SUN, BRAIN)).map { it.id }, rig.store.last.pantry)
    }

    @Test
    fun debugStockNeverFillsPastTheLimit() {
        val twoShort = List(Tuning.PANTRY_MAX - 2) { EGG }
        val rig = pantryRig(twoShort)
        rig.vm.debugStock(listOf(SUN, BRAIN, POT))
        assertEquals("the first that fit, in order", twoShort + listOf(SUN, BRAIN), rig.shelf)
        assertEquals(Tuning.PANTRY_MAX, rig.shelf.size)
        rig.settle()
        assertEquals(rig.game, rig.store.last)

        val saves = rig.store.saves.size
        rig.vm.debugStock(listOf(POT))
        assertEquals("a full shelf takes nothing", Tuning.PANTRY_MAX, rig.shelf.size)
        rig.settle()
        assertEquals(saves, rig.store.saves.size)
    }

    @Test
    fun debugStockWorksFromAnyScreenAndKeepsTheBoard() {
        val title = Rig(gameOf().copy(pantry = SHELF.map { it.id })).loaded()
        title.vm.debugStock(listOf(SUN))
        assertEquals("on the title", Screen.Title, title.ui.screen)
        assertEquals(SHELF + SUN, title.shelf)

        val rig = pantryRig()
        rig.openPantry()
        rig.pick(0, 2)
        rig.vm.debugStock(listOf(SUN))
        assertEquals("the pantry on screen: the board is as it was", listOf(0, 2), rig.visit.board)
        assertEquals(SHELF + SUN, rig.shelf)
    }

    @Test
    fun debugStockWaitsForTheSaveToLoad() {
        val rig = Rig(gameOf().copy(pantry = SHELF.map { it.id }))
        rig.vm.debugStock(listOf(SUN))
        rig.settle()
        assertEquals(Screen.Loading, rig.ui.screen)
        assertTrue("never saved over the real game", rig.store.saves.isEmpty())
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
        rig.vm.openStable()
        rig.vm.onStablePick(PetId.FREDDY)
        assertEquals(1, rig.jason.sceneIndex)
        assertEquals(0, rig.game.pets.getValue(PetId.FREDDY).sceneIndex)
    }

    // ---- train before the first fight ------------------------------------------------------------

    /** A new player's stable: just the starter, who has never fought and never trained. */
    private fun newPlayer(pet: PetState = PetState(PetId.JASON)) = loneStable(pet)

    /** Plays [game] to its end with [score] and leaves the results card, as a player does. */
    private fun Rig.trainThrough(game: TrainingGame, score: Int = 0) {
        trainAt(game)
        vm.onTrainingOver(session(), score)
        vm.onTrainingDone(session())
    }

    @Test
    fun aNewPlayerIsTurnedAwayFromTheFirstFight() {
        val rig = Rig(newPlayer()).started()
        rig.vm.onFightPressed()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(RefuseReason.NEEDS_TRAINING, rig.ui.refuse)
        assertEquals(Anim.FAIL, rig.ui.petAnim!!.anim)
        assertTrue(rig.ui.busy)
        assertEquals(listOf("refuse"), rig.ui.sfx)

        rig.vm.onFightPressed() // mashing during the refusal
        assertEquals(Screen.Home, rig.ui.screen)
        rig.finishAnim()
        assertFalse(rig.ui.busy)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun bothSoloGamesOpenTheFirstFight() {
        val rig = Rig(newPlayer()).started()
        rig.trainThrough(TrainingGame.SLASH_SAYS, 3)
        assertEquals(setOf("SLASH_SAYS"), rig.jason.trainedGames)
        rig.vm.onFightPressed()
        assertEquals("one of the two is not enough", RefuseReason.NEEDS_TRAINING, rig.ui.refuse)
        assertEquals(Screen.Home, rig.ui.screen)
        rig.finishAllAnims()

        rig.trainThrough(TrainingGame.ORGAN_DROP, 3)
        assertEquals(setOf("SLASH_SAYS", "ORGAN_DROP"), rig.jason.trainedGames)
        rig.vm.onFightPressed()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
        assertNull(rig.ui.refuse)
    }

    @Test
    fun theGamesAreRecordedAndSavedWithThePayout() {
        val rig = Rig(newPlayer()).started()
        rig.trainAt(TrainingGame.ORGAN_DROP)
        rig.vm.onTrainingOver(rig.session(), 4)
        assertEquals(setOf("ORGAN_DROP"), rig.jason.trainedGames)
        rig.settle()
        assertEquals(setOf("ORGAN_DROP"), rig.store.last.pets.getValue(PetId.JASON).trainedGames)
        assertEquals(rig.game, rig.store.last)
    }

    @Test
    fun aRejectedReportRecordsNothingForANewPlayer() {
        val rig = Rig(newPlayer()).started()
        rig.trainAt(TrainingGame.SLASH_SAYS)
        val stale = rig.session()
        rig.vm.onTrainingQuit(stale)
        rig.trainAt(TrainingGame.ORGAN_DROP)
        val now = rig.session()
        assertNotEquals(stale, now)
        val reel = rig.ui

        // A report from the session before, or from one that never was: nothing paid, nothing recorded.
        rig.vm.onTrainingOver(stale, 8)
        rig.vm.onTrainingOver(now + 1_000L, 8)
        assertEquals(reel, rig.ui)
        assertTrue(rig.jason.trainedGames.isEmpty())

        // Back on the reel, then the game's report coming in late for the very session that left.
        rig.vm.onTrainingQuit(now)
        rig.vm.onTrainingOver(now, 8)
        assertEquals(Screen.Home, rig.ui.screen)
        assertTrue(rig.jason.trainedGames.isEmpty())
        rig.settle()
        assertTrue(rig.store.last.pets.getValue(PetId.JASON).trainedGames.isEmpty())
        rig.vm.onFightPressed()
        assertEquals("still untrained", RefuseReason.NEEDS_TRAINING, rig.ui.refuse)
    }

    @Test
    fun anyEndedGameCountsEvenOneBackCutShortAtScoreZero() {
        val rig = Rig(newPlayer()).started()
        // Back mid-game reports the score so far, which can be nothing at all.
        rig.trainThrough(TrainingGame.SLASH_SAYS, 0)
        rig.trainThrough(TrainingGame.ORGAN_DROP, 0)
        rig.vm.onFightPressed()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
    }

    @Test
    fun quittingTheReelDoesNotCount() {
        val rig = Rig(newPlayer()).started()
        for (game in listOf(TrainingGame.SLASH_SAYS, TrainingGame.ORGAN_DROP)) {
            rig.trainAt(game)
            rig.vm.onTrainingQuit(rig.session())
        }
        assertTrue(rig.jason.trainedGames.isEmpty())
        rig.vm.onFightPressed()
        assertEquals(RefuseReason.NEEDS_TRAINING, rig.ui.refuse)
    }

    @Test
    fun playingTheSameSoloGameAgainDoesNotCoverTheOther() {
        val rig = Rig(newPlayer()).started()
        rig.trainThrough(TrainingGame.SLASH_SAYS, 5)
        rig.trainThrough(TrainingGame.SLASH_SAYS, 6)
        rig.vm.onFightPressed()
        assertEquals(RefuseReason.NEEDS_TRAINING, rig.ui.refuse)
    }

    @Test
    fun aMateGameDoesNotCountTowardTheFirstFight() {
        val rig = Rig(GameState(pets = mapOf(PetId.JASON to PetState(PetId.JASON), PetId.FREDDY to PetState(PetId.FREDDY)), selected = PetId.JASON, lastSeenMs = T0)).started()
        rig.trainThrough(TrainingGame.QUICK_DRAW, 5)
        rig.trainThrough(TrainingGame.HIDE_AND_SEEK, 5)
        assertEquals(setOf("QUICK_DRAW", "HIDE_AND_SEEK"), rig.jason.trainedGames)
        assertTrue("the mate trains nothing of its own", rig.game.pets.getValue(PetId.FREDDY).trainedGames.isEmpty())
        rig.vm.onFightPressed()
        assertEquals(RefuseReason.NEEDS_TRAINING, rig.ui.refuse)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun thePetOnShowIsTheOneThatMustHaveTrained() {
        val rig = Rig(GameState(pets = mapOf(PetId.JASON to jason(), PetId.MICHAEL to PetState(PetId.MICHAEL)), selected = PetId.JASON, lastSeenMs = T0)).started()
        rig.vm.onFightPressed()
        assertEquals("Jason has trained", Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
        rig.vm.onSwitcherBack()

        rig.vm.openStable()
        rig.vm.onStablePick(PetId.MICHAEL)
        rig.vm.onFightPressed()
        assertEquals("Michael has not", RefuseReason.NEEDS_TRAINING, rig.ui.refuse)
    }

    @Test
    fun aPlayerWhoHasFoughtIsNeverGated() {
        for (veteran in listOf(PetState(PetId.JASON, wins = 1), PetState(PetId.JASON, losses = 1))) {
            val rig = Rig(newPlayer(veteran)).started()
            rig.vm.onFightPressed()
            assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
            assertNull(rig.ui.refuse)
            assertTrue(rig.jason.trainedGames.isEmpty())
        }
        // A fight anywhere in the stable is enough, not only the selected pet's.
        val stable = GameState(
            pets = mapOf(PetId.JASON to PetState(PetId.JASON), PetId.FREDDY to PetState(PetId.FREDDY, wins = 2)),
            selected = PetId.JASON,
            lastSeenMs = T0,
        )
        val rig = Rig(stable).started()
        rig.vm.onFightPressed()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
    }

    @Test
    fun aStarvingNewPlayerIsToldItIsTooHungryFirst() {
        val rig = Rig(newPlayer(PetState(PetId.JASON, hunger = 90.0))).started()
        rig.vm.onFightPressed()
        assertEquals(RefuseReason.TOO_HUNGRY, rig.ui.refuse)
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(listOf("refuse"), rig.ui.sfx)
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

    /**
     * A script where Jason (strength 100) trades STRIKEs with a CPU that only ever picks STRIKE. The CPU picks as
     * each of Jason's moves comes in, before the turn's dice: on turn 1 Jason has no habit yet, so no habit roll;
     * on turns 2 and 3 a habit roll the CPU ignores (0.99 >= 0.25), then both attack.
     */
    private fun winningScript(): ScriptedRandom {
        val roll = listOf(Roll().miss, Roll().variance, Roll().crit)
        val doubles = buildList {
            addAll(roll + roll)
            repeat(2) {
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
        assertEquals("the second HEAVY lands as a combo and finishes him", 2, turns)
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
    fun theCpuPicksAsThePlayersMoveComesInAndTheTurnRemembersIt() {
        // Nothing is drawn for the CPU as the fight starts: only the opponent's build (two ints). Its pick (a
        // STRIKE, roll 0) comes with Jason's move, ahead of the turn's own dice (Jason has no habit yet).
        val roll = listOf(Roll().miss, Roll().variance, Roll().crit)
        val rng = ScriptedRandom(roll + roll, ints = listOf(0, 0, 0))
        val rig = Rig(strongJason(), rng = rng).started()
        rig.toFight()
        assertNull((rig.ui.screen as Screen.Fight).cpuMove)
        assertEquals("the CPU has not picked yet", 1, rng.remainingInts)

        rig.vm.onMove(Move.STRIKE)
        val turn = rig.ui.screen as Screen.Fight
        assertEquals(Move.STRIKE, turn.playerMove)
        assertEquals(Move.STRIKE, turn.cpuMove)
        assertEquals(0, rng.remainingInts)
        assertEquals(0, rng.remainingDoubles)
    }

    @Test
    fun twoFightsWithTheSameSeedPlayTheSameCpuMoves() {
        fun cpuMoves(seed: Int): List<Move?> {
            val rig = Rig(strongJason(), rng = Random(seed)).started()
            rig.toFight()
            val moves = mutableListOf<Move?>()
            while (rig.ui.screen is Screen.Fight) {
                rig.vm.onMove(Move.BLOCK)
                moves += (rig.ui.screen as Screen.Fight).cpuMove
                rig.finishAnim()
                check(moves.size < 20) { "the fight never ended" }
            }
            return moves
        }
        val first = cpuMoves(seed = 21)
        assertTrue("the CPU picks a move every turn", first.isNotEmpty() && first.none { it == null || it == Move.RUN })
        assertEquals(first, cpuMoves(seed = 21))
    }

    @Test
    fun runningAwayEndsTheFightAsALoss() {
        // The script has the opponent's build and nothing else: a CPU pick would throw.
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
    fun resumeAfterANapDecaysAndWelcomesBack() {
        // The launcher brings back the running Activity: no start(), just onResume().
        val rig = Rig(gameOf()).started()
        assertNull(rig.ui.toast)
        rig.clock.now += 5 * HOUR
        rig.vm.onResume()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(40.0, rig.jason.hunger, 1e-9)
        assertEquals("Away 5h · Hunger +20", rig.ui.toast)
    }

    @Test
    fun resumeAfterTenMinutesOnHomeShowsTheToast() {
        val rig = Rig(gameOf()).started()
        rig.clock.now += 10 * MINUTE
        rig.vm.onResume()
        assertEquals("Away 10m · Hunger +1", rig.ui.toast)
    }

    @Test
    fun resumeUnderTenMinutesDecaysWithoutAToast() {
        val rig = Rig(gameOf()).started()
        rig.clock.now += 9 * MINUTE
        rig.vm.onResume()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(20.6, rig.jason.hunger, 1e-9)
        assertNull(rig.ui.toast)
    }

    @Test
    fun resumeOffTheHomeScreenShowsNoToast() {
        val rig = Rig(gameOf()).started()
        rig.vm.onFightPressed()
        rig.clock.now += 5 * HOUR
        rig.vm.onResume()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
        assertEquals(40.0, rig.jason.hunger, 1e-9)
        assertNull(rig.ui.toast)
    }

    @Test
    fun tickStaysSilentEvenAfterALongGap() {
        val rig = Rig(gameOf()).started()
        rig.clock.now += 5 * HOUR
        rig.vm.tick()
        assertEquals(40.0, rig.jason.hunger, 1e-9)
        assertNull(rig.ui.toast)
    }

    @Test
    fun aClockBelowTheFloorIsTreatedAsUnknown() {
        // Installed at `floor`; this boot's clock is stale (T0, a month earlier), as is the saved lastSeen.
        val floor = T0 + 30 * DAY
        val rig = Rig(gameOf(lastSeenMs = T0 - 3 * HOUR), clockFloorMs = floor).started()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals("no decay", 20.0, rig.jason.hunger, 1e-9)
        assertNull(rig.ui.toast)
        assertEquals("no lastSeen write", T0 - 3 * HOUR, rig.game.lastSeenMs)

        rig.clock.now += 2 * HOUR
        rig.vm.onResume()
        rig.vm.tick()
        assertEquals(20.0, rig.jason.hunger, 1e-9)
        assertEquals(T0 - 3 * HOUR, rig.game.lastSeenMs)
    }

    @Test
    fun aLastSeenBelowTheFloorCountsAsAFirstRun() {
        // The clock has synced, but the save was last written under the stale clock: that gap is not
        // a real absence, so no 30-day jump and no fake death.
        val floor = T0 - 10 * DAY
        val rig = Rig(gameOf(lastSeenMs = floor - 20 * DAY), clockFloorMs = floor).started()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(20.0, rig.jason.hunger, 1e-9)
        assertFalse(rig.jason.fakeDeathPending)
        assertNull(rig.ui.toast)
        assertEquals(T0, rig.game.lastSeenMs)
        rig.settle()
        assertEquals(T0, rig.store.last.lastSeenMs)

        rig.clock.now += 3 * HOUR
        rig.vm.tick()
        assertEquals(32.0, rig.jason.hunger, 1e-9)
    }

    @Test
    fun fakeDeathFromATickWaitsForTheActionToFinish() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.PLAY)
        rig.clock.now += 7 * DAY
        rig.vm.tick()
        assertEquals("not mid-animation", Screen.Home, rig.ui.screen)
        assertTrue(rig.ui.busy)

        rig.finishAnim()
        assertEquals("not between the steps", Screen.Home, rig.ui.screen)
        rig.finishAnim()
        assertEquals(Screen.FakeDeath(PetId.JASON), rig.ui.screen)
        assertFalse(rig.ui.busy)
    }

    @Test
    fun fakeDeathFromATickWaitsOffTheHomeScreenUntilHome() {
        val rig = Rig(gameOf()).started()
        rig.vm.onFightPressed()
        rig.clock.now += 7 * DAY
        rig.vm.tick()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)

        rig.vm.onSwitcherBack()
        assertEquals(Screen.FakeDeath(PetId.JASON), rig.ui.screen)
    }

    // ---- saving ------------------------------------------------------------------------------

    @Test
    fun saveFailureDoesNotCrashOrStopTheGame() {
        val store = FakeStore(gameOf(pets = arrayOf(jason(hunger = 60.0)))).apply { failSaves = true }
        val rig = Rig(gameOf(), store = store).started()
        assertEquals(Screen.Home, rig.ui.screen)

        rig.vm.onCare(CareAction.PLAY)
        rig.finishAllAnims()
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
        rig.vm.onCare(CareAction.PLAY)
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
        val vm = GameViewModel(store, FakeClock(T0), Random(1), scope)
        vm.start()
        scope.runCurrent() // the first save is now in flight and slow
        vm.onStart()
        vm.onCare(CareAction.PLAY) // its successor must wait for it
        scope.advanceUntilIdle()
        assertEquals(listOf(60.0, 68.0), store.saved.map { it.pets.getValue(PetId.JASON).hunger })
        assertEquals(vm.state.value.game, store.saved.last())
    }

    @Test
    fun playStartsWithAGagOnItsFirstRequestOnly() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 20.0)))).started()
        rig.vm.onCare(CareAction.PLAY)
        val first = rig.ui.petAnim!!
        assertEquals(Anim.JUMP, first.anim)
        assertNotNull(first.gag)
        rig.finishAnim()
        assertEquals(Anim.WAVE, rig.ui.petAnim!!.anim)
        assertNull(rig.ui.petAnim!!.gag)
        rig.finishAnim()
        assertFalse(rig.ui.busy)
    }

    @Test
    fun twoPlaysInARowDoDifferentGags() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 20.0, funLevel = 10.0)))).started()
        rig.vm.onCare(CareAction.PLAY)
        val first = rig.ui.petAnim!!.gag
        rig.finishAllAnims()
        rig.vm.onCare(CareAction.PLAY)
        val second = rig.ui.petAnim!!.gag
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test
    fun trainPlaysNoAnimationOnHome() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 20.0)))).started()
        rig.vm.onCare(CareAction.PLAY)
        rig.finishAllAnims()
        rig.vm.onCare(CareAction.TRAIN)
        assertNull("training plays no animation of its own", rig.ui.petAnim)
    }

    // ---- the stable: unlocking killers and switching between them ------------------------------

    /** A stable of just a strong Jason, strong enough to win [winningScript]'s fight. */
    private fun soloJason() = GameState(pets = mapOf(PetId.JASON to jason(strength = 100.0)), selected = PetId.JASON, lastSeenMs = T0)

    /** Plays the fight out with [move] every turn. */
    private fun Rig.playOut(move: Move = Move.STRIKE) {
        var turns = 0
        while (ui.screen is Screen.Fight) {
            vm.onMove(move)
            finishAnim()
            check(++turns < 20) { "the fight never ended" }
        }
    }

    @Test
    fun beatingALockedKillerUnlocksIt() {
        val rig = Rig(soloJason(), rng = winningScript()).started()
        rig.toFight(PetId.FREDDY)
        rig.vm.consumeSounds()
        rig.playOut()

        val unlocked = rig.ui.screen as Screen.Unlocked
        assertEquals(PetId.FREDDY, unlocked.pet)
        assertTrue(unlocked.results.won)
        assertEquals(setOf(PetId.JASON, PetId.FREDDY), rig.game.pets.keys)
        // It joins at its defaults, from now: the stable's clock is brought up to the moment it joined.
        assertEquals(PetState(PetId.FREDDY), rig.game.pets.getValue(PetId.FREDDY))
        assertEquals(rig.clock.now, rig.game.lastSeenMs)
        assertEquals(listOf("you_win", "unlocked"), rig.ui.voice)
        rig.settle()
        assertTrue(rig.store.last.isUnlocked(PetId.FREDDY))

        rig.vm.onUnlockedDone()
        assertEquals(unlocked.results, rig.ui.screen)
        rig.vm.onUnlockedDone() // key repeat
        assertEquals(unlocked.results, rig.ui.screen)
        rig.vm.onResultsDone()
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun aKillerUnlockedRightBeforeQuittingStillFeelsTheNextAbsence() {
        val rig = Rig(soloJason(), rng = winningScript()).started()
        rig.toFight(PetId.FREDDY)
        rig.clock.now += 30_000 // the fight took half a minute; no tick has run since the last one
        rig.playOut()
        assertTrue(rig.ui.screen is Screen.Unlocked)
        rig.settle()

        // The player quits at once and comes back a week later: Freddy was there for all of it.
        val later = Rig(rig.store.last, clock = FakeClock(rig.clock.now + 8 * DAY)).started()
        val freddy = later.game.pets.getValue(PetId.FREDDY)
        assertTrue(freddy.fakeDeathPending)
        assertEquals(Tuning.FAKE_DEATH_HUNGER, freddy.hunger, 1e-9)
        assertEquals(Tuning.FAKE_DEATH_FUN, freddy.funLevel, 1e-9)
    }

    @Test
    fun underAStaleClockAnUnlockedKillerWaitsForTheRealTime() {
        // Before the TV's clock has synced, no time can be applied, so the newcomer keeps the fresh-join mark and
        // sits out whatever gap the real clock turns out to be.
        val stale = FakeClock(Tuning.MIN_VALID_EPOCH_MS - DAY)
        val rig = Rig(soloJason(), rng = winningScript(), clock = stale).started()
        rig.toFight(PetId.FREDDY)
        rig.playOut()
        assertEquals(PetState(PetId.FREDDY, freshJoin = true), rig.game.pets.getValue(PetId.FREDDY))
    }

    @Test
    fun aPotionIsOnlyDrunkBelowFullHealthAndWhileAnyAreLeft() {
        // Dice: the opponent's build, then turn 1 the CPU HEAVYs Jason's BLOCK; turns 2 and 3 Jason drinks (rolling
        // the smallest heal) while the CPU, ignoring his BLOCK habit, STRIKEs. Every swing lands plainly. The CPU
        // picks as each move comes in (from turn 2 a habit roll, then the pick), ahead of the turn's own dice.
        val swing = listOf(Roll().miss, Roll().variance, Roll().crit)
        val doubles = swing + 0.99 + 0.0 + swing + 0.99 + 0.0 + swing
        val rig = Rig(strongJason(), rng = ScriptedRandom(doubles, ints = listOf(0, 0, 40, 0, 0))).started()
        rig.toFight(PetId.FREDDY)

        rig.vm.onMove(Move.POTION) // at full health a potion would be wasted: ignored
        assertFalse(rig.ui.busy)
        assertEquals(2, (rig.ui.screen as Screen.Fight).fight.playerPotions)

        rig.vm.onMove(Move.BLOCK)
        rig.finishAnim()
        for (left in listOf(1, 0)) {
            rig.vm.onMove(Move.POTION)
            val fight = rig.ui.screen as Screen.Fight
            assertEquals(Move.POTION, fight.playerMove)
            assertEquals(left, fight.fight.playerPotions)
            assertTrue(fight.lastEvents.first() is FightEvent.Heal)
            rig.finishAnim()
        }

        val before = rig.ui
        rig.vm.onMove(Move.POTION) // none left: ignored
        assertEquals(before, rig.ui)
    }

    @Test
    fun aFighterNearlyDoneTurnsTheFightIntenseAndAPotionDoesNotUndoIt() {
        // Dice: the opponent's build, then turn 1 the CPU's HEAVY crits Jason's BLOCK (77 HP down to 12, under 30%);
        // turn 2 Jason drinks the biggest heal while the CPU, ignoring his BLOCK habit, STRIKEs (back up to 53).
        val crit = Roll(crit = 0.0)
        val swing = listOf(Roll().miss, Roll().variance, Roll().crit)
        val doubles = listOf(crit.miss, crit.variance, crit.crit) + 0.99 + 0.99 + swing
        val rig = Rig(strongJason(), rng = ScriptedRandom(doubles, ints = listOf(0, 0, 40, 0))).started()
        rig.toFight(PetId.FREDDY)
        assertFalse((rig.ui.screen as Screen.Fight).intense)
        assertEquals("fight", musicTrackFor(rig.ui))

        rig.vm.onMove(Move.BLOCK)
        val hurt = rig.ui.screen as Screen.Fight
        assertEquals(12, hurt.fight.player.hp)
        assertFalse("the music waits for the blow to play out", hurt.intense)
        assertEquals("fight", musicTrackFor(rig.ui))
        rig.finishAnim()
        assertTrue("and hardens once it has", (rig.ui.screen as Screen.Fight).intense)
        assertEquals("fight_intense", musicTrackFor(rig.ui))

        rig.vm.onMove(Move.POTION)
        val healed = rig.ui.screen as Screen.Fight
        assertEquals(53, healed.fight.player.hp)
        assertFalse("nobody is nearly done any more", nearlyDone(healed.fight))
        assertTrue("but the music stays hard to the end of the fight", healed.intense)
        rig.finishAnim()
        assertTrue("the potion played out too", (rig.ui.screen as Screen.Fight).intense)
        assertEquals("fight_intense", musicTrackFor(rig.ui))
    }

    @Test
    fun aKnockoutFromAboveTheLineEndsTheFightOnThePlainMusic() {
        // winningScript's fight: Freddy goes 62, 41, 20 (still above 30%), then Jason's third strike knocks him out
        // and leaves Jason himself at 14 of 77. Nobody was nearly done while the fight went on.
        val rig = Rig(strongJason(), rng = winningScript()).started()
        rig.toFight(PetId.FREDDY)
        var turns = 0
        while (rig.ui.screen is Screen.Fight) {
            rig.vm.onMove(Move.STRIKE)
            assertFalse((rig.ui.screen as Screen.Fight).intense)
            assertEquals("fight", musicTrackFor(rig.ui))
            rig.finishAnim()
            check(++turns < 20) { "the fight never ended" }
        }
        val results = rig.ui.screen as Screen.Results
        assertTrue(results.won)
        assertTrue("the last blow left Jason nearly done too", nearlyDone(results.fight!!))
    }

    @Test
    fun beatingAKillerYouHaveIsARematch() {
        val rig = Rig(strongJason(), rng = winningScript()).started()
        val freddy = rig.game.pets.getValue(PetId.FREDDY)
        rig.toFight(PetId.FREDDY)
        rig.playOut()
        assertTrue((rig.ui.screen as Screen.Results).won)
        assertEquals(freddy, rig.game.pets.getValue(PetId.FREDDY))
    }

    @Test
    fun losingUnlocksNoOne() {
        // As in losingAFightRecordsALoss: the CPU always HEAVYs and Jason's BLOCKs do nothing against it.
        val heavy = listOf(Roll().miss, Roll().variance, Roll().crit)
        val doubles = buildList {
            addAll(heavy)
            repeat(2) {
                add(0.99)
                addAll(heavy)
            }
        }
        val rig = Rig(soloJason(), rng = ScriptedRandom(doubles, ints = listOf(0, 0, 40, 40, 40))).started()
        rig.toFight(PetId.PINHEAD)
        rig.playOut(Move.BLOCK)
        assertFalse((rig.ui.screen as Screen.Results).won)
        assertEquals(setOf(PetId.JASON), rig.game.pets.keys)
    }

    @Test
    fun runningUnlocksNoOne() {
        val rig = Rig(soloJason()).started()
        rig.toFight(PetId.PINHEAD)
        rig.vm.onMove(Move.RUN)
        rig.finishAnim()
        assertTrue((rig.ui.screen as Screen.Results).ran)
        assertEquals(setOf(PetId.JASON), rig.game.pets.keys)
    }

    @Test
    fun theStableNeedsASecondKillerAndAnIdleHome() {
        val solo = Rig(soloJason()).started()
        solo.vm.openStable()
        assertFalse("nobody else to go see", solo.ui.stableOpen)

        val rig = Rig(gameOf()).started()
        rig.vm.onCare(CareAction.PLAY)
        rig.vm.openStable()
        assertFalse("not while busy", rig.ui.stableOpen)
        rig.finishAllAnims()
        rig.vm.onFightPressed()
        rig.vm.openStable()
        assertFalse("not off Home", rig.ui.stableOpen)
    }

    @Test
    fun pickingInTheStableGoesToSeeThem() {
        val rig = Rig(GameState(pets = mapOf(PetId.JASON to jason(), PetId.MICHAEL to PetState(PetId.MICHAEL)), selected = PetId.JASON, lastSeenMs = T0)).started()
        rig.vm.onStablePick(PetId.MICHAEL)
        assertEquals("closed: ignored", PetId.JASON, rig.game.selected)

        rig.vm.openStable()
        rig.vm.onStablePick(PetId.FREDDY)
        assertEquals("locked: ignored", PetId.JASON, rig.game.selected)
        assertTrue(rig.ui.stableOpen)

        rig.vm.onStablePick(PetId.MICHAEL)
        assertEquals(PetId.MICHAEL, rig.game.selected)
        assertFalse(rig.ui.stableOpen)
        assertEquals(Screen.Home, rig.ui.screen)
        rig.settle()
        assertEquals(PetId.MICHAEL, rig.store.last.selected)
    }

    @Test
    fun whileTheStableIsOpenHomeTakesNoInput() {
        val rig = Rig(gameOf()).started()
        rig.vm.openStable()
        val before = rig.ui
        rig.vm.onCare(CareAction.FEED)
        rig.vm.onWalk(1)
        rig.vm.onFightPressed()
        assertEquals(before, rig.ui)
    }

    // ---- the secret killer: here comes a new challenger -------------------------------------------

    /**
     * The seven killers anyone can unlock and nobody else, the secret one not revealed: a stable that has beaten
     * everyone, as an old save that had them all before he existed. [jason] is the one selected; the default is strong
     * enough to win [winningScript]'s fight.
     */
    private fun everyoneButTheSecret(jason: PetState = jason(strength = 100.0)) = GameState(
        pets = Starters.associateWith { if (it == PetId.JASON) jason else PetState(it, trainedGames = TRAINED) },
        selected = PetId.JASON,
        lastSeenMs = T0,
    )

    /** FIGHT on Home, which has to open the challenger's entrance: its screen. */
    private fun Rig.toChallenger(): Screen.Challenger {
        vm.onFightPressed()
        return ui.screen as? Screen.Challenger ?: error("expected the challenger's entrance, got ${ui.screen}")
    }

    /** Plays out a fight with [move] every turn, however long it takes, the CPU on real dice. */
    private fun Rig.fightOut(move: Move) {
        var turns = 0
        while (ui.screen is Screen.Fight) {
            vm.onMove(move)
            finishAnim()
            check(++turns < 200) { "the fight never ended" }
        }
    }

    @Test
    fun anOldSaveWithEveryoneGetsTheChallenger() {
        // A 0.7.0 save: all seven, and no `revealed`, a field it did not have.
        val trained = TRAINED.joinToString(",") { "\"$it\"" }
        val pets = Starters.joinToString(",") { """"${it.name}":{"id":"${it.name}","trainedGames":[$trained]}""" }
        val v070 = """{"version":2,"selected":"JASON","lastSeenMs":$T0,"pets":{$pets}}"""
        val rig = Rig(SaveCodec.decode(v070)).started()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(Starters.toSet(), rig.game.pets.keys)
        assertTrue(rig.game.revealed.isEmpty())

        val challenger = rig.toChallenger()
        assertEquals(PetId.JAWS, challenger.pet)
        assertNull(rig.ui.refuse)
        assertFalse("the entrance is a screen, not an action: nothing waits on an animation", rig.ui.busy)
        assertTrue("the entrance plays its own sounds, on its own timeline", rig.ui.sfx.isEmpty() && rig.ui.voice.isEmpty())

        // FIGHT again (a mashed key reaching the view model late) does nothing: Home is not up.
        rig.vm.onFightPressed()
        assertEquals(challenger, rig.ui.screen)
    }

    @Test
    fun theRevealIsSavedWhenTheEntranceStarts() {
        val rig = Rig(everyoneButTheSecret()).started()
        rig.settle()
        val saves = rig.store.saves.size

        val challenger = rig.toChallenger()
        assertEquals(setOf("JAWS"), rig.game.revealed)
        rig.settle()
        assertEquals("saved once, as the entrance starts", saves + 1, rig.store.saves.size)
        assertEquals(setOf("JAWS"), rig.store.last.revealed)
        assertEquals(Starters.toSet(), rig.store.last.pets.keys)
        assertEquals("still on the entrance: the save does not wait for it to end", challenger, rig.ui.screen)

        // He is in every list from now on, still to beat, and no FIGHT plays the entrance again.
        assertTrue(rig.game.isVisible(PetId.JAWS))
        assertFalse(rig.game.isUnlocked(PetId.JAWS))
        assertNull(rig.game.challengerDue())
    }

    @Test
    fun doneGoesToTheVsCardAgainstHim() {
        val rig = Rig(everyoneButTheSecret()).started()
        val challenger = rig.toChallenger()

        rig.vm.onChallengerDone(challenger.session)
        assertEquals(Screen.Versus(PetId.JAWS), rig.ui.screen)
        // As picking him in the switcher would: the announcer's "<player> versus <opponent>".
        assertEquals(listOf("jason", "versus", "jaws"), rig.ui.voice)
        assertEquals(PetId.JASON, rig.game.selected)
        assertFalse(rig.ui.busy)

        rig.vm.onVersusDone()
        val fight = rig.ui.screen as Screen.Fight
        assertEquals(PetId.JASON, fight.fight.player.id)
        assertEquals(PetId.JAWS, fight.fight.cpu.id)
    }

    @Test
    fun aStarvingPetDoesNotTriggerTheReveal() {
        val rig = Rig(everyoneButTheSecret(jason(hunger = 90.0))).started()
        rig.settle()
        val saves = rig.store.saves.size

        rig.vm.onFightPressed()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(RefuseReason.TOO_HUNGRY, rig.ui.refuse)
        rig.finishAnim()
        assertTrue(rig.game.revealed.isEmpty())
        assertEquals("he is still due, for the FIGHT that is not refused", PetId.JAWS, rig.game.challengerDue())
        rig.settle()
        assertEquals(saves, rig.store.saves.size)

        // Nor does a new player who has to train before a first fight.
        val untrained = Rig(everyoneButTheSecret(PetState(PetId.JASON))).started()
        untrained.vm.onFightPressed()
        assertEquals(Screen.Home, untrained.ui.screen)
        assertEquals(RefuseReason.NEEDS_TRAINING, untrained.ui.refuse)
        assertTrue(untrained.game.revealed.isEmpty())
    }

    @Test
    fun losingLeavesHimAsALockedCard() {
        // Jason, with no strength at all, strikes every turn against him and loses.
        val rig = Rig(everyoneButTheSecret(jason(strength = 0.0)), rng = Random(7)).started()
        rig.vm.onChallengerDone(rig.toChallenger().session)
        rig.vm.onVersusDone()
        rig.fightOut(Move.STRIKE)
        val results = rig.ui.screen as Screen.Results
        assertFalse(results.won)
        assertFalse(results.ran)
        assertFalse(rig.game.isUnlocked(PetId.JAWS))
        rig.vm.onResultsDone()
        assertEquals(Screen.Home, rig.ui.screen)
        rig.settle()
        assertEquals(setOf("JAWS"), rig.store.last.revealed)

        // The next FIGHT opens the switcher, where he waits at the end of the row, locked until he is beaten.
        rig.vm.onFightPressed()
        assertEquals(Screen.Switcher(SwitcherMode.OPPONENT), rig.ui.screen)
        val opponents = lineup(SwitcherMode.OPPONENT, rig.game)
        assertEquals(PetId.JAWS, opponents.last())
        assertEquals(7, opponents.size)
        assertFalse(rig.game.isUnlocked(PetId.JAWS))
        rig.vm.onSwitcherPick(PetId.JAWS)
        assertEquals(Screen.Versus(PetId.JAWS), rig.ui.screen)
    }

    @Test
    fun beatingHimUnlocksHim() {
        val rig = Rig(everyoneButTheSecret(), rng = winningScript()).started()
        rig.vm.onChallengerDone(rig.toChallenger().session)
        rig.vm.onVersusDone()
        rig.vm.consumeSounds()
        rig.playOut()

        val unlocked = rig.ui.screen as Screen.Unlocked
        assertEquals(PetId.JAWS, unlocked.pet)
        assertTrue(unlocked.results.won)
        assertEquals(listOf("you_win", "unlocked"), rig.ui.voice)
        assertEquals(PetId.entries.toSet(), rig.game.pets.keys)
        assertEquals(PetState(PetId.JAWS), rig.game.pets.getValue(PetId.JAWS))
        rig.settle()
        assertTrue(rig.store.last.isUnlocked(PetId.JAWS))
        assertNull(rig.game.challengerDue())
    }

    @Test
    fun aStaleSessionIsIgnored() {
        val rig = Rig(everyoneButTheSecret()).started()
        val challenger = rig.toChallenger()

        rig.vm.onChallengerDone(challenger.session + 1)
        rig.vm.onChallengerDone(challenger.session - 1)
        assertEquals(challenger, rig.ui.screen)
        assertTrue(rig.ui.voice.isEmpty())

        rig.vm.onChallengerDone(challenger.session)
        assertEquals(Screen.Versus(PetId.JAWS), rig.ui.screen)
        val after = rig.ui
        // The entrance's end reported again (OK on the frame it ended by itself) changes nothing on the VS card,
        rig.vm.onChallengerDone(challenger.session)
        assertEquals(after, rig.ui)
        // nor anywhere else.
        rig.vm.onVersusBack()
        rig.vm.onSwitcherBack()
        assertEquals(Screen.Home, rig.ui.screen)
        rig.vm.onChallengerDone(challenger.session)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    // ---- sounds and toast --------------------------------------------------------------------

    @Test
    fun consumeSoundsClearsBothQueues() {
        val rig = Rig(gameOf(pets = arrayOf(jason(hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.PLAY)
        rig.finishAllAnims()
        rig.vm.onFightPressed()
        rig.vm.onSwitcherPick(PetId.GHOSTFACE)
        // Unconsumed sounds pile up in order.
        assertEquals(listOf("hand_whoosh"), rig.ui.sfx)
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
