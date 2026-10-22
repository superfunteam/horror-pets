package team.superfun.horrorpets.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.applyFightResult
import team.superfun.horrorpets.fight.buildFighter
import team.superfun.horrorpets.fight.forfeit
import team.superfun.horrorpets.fight.other
import team.superfun.horrorpets.fight.cpuPlan
import team.superfun.horrorpets.fight.planCpu
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
import team.superfun.horrorpets.model.attackById
import team.superfun.horrorpets.model.pendingAttacks
import team.superfun.horrorpets.pvp.PvpException
import team.superfun.horrorpets.pvp.PvpMatch
import team.superfun.horrorpets.pvp.PvpRelay
import team.superfun.horrorpets.pvp.Role
import team.superfun.horrorpets.pvp.resolvePvpTurn

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
    private val pvpRelay: PvpRelay? = null,
    private val onHostCode: (String?) -> Unit = {},
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
            val home = s.copy(screen = Screen.Home).routeHome()
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
        val routed = s.copy(game = game).routeHome()
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
        _state.value = s.copy(game = game, stableOpen = false).routeHome()
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
                _state.value = s.copy(game = game, screen = Screen.Home).routeHome()
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

    /**
     * Back on the VS card: no fight exists yet, so it simply returns to the opponent switcher. In PvP that walks
     * out of the room, and the other player wins by forfeit.
     */
    fun onVersusBack() {
        val s = _state.value
        val versus = s.screen as? Screen.Versus ?: return
        if (versus.pvp) endMatch(leave = true)
        _state.value = s.copy(screen = Screen.Switcher(SwitcherMode.OPPONENT))
    }

    /** The VS card is done: the fight starts with the selected pet against the picked opponent (or the other player). */
    fun onVersusDone() {
        val s = _state.value
        val versus = s.screen as? Screen.Versus ?: return
        if (versus.pvp) {
            val m = match ?: return
            val fight = m.start()
            canonical = fight
            _state.value = s.copy(screen = Screen.Fight(m.view(fight), emptyList(), pvp = PvpView()))
            watchTurn()
            return
        }
        val player = s.game.selected?.let { s.game.pets[it] } ?: return
        // The CPU plans its first move (and its tell) before the player sees the menu.
        _state.value = s.copy(screen = Screen.Fight(planCpu(startFight(player, versus.opponent, rng), rng), emptyList()))
    }

    /**
     * Plays the player's move against the CPU's reply. The screen animates [Screen.Fight.lastEvents]
     * and then reports the turn's [PetAnimRequest] finished. A SPECIAL without a full meter is ignored, and so is a
     * POTION with none left or at full health.
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
        // A potion needs one left, and at full health it would be wasted.
        if (m == Move.POTION && (fight.playerPotions == 0 || fight.player.hp >= fight.player.maxHp)) return
        if (screen.pvp != null) {
            lockIn(s, screen, m)
            return
        }

        // The CPU plays the move it planned (and told, or feinted) before the player chose, then plans the
        // next turn's. Running ends the fight before the CPU acts, and the engine ignores the plan it is handed.
        val plan = if (m == Move.RUN) null else fight.cpuPlan ?: cpuPlan(fight, rng)
        val turn = resolveTurn(fight, m, plan?.move ?: Move.BLOCK, rng)
        _state.value = s.copy(
            screen = Screen.Fight(planCpu(turn.state, rng), turn.events, playerMove = m, cpuMove = plan?.move, cpuTell = plan?.tell),
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
        val results = Screen.Results(reward, won = won, ran = fight.playerRan, fight = fight)
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

    // ---- pvp ---------------------------------------------------------------------------------

    /** The PvP room this player is fighting in (or about to), from its side. */
    private var match: PvpMatch? = null

    /** The room this player opened and is waiting in for a challenger, until one joins. */
    private var hostedRoom: Pair<String, String>? = null

    /** The PvP fight as both apps keep it: the host as PLAYER (see [PvpMatch]). */
    private var canonical: FightState? = null

    /** The lobby's request or wait, or the fight's watch on the current turn: one at a time. */
    private var pvpJob: Job? = null

    /** Whether PvP is on offer: only with a relay to meet through. */
    val pvpAvailable: Boolean get() = pvpRelay != null

    /** PvP Battle, the last pick in the opponent switcher: on to the lobby. */
    fun onPvpPicked() {
        val s = _state.value
        val switcher = s.screen as? Screen.Switcher ?: return
        if (switcher.mode != SwitcherMode.OPPONENT || pvpRelay == null || s.game.selected == null) return
        _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Menu()))
    }

    /** Opens a room: once the relay answers, its code is shown (and offered to a phone tapped against this one). */
    fun onLobbyHost() {
        val relay = pvpRelay ?: return
        val s = _state.value
        val lobby = s.screen as? Screen.PvpLobby ?: return
        if (lobby.phase !is LobbyPhase.Menu) return
        val me = myFighter(s) ?: return
        _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Hosting()))
        pvpJob?.cancel()
        pvpJob = scope.launch {
            val hosted = try {
                relay.create(me)
            } catch (e: PvpException) {
                setLobby(LobbyPhase.Menu(problemOf(e)), onlyIf = { it is LobbyPhase.Hosting })
                return@launch
            }
            hostedRoom = hosted.room to hosted.token
            onHostCode(hosted.code)
            setLobby(LobbyPhase.Hosting(hosted.code), onlyIf = { it is LobbyPhase.Hosting })
            // Wait for a challenger. A dropped request or two is only a slower wait.
            while (true) {
                delay(LOBBY_POLL_MS)
                val room = try {
                    relay.room(hosted.room, hosted.token)
                } catch (e: PvpException) {
                    continue
                }
                val guest = room.guest ?: continue
                startMatch(PvpMatch(Role.HOST, hosted.room, hosted.token, hosted.seed, me, guest))
                return@launch
            }
        }
    }

    /** On to typing a friend's code. */
    fun onLobbyJoin() {
        val s = _state.value
        val lobby = s.screen as? Screen.PvpLobby ?: return
        if (lobby.phase !is LobbyPhase.Menu) return
        _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Joining()))
    }

    /** A digit of the code; the fourth one joins. */
    fun onLobbyDigit(digit: Int) {
        val s = _state.value
        val phase = (s.screen as? Screen.PvpLobby)?.phase as? LobbyPhase.Joining ?: return
        if (phase.busy || digit !in 0..9 || phase.digits.length >= CODE_LENGTH) return
        val digits = phase.digits + digit
        if (digits.length == CODE_LENGTH) join(digits) else _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Joining(digits)))
    }

    /** Takes back the last digit typed. */
    fun onLobbyErase() {
        val s = _state.value
        val phase = (s.screen as? Screen.PvpLobby)?.phase as? LobbyPhase.Joining ?: return
        if (phase.busy || phase.digits.isEmpty()) return
        _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Joining(phase.digits.dropLast(1))))
    }

    /** A code read from another phone by a tap (NFC): joins its room, from the lobby's menu or code entry. */
    fun onTapCode(code: String) {
        val phase = (_state.value.screen as? Screen.PvpLobby)?.phase ?: return
        if (code.length != CODE_LENGTH || !code.all(Char::isDigit)) return
        when (phase) {
            is LobbyPhase.Menu -> join(code)
            is LobbyPhase.Joining -> if (!phase.busy) join(code)
            is LobbyPhase.Hosting -> Unit
        }
    }

    /** Back in the lobby: from the menu to the opponent switcher, from hosting or joining back to the menu. */
    fun onLobbyBack() {
        val s = _state.value
        val lobby = s.screen as? Screen.PvpLobby ?: return
        pvpJob?.cancel()
        pvpJob = null
        when (lobby.phase) {
            is LobbyPhase.Menu -> _state.value = s.copy(screen = Screen.Switcher(SwitcherMode.OPPONENT))
            is LobbyPhase.Hosting -> {
                closeHostedRoom()
                _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Menu()))
            }
            is LobbyPhase.Joining -> _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Menu()))
        }
    }

    private fun join(code: String) {
        val relay = pvpRelay ?: return
        val s = _state.value
        if (s.screen !is Screen.PvpLobby) return
        val me = myFighter(s) ?: return
        _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Joining(code, busy = true)))
        pvpJob?.cancel()
        pvpJob = scope.launch {
            val joined = try {
                relay.join(code, me)
            } catch (e: PvpException) {
                setLobby(LobbyPhase.Joining("", problem = problemOf(e)), onlyIf = { it is LobbyPhase.Joining })
                return@launch
            }
            startMatch(PvpMatch(Role.GUEST, joined.room, joined.token, joined.seed, joined.host, me))
        }
    }

    /** Both players are in: on to the VS card. */
    private fun startMatch(m: PvpMatch) {
        val s = _state.value
        if ((s.screen as? Screen.PvpLobby) == null) return
        hostedRoom = null
        onHostCode(null)
        match = m
        val mine = if (m.role == Role.HOST) m.host.id else m.guest.id
        val theirs = if (m.role == Role.HOST) m.guest.id else m.host.id
        _state.value = s.copy(
            screen = Screen.Versus(theirs, pvp = true),
            voice = s.voice + listOf(mine.soundName(), "versus", theirs.soundName()),
        )
    }

    /** This player locks in [m] for the turn and waits for the other; a RUN forfeits on the spot. */
    private fun lockIn(s: UiState, screen: Screen.Fight, m: Move) {
        val relay = pvpRelay ?: return
        val pvp = screen.pvp ?: return
        val game = match ?: return
        val fight = canonical ?: return
        if (pvp.waiting) return
        if (m == Move.RUN) {
            // Locked in as a RUN too, so the other app (which may be resolving this turn) agrees it was a forfeit.
            scope.launch {
                runCatching { relay.lockIn(game.room, game.token, fight.turn, Move.RUN) }
                runCatching { relay.leave(game.room, game.token) }
            }
            pvpJob?.cancel()
            pvpJob = null
            endPvpFight(s, forfeit(fight, game.side), playerMove = Move.RUN, cpuMove = null)
            return
        }
        _state.value = s.copy(screen = screen.copy(pvp = pvp.copy(waiting = true)))
        scope.launch {
            repeat(LOCK_IN_TRIES) { attempt ->
                try {
                    relay.lockIn(game.room, game.token, fight.turn, m)
                    return@launch
                } catch (e: PvpException) {
                    delay(TURN_POLL_MS * (attempt + 1))
                }
            }
            lostConnection()
        }
    }

    /** Watches the current turn until both moves are in (or the other player leaves), then plays it. */
    private fun watchTurn() {
        val relay = pvpRelay ?: return
        val game = match ?: return
        val turn = canonical?.turn ?: return
        pvpJob?.cancel()
        pvpJob = scope.launch {
            var failures = 0
            while (true) {
                val view = try {
                    relay.turn(game.room, game.token, turn).also { failures = 0 }
                } catch (e: PvpException) {
                    if (++failures >= MAX_POLL_FAILURES) {
                        lostConnection()
                        return@launch
                    }
                    delay(TURN_POLL_MS)
                    continue
                }
                val host = view.host
                val guest = view.guest
                if (host != null && guest != null) {
                    playPvpTurn(host, guest)
                    return@launch
                }
                if (view.left == game.them) {
                    opponentLeft()
                    return@launch
                }
                val s = _state.value
                val screen = s.screen as? Screen.Fight
                val pvp = screen?.pvp
                if (screen != null && pvp != null && pvp.opponentIn != view.isIn(game.them)) {
                    _state.value = s.copy(screen = screen.copy(pvp = pvp.copy(opponentIn = view.isIn(game.them))))
                }
                delay(TURN_POLL_MS)
            }
        }
    }

    /** Both moves are in: the turn plays out, the same as on the other app. */
    private fun playPvpTurn(host: Move, guest: Move) {
        val game = match ?: return
        val fight = canonical ?: return
        val s = _state.value
        if (s.screen !is Screen.Fight || s.busy) return
        val turn = resolvePvpTurn(fight, host, guest, game.seed)
        canonical = turn.state
        val mine = if (game.role == Role.HOST) host else guest
        val theirs = if (game.role == Role.HOST) guest else host
        _state.value = s.copy(
            screen = Screen.Fight(game.view(turn.state), game.viewEvents(turn.events), playerMove = mine, cpuMove = theirs, pvp = PvpView()),
        ).beginAction(listOf(Anim.ATTACK), finish = ::finishPvpTurn)
    }

    /** The other player walked out: they forfeit, and this player wins. */
    private fun opponentLeft() {
        val game = match ?: return
        val fight = canonical ?: return
        val s = _state.value
        if (s.screen !is Screen.Fight || s.busy || fight.winner != null) return
        endPvpFight(s, forfeit(fight, game.side.other()), playerMove = null, cpuMove = Move.RUN)
    }

    /** Plays the forfeit that ended [fight] (canonical), then on to the results. */
    private fun endPvpFight(s: UiState, fight: FightState, playerMove: Move?, cpuMove: Move?) {
        val game = match ?: return
        canonical = fight
        _state.value = s.copy(
            screen = Screen.Fight(game.view(fight), emptyList(), playerMove = playerMove, cpuMove = cpuMove, pvp = PvpView()),
        ).beginAction(listOf(Anim.ATTACK), finish = ::finishPvpTurn)
    }

    /**
     * A PvP turn's animation is over: the next turn's watch starts, or the fight is paid like any other (but
     * unlocks no one: the other killer is someone else's) and the room is left.
     */
    private fun finishPvpTurn(s: UiState): UiState {
        val fight = (s.screen as? Screen.Fight)?.fight ?: return s
        val winner = fight.winner
        if (winner == null) {
            watchTurn()
            return s
        }
        val pet = s.game.pets[fight.player.id] ?: return s
        endMatch(leave = true)
        val reward = applyFightResult(pet, fight)
        val won = winner == Side.PLAYER
        return s.copy(
            game = s.game.withPet(reward.pet),
            screen = Screen.Results(reward, won = won, ran = fight.playerRan, fight = fight),
            sfx = s.sfx + if (reward.levelsGained > 0) listOf("levelup") else emptyList(),
            voice = s.voice + when {
                fight.playerRan -> emptyList()
                won -> listOf("you_win")
                else -> listOf("you_lose")
            },
        )
    }

    /** The relay stopped answering mid-fight: the fight is off, back to the lobby to say so. */
    private fun lostConnection() {
        val s = _state.value
        if (s.busy) return
        endMatch(leave = true)
        if (s.screen is Screen.Fight || s.screen is Screen.Versus) {
            _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Menu(PvpProblem.LOST)))
        }
    }

    /** Forgets the room (walking out of it first if [leave]) and stops watching it. */
    private fun endMatch(leave: Boolean) {
        val game = match
        val relay = pvpRelay
        if (leave && game != null && relay != null) scope.launch { runCatching { relay.leave(game.room, game.token) } }
        pvpJob?.cancel()
        pvpJob = null
        match = null
        canonical = null
        closeHostedRoom()
    }

    /** Takes down the room this player opened, if nobody has joined it yet. */
    private fun closeHostedRoom() {
        val (room, token) = hostedRoom ?: return
        hostedRoom = null
        onHostCode(null)
        val relay = pvpRelay ?: return
        scope.launch { runCatching { relay.leave(room, token) } }
    }

    private fun setLobby(phase: LobbyPhase, onlyIf: (LobbyPhase) -> Boolean) {
        val s = _state.value
        val lobby = s.screen as? Screen.PvpLobby ?: return
        if (onlyIf(lobby.phase)) _state.value = s.copy(screen = Screen.PvpLobby(phase))
    }

    private fun myFighter(s: UiState): team.superfun.horrorpets.fight.Fighter? = s.game.selected?.let { s.game.pets[it] }?.let(::buildFighter)

    private fun problemOf(e: PvpException): PvpProblem = when (e) {
        is PvpException.NoRoom -> PvpProblem.NO_ROOM
        is PvpException.Full -> PvpProblem.FULL
        is PvpException.Version -> PvpProblem.VERSION
        is PvpException.Offline -> PvpProblem.OFFLINE
    }

    override fun onCleared() {
        // The view model's own scope is gone by now: walk out of the room on a scope of its own.
        val game = match
        val relay = pvpRelay
        if (game != null && relay != null) CoroutineScope(Dispatchers.IO).launch { runCatching { relay.leave(game.room, game.token) } }
        hostedRoom?.let { (room, token) -> if (relay != null) CoroutineScope(Dispatchers.IO).launch { runCatching { relay.leave(room, token) } } }
        super.onCleared()
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
        val done = action.finish(s).copy(busy = false, petAnim = null).routeHome()
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

    private fun UiState.goHome(): UiState = copy(screen = Screen.Home, refuse = null, stableOpen = false).routeHome()

    // ---- attacks: learning and equipping -------------------------------------------------------

    /** Attacks the player put off with Back on the learn screen: not offered again until the next launch. */
    private val putOff = mutableSetOf<String>()

    /** The learn screen's choice: [learn] swaps the new attack into its slot, else the old one stays. The pet knows it either way. */
    fun onLearn(learn: Boolean) {
        val s = _state.value
        val screen = s.screen as? Screen.Learn ?: return
        val pet = s.game.pets[screen.pet] ?: return
        val attack = attackById(screen.attack) ?: return
        val updated = pet.copy(
            known = pet.known + attack.id,
            equipped = if (learn) pet.equipped + (attack.slot to attack.id) else pet.equipped,
        )
        val game = s.game.withPet(updated)
        _state.value = s.copy(game = game, sfx = s.sfx + if (learn) "levelup" else "ui_select").goHome()
        persist(game)
    }

    /** Back on the learn screen: the choice waits until the next launch. */
    fun onLearnLater() {
        val s = _state.value
        val screen = s.screen as? Screen.Learn ?: return
        putOff += screen.attack
        _state.value = s.goHome()
    }

    /** MOVES on Home: the selected pet's attacks. */
    fun onMovesPressed() {
        val s = _state.value
        s.homePet() ?: return
        _state.value = s.copy(screen = Screen.Moves, refuse = null)
    }

    /**
     * Equips attack [id] in its slot, if it is the selected pet's and unlocked by its level (choosing one it was never
     * offered counts as learning it). Anything else is refused.
     */
    fun onEquip(id: String) {
        val s = _state.value
        if (s.screen != Screen.Moves) return
        val pet = s.game.selected?.let { s.game.pets[it] } ?: return
        val attack = attackById(id)
        if (attack == null || attack.pet != pet.id || attack.level > pet.level) {
            _state.value = s.copy(sfx = s.sfx + "refuse")
            return
        }
        if (pet.equipped[attack.slot] == id || (attack.tier == 0 && pet.equipped[attack.slot] == null)) return
        val updated = pet.copy(
            known = if (attack.tier > 0) pet.known + id else pet.known,
            equipped = if (attack.tier == 0) pet.equipped - attack.slot else pet.equipped + (attack.slot to id),
        )
        val game = s.game.withPet(updated)
        _state.value = s.copy(game = game, sfx = s.sfx + "ui_select")
        persist(game)
    }

    /** Back from MOVES. */
    fun onMovesBack() {
        val s = _state.value
        if (s.screen != Screen.Moves) return
        _state.value = s.goHome()
    }

    /**
     * A pet whose fake death has not been shown yet is shown it the moment Home would take over.
     * State-based on purpose: it holds whether the flag was set by a load, a tick, a resume or a switch.
     */
    /**
     * Home is not always where the player lands: the selected pet's due fake death comes first, then any attack it
     * has unlocked but not been offered yet (one learn screen at a time; see [pendingAttacks]).
     */
    private fun UiState.routeHome(): UiState {
        if (screen != Screen.Home || busy) return this
        val id = game.selected ?: return this
        val pet = game.pets[id] ?: return this
        if (pet.fakeDeathPending) return copy(screen = Screen.FakeDeath(id), refuse = null)
        if (stableOpen) return this
        val next = pendingAttacks(pet).firstOrNull { it.id !in putOff } ?: return this
        return copy(screen = Screen.Learn(id, next.id), refuse = null, sfx = sfx + "unlock")
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

/** A PvP room's code is this many digits. */
internal const val CODE_LENGTH = 4

/** How often a host asks whether a challenger has joined, and a fighter whether the other has locked in. */
private const val LOBBY_POLL_MS = 1_500L
private const val TURN_POLL_MS = 800L

/** A fight is called off after this many failed asks in a row (about half a minute without the relay). */
private const val MAX_POLL_FAILURES = 25

/** Tries at locking a move in before the fight is called off. */
private const val LOCK_IN_TRIES = 5

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
