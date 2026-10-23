package team.superfun.horrorpets.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.SceneSpec
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.game.training.bestAt
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.theme.Ink

/** Once the reel hands over, it fades off the game this fast. */
private const val REEL_FADE_MS = 300L

/**
 * Back from being away (the TV's Home button, a call, the screen off): "GET READY" this long, then the game goes on.
 */
private const val RESUME_BEAT_MS = 1_000L

/**
 * What a training game plays with, from the shell ([TrainingScreen]):
 * - [loader] for its art; [pet], the selected killer as it went into the game; [mate], the stable mate the game puts on
 *   screen (set for a game that `needsMate`, null otherwise); [scene], the pet's Home scene, which the shell already
 *   draws behind the game, its floor at [floorY];
 * - [best], the pet's best in this game before this run, for the HUD;
 * - [clock], the game's time: a game waits and animates on it ([GameClock.nowMs], [GameClock.waitMs]), never on `delay`
 *   or the frame clock, so it stops dead while the app is away and through the "GET READY" beat after, and while its
 *   results card is up. It has been running since the reel started (`nowMs` is already about 4800 as a game begins),
 *   so a game measures its time from its own start: `nowMs` as it begins. While held, the shell also keeps every
 *   training key's press and every touch from the game;
 * - [sounds], where it plays its sounds: until the results card, the training screen's key presses make no UI ticks
 *   but the ones a game plays;
 * - [focus]: the game puts `Modifier.focusRequester(focus)` on a focusable element from its first composition, with
 *   [trainingKeys] on it (or inside it), and the shell gives it focus as the game starts;
 * - [score]: the game keeps it current as it plays, since Back ends a game early with the score so far.
 *
 * A game fills the play area (the frame between the film's edges, where the scene is) and draws its [TrainingHud] over
 * itself. When it ends itself it calls its `onOver(score)`, once, and takes no more input after.
 */
class TrainingEnv(
    val loader: AssetLoader,
    val pet: PetState,
    val mate: PetState?,
    val scene: SceneSpec,
    val best: Int,
    val clock: GameClock,
    val sounds: SoundSink,
    val focus: FocusRequester,
    val score: MutableIntState,
) {
    /**
     * The scene's floor line, in px from the top of the play area: where a pet's feet go (see `SpriteView` and
     * `placeAt`), as the shell's scene places it. Known before a game is first composed; it moves only if the play area
     * is resized.
     */
    var floorY: Float by mutableFloatStateOf(Float.NaN)
        internal set
}

/**
 * TRAIN: [screen]'s game on [pet]'s scene, inside training's film ([FilmFrame]), against its stable [mate] when the
 * game uses one. In order:
 * - the reel ([TrainingReel]) spins through the games on offer and lands on the one picked; Back leaves with nothing
 *   gained ([onQuit]);
 * - the game plays on the training clock, which stops while the app is away and for a "GET READY" beat after it comes
 *   back; it ends itself, or Back ends it, with its score ([onOver], reported once);
 * - its results card ([ResultsCard], once [Screen.Training.result] is set) shows over the frozen game until OK, a tap
 *   or Back carries on Home ([onDone]).
 *
 * Each entry ([focusEntry], see `RequestFocusOnEntry`) is a run of its own, from the reel.
 */
@Composable
fun TrainingScreen(
    screen: Screen.Training,
    pet: PetState,
    mate: PetState?,
    loader: AssetLoader,
    onOver: (Int) -> Unit,
    onDone: () -> Unit,
    onQuit: () -> Unit,
    focusEntry: Any?,
) {
    // The router can bring this screen back while it is still fading out (CONTINUE, then TRAIN at once); that is a new
    // run from the reel, not the last run's results. While it fades out (no entry) the run it was on stays.
    val entry = remember { arrayOfNulls<Any>(1) }
    if (focusEntry != null) entry[0] = focusEntry
    key(entry[0]) {
        TrainingRun(screen, pet, mate, loader, onOver, onDone, onQuit, focusEntry)
    }
}

