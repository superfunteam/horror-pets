package team.superfun.horrorpets.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.assets.feedAnim
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.ui.UiState
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SAFE_INSET_FRACTION
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.safeInsetPadding
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi

/** The pet's whole cell is this tall, as a fraction of the screen height (the same on 4:3 and 16:9). */
const val HOME_PET_HEIGHT = 0.46f

/** How far (fraction of the screen width) the pet runs out before the scene changes, and in after. */
private const val WALK_DISTANCE = 0.30f

/** Idle variety: a glance or a hungry wait every 12–20 s. */
private const val VARIETY_MIN_MS = 12_000L
private const val VARIETY_MAX_MS = 20_000L

private const val BUBBLE_MS = 2_000L

/** A run-out still waiting for its scene change after this long is abandoned (it never should be). */
private const val WALK_STUCK_MS = 1_500L

/** Strips decoded ahead of time on Home (with the pet's feed animation; 8 of the loader's 12 slots with IDLE). */
private val HOME_WARM_ANIMS = listOf(Anim.RUN_RIGHT, Anim.RUN_LEFT, Anim.JUMP, Anim.WAVE, Anim.ATTACK, Anim.FAIL)

/** Arrival: a run that settles gently onto the centre mark instead of braking hard and running in place. */
private val ArriveEasing = CubicBezierEasing(0.1f, 0f, 0.55f, 1f)
private const val SCENE_TITLE_MS = 3_200L

/** The refusal bubble's tail sits this far above the feet, as a fraction of the cell height (just over the head). */
private const val BUBBLE_ABOVE_FEET = 0.91f

/**
 * Home: the selected pet standing in its current scene, with the stat card, scene dots, welcome toast
 * and the action bar over it (the spec's layout A).
 *
 * [focusEntry] is the router's screen-entry key (see `RequestFocusOnEntry`): a new value gives PLAY
 * focus, and null (the screen is fading out) also turns off Back. OK is ignored for a moment after each
 * entry (see `rememberOkReady`), so an OK mashed through the end of a fight cannot fire PLAY.
 *
 * The view model drives the pet through [UiState.petAnim]; this screen plays each request and reports
 * it with [onAnimFinished]. Everything else the pet does is local: idle glances, running out of one
 * scene and into the next, and the refusal bubble.
 */
