package team.superfun.horrorpets.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.data.Clock
import team.superfun.horrorpets.data.SaveStore
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.applyFightResult
import team.superfun.horrorpets.fight.cpuPick
import team.superfun.horrorpets.fight.resolveTurn
import team.superfun.horrorpets.fight.startFight
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.CareResult
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.applyElapsed
import team.superfun.horrorpets.game.canFight
import team.superfun.horrorpets.game.perform
import team.superfun.horrorpets.model.AwaySummary
import team.superfun.horrorpets.model.FEED_MUNCHES
import team.superfun.horrorpets.model.Food
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.PlayGag
import team.superfun.horrorpets.model.nextFood
import team.superfun.horrorpets.model.nextPlayGag

/**
 * The state machine behind every screen. The screens render [state] and call the `on...` functions;
 * all rules (what a press does, what is ignored, what is saved) live here.
 *
 * Threading: every function must be called on one thread, the main thread in the app. The state
 * machine itself is not synchronized. Saves run on [scope] and are queued, so they reach the store
 * in the order the state changed.
 *
 * Animation protocol: an action emits one [PetAnimRequest] at a time in [UiState.petAnim] and stays
 * [UiState.busy] until the UI has called [onAnimFinished] for the last one. A call with a token that
 * is not the current request's is ignored, so a late or repeated callback can never skip a step or
 * end the next action. While busy, care, walk, fight and move presses are ignored, which makes a
 * held or mashed remote key apply an action exactly once. Looping animations (the run strips)
 * never end on their own, so the UI must report them finished after one pass.
 *
 * Loading: a load that fails is tried once more. If that fails too, the game starts fresh in memory
 * and nothing is saved for the rest of the session, so the real save is still there next launch.
 *
 * @param scope where loading and saving run; null uses `viewModelScope`.
 * @param feedAnim the animation a pet plays while eating (its manifest's `feed` entry). FEED plays it
 *   [FEED_MUNCHES] times, the first with the dropped [Food] (see [PetAnimRequest.food]).
 * @param canDance whether a pet's sprites ship the optional DANCE (its manifest has `dance`): such a pet
 *   plays it for PLAY instead of JUMP then WAVE.
 * @param onError receives every failed save, and a load that failed twice. The game keeps running from memory.
 * @param clockFloorMs the earliest believable wall-clock time (see [applyElapsed]); the app passes its
 *   first-install time, so a stale boot clock can neither decay the pets nor stamp the save.
 * @param pickRng picks the food a feed drops and the gag a play does. Kept apart from [rng], so caring for a
 *   pet never shifts a fight's rolls.
 */