@Composable
private fun TrainingRun(
    screen: Screen.Training,
    pet: PetState,
    mate: PetState?,
    loader: AssetLoader,
    onOver: (Int) -> Unit,
    onDone: () -> Unit,
    onQuit: () -> Unit,
    focusEntry: Any?,
) {
    val result = screen.result
    val sounds = LocalSoundSink.current
    val latestOnOver by rememberUpdatedState(onOver)

    // Fixed as the run starts: the pet pays as the game ends, and its HUD and scene must not move under the card.
    val scene = remember { loader.scenes().getValue(pet.id).let { it[pet.sceneIndex.mod(it.size)] } }
    val spin = remember { ReelSpin.of(screen.options, screen.game) }
    // Opened on a game already paid for (the Activity was recreated on its card): no reel and no game, just the card.
    val paidOnEntry = remember { result != null }

    var reelLoaded by remember { mutableStateOf(false) }
    var gameOn by remember { mutableStateOf(false) }
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var ended by remember { mutableStateOf(false) }

    val away = rememberAwayPause()
    val clock = rememberGameClock(
        paused = away.paused || result != null || focusEntry == null || !(reelLoaded || gameOn),
    )
    val focus = remember { FocusRequester() }
    val score = remember { mutableIntStateOf(0) }
    val env = remember { TrainingEnv(loader, pet, mate, scene, pet.bestAt(screen.game), clock, sounds, focus, score) }
    val finish: (Int) -> Unit = remember {
        { s ->
            if (!ended) {
                ended = true
                latestOnOver(s)
            }
        }
    }

    GameBackHandler(enabled = focusEntry != null) {
        when {
            screen.result != null -> onDone()
            !gameOn -> onQuit()
            else -> finish(score.intValue) // once: after the game has ended, Back waits for its card
        }
    }

    val rootFocus = remember { FocusRequester() }
    RequestFocusOnEntry(rootFocus, focusEntry)
    val gameShown = gameOn && !env.floorY.isNaN()
    LaunchedEffect(gameShown) {
        if (gameShown && focusEntry != null && screen.result == null) focus.requestFocus()
    }

    val reelShown by remember {
        derivedStateOf { !paidOnEntry && (!gameOn || clock.nowMs < gameStartMs + REEL_FADE_MS) }
    }

    // While held (away, then the GET READY beat) the game's clock is frozen, so it gets no presses either.
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { swallowedWhileHeld(it.key, it.type, held = away.paused && result == null) }
            .focusable(),
    ) {
        RunningFilm(run = { if (gameOn || paidOnEntry) spin.total.toFloat() else spin.position(clock.nowMs) }) {
            SceneBackdrop(loader, scene) { floorY -> SideEffect { env.floorY = floorY } }
            if (gameShown) TrainingGameView(screen.game, env, finish)
            // Gone under the card, even mid-fade (a game ended at once) or mid-spin (the debug puppet's score).
            if (reelShown && result == null) {
                TrainingReel(
                    options = screen.options,
                    landOn = screen.game,
                    loader = loader,
                    scene = scene,
                    best = env.best,
                    clock = clock,
                    onLoaded = { reelLoaded = true },
                    onReady = {
                        if (!gameOn && focusEntry != null) {
                            gameStartMs = clock.nowMs
                            gameOn = true
                        }
                    },
                    modifier = Modifier.graphicsLayer {
                        alpha = if (!gameOn) 1f else 1f - ((clock.nowMs - gameStartMs) / REEL_FADE_MS.toFloat()).coerceIn(0f, 1f)
                    },
                )
            }
            if (away.beat && result == null) GetReady()
            if (result != null) ResultsCard(screen.game, result, onDone, focusEntry)
        }
    }
}

/** The film, its [run] read here so that only this recomposes as the reel spins. */
@Composable
private fun RunningFilm(run: () -> Float, content: @Composable BoxScope.() -> Unit) = FilmFrame(run(), content)

/** [game]'s own composable. */
@Composable
private fun TrainingGameView(game: TrainingGame, env: TrainingEnv, onOver: (Int) -> Unit) {
    when (game) {
        TrainingGame.SLASH_SAYS -> SlashSaysGame(env, onOver)
        TrainingGame.QUICK_DRAW -> QuickDrawGame(env, onOver)
        TrainingGame.HIDE_AND_SEEK -> HideAndSeekGame(env, onOver)
        TrainingGame.ORGAN_DROP -> OrganDropGame(env, onOver)
    }
}

/** "GET READY" over the held game as the app comes back; the game gets no touch through it. */
@Composable
private fun GetReady() {
    Box(Modifier.fillMaxSize().swallowTouches().background(Ink.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        GlowText("GET READY", displayStyle(46.sp, color = TrainingGold, tracking = 0.06f, lineHeight = 48.sp))
    }
}

/**
 * Whether the app is [away] (paused: the TV's Home button, a call, the screen going off) and the "GET READY" [beat]
 * after it comes back; training holds through both ([paused]).
 */
@Stable
private class AwayPause {
    var away by mutableStateOf(false)
    var beat by mutableStateOf(false)

    /** How many times the app has come back, to start each beat. */
    var returns by mutableIntStateOf(0)

    val paused: Boolean get() = away || beat
}

/**
 * The Activity's comings and goings as an [AwayPause]: ON_PAUSE is away, ON_RESUME after it starts a [RESUME_BEAT_MS]
 * beat.
 */
@Composable
private fun rememberAwayPause(): AwayPause {
    val pause = remember { AwayPause() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> pause.away = true
                Lifecycle.Event.ON_RESUME -> if (pause.away) {
                    pause.away = false
                    pause.beat = true
                    pause.returns++
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(pause.returns) {
        if (pause.returns == 0) return@LaunchedEffect
        delay(RESUME_BEAT_MS)
        pause.beat = false
    }
    return pause
}
