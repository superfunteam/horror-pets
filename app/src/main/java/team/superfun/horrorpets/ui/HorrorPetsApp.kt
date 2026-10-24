package team.superfun.horrorpets.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.pantryItems
import team.superfun.horrorpets.model.visiblePets
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.fakedeath.FakeDeathScreen
import team.superfun.horrorpets.ui.fight.ChallengerScreen
import team.superfun.horrorpets.ui.fight.UnlockedScreen
import team.superfun.horrorpets.ui.title.TitleScreen
import team.superfun.horrorpets.ui.fight.FightScreen
import team.superfun.horrorpets.ui.fight.ResultsCard
import team.superfun.horrorpets.ui.fight.VersusScreen
import team.superfun.horrorpets.ui.pvp.PvpLobbyScreen
import team.superfun.horrorpets.ui.attacks.LearnScreen
import team.superfun.horrorpets.ui.attacks.MovesScreen
import team.superfun.horrorpets.model.attackById
import team.superfun.horrorpets.model.kitOf
import team.superfun.horrorpets.ui.fight.arenaScene
import team.superfun.horrorpets.ui.home.HomeScreen
import team.superfun.horrorpets.ui.pantry.PantryScreen
import team.superfun.horrorpets.ui.switcher.SwitcherScreen
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.training.TrainingScreen
import team.superfun.horrorpets.update.UpdateActions
import team.superfun.horrorpets.update.UpdateStep

/** Cross-fade between screens. */
private const val SCREEN_FADE_MS = 280

/**
 * The app's screen router: renders [GameViewModel.state] and wires every screen to the view model.
 *
 * Screens are keyed by their type, so a screen whose state changes in place (a fight turn) stays
 * mounted, while moving between screens cross-fades. The view model's sound queues are drained into
 * [sounds] as they fill, and screens reach the same sink through [LocalSoundSink] for the sounds they
 * time themselves. [scriptedPicks] are moves picked for the player by a debug build's puppet (see
 * `FightScreen`); a release build's never has any. [showFightHud] off hides the fight screen's HUD, which only a
 * debug build's puppet does (`hud off`, for clean footage). [update] is the update window's step, shown over the title
 * only, its buttons doing [updateActions] (see `UpdateController`).
 */