class GameViewModel(
    private val store: SaveStore,
    private val clock: Clock,
    private val rng: Random,
    scope: CoroutineScope?,
    private val feedAnim: (PetId) -> Anim,
    private val canDance: (PetId) -> Boolean = { false },
    private val onError: (Throwable) -> Unit = {},
    private val clockFloorMs: Long = Tuning.MIN_VALID_EPOCH_MS,
    private val pickRng: Random = Random.Default,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var started = false
    private var nextToken = 1L
    private var lastFood: Food? = null
    private var lastGag: PlayGag? = null

    /** What happened while the app was closed, read at launch and toasted once Start takes the player Home. */
    private var pendingAway: AwaySummary? = null

    /** The action whose animations are playing, if any. [UiState.petAnim] is its current step. */
    private var running: Running? = null

    private val saveLock = Mutex()

    /** Set when the save could not be read: this session must never overwrite it. */
    private var savingDisabled = false

    /** What is left of the running action: the animations still to play, and what happens after the last. */
    private class Running(val remaining: ArrayDeque<Anim>, val finish: (UiState) -> UiState)

    // ---- lifecycle ---------------------------------------------------------------------------

    /** Loads the save, applies the time since it was last seen, and shows the title screen. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val loaded = loadOrNull() ?: GameState().also { savingDisabled = true }
            val (game, away) = applyElapsed(loaded, clock.nowMs(), clockFloorMs)
            pendingAway = away
            _state.value = _state.value.copy(game = game, screen = Screen.Title)
            persist(game)
        }
    }

    /**
     * Start pressed on the title screen: a new game goes to the starter pick, anyone else Home (to their pet's fake
     * death first, if it is due), with the welcome back for the time they were away.
     */
    fun onStart() {
        val s = _state.value
        if (s.screen != Screen.Title) return
        val away = pendingAway
        pendingAway = null
        _state.value = if (s.game.selected == null) {
            s.copy(screen = Screen.Switcher(SwitcherMode.ONBOARD), voice = s.voice + "choose_your_killer")
        } else {
            val home = s.copy(screen = Screen.Home).routeFakeDeath()
            if (away == null) home else home.withAwayToast(away)
        }
    }

    /** The save, or null when it could not be read twice in a row (the second failure is reported). */
    private suspend fun loadOrNull(): GameState? {
        val firstFailure = try {
            return store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        delay(LOAD_RETRY_DELAY_MS) // a moment for a transient I/O problem to clear
        return try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.addSuppressed(firstFailure)
            onError(e)
            null
        }
    }

    /**
     * The app came back to the foreground. Usually the launcher resumes the Activity that is still
     * running (no [start]), so this is where a returning player gets the welcome-back toast.
     */
    fun onResume() = advanceClock(welcomeBack = true)

    /** Call about once a minute while the app is foregrounded, so a TV left on keeps getting hungrier. Never toasts. */
    fun tick() = advanceClock(welcomeBack = false)

    private fun advanceClock(welcomeBack: Boolean) {
        val s = _state.value
        if (s.screen == Screen.Loading) return // the save has not been read yet: never overwrite it
        val (game, away) = applyElapsed(s.game, clock.nowMs(), clockFloorMs)
        if (game == s.game) return
        val routed = s.copy(game = game).routeFakeDeath()
        _state.value = if (welcomeBack) routed.withAwayToast(away) else routed
        persist(game)
    }

    /** Shows the welcome-back toast for [away] when Home is on screen and the absence is worth one (see [awayToast]). */
    private fun UiState.withAwayToast(away: AwaySummary): UiState {
        if (screen != Screen.Home) return this
        val toast = awayToast(away) ?: return this
        return copy(toast = toast)
    }

    // ---- home: care and walking --------------------------------------------------------------

    fun onCare(a: CareAction) {
        val s = _state.value
        val pet = s.homePet() ?: return
        when (val result = perform(a, pet)) {
            is CareResult.Refused -> refuse(s, result.reason)
            is CareResult.Done -> {
                val game = s.game.withPet(result.pet)
                val anims = when (a) {
                    CareAction.FEED -> List(FEED_MUNCHES) { feedAnim(pet.id) }
                    CareAction.PLAY -> if (canDance(pet.id)) listOf(Anim.DANCE) else listOf(Anim.JUMP, Anim.WAVE)
                    CareAction.TRAIN -> listOf(Anim.ATTACK, Anim.ATTACK)
                }
                val sfx = listOf(
                    when (a) {
                        CareAction.FEED -> "food_drop"
                        CareAction.PLAY -> "hand_whoosh"
                        CareAction.TRAIN -> "train"
                    },
                ) + if (result.levelsGained > 0) listOf("levelup") else emptyList()
                val food = if (a == CareAction.FEED) nextFood(lastFood, pickRng).also { lastFood = it } else null
                val gag = if (a == CareAction.PLAY) nextPlayGag(lastGag, pickRng).also { lastGag = it } else null
                _state.value = s.copy(game = game, refuse = null, sfx = s.sfx + sfx).beginAction(anims, food, gag)
                persist(game)
            }
        }
    }

    /** Walks to the neighbouring scene: -1 is left, +1 is right, wrapping around. The scene changes after the run. */
    fun onWalk(dir: Int) {
        if (dir != -1 && dir != 1) return
        val s = _state.value
        if (s.homePet() == null) return
        val anim = if (dir > 0) Anim.RUN_RIGHT else Anim.RUN_LEFT
        _state.value = s.copy(refuse = null).beginAction(listOf(anim)) { done ->
            val id = done.game.selected
            val pet = id?.let { done.game.pets[it] }
            if (pet == null) {
                done
            } else {
                val scene = (pet.sceneIndex + dir).mod(Tuning.SCENE_COUNT)
                done.copy(game = done.game.withPet(pet.copy(sceneIndex = scene)))
            }
        }
    }

    // ---- the stable, the carousel, versus, fake death -----------------------------------------

    /** Opens the stable over Home, when there is someone else in it to go see. */
    fun openStable() {
        val s = _state.value
        if (s.homePet() == null || s.game.pets.size < 2) return
        _state.value = s.copy(stableOpen = true, refuse = null)
    }

    fun closeStable() {
        val s = _state.value
        if (!s.stableOpen) return
        _state.value = s.copy(stableOpen = false)
    }

    /** Goes to see [p], a killer in the stable (a locked one is ignored), and closes the stable. */
    fun onStablePick(p: PetId) {
        val s = _state.value
        if (!s.stableOpen || !s.game.isUnlocked(p)) return
        val game = s.game.copy(selected = p)
        _state.value = s.copy(game = game, stableOpen = false).routeFakeDeath()
        if (game != s.game) persist(game)
    }

    /** Opens the opponent switcher, or refuses when the selected pet is too hungry to fight. */
    fun onFightPressed() {
        val s = _state.value
        val pet = s.homePet() ?: return
        if (!canFight(pet)) {
            refuse(s, RefuseReason.TOO_HUNGRY)
            return
        }
        _state.value = s.copy(screen = Screen.Switcher(SwitcherMode.OPPONENT), refuse = null)
    }

    fun onSwitcherPick(p: PetId) {
        val s = _state.value
        val switcher = s.screen as? Screen.Switcher ?: return
        when (switcher.mode) {
            SwitcherMode.OPPONENT -> {
                val player = s.game.selected ?: return
                if (p == player) return
                _state.value = s.copy(
                    screen = Screen.Versus(p),
                    voice = s.voice + listOf(player.soundName(), "versus", p.soundName()),
                )
            }

            SwitcherMode.ONBOARD -> {
                // The starter is the whole stable to begin with: the others are unlocked by beating them.
                val game = s.game.copy(pets = s.game.pets + (p to (s.game.pets[p] ?: PetState(p))), selected = p)
                _state.value = s.copy(game = game, screen = Screen.Home).routeFakeDeath()
                persist(game)
            }
        }
    }

    /** Back out of the opponent pick. There is nothing to go back to while picking the starter. */
    fun onSwitcherBack() {
        val s = _state.value
        val switcher = s.screen as? Screen.Switcher ?: return
        if (switcher.mode == SwitcherMode.ONBOARD) return
        _state.value = s.goHome()
    }

    /** The reveal is over: this pet is not dead any more. */
    fun onFakeDeathDone() {
        val s = _state.value
        val fakeDeath = s.screen as? Screen.FakeDeath ?: return
        val pet = s.game.pets[fakeDeath.pet] ?: return
        val game = s.game.withPet(pet.copy(fakeDeathPending = false))
        _state.value = s.copy(game = game).goHome()
        persist(game)
    }

    // ---- fight -------------------------------------------------------------------------------

    /** Back on the VS card: no fight exists yet, so it simply returns to the opponent switcher. */
    fun onVersusBack() {
        val s = _state.value
        if (s.screen !is Screen.Versus) return
        _state.value = s.copy(screen = Screen.Switcher(SwitcherMode.OPPONENT))
    }

    /** The VS card is done: the fight starts with the selected pet against the picked opponent. */
    fun onVersusDone() {
        val s = _state.value
        val versus = s.screen as? Screen.Versus ?: return
        val player = s.game.selected?.let { s.game.pets[it] } ?: return
        _state.value = s.copy(screen = Screen.Fight(startFight(player, versus.opponent, rng), emptyList()))
    }

    /**
     * Plays the player's move against the CPU's reply. The screen animates [Screen.Fight.lastEvents]
     * and then reports the turn's [PetAnimRequest] finished. A SPECIAL without a full meter is ignored.
     *
     * A turn queues no sounds: the fight screen plays each event's sound (hits, crits, misses, blocks,
     * the KO call) itself, at the moment it shows that event.
     */
    fun onMove(m: Move) {
        val s = _state.value
        val screen = s.screen as? Screen.Fight ?: return
        val fight = screen.fight
        if (s.busy || fight.winner != null) return
        if (m == Move.SPECIAL && fight.player.meter < FightTuning.METER_MAX) return

        // Running ends the fight before the CPU acts: it picks nothing, and the engine ignores the
        // placeholder it is handed.
        val cpuPicked = if (m == Move.RUN) null else cpuPick(fight, rng)
        val turn = resolveTurn(fight, m, cpuPicked ?: Move.BLOCK, rng)
        _state.value = s.copy(
            screen = Screen.Fight(turn.state, turn.events, playerMove = m, cpuMove = cpuPicked),
        ).beginAction(listOf(Anim.ATTACK), finish = ::finishTurn)
    }

    /**
     * The turn's animation is over. A finished fight turns into its results and the pet is paid; beating a killer
     * the player did not have unlocks it (it joins the stable at its defaults) and shows that before the results.
     *
     * The stable's clock is brought up to now first (as the minute tick would), so the newcomer joins from this
     * moment and decays like the rest through the next absence, even one that starts before the next tick. Only
     * under a clock not yet trusted (no time can be applied) does it keep the fresh-join mark and wait for it.
     */
    private fun finishTurn(s: UiState): UiState {
        val fight = (s.screen as? Screen.Fight)?.fight ?: return s
        val winner = fight.winner ?: return s
        val pet = s.game.pets[fight.player.id] ?: return s
        val reward = applyFightResult(pet, fight)
        val won = winner == Side.PLAYER
        val results = Screen.Results(reward, won = won, ran = fight.playerRan)
        val unlock = fight.cpu.id.takeIf { won && !fight.playerRan && !s.game.isUnlocked(it) }
        val voice = when {
            fight.playerRan -> emptyList()
            won -> listOf("you_win")
            else -> listOf("you_lose")
        } + if (unlock != null) listOf("unlocked") else emptyList()
        val game = if (unlock == null) {
            s.game.withPet(reward.pet)
        } else {
            val now = clock.nowMs()
            val current = applyElapsed(s.game.withPet(reward.pet), now, clockFloorMs).first
            current.withPet(PetState(unlock, freshJoin = now < clockFloorMs))
        }
        return s.copy(
            game = game,
            screen = if (unlock == null) results else Screen.Unlocked(unlock, results),
            sfx = s.sfx + (if (reward.levelsGained > 0) listOf("levelup") else emptyList()) + (if (unlock != null) listOf("unlock") else emptyList()),
            voice = s.voice + voice,
        )
    }

    /** The unlock has been shown: on to the fight's results. */
    fun onUnlockedDone() {
        val s = _state.value
        val unlocked = s.screen as? Screen.Unlocked ?: return
        _state.value = s.copy(screen = unlocked.results)
    }

    fun onResultsDone() {
        val s = _state.value
        if (s.screen !is Screen.Results) return
        _state.value = s.goHome()
    }

    // ---- animation, toast, sounds ------------------------------------------------------------

    /** The UI finished playing the request with [token]. Anything but the current request's token is ignored. */
    fun onAnimFinished(token: Long) {
        val s = _state.value
        val current = s.petAnim ?: return
        val action = running ?: return
        if (current.token != token) return

        val next = action.remaining.removeFirstOrNull()
        if (next != null) {
            _state.value = s.copy(petAnim = PetAnimRequest(next, nextToken++))
            return
        }
        running = null
        val done = action.finish(s).copy(busy = false, petAnim = null).routeFakeDeath()
        _state.value = done
        if (done.game != s.game) persist(done.game)
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }

    fun consumeSounds() {
        _state.value = _state.value.copy(sfx = emptyList(), voice = emptyList())
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The selected pet when Home is taking input (not busy, the stable not open), else null. */
    private fun UiState.homePet(): PetState? {
        if (screen != Screen.Home || busy || stableOpen) return null
        return game.selected?.let { game.pets[it] }
    }

    /** Starts an action: [anims] play one after another, then [finish] produces the final state. */
    private fun UiState.beginAction(
        anims: List<Anim>,
        food: Food? = null,
        gag: PlayGag? = null,
        finish: (UiState) -> UiState = { it },
    ): UiState {
        running = Running(ArrayDeque(anims.drop(1)), finish)
        return copy(busy = true, petAnim = PetAnimRequest(anims.first(), nextToken++, food, gag))
    }

    private fun refuse(s: UiState, reason: RefuseReason) {
        _state.value = s.copy(refuse = reason, sfx = s.sfx + "refuse").beginAction(listOf(Anim.FAIL))
    }

    private fun UiState.goHome(): UiState = copy(screen = Screen.Home, refuse = null, stableOpen = false).routeFakeDeath()

    /**
     * A pet whose fake death has not been shown yet is shown it the moment Home would take over.
     * State-based on purpose: it holds whether the flag was set by a load, a tick, a resume or a switch.
     */
    private fun UiState.routeFakeDeath(): UiState {
        if (screen != Screen.Home || busy) return this
        val id = game.selected ?: return this
        if (game.pets[id]?.fakeDeathPending != true) return this
        return copy(screen = Screen.FakeDeath(id), refuse = null)
    }

    private fun GameState.withPet(p: PetState): GameState = copy(pets = pets + (p.id to p))

    /**
     * Saves [game] after the saves already queued. A failed save is reported and the game carries on.
     * Does nothing in a session whose save could not be loaded.
     */
    private fun persist(game: GameState) {
        if (savingDisabled) return
        scope.launch {
            saveLock.withLock {
                try {
                    store.save(game)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }
}

/** The pause before the one retry of a failed load. */
private const val LOAD_RETRY_DELAY_MS = 250L

/** A pet's name as the announcer audio files spell it ("jason", "freddy", ...). */
private fun PetId.soundName(): String = name.lowercase()

/**
 * The welcome-back toast: `"Away 2d 4h · Hunger +80"`, with the days left out when there are none,
 * minutes instead of `0h` for an absence under an hour (`"Away 25m · Hunger +2"`), a real minus sign
 * (U+2212) for losses, and just the time when nothing notable changed. Null when the pet was gone
 * for less than [Tuning.TOAST_MIN_AWAY_MS].
 */
internal fun awayToast(away: AwaySummary): String? {
    if (away.elapsedMs < Tuning.TOAST_MIN_AWAY_MS) return null
    val days = away.elapsedMs / Tuning.DAY_MS
    val hours = away.elapsedMs % Tuning.DAY_MS / Tuning.HOUR_MS
    val time = when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h"
        else -> "${away.elapsedMs / Tuning.MINUTE_MS}m"
    }
    val stat = away.biggestStat ?: return "Away $time"
    val sign = if (away.biggestDelta < 0) "\u2212" else "+" // a real minus sign, not a hyphen
    return "Away $time \u00b7 $stat $sign${abs(away.biggestDelta)}"
}
