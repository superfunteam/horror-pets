package team.superfun.horrorpets.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.fakedeath.FakeDeathScreen
import team.superfun.horrorpets.ui.fight.FightScreen
import team.superfun.horrorpets.ui.fight.ResultsCard
import team.superfun.horrorpets.ui.fight.VersusScreen
import team.superfun.horrorpets.ui.home.HomeScreen
import team.superfun.horrorpets.ui.switcher.SwitcherScreen
import team.superfun.horrorpets.ui.theme.Ink

/** Cross-fade between screens. */
private const val SCREEN_FADE_MS = 280

/**
 * The app's screen router: renders [GameViewModel.state] and wires every screen to the view model.
 *
 * Screens are keyed by their type, so a screen whose state changes in place (a fight turn) stays
 * mounted, while moving between screens cross-fades. The view model's sound queues are drained into
 * [sounds] as they fill, and screens reach the same sink through [LocalSoundSink] for the sounds they
 * time themselves.
 */
@Composable
fun HorrorPetsApp(vm: GameViewModel, loader: AssetLoader, sounds: SoundSink, onExit: () -> Unit) {
    val state by vm.state.collectAsState()

    // Back can arrive between a state change and the recomposition that shows it (Menu then Back in
    // quick succession): only leave the app if the view model is really still on the exiting screen.
    val exitFromHome = { if (vm.state.value.screen == Screen.Home) onExit() }
    val exitFromOnboarding = {
        if (vm.state.value.screen == Screen.Switcher(SwitcherMode.ONBOARD)) onExit()
    }

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

    CompositionLocalProvider(LocalSoundSink provides sounds) {
        AnimatedContent(
            targetState = state.screen,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(SCREEN_FADE_MS)) togetherWith fadeOut(tween(SCREEN_FADE_MS)) },
            modifier = Modifier.fillMaxSize().background(Ink),
            label = "screen",
        ) { screen ->
            val focusEntry: Any? = if (screen::class == state.screen::class) entry else null
            when (screen) {
                // The router's own Ink fill (on AnimatedContent) is the only app background; screens draw over it.
                Screen.Loading -> Box(Modifier.fillMaxSize())

                is Screen.Switcher -> SwitcherScreen(
                    mode = screen.mode,
                    game = state.game,
                    loader = loader,
                    onPick = vm::onSwitcherPick,
                    onBack = vm::onSwitcherBack,
                    onExit = exitFromOnboarding,
                    focusEntry = focusEntry,
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
                    onOpenSwitcher = vm::openSwitcher,
                    onAnimFinished = vm::onAnimFinished,
                    onToastDone = vm::consumeToast,
                    onExit = exitFromHome,
                    focusEntry = focusEntry,
                )

                is Screen.Versus -> VersusScreen(
                    player = state.game.selected ?: screen.opponent,
                    opponent = screen.opponent,
                    onDone = vm::onVersusDone,
                    focusEntry = focusEntry,
                )

                is Screen.Fight -> FightScreen(
                    screen = screen,
                    petAnim = state.petAnim,
                    onMove = vm::onMove,
                    onAnimFinished = vm::onAnimFinished,
                    focusEntry = focusEntry,
                )

                is Screen.Results -> ResultsCard(screen = screen, onDone = vm::onResultsDone, focusEntry = focusEntry)
            }
        }
    }
}

/** Hands out increasing numbers; one per screen entry. */
private class EntryCounter {
    private var last = 0

    fun next(): Int = ++last
}