@Composable
fun HomeScreen(
    state: UiState,
    loader: AssetLoader,
    onCare: (CareAction) -> Unit,
    onWalk: (dir: Int) -> Unit,
    onFight: () -> Unit,
    onOpenSwitcher: () -> Unit,
    onAnimFinished: (token: Long) -> Unit,
    onToastDone: () -> Unit,
    onExit: () -> Unit,
    focusEntry: Any? = Unit,
) {
    val petId = state.game.selected ?: return
    val pet = state.game.pets[petId] ?: return
    val scenes = remember(loader, petId) { loader.scenes().getValue(petId) }
    val sceneIndex = pet.sceneIndex.mod(scenes.size)
    val scene = scenes[sceneIndex]
    val manifest = remember(loader, petId) { loader.manifest(petId) }
    val tint = remember(scene) { hexColor(scene.tint) }

    val playFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    var lastWasFight by remember { mutableStateOf(false) }

    // Only the screen being entered handles Back: a Home that is fading out must never exit the app.
    BackHandler(enabled = focusEntry != null, onBack = onExit)
    RequestFocusOnEntry(playFocus, focusEntry)
    val okReady = rememberOkReady(focusEntry)

    // Warm the strips Home's actions play, so a first press starts at once. The run strips matter most:
    // the walk-out fade is timed from the press, the view model's scene change from the sprite's first
    // frame, and a cold decode between the two would leave an empty scene on screen.
    LaunchedEffect(loader, petId) {
        delay(800)
        withContext(Dispatchers.IO) {
            for (anim in HOME_WARM_ANIMS + manifest.feedAnim()) loader.strip(petId, anim)
        }
    }

    // ---- what the pet is doing -------------------------------------------------------------------
    val scope = rememberCoroutineScope()
    val walkX = remember { Animatable(0f) }
    val walkAlpha = remember { Animatable(1f) }
    val walkJob = remember { JobSlot() }
    var walk by remember { mutableStateOf<Walk?>(null) }
    var variety by remember { mutableStateOf<Local?>(null) }
    var bubble by remember { mutableStateOf<Bubble?>(null) }
    var showSceneTitle by remember { mutableStateOf(false) }

    val request = state.petAnim
    LaunchedEffect(request?.token) {
        if (request == null) return@LaunchedEffect
        variety = null
        if (state.refuse != null) {
            bubble = Bubble(refusalText(state.refuse, lastWasFight))
        }
        val dir = when (request.anim) {
            Anim.RUN_RIGHT -> 1
            Anim.RUN_LEFT -> -1
            else -> 0
        }
        walkJob.cancel()
        if (dir == 0) {
            walk = null
            walkX.snapTo(0f)
            walkAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        // Walking: the toast has done its job, and the location title will need its space.
        if (state.toast != null) onToastDone()
        walk = Walk(dir, request.anim, key = request.token, arriving = false)
        val cycle = manifest.spec(request.anim).durationsMs.sum()
        // Decode the destination while the pet runs, so the cross-fade can start the moment it arrives.
        val destination = scenes[(sceneIndex + dir).mod(scenes.size)]
        scope.launch(Dispatchers.IO) { loader.sceneBitmap(destination) }
        // The run-out deliberately lasts a little longer than the run cycle: the view model changes the
        // scene one cycle after the sprite's first frame, a frame or two after this starts, and the pet
        // should still be fading when the new scene starts to cross-fade in rather than leave a gap.
        walkJob.job = scope.launch {
            launch { walkAlpha.animateTo(0f, tween(cycle * 70 / 100, delayMillis = cycle * 50 / 100)) }
            walkX.animateTo(dir * WALK_DISTANCE, tween(cycle * 120 / 100, easing = FastOutLinearInEasing))
        }
    }

    // The view model moved the pet to the next scene: run in from the other side.
    LaunchedEffect(sceneIndex) {
        val out = walk ?: return@LaunchedEffect
        if (out.arriving) return@LaunchedEffect
        walkJob.cancel()
        val cycle = manifest.spec(out.anim).durationsMs.sum()
        walk = Walk(out.dir, out.anim, out.key, arriving = true)
        showSceneTitle = true
        walkJob.job = scope.launch {
            walkX.snapTo(-out.dir * WALK_DISTANCE)
            walkAlpha.snapTo(0f)
            launch { walkAlpha.animateTo(1f, tween(cycle * 45 / 100)) }
            walkX.animateTo(0f, tween(cycle * 120 / 100, easing = ArriveEasing))
            walk = null
        }
    }
    // Safety net: if a run ever ends without the scene changing, bring the pet back instead of leaving
    // it faded out at the edge.
    LaunchedEffect(walk, request == null) {
        val out = walk ?: return@LaunchedEffect
        if (out.arriving || request != null) return@LaunchedEffect
        delay(WALK_STUCK_MS)
        walkJob.cancel()
        walk = null
        walkX.snapTo(0f)
        walkAlpha.animateTo(1f, tween(250))
    }
    LaunchedEffect(showSceneTitle, sceneIndex) {
        if (showSceneTitle) {
            delay(SCENE_TITLE_MS)
            showSceneTitle = false
        }
    }

    val idle = request == null && walk == null
    val hungry by rememberUpdatedState(pet.hunger >= Tuning.HUNGRY_AT)
    LaunchedEffect(idle, variety, petId) {
        if (!idle || variety != null) return@LaunchedEffect
        delay(Random.nextLong(VARIETY_MIN_MS, VARIETY_MAX_MS + 1))
        val anim = when {
            hungry -> Anim.WAIT
            Random.nextBoolean() -> Anim.LOOK_A
            else -> Anim.LOOK_B
        }
        variety = Local(anim)
    }
    LaunchedEffect(bubble) {
        if (bubble != null) {
            delay(BUBBLE_MS)
            bubble = null
        }
    }

    // Exactly one animation is on screen; the view model's request always wins. A walk keeps playing its
    // run under the request's token from the press until the pet has run into the next scene, so the run
    // never restarts or drops to IDLE at the edge between the request ending and the arrival starting.
    val shownAnim = request?.anim ?: walk?.anim ?: variety?.anim ?: Anim.IDLE
    val playKey: Any = request?.token ?: walk?.key ?: variety ?: IdleKey
    val currentVariety = variety
    val onShownFinished: (() -> Unit)? = when {
        request != null -> {
            { onAnimFinished(request.token) }
        }
        walk == null && currentVariety != null -> {
            { variety = null }
        }
        else -> null
    }

    // No background: the scene covers the screen, and the app root's Ink shows while it loads.
    BoxWithConstraints(Modifier.fillMaxSize().ignoreOkUnless(okReady)) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val petHeightPx = screenH * HOME_PET_HEIGHT
        val cardWidth = (maxWidth * 0.27f).coerceIn(216.dp, 252.dp)
        val inset = safeInsetPadding()
        val toastMax = toastMaxWidth(maxWidth, scenes.size)

        SceneBackdrop(loader = loader, scene = scene) { floorY ->
            SpriteView(
                loader = loader,
                pet = petId,
                anim = shownAnim,
                heightPx = petHeightPx,
                tint = tint,
                tintStrength = scene.tintStrength,
                shadowOpacity = scene.shadowOpacity,
                playKey = playKey,
                onFinished = onShownFinished,
                modifier = Modifier
                    .placeAt(x = { screenW / 2f + walkX.value * screenW }, y = { floorY })
                    .graphicsLayer { alpha = walkAlpha.value },
            )
            val tailPx = with(LocalDensity.current) { (BubbleTailInset + BubbleTailWidth / 2).toPx() }
            RefusalBubble(
                text = bubble?.text,
                // Pinned by its tail, not its middle: the body hangs to the right of the head, away from
                // the stat card, which on 4:3 sits close to the pet.
                modifier = Modifier.placeAt(
                    x = { screenW / 2f + walkX.value * screenW - tailPx },
                    y = { floorY - petHeightPx * manifest.bodyScale * BUBBLE_ABOVE_FEET },
                    pivotX = 0f,
                ),
            )
        }

        Box(Modifier.fillMaxSize().padding(inset)) {
            StatCard(
                pet = pet,
                onOpenSwitcher = onOpenSwitcher,
                width = cardWidth,
                focusRequester = cardFocus,
                modifier = Modifier.align(Alignment.TopStart),
            )
            SceneMarker(
                count = scenes.size,
                index = sceneIndex,
                title = scene.title,
                showTitle = showSceneTitle,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            WelcomeToast(
                state.toast,
                onDismissed = onToastDone,
                modifier = Modifier.align(Alignment.TopEnd).widthIn(max = toastMax),
            )
            ActionBar(
                prevSceneTitle = scenes[(sceneIndex - 1).mod(scenes.size)].title,
                nextSceneTitle = scenes[(sceneIndex + 1).mod(scenes.size)].title,
                onWalk = onWalk,
                onCare = {
                    lastWasFight = false
                    onCare(it)
                },
                onFight = {
                    lastWasFight = true
                    onFight()
                },
                playFocus = playFocus,
                statCardFocus = cardFocus,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private fun refusalText(reason: RefuseReason, fight: Boolean): String = when (reason) {
    RefuseReason.TOO_FULL -> "Too full!"
    RefuseReason.TOO_HUNGRY -> if (fight) "Too hungry to fight" else "Too hungry to train"
}

/** A local (not view-model) animation; each instance is a distinct playback key. */
@Immutable
private class Local(val anim: Anim)

/**
 * A walk between scenes: running out toward [dir], then ([arriving]) in from the other side. [key] is the
 * view model's run request token, kept as the playback key for the whole walk so the run plays on
 * seamlessly after the request itself has finished.
 */
@Immutable
private class Walk(val dir: Int, val anim: Anim, val key: Any, val arriving: Boolean)

/** One showing of the refusal bubble; a new instance restarts its timer even with the same text. */
@Immutable
private class Bubble(val text: String)

private object IdleKey

/** The coroutine running the current walk-out or walk-in, so the next one can cancel it. */
private class JobSlot {
    var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/** The scene dots: the active one is this wide, the others 10dp, and [SceneDotGap] apart. */
private val ActiveSceneDot = 14.dp
private val SceneDotGap = 12.dp

/**
 * How wide the welcome-back toast (top right) may be on a [screenWidth]-wide screen: from the safe inset
 * in to a little short of the [sceneCount] scene dots at the top centre. A long toast (the 6-day, 3-digit
 * kind) steps its text down to fit instead of covering the dots, at any font scale.
 */
private fun toastMaxWidth(screenWidth: Dp, sceneCount: Int): Dp {
    val dotsHalf = ((10.dp + SceneDotGap) * (sceneCount - 1) + ActiveSceneDot) / 2f
    return screenWidth * (0.5f - SAFE_INSET_FRACTION) - dotsHalf - 16.dp
}

/** Scene dots at the top centre; after a walk, the new scene's name fades in under them for a few seconds. */
@Composable
private fun SceneMarker(count: Int, index: Int, title: String, showTitle: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(SceneDotGap), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { i -> SceneDot(active = i == index) }
        }
        AnimatedVisibility(
            visible = showTitle,
            enter = fadeIn(tween(400)) + expandVertically(tween(300)),
            exit = fadeOut(tween(600)) + shrinkVertically(tween(600)),
        ) {
            Text(
                text = title.uppercase(),
                style = displayStyle(22.sp, tracking = 0.14f, halo = true),
                maxLines = 1,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun SceneDot(active: Boolean) {
    val size by animateDpAsState(if (active) ActiveSceneDot else 10.dp, tween(250), label = "dotSize")
    val color by animateColorAsState(if (active) TextHi else TextHi.copy(alpha = 0.35f), tween(250), label = "dotColor")
    Box(
        Modifier
            .size(size)
            .drawBehind {
                if (active) softGlow(Color.White, alpha = 0.35f, cornerRadius = this.size.minDimension / 2f, spread = 6.dp, layers = 4)
            }
            .background(color, CircleShape),
    )
}

/** The bubble's tail: its distance from the bubble's left edge, and its width. */
private val BubbleTailInset = 40.dp
private val BubbleTailWidth = 26.dp

/** Longer refusals wrap onto two lines rather than reach across the screen. */
private val BubbleMaxWidth = 330.dp

/** Bone-white speech bubble whose tail points down at the pet's head, popping in when it refuses. */
@Composable
private fun RefusalBubble(text: String?, modifier: Modifier = Modifier) {
    var lastText by remember { mutableStateOf("") }
    if (text != null) lastText = text
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 500f), initialScale = 0.6f, transformOrigin = BubblePivot),
        exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.85f, transformOrigin = BubblePivot),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Box(
                Modifier
                    .widthIn(max = BubbleMaxWidth)
                    .drawBehind {
                        softGlow(Color.Black, alpha = 0.6f, cornerRadius = 16.dp.toPx(), spread = 14.dp, offsetY = 4.dp.toPx())
                    }
                    .background(BubbleWhite, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = lastText.uppercase(),
                    style = displayStyle(28.sp, color = Ink, tracking = 0.05f, lineHeight = 30.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
            Canvas(Modifier.padding(start = BubbleTailInset).size(width = BubbleTailWidth, height = 14.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path, BubbleWhite)
            }
            // Room under the tail so it floats just clear of the head.
            Box(Modifier.height(6.dp))
        }
    }
}

private val BubbleWhite = Color(0xFFF4F0EA)

/** Pop from near the tail, so the bubble grows out of the pet's head. */
private val BubblePivot = TransformOrigin(0.2f, 1f)
