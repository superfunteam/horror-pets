package team.superfun.horrorpets.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.data.Clock
import team.superfun.horrorpets.data.SaveStore
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
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
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

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
 * @param scope where loading and saving run; null uses `viewModelScope`.
 * @param feedAnim the animation a pet plays while eating (its manifest's `feed` entry).
 * @param onError receives every failed save or load. The game keeps running from memory.
 */
class GameViewModel(
    private val store: SaveStore,
    private val clock: Clock,
    private val rng: Random,
    scope: CoroutineScope?,
    private val feedAnim: (PetId) -> Anim,
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var started = false
    private var nextToken = 1L

    /** The action whose animations are playing, if any. [UiState.petAnim] is its current step. */
    private var running: Running? = null

    private val saveLock = Mutex()

    /** What is left of the running action: the animations still to play, and what happens after the last. */
    private class Running(val remaining: ArrayDeque<Anim>, val finish: (UiState) -> UiState)

    // ---- lifecycle ---------------------------------------------------------------------------

    /** Loads the save, applies the time since it was last seen, and picks the first screen. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val loaded = try {
                store.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                GameState()
            }
            val (game, away) = applyElapsed(loaded, clock.nowMs())
            val base = _state.value.copy(game = game)
            _state.value = if (game.selected == null) {
                base.copy(screen = Screen.Switcher(SwitcherMode.ONBOARD), voice = base.voice + "choose_your_killer")
            } else {
                val routed = base.copy(screen = Screen.Home).routeFakeDeath()
                if (routed.screen == Screen.Home) routed.copy(toast = awayToast(away)) else routed
            }
            persist(game)
        }
    }

    /** The app came back to the foreground. */
    fun onResume() = advanceClock()

    /** Call about once a minute while the app is foregrounded, so a TV left on keeps getting hungrier. */
    fun tick() = advanceClock()

    private fun advanceClock() {
        val s = _state.value
        if (s.screen == Screen.Loading) return // the save has not been read yet: never overwrite it
        val (game, _) = applyElapsed(s.game, clock.nowMs())
        if (game == s.game) return
        _state.value = s.copy(game = game).routeFakeDeath()
        persist(game)
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
                    CareAction.FEED -> listOf(feedAnim(pet.id))
                    CareAction.PLAY -> listOf(Anim.JUMP, Anim.WAVE)
                    CareAction.TRAIN -> listOf(Anim.ATTACK, Anim.ATTACK)
                }
                val sfx = listOf(
                    when (a) {
                        CareAction.FEED -> "chomp"
                        CareAction.PLAY -> "play"
                        CareAction.TRAIN -> "train"
                    },
                ) + if (result.levelsGained > 0) listOf("levelup") else emptyList()
                _state.value = s.copy(game = game, refuse = null, sfx = s.sfx + sfx).beginAction(anims)
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

    // ---- switcher, versus, fake death --------------------------------------------------------

    fun openSwitcher() {
        val s = _state.value
        if (s.homePet() == null) return
        _state.value = s.copy(screen = Screen.Switcher(SwitcherMode.SELECT), refuse = null)
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

            SwitcherMode.SELECT, SwitcherMode.ONBOARD -> {
                val game = s.game.copy(selected = p)
                _state.value = s.copy(game = game, screen = Screen.Home).routeFakeDeath()
                if (game != s.game) persist(game)
            }
        }
    }

    /** Back out of the switcher. There is nothing to go back to while onboarding. */
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
     */
    fun onMove(m: Move) {
        val s = _state.value
        val screen = s.screen as? Screen.Fight ?: return
        val fight = screen.fight
        if (s.busy || fight.winner != null) return
        if (m == Move.SPECIAL && fight.player.meter < FightTuning.METER_MAX) return

        // Running ends the fight before the CPU acts; the engine ignores the CPU's move then.
        val cpuMove = if (m == Move.RUN) Move.BLOCK else cpuPick(fight, rng)
        val turn = resolveTurn(fight, m, cpuMove, rng)
        val sounds = fightSounds(turn.events, turn.state)
        _state.value = s.copy(
            screen = Screen.Fight(turn.state, turn.events),
            sfx = s.sfx + sounds.sfx,
            voice = s.voice + sounds.voice,
        ).beginAction(listOf(Anim.ATTACK), ::finishTurn)
    }

    /** The turn's animation is over. A finished fight turns into its results and the pet is paid. */
    private fun finishTurn(s: UiState): UiState {
        val fight = (s.screen as? Screen.Fight)?.fight ?: return s
        val winner = fight.winner ?: return s
        val pet = s.game.pets[fight.player.id] ?: return s
        val reward = applyFightResult(pet, fight)
        val won = winner == Side.PLAYER
        val voice = when {
            fight.playerRan -> emptyList()
            won -> listOf("you_win")
            else -> listOf("you_lose")
        }
        return s.copy(
            game = s.game.withPet(reward.pet),
            screen = Screen.Results(reward, won = won, ran = fight.playerRan),
            sfx = s.sfx + if (reward.levelsGained > 0) listOf("levelup") else emptyList(),
            voice = s.voice + voice,
        )
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

    /** The selected pet when Home is taking input (not busy), else null. */
    private fun UiState.homePet(): PetState? {
        if (screen != Screen.Home || busy) return null
        return game.selected?.let { game.pets[it] }
    }

    /** Starts an action: [anims] play one after another, then [finish] produces the final state. */
    private fun UiState.beginAction(anims: List<Anim>, finish: (UiState) -> UiState = { it }): UiState {
        running = Running(ArrayDeque(anims.drop(1)), finish)
        return copy(busy = true, petAnim = PetAnimRequest(anims.first(), nextToken++))
    }

    private fun refuse(s: UiState, reason: RefuseReason) {
        _state.value = s.copy(refuse = reason, sfx = s.sfx + "refuse").beginAction(listOf(Anim.FAIL))
    }

    private fun UiState.goHome(): UiState = copy(screen = Screen.Home, refuse = null).routeFakeDeath()

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

    /** Saves [game] after the saves already queued. A failed save is reported and the game carries on. */
    private fun persist(game: GameState) {
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

/** A pet's name as the announcer audio files spell it ("jason", "freddy", ...). */
private fun PetId.soundName(): String = name.lowercase()

/**
 * The welcome-back toast: `"Away 2d 4h · Hunger +80"`, with the days left out when there are none,
 * a real minus sign (U+2212) for losses, and just the time when nothing notable changed. Null when
 * the pet was gone for less than [Tuning.TOAST_MIN_AWAY_MS].
 */
internal fun awayToast(away: AwaySummary): String? {
    if (away.elapsedMs < Tuning.TOAST_MIN_AWAY_MS) return null
    val days = away.elapsedMs / Tuning.DAY_MS
    val hours = away.elapsedMs % Tuning.DAY_MS / Tuning.HOUR_MS
    val time = if (days > 0) "${days}d ${hours}h" else "${hours}h"
    val stat = away.biggestStat ?: return "Away $time"
    val sign = if (away.biggestDelta < 0) "−" else "+"
    return "Away $time · $stat $sign${abs(away.biggestDelta)}"
}

/** The sound queues' additions for one turn. */
internal data class FightSounds(val sfx: List<String>, val voice: List<String>)

/**
 * Maps a turn's [events] to sound names, in event order. A hit is `hit`, `heavy_hit` or
 * `special_<attacker's pet>`; a crit is `crit` instead (and the announcer says `critical`, once per
 * turn even when both sides crit); a miss is `miss`, a block `block`, and a KO has the announcer
 * say `ko`. [fight] is the state after the turn, used to tell which pet a side is.
 */
internal fun fightSounds(events: List<FightEvent>, fight: FightState): FightSounds {
    val sfx = mutableListOf<String>()
    val voice = mutableListOf<String>()
    for (event in events) {
        when (event) {
            is FightEvent.Hit -> if (event.crit) {
                sfx += "crit"
                if ("critical" !in voice) voice += "critical"
            } else {
                sfx += when (event.move) {
                    Move.HEAVY -> "heavy_hit"
                    Move.SPECIAL -> {
                        val attacker = if (event.attacker == Side.PLAYER) fight.player else fight.cpu
                        "special_${attacker.id.soundName()}"
                    }
                    else -> "hit"
                }
            }

            is FightEvent.Miss -> sfx += "miss"
            is FightEvent.Blocked -> sfx += "block"
            is FightEvent.Ko -> voice += "ko"
            is FightEvent.Interrupted, is FightEvent.SpecialReady -> Unit
        }
    }
    return FightSounds(sfx, voice)
}
