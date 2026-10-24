package team.superfun.horrorpets.puppet

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.training.eligibleGames
import team.superfun.horrorpets.game.training.pickMate
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.model.attackById
import team.superfun.horrorpets.model.kitOf
import team.superfun.horrorpets.model.pantryItems
import team.superfun.horrorpets.model.pendingAttacks
import team.superfun.horrorpets.ui.GameViewModel
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.SwitcherMode
import team.superfun.horrorpets.ui.UiState

/** The broadcast that carries commands to the running game, in its [EXTRA_CMD]. */
const val PUPPET_ACTION = "team.superfun.horrorpets.PUPPET"

/** The extra holding a line of commands (see [splitCommands]), on the broadcast or on MainActivity's launch. */
const val EXTRA_CMD = "cmd"

/** The launch extra from before the puppet, still taken: `--ei debug_level 14` is `level 14`. */
const val EXTRA_DEBUG_LEVEL = "debug_level"

/** Every command and what came of it is logged under this tag: `adb logcat -s Puppet`. */
private const val TAG = "Puppet"

/** Launch commands wait this long after the save has loaded, so the launch's own save of it cannot overwrite them. */
private const val LAUNCH_SETTLE_MS = 1_000L

/** How long a command waits for the action playing (a turn, a feed) to end before it gives up. */
private const val IDLE_WAIT_MS = 30_000L

/** How long `wait home` waits. */
private const val WAIT_HOME_MS = 600_000L

/** Autoplay leaves the menu up this long before picking, so the footage shows it and its cursor. */
private const val MENU_LOOK_MS = 800L

/** Autoplay leaves a results, unlock or learn card up this long before continuing. */
private const val CARD_HOLD_MS = 2_500L

/**
 * Autoplay leaves a pantry's meal this long before carrying on: its toss, munch and reaction play out first, and the
 * kitchen carries on Home by itself a few seconds into the reaction, so this only matters when nothing is drawing it.
 */
private const val MEAL_HOLD_MS = 9_000L

/** How long the fight screen gets to take a pick (its cursor holds a moment first) before the game gets it directly. */
private const val PICK_TAKE_MS = 1_500L

private const val NO_PET = "no active pet yet: pet <killer> first"

/**
 * Debug builds' [Puppet] (installed by [PuppetInstaller]). It takes lines of commands from adb, as the [EXTRA_CMD]
 * extra of MainActivity's launch or a [PUPPET_ACTION] broadcast while it runs, and drives the game through its view
 * model, logging each command and its outcome under the `Puppet` tag. See the README's "Puppet mode" for the commands.
 *
 * The receiver only takes broadcasts from senders holding `DUMP`, which adb's shell does and an ordinary app
 * cannot, so nothing else on the device can drive a debug build either.
 */
internal object DebugPuppet : Puppet {
    private val picks = MutableSharedFlow<Move>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val fightPicks: Flow<Move> = picks.asSharedFlow()

    /** Whether fights show their HUD (`hud on|off`). Kept for the process, as [autoplay] is. */
    val hud = MutableStateFlow(true)
    override val fightHud: StateFlow<Boolean> = hud.asStateFlow()

    /** Whether autoplay is on. Kept for the process, so it carries on in an Activity that is recreated. */
    var autoplay = false

    /** `fake_version`'s version, kept for the process like [autoplay]: the update check reads it as it runs. */
    @Volatile
    override var fakeVersion: String? = null

    private val checks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val updateChecks: Flow<Unit> = checks.asSharedFlow()

    /** Asks MainActivity for an update check now; false when nothing is listening for one. */
    fun checkUpdate(): Boolean = checks.subscriptionCount.value > 0 && checks.tryEmit(Unit)

    /** The Activity being driven, while there is one. */
    private var session: Session? = null

    override fun attach(activity: ComponentActivity, vm: GameViewModel) {
        val s = Session(activity, vm, picks)
        session = s
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                s.close()
                if (session === s) session = null
            }
        })
        Log.i(TAG, "Ready: adb shell am broadcast -a $PUPPET_ACTION --es $EXTRA_CMD \"status\"")
    }

    override fun onIntent(intent: Intent) {
        val lines = listOfNotNull(
            intent.getStringExtra(EXTRA_CMD),
            intent.getIntExtra(EXTRA_DEBUG_LEVEL, 0).takeIf { it > 0 }?.let { "level $it" },
        )
        if (lines.isNotEmpty()) session?.run(lines.joinToString(";"), from = "launch", afterLoad = true)
    }
}

