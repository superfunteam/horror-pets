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
import team.superfun.horrorpets.fight.cpuPick
import team.superfun.horrorpets.fight.forfeit
import team.superfun.horrorpets.fight.other
import team.superfun.horrorpets.fight.resolveTurn
import team.superfun.horrorpets.fight.startFight
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.CareResult
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.applyElapsed
import team.superfun.horrorpets.game.canFight
import team.superfun.horrorpets.game.canTrain
import team.superfun.horrorpets.game.pantry.CookResult
import team.superfun.horrorpets.game.pantry.canEat
import team.superfun.horrorpets.game.pantry.cook
import team.superfun.horrorpets.game.pantry.feed
import team.superfun.horrorpets.game.pantry.topUp
import team.superfun.horrorpets.game.perform
import team.superfun.horrorpets.game.training.applyTraining
import team.superfun.horrorpets.game.training.eligibleGames
import team.superfun.horrorpets.game.training.mustTrainBeforeFighting
import team.superfun.horrorpets.game.training.pickMate
import team.superfun.horrorpets.game.training.pickTrainingGame
import team.superfun.horrorpets.model.AwaySummary
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.PlayGag
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.model.attackById
import team.superfun.horrorpets.model.challengerDue
import team.superfun.horrorpets.model.nextPlayGag
import team.superfun.horrorpets.model.pantryItems
import team.superfun.horrorpets.model.pendingAttacks
import team.superfun.horrorpets.pvp.PvpException
import team.superfun.horrorpets.pvp.PvpMatch
import team.superfun.horrorpets.pvp.PvpRelay
import team.superfun.horrorpets.pvp.Role
import team.superfun.horrorpets.pvp.resolvePvpTurn
import team.superfun.horrorpets.ui.fight.nearlyDone

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
 * @param canDance whether a pet's sprites ship the optional DANCE (its manifest has `dance`): such a pet
 *   plays it for PLAY instead of JUMP then WAVE.
 * @param onError receives every failed save, and a load that failed twice. The game keeps running from memory.
 * @param clockFloorMs the earliest believable wall-clock time (see [applyElapsed]); the app passes its
 *   first-install time, so a stale boot clock can neither decay the pets nor stamp the save.
 * @param pickRng picks the gag a play does, the game (and its mate) a train opens and what a visit to the pantry tops
 *   the shelf up with. Kept apart from [rng], so caring for a pet never shifts a fight's rolls.
 */