@Composable
fun HorrorPetsApp(
    vm: GameViewModel,
    loader: AssetLoader,
    sounds: SoundSink,
    onExit: () -> Unit,
    scriptedPicks: Flow<Move> = emptyFlow(),
    showFightHud: Boolean = true,
    update: UpdateStep = UpdateStep.None,
    updateActions: UpdateActions = UpdateActions.None,
) {
    val state by vm.state.collectAsState()

    // Back can arrive between a state change and the recomposition that shows it (Menu then Back in
    // quick succession): only leave the app if the view model is really still on the exiting screen.
    val exitFromHome = { if (vm.state.value.screen == Screen.Home && !vm.state.value.stableOpen) onExit() }
    val exitFromOnboarding = {
        if (vm.state.value.screen == Screen.Switcher(SwitcherMode.ONBOARD)) onExit()
    }
    val exitFromTitle = { if (vm.state.value.screen == Screen.Title) onExit() }

    LaunchedEffect(vm, sounds) {
        vm.state.collect { s ->
            if (s.sfx.isEmpty() && s.voice.isEmpty()) return@collect
            s.sfx.forEach(sounds::sfx)
            if (s.voice.isNotEmpty()) sounds.voice(s.voice)
            vm.consumeSounds()
        }
    }

    // A fresh value each time the router switches screens (see RequestFocusOnEntry). A screen gets it only
    // while it is the target, so one that is fading out never pulls focus back from the one fading in.
    val entries = remember { EntryCounter() }
    val entry = remember(state.screen::class) { entries.next() }

    // The fight's arena is a random scene of the opponent: rolled when its VS card comes up, then kept
    // through every turn (and an Activity recreation) until the next VS card. VS, Fight and Results read it.
    var arenaRoll by rememberSaveable { mutableIntStateOf(0) }
    val versusEntry = if (state.screen is Screen.Versus) entry else null
    LaunchedEffect(versusEntry) {
        if (versusEntry != null) arenaRoll = Random.nextInt(Int.MAX_VALUE)
    }

    // The player's pet as it went into the fight, and who it fought, for the results card's
    // before -> after and its backdrop. Updated all through the fight, never after it.
    var lastFight by remember { mutableStateOf<FightMemo?>(null) }
    val fightScreen = state.screen as? Screen.Fight
    val fightingPet = fightScreen?.let { state.game.pets[it.fight.player.id] }
    LaunchedEffect(fightingPet, fightScreen?.fight?.cpu?.id) {
        if (fightScreen != null && fightingPet != null) lastFight = FightMemo(fightingPet, fightScreen.fight.cpu.id)
    }

    CompositionLocalProvider(LocalSoundSink provides sounds) {
        AnimatedContent(
            targetState = state.screen,
            contentKey = { it::class },
            transitionSpec = {
                // The challenger's entrance cuts to black on its first note, with no fade.
                if (targetState is Screen.Challenger) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(tween(SCREEN_FADE_MS)) togetherWith fadeOut(tween(SCREEN_FADE_MS))
                }
            },
            modifier = Modifier.fillMaxSize().background(Ink),
            label = "screen",
        ) { screen ->
            val focusEntry: Any? = if (screen::class == state.screen::class) entry else null
            when (screen) {
                // The router's own Ink fill (on AnimatedContent) is the only app background; screens draw over it.
                Screen.Loading -> Box(Modifier.fillMaxSize())

                Screen.Title -> TitleScreen(
                    loader = loader,
                    onStart = vm::onStart,
                    onExit = exitFromTitle,
                    focusEntry = focusEntry,
                    update = update,
                    updateActions = updateActions,
                )

                is Screen.Switcher -> SwitcherScreen(
                    mode = screen.mode,
                    game = state.game,
                    loader = loader,
                    onPick = vm::onSwitcherPick,
                    onBack = vm::onSwitcherBack,
                    onExit = exitFromOnboarding,
                    focusEntry = focusEntry,
                    pvp = vm.pvpAvailable,
                    onPvp = vm::onPvpPicked,
                )

                is Screen.FakeDeath -> FakeDeathScreen(
                    pet = screen.pet,
                    game = state.game,
                    loader = loader,
                    onDone = vm::onFakeDeathDone,
                    focusEntry = focusEntry,
                )

                Screen.Home -> HomeScreen(
                    state = state,
                    loader = loader,
                    onCare = vm::onCare,
                    onWalk = vm::onWalk,
                    onFight = vm::onFightPressed,
                    onMoves = vm::onMovesPressed,
                    onOpenStable = vm::openStable,
                    onCloseStable = vm::closeStable,
                    onStablePick = vm::onStablePick,
                    onAnimFinished = vm::onAnimFinished,
                    onToastDone = vm::consumeToast,
                    onExit = exitFromHome,
                    focusEntry = focusEntry,
                )

                is Screen.Challenger -> ChallengerScreen(
                    pet = screen.pet,
                    session = screen.session,
                    loader = loader,
                    onDone = vm::onChallengerDone,
                    focusEntry = focusEntry,
                )

                is Screen.Versus -> VersusScreen(
                    player = state.game.selected ?: screen.opponent,
                    opponent = screen.opponent,
                    loader = loader,
                    arenaRoll = arenaRoll,
                    onDone = vm::onVersusDone,
                    onBack = vm::onVersusBack,
                    focusEntry = focusEntry,
                )

                is Screen.Learn -> {
                    val pet = state.game.pets[screen.pet]
                    val attack = attackById(screen.attack)
                    if (pet != null && attack != null) {
                        LearnScreen(
                            pet = pet,
                            attack = attack,
                            current = kitOf(pet).of(attack.slot),
                            loader = loader,
                            onLearn = { vm.onLearn(learn = true) },
                            onKeep = { vm.onLearn(learn = false) },
                            onLater = vm::onLearnLater,
                            focusEntry = focusEntry,
                        )
                    }
                }

                Screen.Moves -> {
                    val pet = state.game.selected?.let { state.game.pets[it] }
                    if (pet != null) MovesScreen(pet = pet, loader = loader, onEquip = vm::onEquip, onBack = vm::onMovesBack, focusEntry = focusEntry)
                }

                is Screen.Training -> {
                    val pet = state.game.selected?.let { state.game.pets[it] }
                    if (pet != null) {
                        TrainingScreen(
                            screen = screen,
                            pet = pet,
                            mate = screen.mate?.let { state.game.pets[it] },
                            loader = loader,
                            onOver = vm::onTrainingOver,
                            onDone = vm::onTrainingDone,
                            onQuit = vm::onTrainingQuit,
                            focusEntry = focusEntry,
                        )
                    }
                }

                is Screen.Pantry -> {
                    val pet = state.game.selected?.let { state.game.pets[it] }
                    if (pet != null) {
                        PantryScreen(
                            screen = screen,
                            pet = pet,
                            shelf = remember(state.game.pantry) { state.game.pantryItems() },
                            loader = loader,
                            onPick = vm::onPantryPick,
                            onCook = vm::onPantryCook,
                            onFeed = vm::onPantryFeed,
                            onDone = vm::onPantryDone,
                            onBack = vm::onPantryBack,
                            focusEntry = focusEntry,
                        )
                    }
                }

                is Screen.PvpLobby -> PvpLobbyScreen(
                    phase = screen.phase,
                    onHost = vm::onLobbyHost,
                    onJoin = vm::onLobbyJoin,
                    onDigit = vm::onLobbyDigit,
                    onErase = vm::onLobbyErase,
                    onTapCode = vm::onTapCode,
                    onBack = vm::onLobbyBack,
                    focusEntry = focusEntry,
                )

                is Screen.Fight -> FightScreen(
                    screen = screen,
                    petAnim = state.petAnim,
                    loader = loader,
                    arenaRoll = arenaRoll,
                    onMove = vm::onMove,
                    onAnimFinished = vm::onAnimFinished,
                    focusEntry = focusEntry,
                    isBusy = { vm.state.value.busy },
                    scriptedPicks = scriptedPicks,
                    showHud = showFightHud,
                )

                is Screen.Unlocked -> UnlockedScreen(
                    pet = screen.pet,
                    unlocked = state.game.pets.size,
                    total = state.game.visiblePets().size,
                    loader = loader,
                    arena = lastFight?.let { arenaScene(loader, it.opponent, arenaRoll) },
                    onDone = vm::onUnlockedDone,
                    focusEntry = focusEntry,
                )

                is Screen.Results -> {
                    val memo = lastFight?.takeIf { it.pet.id == screen.reward.pet.id }
                    ResultsCard(
                        screen = screen,
                        before = memo?.pet,
                        loader = loader,
                        arena = memo?.let { arenaScene(loader, it.opponent, arenaRoll) },
                        onDone = vm::onResultsDone,
                        focusEntry = focusEntry,
                    )
                }
            }
        }
    }
}

/** The pet that went into the last fight, as it was during it, and the opponent it fought. */
@Immutable
private class FightMemo(val pet: PetState, val opponent: PetId)

/** Hands out increasing numbers; one per screen entry. */
private class EntryCounter {
    private var last = 0

    fun next(): Int = ++last
}