/** What came of a command. */
private sealed interface Outcome {
    data class Done(val what: String) : Outcome

    data class Refused(val why: String) : Outcome
}

/** The puppet's hold on one MainActivity and its view model, from its creation to its destruction. */
private class Session(
    private val activity: ComponentActivity,
    private val vm: GameViewModel,
    private val picks: MutableSharedFlow<Move>,
) {
    private val scope = activity.lifecycleScope

    /** The line of commands still running (waiting, mostly), if any: a new line replaces it. */
    private var line: Job? = null
    private var autoplayJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(EXTRA_CMD)
            if (text == null) Log.w(TAG, "A broadcast without a $EXTRA_CMD extra: nothing to do") else run(text, from = "broadcast")
        }
    }

    init {
        // Exported so adb can reach it (API 33+ wants that said), and only from a sender holding DUMP (adb's shell).
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(PUPPET_ACTION),
            Manifest.permission.DUMP,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Each screen as it comes up, so a script can follow along (and see a fight end).
        scope.launch {
            vm.state.map { it.screen }.distinctUntilChanged { a, b -> a::class == b::class }.collect {
                Log.d(TAG, "Screen: ${describe(vm.state.value)}")
            }
        }
        if (DebugPuppet.autoplay) startAutoplay()
    }

    fun close() {
        activity.unregisterReceiver(receiver)
    }

    /**
     * Runs [text]'s commands in order (see [splitCommands]), each once the action playing has ended. A refused one
     * drops the rest. A new line replaces this one if it is still running. [afterLoad] holds them until the save
     * has loaded and settled, for a launch's.
     */
    fun run(text: String, from: String, afterLoad: Boolean = false) {
        val commands = splitCommands(text)
        if (commands.isEmpty()) {
            Log.w(TAG, "No commands in the $from's \"$text\"")
            return
        }
        line?.takeIf { it.isActive }?.let {
            Log.i(TAG, "Dropping what is left of the last line for this one")
            it.cancel()
        }
        line = scope.launch {
            if (afterLoad) {
                vm.state.first { it.screen != Screen.Loading }
                delay(LAUNCH_SETTLE_MS)
            }
            for ((i, command) in commands.withIndex()) {
                Log.i(TAG, "> $command")
                when (val outcome = execute(parseCommand(command))) {
                    is Outcome.Done -> Log.i(TAG, "  ok: ${outcome.what}")
                    is Outcome.Refused -> {
                        Log.w(TAG, "  refused: ${outcome.why}")
                        val rest = commands.drop(i + 1)
                        if (rest.isNotEmpty()) Log.w(TAG, "  skipped: ${rest.joinToString("; ")}")
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun execute(command: PuppetCommand): Outcome {
        val immediate = command is PuppetCommand.Autoplay || command is PuppetCommand.Hud || command is PuppetCommand.Status ||
            command is PuppetCommand.Wait || command is PuppetCommand.WaitHome || command is PuppetCommand.Invalid ||
            command is PuppetCommand.FakeVersion || command is PuppetCommand.CheckUpdate
        if (!immediate && !awaitIdle()) return Outcome.Refused("the game was still busy after ${IDLE_WAIT_MS / 1000} s")
        return when (command) {
            PuppetCommand.UnlockAll -> {
                val before = state().game.pets.size
                edit({ "${it.game.pets.size} killers in the stable (${it.game.pets.size - before} new)" }) {
                    it.copy(game = it.game.withAllUnlocked())
                }
            }

            is PuppetCommand.Pet -> {
                leavingRefusal()?.let { return Outcome.Refused(it) }
                val joins = !state().game.isUnlocked(command.pet)
                edit({ "${petLine(it)}${if (joins) ", new to the stable" else ""}${screenNote(it)}" }) {
                    it.copy(game = it.game.withActive(command.pet), screen = Screen.Home, stableOpen = false, refuse = null)
                }
            }

            is PuppetCommand.Level -> {
                val pet = activePet() ?: return Outcome.Refused(NO_PET)
                edit({ "${petLine(it)}, ${activePet()?.let(::pendingAttacks)?.size ?: 0} attacks to learn${screenNote(it)}" }) {
                    it.copy(game = it.game.withPet(pet.atLevel(command.level)))
                }
            }

            PuppetCommand.LearnAll -> {
                val pet = activePet() ?: return Outcome.Refused(NO_PET)
                val learned = pet.withEverythingLearned()
                val kit = kitOf(learned)
                edit({ "${pet.id.displayName} fights with ${kit.light.name}, ${kit.heavy.name}, ${kit.special.name}${screenNote(it)}" }) {
                    // Its learn screen would have nothing left to offer.
                    it.copy(game = it.game.withPet(learned), screen = if (it.screen is Screen.Learn) Screen.Home else it.screen)
                }
            }

            is PuppetCommand.Fight -> {
                leavingRefusal()?.let { return Outcome.Refused(it) }
                val me = activePet()?.id ?: return Outcome.Refused(NO_PET)
                if (me == command.opponent) return Outcome.Refused("${me.displayName} cannot fight itself")
                edit({ "VS card: ${me.displayName} vs ${command.opponent.displayName}" }) {
                    // The VS card's announcer, as picking the opponent queues it.
                    val voice = listOf(me.name.lowercase(), "versus", command.opponent.name.lowercase())
                    it.copy(screen = Screen.Versus(command.opponent), stableOpen = false, refuse = null, voice = it.voice + voice)
                }
            }

            is PuppetCommand.Autoplay -> {
                DebugPuppet.autoplay = command.on
                if (command.on) startAutoplay() else autoplayJob?.cancel()
                Outcome.Done("autoplay ${if (command.on) "on" else "off"}")
            }

            is PuppetCommand.Hud -> {
                DebugPuppet.hud.value = command.on
                Outcome.Done(if (command.on) "hud on" else "hud off: fights hide their bars, meters, turn, chips, menu and help")
            }

            is PuppetCommand.Pick -> {
                val screen = state().screen as? Screen.Fight ?: return Outcome.Refused("not in a fight")
                if (screen.pvp?.waiting == true) return Outcome.Refused("locked in already: waiting for the other player")
                pickRefusal(screen.fight, command.move)?.let { return Outcome.Refused(it) }
                Outcome.Done("${command.move} ${pick(command.move)}")
            }

            PuppetCommand.MeterFull -> {
                val screen = state().screen as? Screen.Fight ?: return Outcome.Refused("not in a fight")
                if (screen.pvp != null) {
                    return Outcome.Refused("not in PvP: both apps play each turn the same way, and only this one's meter would fill")
                }
                if (screen.fight.winner != null) return Outcome.Refused("the fight is over")
                edit({ "meter full: SPECIAL is in the menu" }) {
                    val fight = it.screen as? Screen.Fight
                    if (fight == null) it else it.copy(screen = fight.copy(fight = fight.fight.withFullMeter()))
                }
            }

            is PuppetCommand.Care -> {
                goHome()?.let { return Outcome.Refused(it) }
                val before = activePet() ?: return Outcome.Refused(NO_PET)
                if (command.action == CareAction.TRAIN) return openTraining(before, command.game)
                if (command.action == CareAction.FEED) return openPantry()
                vm.onCare(command.action)
                val after = state()
                val pet = activePet() ?: before
                when {
                    after.refuse != null -> Outcome.Refused("${pet.id.displayName} turned it down: ${after.refuse}")
                    !after.busy -> Outcome.Refused("Home did not take it")
                    else -> Outcome.Done("${pet.id.displayName}: ${playChange(before, pet)}")
                }
            }

            is PuppetCommand.Score -> {
                val screen = state().screen as? Screen.Training ?: return Outcome.Refused("not training: train first")
                if (screen.result != null) return Outcome.Refused("the game is over already: home, or train again")
                vm.onTrainingOver(screen.session, command.score)
                val result = (state().screen as? Screen.Training)?.result ?: return Outcome.Refused("training did not take the score")
                val best = if (result.newBest) "a new best" else "best ${result.best}"
                val strength = (result.strength * 10).roundToInt() / 10.0
                val levels = if (result.levelsGained > 0) ", +${result.levelsGained} level" else ""
                Outcome.Done("${describe(state())} ($best): +$strength strength, +${result.xp} XP$levels")
            }

            is PuppetCommand.Stock -> {
                if (state().screen == Screen.Loading) return Outcome.Refused("the save is still loading: nothing was added")
                val before = state().game.pantry.size
                vm.debugStock(command.items)
                val added = state().game.pantry.size - before
                if (added == 0) {
                    Outcome.Refused("the pantry is full: ${Tuning.PANTRY_MAX} items")
                } else {
                    Outcome.Done("stocked $added of ${command.items.size}: ${state().game.pantry.size} on the shelf")
                }
            }

            is PuppetCommand.Cook -> {
                pantryRefusal()?.let { return Outcome.Refused(it) }
                val wanted = listOf(command.a, command.b)
                val places = placesOnShelf(wanted)
                places.indexOf(null).takeIf { it >= 0 }?.let { return Outcome.Refused(notEnoughOnShelf(wanted, wanted[it])) }
                val session = (state().screen as Screen.Pantry).session
                val before = (state().screen as Screen.Pantry).cook?.token
                putOnBoard(places.filterNotNull())
                vm.onPantryCook(session)
                val cooked = (state().screen as? Screen.Pantry)?.cook?.takeIf { it.token != before }
                val inputs = wanted.joinToString(" + ") { it.id }
                if (cooked == null) {
                    Outcome.Refused("$inputs will not cook: a botched dish is a dead end, so they stay on the board")
                } else {
                    Outcome.Done("$inputs = ${cooked.result.id}, ${state().game.pantry.size} on the shelf")
                }
            }

            is PuppetCommand.Serve -> {
                pantryRefusal()?.let { return Outcome.Refused(it) }
                val place = placesOnShelf(listOf(command.item)).single() ?: return Outcome.Refused("${command.item.id} is not on the shelf")
                val session = (state().screen as Screen.Pantry).session
                putOnBoard(listOf(place))
                vm.onPantryFeed(session)
                val pet = activePet()?.id?.displayName ?: "the killer"
                val fed = (state().screen as? Screen.Pantry)?.fed
                if (fed == null) {
                    Outcome.Refused("$pet is too full to eat: hunger ${activePet()?.hunger?.toInt()}, and it eats from ${Tuning.FEED_REFUSE_BELOW.toInt()} up")
                } else {
                    val levels = if (fed.levelsGained > 0) ", +${fed.levelsGained} level" else ""
                    Outcome.Done("$pet ate ${fed.item.id}: ${fed.reaction}, \"${fed.line}\", +${fed.xp} XP$levels")
                }
            }

            PuppetCommand.Home -> goHome()?.let { Outcome.Refused(it) } ?: Outcome.Done("on Home${screenNote(state())}")

            is PuppetCommand.Wait -> {
                delay(command.ms)
                Outcome.Done("waited ${command.ms / 1000.0} s")
            }

            PuppetCommand.WaitHome -> {
                val home = withTimeoutOrNull(WAIT_HOME_MS) { vm.state.first { it.screen == Screen.Home && !it.busy } }
                if (home == null) Outcome.Refused("still not Home after ${WAIT_HOME_MS / 60_000} min") else Outcome.Done("on Home")
            }

            PuppetCommand.Status -> {
                val s = state()
                val hud = if (DebugPuppet.hud.value) "on" else "off"
                Outcome.Done("${describe(s)}; ${petLine(s)}; autoplay ${if (DebugPuppet.autoplay) "on" else "off"}; hud $hud")
            }

            is PuppetCommand.FakeVersion -> {
                DebugPuppet.fakeVersion = command.version
                Outcome.Done(
                    if (command.version == null) "the update check uses this build's own version" else "the update check takes this build for ${command.version}",
                )
            }

            PuppetCommand.CheckUpdate -> {
                if (!DebugPuppet.checkUpdate()) return Outcome.Refused("nothing is listening for update checks")
                val note = if (state().screen == Screen.Title) "" else " (its window only shows over the title)"
                Outcome.Done("checking for an update as ${DebugPuppet.fakeVersion ?: "this build's own version"}$note: adb logcat -s Updates")
            }

            is PuppetCommand.Invalid -> Outcome.Refused(command.reason)
        }
    }

    // ---- autoplay ----------------------------------------------------------------------------

    /** What autoplay is waiting to act on. */
    private sealed interface Cue {
        /** The fight's menu is up for [fight]'s turn. */
        data class Menu(val fight: FightState) : Cue

        /** A card that waits for Continue: results (a fight's or a training game's), an unlock or a learn screen, or a pantry's reaction. */
        data class Card(val screen: Screen) : Cue
    }

    private fun cueOf(s: UiState): Cue? {
        if (s.busy) return null
        return when (val screen = s.screen) {
            is Screen.Fight -> if (screen.fight.winner == null && screen.pvp?.waiting != true) Cue.Menu(screen.fight) else null
            is Screen.Results, is Screen.Unlocked, is Screen.Learn -> Cue.Card(screen)
            // A training game is played by hand (or ended with `score`); only its results card waits for Continue.
            is Screen.Training -> if (screen.result != null) Cue.Card(screen) else null
            // The pantry is cooked and fed by hand (or with `cook` and `serve`); only the reaction after the meal waits for Continue.
            is Screen.Pantry -> if (screen.fed != null) Cue.Card(screen) else null
            else -> null
        }
    }

    /** Autoplays from now until it is turned off: each time the game waits on the player, it acts after a moment. */
    private fun startAutoplay() {
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            // A new cue (the turn played, the card went) cancels whatever the last one was still waiting to do.
            vm.state.map(::cueOf).distinctUntilChanged().collectLatest { cue ->
                when (cue) {
                    is Cue.Menu -> {
                        delay(MENU_LOOK_MS)
                        val choice = autoplayPick(cue.fight)
                        // Logged first: the turn starting as the pick is taken cancels this block.
                        Log.i(TAG, "Autoplay, turn ${cue.fight.turn}: ${choice.move} (${choice.why})")
                        pick(choice.move)
                    }
                    is Cue.Card -> {
                        delay(if (cue.screen is Screen.Pantry) MEAL_HOLD_MS else CARD_HOLD_MS)
                        when (val card = cue.screen) {
                            is Screen.Results -> vm.onResultsDone()
                            is Screen.Unlocked -> vm.onUnlockedDone()
                            is Screen.Training -> vm.onTrainingDone(card.session)
                            is Screen.Pantry -> vm.onPantryDone(card.session)
                            is Screen.Learn -> {
                                Log.i(TAG, "Autoplay: learns ${attackById(card.attack)?.name ?: card.attack}")
                                vm.onLearn(learn = true)
                            }
                            else -> Unit
                        }
                    }
                    null -> Unit
                }
            }
        }
    }

    /**
     * Picks [move] through the fight screen, whose cursor goes to it first, or straight through the view model when
     * the screen has not taken it in a moment. Says which, for the log.
     */
    private suspend fun pick(move: Move): String {
        picks.emit(move)
        val taken = withTimeoutOrNull(PICK_TAKE_MS) {
            vm.state.first { it.busy || it.screen !is Screen.Fight || (it.screen as Screen.Fight).pvp?.waiting == true }
        }
        if (taken != null) return "picked on the menu"
        Log.w(TAG, "The fight screen did not take $move: playing it directly")
        vm.onMove(move)
        return "played directly"
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun state(): UiState = vm.state.value

    /** Waits for the action playing (and the loading) to end; false when it has not in [IDLE_WAIT_MS]. */
    private suspend fun awaitIdle(): Boolean =
        withTimeoutOrNull(IDLE_WAIT_MS) { vm.state.first { !it.busy && it.screen != Screen.Loading } } != null

    private fun activePet(): PetState? = state().game.let { g -> g.selected?.let { g.pets[it] } }

    /** Applies [change] through the view model; [what] then says what came of it, from the new state. */
    private fun edit(what: (UiState) -> String, change: (UiState) -> UiState): Outcome =
        if (vm.debugEdit(change)) Outcome.Done(what(state())) else Outcome.Refused("the game is busy")

    /** Why the game cannot be taken off the screen it is on, or null when it can. */
    private fun leavingRefusal(): String? = when (val screen = state().screen) {
        Screen.Loading -> "the save is still loading"
        is Screen.Fight -> "in a fight: finish it first (autoplay on, or move run)"
        is Screen.PvpLobby -> "in the PvP lobby: home first"
        is Screen.Versus -> if (screen.pvp) "on a PvP VS card: home first" else null
        else -> null
    }

    /**
     * Takes the game Home the way a player would, screen by screen: past the title, out of a menu, through the cards
     * after a fight, putting off a learn screen until the next launch (and past a fake death). Not out of a fight.
     * Returns why it could not, or null once Home is up.
     */
    private fun goHome(): String? {
        repeat(MAX_STEPS_HOME) {
            val s = state()
            if (s.busy) return "an action is playing"
            when (val screen = s.screen) {
                Screen.Home -> if (s.stableOpen) vm.closeStable() else return null
                Screen.Loading -> return "the save is still loading"
                Screen.Title -> vm.onStart()
                is Screen.Switcher -> if (screen.mode == SwitcherMode.ONBOARD) return NO_PET else vm.onSwitcherBack()
                is Screen.FakeDeath -> vm.onFakeDeathDone()
                is Screen.Versus -> vm.onVersusBack()
                is Screen.PvpLobby -> vm.onLobbyBack()
                is Screen.Learn -> vm.onLearnLater()
                Screen.Moves -> vm.onMovesBack()
                is Screen.Training -> if (screen.result == null) vm.onTrainingQuit(screen.session) else vm.onTrainingDone(screen.session)
                is Screen.Pantry -> if (screen.fed == null) vm.onPantryBack(screen.session) else vm.onPantryDone(screen.session)
                is Screen.Fight -> return "in a fight: finish it first (autoplay on, or move run)"
                is Screen.Unlocked -> vm.onUnlockedDone()
                is Screen.Results -> vm.onResultsDone()
            }
        }
        return "could not get Home from ${describe(state())}"
    }

    /**
     * TRAIN for [pet], from Home: opens training on [game] when it is given, else on the one the pick makes. Nothing
     * is paid until the game ends (see `score`). A [game] that needs a stable mate the stable does not have is
     * refused, changing nothing.
     */
    private fun openTraining(pet: PetState, game: TrainingGame?): Outcome {
        if (game != null) {
            if (game !in eligibleGames(hasMate = pickMate(state().game, Random.Default) != null)) {
                return Outcome.Refused("${game.title} needs another killer in the stable: unlock_all first")
            }
            vm.debugNextTraining = game
        }
        vm.onCare(CareAction.TRAIN)
        vm.debugNextTraining = null // for the case Home did not take it
        val after = state()
        return when {
            after.refuse != null -> Outcome.Refused("${pet.id.displayName} turned it down: ${after.refuse}")
            after.screen !is Screen.Training -> Outcome.Refused("Home did not take it")
            else -> Outcome.Done(describe(after))
        }
    }

    /**
     * FEED, from Home: opens the pantry, its shelf topped up as for a player. Nothing is eaten until `serve`, and a pet too
     * full to eat can still cook.
     */
    private fun openPantry(): Outcome {
        vm.onCare(CareAction.FEED)
        val after = state()
        return if (after.screen is Screen.Pantry) Outcome.Done(describe(after)) else Outcome.Refused("Home did not take it")
    }

    /** Why the pantry can not cook or serve now, or null when it can: it is not on screen, or the killer has eaten already. */
    private fun pantryRefusal(): String? = when (val screen = state().screen) {
        is Screen.Pantry -> if (screen.fed != null) "the killer has eaten already: home, or feed for a new visit" else null
        else -> "not in the pantry: feed first"
    }

    /**
     * The shelf place of each of [wanted], in order: the first place that holds it and that no item before it took, so
     * the same id twice is two different places. Null where the shelf has none left.
     */
    private fun placesOnShelf(wanted: List<PantryItem>): List<Int?> {
        val shelf = state().game.pantryItems()
        val taken = mutableSetOf<Int>()
        return wanted.map { item -> shelf.indices.firstOrNull { it !in taken && shelf[it] == item }?.also { taken += it } }
    }

    /**
     * Why [wanted] can not all be found on the shelf, [missing] being the first that is short: it is not there at all,
     * or (the same item twice, as `cook grill grill`) there is only one.
     */
    private fun notEnoughOnShelf(wanted: List<PantryItem>, missing: PantryItem): String {
        val need = wanted.count { it == missing }
        val have = state().game.pantryItems().count { it == missing }
        return if (have == 0) "${missing.id} is not on the shelf" else "that cooks $need ${missing.id}, and the shelf has $have"
    }

    /** Puts exactly [places] on the pantry's board, in that order, through its picks: whatever stood there comes off first. */
    private fun putOnBoard(places: List<Int>) {
        val pantry = state().screen as? Screen.Pantry ?: return
        pantry.board.forEach { vm.onPantryPick(pantry.session, it) }
        places.forEach { vm.onPantryPick(pantry.session, it) }
    }

    /** "Freddy, level 14": the active pet in [s]. */
    private fun petLine(s: UiState): String {
        val pet = s.game.selected?.let { s.game.pets[it] } ?: return "no pet"
        return "${pet.id.displayName}, level ${pet.level}"
    }

    /** ", now on <screen>" when [s] is not Home, as when the active pet has a learn screen or its fake death due. */
    private fun screenNote(s: UiState): String = if (s.screen == Screen.Home) "" else ", now on ${describe(s)}"

    /** What a play changed: the pet's fun (FEED opens the pantry and TRAIN a game, which report their own). */
    private fun playChange(before: PetState, after: PetState): String =
        "play, fun ${before.funLevel.toInt()} -> ${after.funLevel.toInt()}" +
            if (after.level > before.level) ", now level ${after.level}" else ""

    private companion object {
        /** More than any trip Home takes: a learn screen for each attack a big level jump unlocks, then the rest. */
        const val MAX_STEPS_HOME = 20
    }
}

/** Where the game is, in a few words, for the log. */
private fun describe(s: UiState): String = when (val screen = s.screen) {
    Screen.Loading -> "loading"
    Screen.Title -> "title"
    is Screen.Switcher -> if (screen.mode == SwitcherMode.ONBOARD) "starter pick" else "opponent pick"
    is Screen.FakeDeath -> "fake death of ${screen.pet.displayName}"
    Screen.Home -> if (s.stableOpen) "Home, stable open" else "Home"
    is Screen.Versus -> "VS card vs ${screen.opponent.displayName}${if (screen.pvp) " (PvP)" else ""}"
    is Screen.PvpLobby -> "PvP lobby"
    is Screen.Learn -> "learn screen for ${attackById(screen.attack)?.name ?: screen.attack}"
    Screen.Moves -> "moves"
    is Screen.Training -> "training: ${screen.game.title}" +
        (screen.mate?.let { ", mate ${it.displayName}" } ?: "") +
        (screen.result?.let { ", scored ${it.score}" } ?: "")
    is Screen.Pantry -> "pantry: ${s.game.pantry.size} items" + (screen.fed?.let { ", fed ${it.item.id}" } ?: "")
    is Screen.Fight -> screen.fight.let { f ->
        "fight vs ${f.cpu.id.displayName}${if (screen.pvp != null) " (PvP)" else ""}, turn ${f.turn}, " +
            "HP ${f.player.hp}/${f.player.maxHp} vs ${f.cpu.hp}/${f.cpu.maxHp}, meter ${f.player.meter}"
    }
    is Screen.Results -> {
        val result = when {
            screen.ran -> "ran"
            screen.won -> "won"
            else -> "lost"
        }
        "results: $result${screen.fight?.let { " vs ${it.cpu.id.displayName} in ${it.turnsPlayed} turns" } ?: ""}, +${screen.reward.xpGained} XP"
    }
    is Screen.Unlocked -> "${screen.pet.displayName} unlocked"
}
