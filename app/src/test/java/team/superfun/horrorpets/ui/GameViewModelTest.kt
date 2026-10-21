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
) {
    val errors = mutableListOf<Throwable>()
    val scope = TestScope(StandardTestDispatcher())
    val vm = GameViewModel(
        store = store,
        clock = clock,
        rng = rng,
        scope = scope,
        feedAnim = { if (it == PetId.FREDDY || it == PetId.GHOSTFACE) Anim.WORK else Anim.REVIEW },
        // As the real manifests have it: only Leatherface ships a dance.
        canDance = { it == PetId.LEATHERFACE },
        onError = { errors += it },
        clockFloorMs = clockFloorMs,
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

    /** A save as v0.1.0 wrote it (four pets, every field), last seen [lastSeenMs]. */
    private fun v010Save(lastSeenMs: Long): String = """
        {"version":1,"pets":{
        "JASON":{"id":"JASON","hunger":12.5,"funLevel":64.0,"strength":31.0,"level":3,"xp":120,"moodBonus":4.5,"wins":5,"losses":2,"sceneIndex":1,"fakeDeathPending":false},
        "FREDDY":{"id":"FREDDY","hunger":40.0,"funLevel":22.25,"strength":18.0,"level":2,"xp":15,"moodBonus":0.0,"wins":1,"losses":3,"sceneIndex":2,"fakeDeathPending":false},
        "PINHEAD":{"id":"PINHEAD","hunger":20.0,"funLevel":80.0,"strength":20.0,"level":1,"xp":0,"moodBonus":-10.0,"wins":0,"losses":1,"sceneIndex":0,"fakeDeathPending":false},
        "GHOSTFACE":{"id":"GHOSTFACE","hunger":30.0,"funLevel":55.0,"strength":61.0,"level":7,"xp":640,"moodBonus":12.0,"wins":12,"losses":9,"sceneIndex":2,"fakeDeathPending":false}},
        "selected":"GHOSTFACE","lastSeenMs":$lastSeenMs}
    """.trimIndent()

    private val originalFour = listOf(PetId.JASON, PetId.FREDDY, PetId.PINHEAD, PetId.GHOSTFACE)

    /** The killers added since v0.1.0. */
    private val sinceV010 = listOf(PetId.CHUCKY, PetId.MICHAEL, PetId.LEATHERFACE)

    @Test
    fun aV010UpgradeAfterAWeekAwayFakeKillsTheOriginalFourButTheNewcomersArriveFresh() {
        val loaded = SaveCodec.decode(v010Save(T0 - 8 * DAY))
        val rig = Rig(loaded).started()

        // The four original killers decay and fake-die exactly as they did before the update.
        val asBefore = applyElapsed(loaded.copy(pets = loaded.pets.filterKeys { it in originalFour }), T0).first
        for (id in originalFour) {
            assertEquals("$id", asBefore.pets.getValue(id), rig.game.pets.getValue(id))
            assertTrue("$id", rig.game.pets.getValue(id).fakeDeathPending)
        }
        assertEquals(Screen.FakeDeath(PetId.GHOSTFACE), rig.ui.screen)
        // The three killers added since v0.1.0 have only just arrived: no time away, no fake death, nothing decayed.
        for (id in sinceV010) {
            assertEquals("$id", PetState(id), rig.game.pets.getValue(id))
            assertEquals("$id saved", PetState(id), rig.store.last.pets.getValue(id))
        }
        assertEquals(T0, rig.store.last.lastSeenMs)

        // After the reveal, picking Chucky goes straight Home: no R.I.P. for a pet that just moved in.
        rig.vm.onFakeDeathDone()
        rig.vm.openSwitcher()
        rig.vm.onSwitcherPick(PetId.CHUCKY)
        assertEquals(Screen.Home, rig.ui.screen)
    }

    @Test
    fun anUpgradeUnderAStaleClockKeepsTheNewcomersFreshUntilTheClockIsGood() {
        // First launch after the update, before the TV's clock has synced: nothing is applied, and the save
        // written now must still say that Chucky, Michael and Leatherface have only just joined.
        val loaded = SaveCodec.decode(v010Save(T0 - 8 * DAY))
        val stale = Rig(loaded, clock = FakeClock(Tuning.MIN_VALID_EPOCH_MS - DAY)).started()
        val saved = stale.store.last
        for (id in sinceV010) assertTrue("$id", saved.pets.getValue(id).freshJoin)
        assertEquals(T0 - 8 * DAY, saved.lastSeenMs)

        // Next launch with a good clock: the original four decay and fake-die, the new three arrive fresh.
        val reloaded = SaveCodec.decode(SaveCodec.encode(saved))
        val good = Rig(reloaded).started()
        val asBefore = applyElapsed(loaded.copy(pets = loaded.pets.filterKeys { it in originalFour }), T0).first
        for (id in originalFour) assertEquals("$id", asBefore.pets.getValue(id), good.game.pets.getValue(id))
        for (id in sinceV010) {
            assertEquals("$id", PetState(id), good.game.pets.getValue(id))
            assertEquals("$id saved", PetState(id), good.store.last.pets.getValue(id))
        }
    }

    @Test
    fun anUpgradeAfterTenHoursDecaysTheOriginalFourButNotTheNewcomers() {
        val loaded = SaveCodec.decode(v010Save(T0 - 10 * HOUR))
        val rig = Rig(loaded).started()
        assertEquals(12.5 + 40.0, rig.game.pets.getValue(PetId.JASON).hunger, 1e-9)
        for (id in sinceV010) assertEquals("$id", PetState(id), rig.game.pets.getValue(id))

        // Once they have joined, they decay like everyone else.
        rig.clock.now = T0 + 10 * HOUR
        rig.vm.tick()
        assertEquals(20.0 + 40.0, rig.game.pets.getValue(PetId.CHUCKY).hunger, 1e-9)
        assertEquals(20.0 + 40.0, rig.game.pets.getValue(PetId.LEATHERFACE).hunger, 1e-9)
    }

    /** A save as v0.2.0 wrote it (six pets, every field, the fresh-join mark included), last seen [lastSeenMs]. */
    private fun v020Save(lastSeenMs: Long): String = """
        {"version":1,"pets":{
        "JASON":{"id":"JASON","hunger":12.5,"funLevel":64.0,"strength":31.0,"level":3,"xp":120,"moodBonus":4.5,"wins":5,"losses":2,"sceneIndex":1,"fakeDeathPending":false,"freshJoin":false},
        "FREDDY":{"id":"FREDDY","hunger":40.0,"funLevel":22.25,"strength":18.0,"level":2,"xp":15,"moodBonus":0.0,"wins":1,"losses":3,"sceneIndex":2,"fakeDeathPending":false,"freshJoin":false},
        "PINHEAD":{"id":"PINHEAD","hunger":20.0,"funLevel":80.0,"strength":20.0,"level":1,"xp":0,"moodBonus":-10.0,"wins":0,"losses":1,"sceneIndex":0,"fakeDeathPending":false,"freshJoin":false},
        "GHOSTFACE":{"id":"GHOSTFACE","hunger":30.0,"funLevel":55.0,"strength":61.0,"level":7,"xp":640,"moodBonus":12.0,"wins":12,"losses":9,"sceneIndex":2,"fakeDeathPending":false,"freshJoin":false},
        "CHUCKY":{"id":"CHUCKY","hunger":35.0,"funLevel":70.0,"strength":26.0,"level":2,"xp":40,"moodBonus":0.0,"wins":2,"losses":1,"sceneIndex":1,"fakeDeathPending":false,"freshJoin":false},
        "MICHAEL":{"id":"MICHAEL","hunger":45.0,"funLevel":50.0,"strength":44.0,"level":4,"xp":210,"moodBonus":-3.0,"wins":6,"losses":4,"sceneIndex":0,"fakeDeathPending":false,"freshJoin":false}},
        "selected":"MICHAEL","lastSeenMs":$lastSeenMs}
    """.trimIndent()

    private val v020Six = listOf(PetId.JASON, PetId.FREDDY, PetId.PINHEAD, PetId.GHOSTFACE, PetId.CHUCKY, PetId.MICHAEL)

    @Test
    fun aV020UpgradeAfterAWeekAwayFakeKillsTheSixButLeatherfaceArrivesFresh() {
        val loaded = SaveCodec.decode(v020Save(T0 - 8 * DAY))
        val rig = Rig(loaded).started()

        // The six killers v0.2.0 had decay and fake-die exactly as they did before the update.
        val asBefore = applyElapsed(loaded.copy(pets = loaded.pets.filterKeys { it in v020Six }), T0).first
        for (id in v020Six) {
            assertEquals("$id", asBefore.pets.getValue(id), rig.game.pets.getValue(id))
            assertTrue("$id", rig.game.pets.getValue(id).fakeDeathPending)
        }
        assertEquals(Screen.FakeDeath(PetId.MICHAEL), rig.ui.screen)
        // Leatherface has only just arrived: no time away, no fake death, nothing decayed, in memory and saved.
        assertEquals(PetState(PetId.LEATHERFACE), rig.game.pets.getValue(PetId.LEATHERFACE))
        assertEquals(PetState(PetId.LEATHERFACE), rig.store.last.pets.getValue(PetId.LEATHERFACE))
        assertEquals(T0, rig.store.last.lastSeenMs)

        // After the reveal, picking Leatherface goes straight Home: no R.I.P. for a pet that just moved in.
        rig.vm.onFakeDeathDone()
        rig.vm.openSwitcher()
        rig.vm.onSwitcherPick(PetId.LEATHERFACE)
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(PetId.LEATHERFACE, rig.game.selected)
    }

    @Test
    fun aV020UpgradeAfterTenHoursDecaysTheSixButNotLeatherface() {
        val loaded = SaveCodec.decode(v020Save(T0 - 10 * HOUR))
        val rig = Rig(loaded).started()
        assertEquals(Screen.Home, rig.ui.screen)
        assertEquals(45.0 + 40.0, rig.game.pets.getValue(PetId.MICHAEL).hunger, 1e-9)
        assertEquals(PetState(PetId.LEATHERFACE), rig.game.pets.getValue(PetId.LEATHERFACE))

        // Once he has joined, he decays like everyone else.
        rig.clock.now = T0 + 10 * HOUR
        rig.vm.tick()
        assertEquals(20.0 + 40.0, rig.game.pets.getValue(PetId.LEATHERFACE).hunger, 1e-9)
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
        // The UI derives the candidates from game.selected: everyone else, so six of seven pets.
        val opponents = PetId.entries.filter { it != rig.game.selected }
        assertEquals(6, opponents.size)
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
        assertEquals(listOf("play"), rig.ui.sfx)

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
    fun trainAndFeedAreTheSameForADancer() {
        val rig = Rig(gameOf(selected = PetId.LEATHERFACE, pets = arrayOf(PetState(PetId.LEATHERFACE, hunger = 60.0)))).started()
        rig.vm.onCare(CareAction.FEED)
        assertEquals(Anim.REVIEW, rig.ui.petAnim!!.anim)
        rig.finishAllAnims()
        rig.vm.onCare(CareAction.TRAIN)
        assertEquals(Anim.ATTACK, rig.ui.petAnim!!.anim)
        rig.finishAnim()
        assertEquals(Anim.ATTACK, rig.ui.petAnim!!.anim)
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
        rig.vm.openSwitcher()
        rig.clock.now += 5 * HOUR
        rig.vm.onResume()
        assertEquals(Screen.Switcher(SwitcherMode.SELECT), rig.ui.screen)
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
