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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.audio.SoundSink
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
 * [sounds] as they fill.
 */
@Composable
fun HorrorPetsApp(vm: GameViewModel, loader: AssetLoader, sounds: SoundSink, onExit: () -> Unit) {
    val state by vm.state.collectAsState()

    LaunchedEffect(vm, sounds) {
        vm.state.collect { s ->
            if (s.sfx.isEmpty() && s.voice.isEmpty()) return@collect
            s.sfx.forEach(sounds::sfx)
            if (s.voice.isNotEmpty()) sounds.voice(s.voice)
            vm.consumeSounds()
        }
    }

    AnimatedContent(
        targetState = state.screen,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(SCREEN_FADE_MS)) togetherWith fadeOut(tween(SCREEN_FADE_MS)) },
        modifier = Modifier.fillMaxSize().background(Ink),
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Loading -> Box(Modifier.fillMaxSize().background(Ink))

            is Screen.Switcher -> SwitcherScreen(
                mode = screen.mode,
                game = state.game,
                loader = loader,
                onPick = vm::onSwitcherPick,
                onBack = vm::onSwitcherBack,
                onExit = onExit,
            )

            is Screen.FakeDeath -> FakeDeathScreen(pet = screen.pet, onDone = vm::onFakeDeathDone)

            Screen.Home -> HomeScreen(
                state = state,
                loader = loader,
                onCare = vm::onCare,
                onWalk = vm::onWalk,
                onFight = vm::onFightPressed,
                onOpenSwitcher = vm::openSwitcher,
                onAnimFinished = vm::onAnimFinished,
                onToastDone = vm::consumeToast,
                onExit = onExit,
            )

            is Screen.Versus -> VersusScreen(
                player = state.game.selected ?: screen.opponent,
                opponent = screen.opponent,
                onDone = vm::onVersusDone,
            )

            is Screen.Fight -> FightScreen(
                screen = screen,
                petAnim = state.petAnim,
                onMove = vm::onMove,
                onAnimFinished = vm::onAnimFinished,
            )

            is Screen.Results -> ResultsCard(screen = screen, onDone = vm::onResultsDone)
        }
    }
}