class GameViewModel(
    private val store: SaveStore,
    private val clock: Clock,
    private val rng: Random,
    scope: CoroutineScope?,
    private val canDance: (PetId) -> Boolean = { false },
    private val onError: (Throwable) -> Unit = {},
    private val clockFloorMs: Long = Tuning.MIN_VALID_EPOCH_MS,
    private val pickRng: Random = Random.Default,
    private val pvpRelay: PvpRelay? = null,
    private val onHostCode: (String?) -> Unit = {},
    /** A clock that only ever runs forward, in milliseconds, for short waits such as a tap's quiet moment. */
    private val uptimeMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var started = false
    private var nextToken = 1L
    private var lastGag: PlayGag? = null
    private var lastTraining: TrainingGame? = null

    /** The next TRAIN's [Screen.Training.session]. */
    private var nextTrainingSession = 1L

    /** The next FEED's [Screen.Pantry.session]. */
    private var nextPantrySession = 1L

    /** The next challenger entrance's [Screen.Challenger.session]. */
    private var nextChallengerSession = 1L

    /**
     * Debug builds only: the game the next TRAIN opens, when it is on offer (see the puppet's `train <game>`). Any
     * TRAIN uses it up, refused or not, so a forced game never waits around for a later one.
     */
    internal var debugNextTraining: TrainingGame? = null

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

    /**
     * PLAY acts on the selected pet at once. FEED opens the pantry, where the pet is fed and cooking is done (see
     * [onPantryFeed]); TRAIN opens a training game, which pays when it ends (see [onTrainingOver]).
     */
    fun onCare(a: CareAction) {
        val s = _state.value
        val pet = s.homePet() ?: return
        when (a) {
            CareAction.FEED -> openPantry(s)
            CareAction.TRAIN -> openTraining(s, pet)
            CareAction.PLAY -> play(s, pet)
        }
    }

    /** PLAY: the pet jumps and waves (or dances, when it ships a dance) after the glove's gag, paid and saved at once. */
    private fun play(s: UiState, pet: PetState) {
        when (val result = perform(CareAction.PLAY, pet)) {
            is CareResult.Refused -> refuse(s, result.reason)
            is CareResult.Done -> {
                val game = s.game.withPet(result.pet)
                val anims = if (canDance(pet.id)) listOf(Anim.DANCE) else listOf(Anim.JUMP, Anim.WAVE)
                val sfx = listOf("hand_whoosh") + if (result.levelsGained > 0) listOf("levelup") else emptyList()
                val gag = nextPlayGag(lastGag, pickRng).also { lastGag = it }
                _state.value = s.copy(game = game, refuse = null, sfx = s.sfx + sfx).beginAction(anims, gag)
                persist(game)
            }
        }
    }

    /**
     * TRAIN on Home: refuses a pet too hungry to train, else opens a training game. The game is random among the ones
     * on offer (those that need a stable mate only when the stable has one) and never the one picked last when there
     * is a choice; the mate, for the games that use one, is a random other killer in the stable. [debugNextTraining]
     * picks the game instead when it is on offer. Nothing is paid or saved until the game ends.
     */
    private fun openTraining(s: UiState, pet: PetState) {
        val forced = debugNextTraining
        debugNextTraining = null
        if (!canTrain(pet)) {
            refuse(s, RefuseReason.TOO_HUNGRY)
            return
        }
        val mate = pickMate(s.game, pickRng)
        val options = eligibleGames(hasMate = mate != null)
        val game = forced?.takeIf { it in options } ?: pickTrainingGame(options, lastTraining, pickRng)
        lastTraining = game
        val training = Screen.Training(game, mate.takeIf { game.needsMate }, options, session = nextTrainingSession++)
        _state.value = s.copy(screen = training, refuse = null)
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

    // ---- training ----------------------------------------------------------------------------

    /**
     * Training [session]'s game ended with [score] (on its own, or Back cut it short): pays it out (see
     * [applyTraining]), saves, and shows the results card, with the level-up sound when it was worth a level. The pet
     * is also marked as having finished the game, at any score, which is what a new player's first fight asks of the
     * solo games. Only the session on screen is paid, and only once, so a report that comes in again (Back on the
     * frame the game ended) or from another session changes nothing.
     */
    fun onTrainingOver(session: Long, score: Int) {
        val s = _state.value
        val training = trainingSession(s, session) ?: return
        if (training.result != null) return
        val pet = s.game.selected?.let { s.game.pets[it] } ?: return
        val outcome = applyTraining(pet, training.game, score)
        val game = s.game.withPet(outcome.pet.copy(trainedGames = outcome.pet.trainedGames + training.game.name))
        val result = TrainingResult(
            score = score.coerceAtLeast(0),
            best = outcome.best,
            newBest = outcome.newBest,
            strength = outcome.strengthGained,
            xp = outcome.xpGained,
            levelsGained = outcome.levelsGained,
        )
        val sfx = if (outcome.levelsGained > 0) listOf("levelup") else emptyList()
        _state.value = s.copy(game = game, screen = training.copy(result = result), sfx = s.sfx + sfx)
        persist(game)
    }

    /**
     * OK on training [session]'s results card: Home, where a level-up's new attack is offered as after any level-up.
     * Ignored until the game has paid, and from any session but the one on screen.
     */
    fun onTrainingDone(session: Long) {
        val s = _state.value
        val training = trainingSession(s, session) ?: return
        if (training.result == null) return
        _state.value = s.goHome()
    }

    /**
     * TRY AGAIN on training [session]'s results card: the same game once more, as a new session with no reel. Where
     * Home would not be Home (a level-up's new attack to offer first), that comes first instead, and refused like TRAIN
     * when the pet has grown too hungry meanwhile. Ignored until the game has paid, and from any session but the one
     * on screen.
     */
    fun onTrainingAgain(session: Long) {
        val s = _state.value
        val training = trainingSession(s, session) ?: return
        if (training.result == null) return
        val pet = s.game.selected?.let { s.game.pets[it] } ?: return
        val home = s.goHome()
        if (home.screen != Screen.Home) {
            _state.value = home
            return
        }
        if (!canTrain(pet)) {
            refuse(home, RefuseReason.TOO_HUNGRY)
            return
        }
        _state.value = home.copy(screen = training.copy(result = null, session = nextTrainingSession++, again = true))
    }

    /**
     * Back on training [session]'s reel: leaves training with nothing paid or spent. Ignored once the game has paid,
     * which only OK carries on from, and from any session but the one on screen.
     */
    fun onTrainingQuit(session: Long) {
        val s = _state.value
        val training = trainingSession(s, session) ?: return
        if (training.result != null) return
        _state.value = s.goHome()
    }

    /** The training screen in [s] when it is [session]'s; null on any other screen or session. */
    private fun trainingSession(s: UiState, session: Long): Screen.Training? =
        (s.screen as? Screen.Training)?.takeIf { it.session == session }

    // ---- the pantry --------------------------------------------------------------------------

    /**
     * FEED on Home: opens the pantry for the selected pet, even one too full to eat (it can still cook). The shelf is
     * topped up first (see [topUp]: a shelf too full for it throws out its oldest botches) and saved when that changed
     * it; nothing else is paid or saved. Opening is not an
     * action: no animation plays and the game is not [UiState.busy].
     */
    private fun openPantry(s: UiState) {
        val stocked = topUp(s.game.pantryItems(), pickRng).map { it.id }
        val game = if (stocked == s.game.pantry) s.game else s.game.copy(pantry = stocked)
        _state.value = s.copy(game = game, screen = Screen.Pantry(session = nextPantrySession++), refuse = null)
        if (game != s.game) persist(game)
    }

    /**
     * Pick on pantry [session]'s shelf: puts the item at [index] on the board, or takes it off again if it is there.
     * A third item is ignored (the board has two places), and so is an index off the shelf. Nothing is saved: the
     * board belongs to the visit. Ignored once the killer has eaten.
     */
    fun onPantryPick(session: Long, index: Int) {
        val s = _state.value
        val pantry = pantrySession(s, session)?.takeIf { it.fed == null } ?: return
        if (index !in s.game.pantryItems().indices) return
        val board = when {
            index in pantry.board -> pantry.board.filter { it != index }
            pantry.board.size < BOARD_PLACES -> pantry.board + index
            else -> return
        }
        _state.value = s.copy(screen = pantry.copy(board = board))
    }

    /**
     * COOK pantry [session]'s board, which must hold two items (so a second press on a board that has just been cooked
     * does nothing). Both are used up and the result takes the shelf place of the earlier of them; the board is cleared,
     * [Screen.Pantry.cook] shows the cook and the game is saved. A way to cook among them plays its own sound (the first
     * on the board, when both are); the screen plays the poof as it shows it. Two botches cook like anything else (into
     * one Pile of Slop, see [cook]). A board that can not be cooked (a botch with anything but another botch) uses
     * nothing and keeps its board: only [Screen.Pantry.refusedCook] changes, with a squelch.
     */
    fun onPantryCook(session: Long) {
        val s = _state.value
        val pantry = pantrySession(s, session)?.takeIf { it.fed == null && it.board.size == BOARD_PLACES } ?: return
        val shelf = s.game.pantryItems()
        val inputs = pantry.board.map { shelf[it] }
        val result = (cook(inputs[0], inputs[1]) as? CookResult.Cooked)?.item
        if (result == null) {
            _state.value = s.copy(screen = pantry.copy(refusedCook = nextToken++), sfx = s.sfx + "pantry_squelch")
            return
        }
        val at = pantry.board.min()
        val left = shelf.filterIndexed { i, _ -> i !in pantry.board }
        // A real dish is a recipe found, for the pantry to mark from now on; slop is not.
        val found = if (result.kind == ItemKind.BOTCHED) s.game.recipesFound else s.game.recipesFound + result.id
        val game = s.game.copy(pantry = (left.take(at) + result + left.drop(at)).map { it.id }, recipesFound = found)
        val method = inputs.firstOrNull { it.kind == ItemKind.METHOD }
        val sfx = listOfNotNull(method?.let { "cook_${it.id}" })
        val show = CookShow(result, inputs, at, nextToken++)
        _state.value = s.copy(game = game, screen = pantry.copy(board = emptyList(), cook = show), sfx = s.sfx + sfx)
        persist(game)
    }

    /**
     * FEED pantry [session]'s board, which must hold one item: the selected pet eats it and is paid (see [feed]), the
     * item leaves the shelf, the board is cleared, [Screen.Pantry.fed] shows how it went and the game is saved. After
     * that the visit does nothing but leave. A pet too full to eat (hunger below [Tuning.FEED_REFUSE_BELOW]) eats
     * nothing: the board stays and [Screen.Pantry.tooFull] changes, with the refusal's sound. That is not [refuse]:
     * it plays a Home animation that nothing on this screen would ever finish. The eating sounds are the screen's.
     */
    fun onPantryFeed(session: Long) {
        val s = _state.value
        val pantry = pantrySession(s, session)?.takeIf { it.fed == null && it.board.size == 1 } ?: return
        val pet = s.game.selected?.let { s.game.pets[it] } ?: return
        if (!canEat(pet)) {
            _state.value = s.copy(screen = pantry.copy(tooFull = nextToken++), sfx = s.sfx + "refuse")
            return
        }
        val shelf = s.game.pantryItems()
        val place = pantry.board.single()
        val item = shelf[place]
        val outcome = feed(pet, item)
        val game = s.game.withPet(outcome.pet).copy(pantry = shelf.filterIndexed { i, _ -> i != place }.map { it.id })
        val fed = FedShow(
            item = item,
            reaction = outcome.reaction,
            line = outcome.line,
            xp = outcome.xpGained,
            levelsGained = outcome.levelsGained,
            strength = outcome.strengthGained,
            funLevel = outcome.funGained,
            mood = outcome.moodGained,
            hungerBefore = pet.hunger,
            hungerAfter = outcome.pet.hunger,
        )
        _state.value = s.copy(game = game, screen = pantry.copy(board = emptyList(), fed = fed))
        persist(game)
    }

    /**
     * OK on pantry [session]'s reaction while the killer could eat more (see [canEat]): FEED again without the walk
     * Home and back, as a new visit (the shelf topped up, as FEED does, so an emptied shelf is restocked). Where Home
     * would not be Home (a level-up's new attack to offer first), that comes first instead; a killer that could not eat
     * more just goes Home, as [onPantryDone]. Ignored until the killer has eaten, and from any session but the one on
     * screen.
     */
    fun onPantryMore(session: Long) {
        val s = _state.value
        pantrySession(s, session)?.takeIf { it.fed != null } ?: return
        val pet = s.game.selected?.let { s.game.pets[it] } ?: return
        val home = s.goHome()
        if (home.screen != Screen.Home || !canEat(pet)) {
            _state.value = home
            return
        }
        openPantry(home)
    }

    /**
     * OK on pantry [session]'s reaction: Home, where a level-up's new attack is offered as after any level-up. Does
     * nothing until the killer has eaten ([Screen.Pantry.fed]), so a FEED pressed twice can never skip its reaction;
     * Back ([onPantryBack]) leaves at any point of the visit.
     */
    fun onPantryDone(session: Long) {
        if (pantrySession(_state.value, session)?.fed == null) return
        leavePantry(session)
    }

    /** Back in pantry [session]: Home with the shelf as it was (what was on the board goes back), nothing paid. */
    fun onPantryBack(session: Long) = leavePantry(session)

    private fun leavePantry(session: Long) {
        val s = _state.value
        pantrySession(s, session) ?: return
        _state.value = s.goHome()
    }

    /** The pantry screen in [s] when it is [session]'s; null on any other screen or session. */
    private fun pantrySession(s: UiState, session: Long): Screen.Pantry? =
        (s.screen as? Screen.Pantry)?.takeIf { it.session == session }

    /**
     * Debug builds only: the puppet's `stock` (see `team.superfun.horrorpets.puppet.Puppet`). Puts [items] on the end of
     * the shelf, as many as fit under [Tuning.PANTRY_MAX] (the first ones, in order), and saves the game. Works on any
     * screen, and a pantry on show keeps its board, as the items only join the end. Ignored while the save is loading.
     */
    internal fun debugStock(items: List<PantryItem>) {
        val s = _state.value
        if (s.screen == Screen.Loading) return
        val room = Tuning.PANTRY_MAX - s.game.pantry.size
        if (room <= 0 || items.isEmpty()) return
        val game = s.game.copy(pantry = s.game.pantry + items.take(room).map { it.id })
        _state.value = s.copy(game = game)
        persist(game)
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

    /**
     * Opens the opponent switcher, or refuses when the selected pet is too hungry to fight (first) or, being a new
     * player's, has not yet trained at both solo games (see [mustTrainBeforeFighting]).
     *
     * A FIGHT that is not refused while a secret killer is due (see [challengerDue]: every other killer is in the
     * stable) is his instead: he is revealed and saved at once, so his entrance plays only this once whatever happens
     * next, and [Screen.Challenger] plays it. [onChallengerDone] then takes the player to his VS card.
     */
    fun onFightPressed() {
        val s = _state.value
        val pet = s.homePet() ?: return
        if (!canFight(pet)) {
            refuse(s, RefuseReason.TOO_HUNGRY)
            return
        }
        if (mustTrainBeforeFighting(s.game, pet)) {
            refuse(s, RefuseReason.NEEDS_TRAINING)
            return
        }
        val challenger = s.game.challengerDue()
        if (challenger != null) {
            val game = s.game.copy(revealed = s.game.revealed + challenger.name)
            val entrance = Screen.Challenger(challenger, session = nextChallengerSession++)
            _state.value = s.copy(game = game, screen = entrance, refuse = null)
            persist(game)
            return
        }
        _state.value = s.copy(screen = Screen.Switcher(SwitcherMode.OPPONENT), refuse = null)
    }

    /**
     * Challenger [session]'s entrance is over (it ended, or OK or Back skipped it): on to the VS card against him, as
     * picking him in the opponent switcher would. Ignored from any session but the one on screen, so an end reported
     * twice (OK on the frame it ended by itself) goes there once.
     */
    fun onChallengerDone(session: Long) {
        val s = _state.value
        val challenger = (s.screen as? Screen.Challenger)?.takeIf { it.session == session } ?: return
        _state.value = s.versusAgainst(challenger.pet) ?: return
    }

    fun onSwitcherPick(p: PetId) {
        val s = _state.value
        val switcher = s.screen as? Screen.Switcher ?: return
        when (switcher.mode) {
            SwitcherMode.OPPONENT -> _state.value = s.versusAgainst(p) ?: return

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
        _state.value = s.copy(screen = Screen.Fight(startFight(player, versus.opponent, rng), emptyList()))
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

        // The CPU picks as the player's move comes in, so nothing about it shows before the player chooses.
        // Running ends the fight before the CPU acts, and the engine ignores the move it is handed.
        val cpuMove = if (m == Move.RUN) null else cpuPick(fight, rng)
        val turn = resolveTurn(fight, m, cpuMove ?: Move.BLOCK, rng)
        // Once a fighter is nearly done the fight stays intense, even if a potion brings him back.
        val intense = screen.intense || nearlyDone(turn.state)
        _state.value = s.copy(
            screen = Screen.Fight(turn.state, turn.events, playerMove = m, cpuMove = cpuMove, intense = intense),
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
        val s = _state.value
        val lobby = s.screen as? Screen.PvpLobby ?: return
        if (lobby.phase !is LobbyPhase.Menu) return
        host(s, fromTap = false)
    }

    /**
     * Opens a room from [s], in the lobby: its code goes on screen (unless a tap opened it, [fromTap]) and to
     * [onHostCode], then a challenger is awaited.
     */
    private fun host(s: UiState, fromTap: Boolean) {
        val relay = pvpRelay ?: return
        val me = myFighter(s) ?: return
        _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Hosting(fromTap = fromTap)), refuse = null)
        pvpJob?.cancel()
        pvpJob = scope.launch {
            val hosted = try {
                relay.create(me)
            } catch (e: PvpException) {
                val problem = problemOf(e)
                // A tap's room that would not open: a moment's quiet, so phones still touching don't try again at once.
                if (fromTap) quietTaps(TAP_RETRY_MS)
                setLobby(if (fromTap) LobbyPhase.Tapped(again = true, problem = problem) else LobbyPhase.Menu(problem), onlyIf = { it is LobbyPhase.Hosting })
                return@launch
            }
            hostedRoom = hosted.room to hosted.token
            onHostCode(hosted.code)
            updateLobby { (it as? LobbyPhase.Hosting)?.copy(code = hosted.code) }
            // Wait for a challenger: sooner after a tap, where the friend is joining right now, but not for long, since
            // a tap's room shows no code to wait over. A dropped request or two is only a slower wait.
            var waited = 0L
            while (true) {
                val poll = if (fromTap) TAP_POLL_MS else LOBBY_POLL_MS
                delay(poll)
                waited += poll
                if (fromTap && waited >= TAP_GUEST_MS) {
                    closeHostedRoom()
                    setLobby(LobbyPhase.Tapped(again = true), onlyIf = { it is LobbyPhase.Hosting })
                    return@launch
                }
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
        if (digits.length == CODE_LENGTH) join(digits, fromTap = false) else _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Joining(digits)))
    }

    /** Takes back the last digit typed. */
    fun onLobbyErase() {
        val s = _state.value
        val phase = (s.screen as? Screen.PvpLobby)?.phase as? LobbyPhase.Joining ?: return
        if (phase.busy || phase.digits.isEmpty()) return
        _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Joining(phase.digits.dropLast(1))))
    }

    // ---- tap fights: two phones tapped together (NFC; see TapFight.kt and pvp/nfc) -------------

    /** Until when ([uptimeMs]) taps are ignored: after a player called one off, or one failed, while the phones may well still touch. */
    private var tapQuietUntilMs = Long.MIN_VALUE

    private fun tapQuiet(): Boolean = uptimeMs() < tapQuietUntilMs

    private fun quietTaps(forMs: Long) {
        tapQuietUntilMs = maxOf(tapQuietUntilMs, uptimeMs() + forMs)
    }

    /** The join under way, if any: one the player stepped out of has an older number (see [join]). */
    private var joinAttempt = 0

    /** Room codes a tap joined that turned out to be another version's: read again, they are left alone. */
    private val otherVersionCodes = mutableSetOf<String>()

    private fun lobbyPhase(): LobbyPhase? = (_state.value.screen as? Screen.PvpLobby)?.phase

    /**
     * A code read off a hosting phone by a tap: joins its room, from wherever a tap may (see [tapJoinOpen]). A tap
     * shows no code, only that the phones are connecting.
     */
    fun onTapCode(code: String): Boolean {
        if (code.length != CODE_LENGTH || !code.all(Char::isDigit)) return false
        if (pvpRelay == null || tapQuiet() || code in otherVersionCodes || !tapJoinOpen(_state.value)) return false
        join(code, fromTap = true)
        return true
    }

    /**
     * Whether this phone would open a room for a phone a tap found ready (see [tapReadyOf]). Asked before the tap
     * claims that phone, so it changes nothing.
     */
    fun canHostForTap(): Boolean {
        val s = _state.value
        if (pvpRelay == null || myFighter(s) == null || tapQuiet() || !tapJoinOpen(s)) return false
        // A phone waiting for its friend's room does not open one of its own.
        return (lobbyPhase() as? LobbyPhase.Tapped)?.again != false
    }

    /** The tapped phone took this one's claim: open a room for both. Whether it did. */
    fun onHostForTap(): Boolean {
        if (!canHostForTap()) return false
        val s = _state.value
        pvpJob?.cancel()
        host(s.copy(sfx = s.sfx + "gotcha"), fromTap = true)
        return true
    }

    /** Whether this phone is opening, or waiting in, a room (for a tap to hand over). */
    fun tapHosting(): Boolean = lobbyPhase() is LobbyPhase.Hosting

    /** Whether this phone opened its room for a tap (so a tap that finds the friend again hands it over). */
    fun hostingForTap(): Boolean = (lobbyPhase() as? LobbyPhase.Hosting)?.fromTap == true

    /** This phone's room got over the tap to the friend's phone ([ok]: connecting), or the phones parted first (tap again). */
    fun onTapHandOver(ok: Boolean) {
        updateLobby { (it as? LobbyPhase.Hosting)?.takeIf { h -> h.fromTap }?.copy(tapAgain = !ok) }
    }

    /** Whether a tap may claim this phone, to have the reader open a room for both (see [tapReadyOf]). */
    fun canBeTapped(): Boolean {
        val s = _state.value
        if (pvpRelay == null || myFighter(s) == null || tapQuiet()) return false
        return tapReadyOf(s)
    }

    /**
     * A friend's phone claimed this one by a tap and is opening a room for both: wait for it to come over the tap.
     * If none comes in [TAP_WAIT_MS], this phone asks for another tap (and reads, to pick the room up).
     */
    fun onTappedAsCard() {
        if (!canBeTapped()) return
        val s = _state.value
        val first = lobbyPhase() !is LobbyPhase.Tapped
        _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Tapped()), refuse = null, sfx = if (first) s.sfx + "gotcha" else s.sfx)
        pvpJob?.cancel()
        pvpJob = scope.launch {
            delay(TAP_WAIT_MS)
            setLobby(LobbyPhase.Tapped(again = true), onlyIf = { it == LobbyPhase.Tapped() })
        }
    }

    /** The friend's room [code], handed over the tap: joins it. Whether this phone took it. */
    fun onTapHandedCode(code: String): Boolean {
        if (lobbyPhase() !is LobbyPhase.Tapped || tapQuiet()) return false
        if (code.length != CODE_LENGTH || !code.all(Char::isDigit)) return false
        join(code, fromTap = true)
        return true
    }

    /** The phones parted before the room came over: tap again (this phone reads now, to pick the friend's room up). */
    fun onTapLost() {
        if (lobbyPhase() != LobbyPhase.Tapped()) return
        pvpJob?.cancel()
        pvpJob = null
        setLobby(LobbyPhase.Tapped(again = true), onlyIf = { it == LobbyPhase.Tapped() })
    }

    /**
     * Back in the lobby: from the menu to the opponent switcher, from hosting or joining back to the menu. A tap's
     * fight is called off back to Home, and taps are ignored for a moment while the phones come apart.
     */
    fun onLobbyBack() {
        val s = _state.value
        val lobby = s.screen as? Screen.PvpLobby ?: return
        val phase = lobby.phase
        // A join under way is let finish (the relay may already have let this player in), and leaves (see [join]).
        if ((phase as? LobbyPhase.Joining)?.busy == true) joinAttempt++ else pvpJob?.cancel()
        pvpJob = null
        val tapped = phase is LobbyPhase.Tapped || (phase as? LobbyPhase.Hosting)?.fromTap == true || (phase as? LobbyPhase.Joining)?.fromTap == true
        if (phase is LobbyPhase.Hosting) closeHostedRoom()
        if (tapped) {
            quietTaps(TAP_QUIET_MS)
            _state.value = s.goHome()
            return
        }
        _state.value = when (phase) {
            is LobbyPhase.Menu -> s.copy(screen = Screen.Switcher(SwitcherMode.OPPONENT))
            else -> s.copy(screen = Screen.PvpLobby(LobbyPhase.Menu()))
        }
    }

    /** Joins room [code], in the lobby (on to it from wherever a tap started this, [fromTap]). */
    private fun join(code: String, fromTap: Boolean) {
        val relay = pvpRelay ?: return
        val s = _state.value
        val me = myFighter(s) ?: return
        _state.value = s.copy(screen = Screen.PvpLobby(LobbyPhase.Joining(code, busy = true, fromTap = fromTap)), refuse = null)
        val attempt = ++joinAttempt
        pvpJob?.cancel()
        pvpJob = scope.launch {
            val walkedAway = { joinAttempt != attempt }
            val joined = try {
                relay.join(code, me)
            } catch (e: PvpException) {
                if (walkedAway()) return@launch
                val problem = problemOf(e)
                if (fromTap) {
                    // Phones still touching would read the same room again at once: a moment's quiet, and never
                    // another version's room again.
                    quietTaps(TAP_RETRY_MS)
                    if (e is PvpException.Version) otherVersionCodes += code
                }
                setLobby(if (fromTap) LobbyPhase.Tapped(again = true, problem = problem) else LobbyPhase.Joining("", problem = problem), onlyIf = { it is LobbyPhase.Joining })
                return@launch
            }
            if (walkedAway()) {
                // The player stepped out while the relay let them in: leave, so the host isn't left facing nobody.
                runCatching { relay.leave(joined.room, joined.token) }
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
        // As in onMove: once either fighter is nearly done, the fight stays intense.
        val intense = s.screen.intense || nearlyDone(turn.state)
        _state.value = s.copy(
            screen = Screen.Fight(
                game.view(turn.state),
                game.viewEvents(turn.events),
                playerMove = mine,
                cpuMove = theirs,
                pvp = PvpView(),
                intense = intense,
            ),
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

    /** Plays the forfeit that ended [fight] (canonical), then on to the results. An intense fight stays intense. */
    private fun endPvpFight(s: UiState, fight: FightState, playerMove: Move?, cpuMove: Move?) {
        val game = match ?: return
        canonical = fight
        val intense = (s.screen as? Screen.Fight)?.intense == true
        _state.value = s.copy(
            screen = Screen.Fight(
                game.view(fight),
                emptyList(),
                playerMove = playerMove,
                cpuMove = cpuMove,
                pvp = PvpView(),
                intense = intense,
            ),
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

    /** The lobby's phase as [change] makes it from the current one; unchanged where it gives null. */
    private fun updateLobby(change: (LobbyPhase) -> LobbyPhase?) {
        val s = _state.value
        val lobby = s.screen as? Screen.PvpLobby ?: return
        val phase = change(lobby.phase) ?: return
        _state.value = s.copy(screen = Screen.PvpLobby(phase))
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
        gag: PlayGag? = null,
        finish: (UiState) -> UiState = { it },
    ): UiState {
        running = Running(ArrayDeque(anims.drop(1)), finish)
        return copy(busy = true, petAnim = PetAnimRequest(anims.first(), nextToken++, gag = gag))
    }

    private fun refuse(s: UiState, reason: RefuseReason) {
        _state.value = s.copy(refuse = reason, sfx = s.sfx + "refuse").beginAction(listOf(Anim.FAIL))
    }

    private fun UiState.goHome(): UiState = copy(screen = Screen.Home, refuse = null, stableOpen = false).routeHome()

    /**
     * The VS card against [opponent], with the announcer's "<player> versus <opponent>" queued as it opens; null
     * without a selected pet, or when [opponent] is that pet (it can not fight itself).
     */
    private fun UiState.versusAgainst(opponent: PetId): UiState? {
        val player = game.selected ?: return null
        if (opponent == player) return null
        return copy(screen = Screen.Versus(opponent), voice = voice + listOf(player.soundName(), "versus", opponent.soundName()))
    }

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

    /**
     * Debug builds only: the puppet's way in (see `team.superfun.horrorpets.puppet.Puppet`), to set up a scene that
     * play would take a while to reach. Applies [edit] to the state, then routes a Home to the fake death or learn
     * screen it is due (as every way Home does) and saves the game if it changed. Refused while an action plays (its
     * animations still have to report in) or the save is still loading. Returns whether it applied.
     */
    internal fun debugEdit(edit: (UiState) -> UiState): Boolean {
        val s = _state.value
        if (s.busy || s.screen == Screen.Loading) return false
        val edited = edit(s).routeHome()
        _state.value = edited
        if (edited.game != s.game) persist(edited.game)
        return true
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

/** The pantry's board has this many places: two items to cook, or the one to feed. */
internal const val BOARD_PLACES = 2

/** A PvP room's code is this many digits. */
internal const val CODE_LENGTH = 4

/** How often a host asks whether a challenger has joined, and a fighter whether the other has locked in. */
private const val LOBBY_POLL_MS = 1_500L

/** How often a host whose room a tap opened looks for the friend, who is joining right now. */
private const val TAP_POLL_MS = 500L

/** How long a phone claimed by a tap waits for the reader's room before it asks for another tap. */
const val TAP_WAIT_MS = 12_000L

/** How long taps are ignored after a player calls one off. */
const val TAP_QUIET_MS = 5_000L

/** How long taps are ignored after one failed (no room, or no way to join it), so phones still touching don't loop. */
const val TAP_RETRY_MS = 5_000L

/** How long a room a tap opened waits for the friend's phone to join, once its code is in: then it is closed. */
const val TAP_GUEST_MS = 15_000L
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
