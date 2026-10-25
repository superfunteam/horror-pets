package team.superfun.horrorpets.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.assets.has
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.DayLight
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.dayLightAt
import team.superfun.horrorpets.game.pantry.MealPerk
import team.superfun.horrorpets.game.pantry.mealStatusOf
import team.superfun.horrorpets.game.training.gamesStillToTrain
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PlayGag
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.model.visiblePets
import team.superfun.horrorpets.ui.UiState
import team.superfun.horrorpets.ui.asleep
import team.superfun.horrorpets.ui.common.BUBBLE_ABOVE_FEET
import team.superfun.horrorpets.ui.common.BUBBLE_MS
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SpeechBubble
import team.superfun.horrorpets.ui.common.SpritePose
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.detectWalkSwipes
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.common.spriteFilter
import team.superfun.horrorpets.ui.common.systemGestureEdges
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi

/** The pet's whole cell is this tall, as a fraction of the screen height (the same on 4:3 and 16:9). */
const val HOME_PET_HEIGHT = 0.46f

/**
 * The farthest out (fraction of the screen width) a pet arrives from when it walks into the next scene. It
 * walks at its stride pace (see [walkPxPerSecond]) for at most [ARRIVAL_MAX_MS], so a slower walker (Chucky,
 * Michael) arrives from closer in rather than skating or dragging.
 */
private const val WALK_DISTANCE = 0.30f
private const val ARRIVAL_MAX_MS = 1_600L

/** Idle variety: a glance or a hungry wait every 12–20 s. */
private const val VARIETY_MIN_MS = 12_000L
private const val VARIETY_MAX_MS = 20_000L

/** Idle strolls: one every 8–15 s. After one, a glance waits at least this long (and the other way round). */
private const val STROLL_MIN_MS = 8_000L
private const val STROLL_MAX_MS = 15_000L
private const val SETTLE_MS = 1_500L

/** How often a stroll heads back toward the middle: more often the further out the pet has drifted. */
private const val HOMEWARD_CHANCE = 0.25f
private const val HOMEWARD_CHANCE_FAR = 0.6f
private const val FAR_FROM_HOME = 0.15f

/** A glance that never reports finished (it always should) is given up on this long after its strip's length. */
private const val VARIETY_SLACK_MS = 2_000L

/** The pet's body reaches about this share of its drawn cell width either side of its centre. */
private const val BODY_HALF_OF_CELL = 0.3f

/** A run-out still waiting for its scene change after this long is abandoned (it never should be). */
private const val WALK_STUCK_MS = 1_500L

/** A hungover killer's idle sway: this many degrees each way, rocking from the head, once over this long. */
private const val HANGOVER_SWAY_DEG = 3f
private const val HANGOVER_SWAY_MS = 2_400f

/** Asleep, the killer leans this many degrees toward the floor and sinks this share of its height, over this long. */
private const val SLUMP_DEG = 12f
private const val SLUMP_SINK = 0.05f
private const val SLUMP_MS = 600

/** A sleeping killer's snore, again every this long: about the clip's own length, so one breath follows another. */
internal const val SNORE_SFX = "snore"
private const val SNORE_EVERY_MS = 4_000L

/**
 * Strips decoded ahead of time on Home, with its PLAY: its DANCE when it has one, else JUMP and WAVE (so at most 7 of
 * the loader's 12 slots, with IDLE). The feed animation is the pantry's to warm.
 */
private val HOME_WARM_ANIMS = listOf(Anim.RUN_RIGHT, Anim.RUN_LEFT, Anim.ATTACK, Anim.FAIL)

private const val SCENE_TITLE_MS = 3_200L

/** The light over the scene cross-fades over this long when the hour brings another part of the day. */
private const val SCENE_LIGHT_FADE_MS = 2_000

/** At night the scene's vignette darkens this many times over (see `SceneBackdrop`'s vignette). */
private const val NIGHT_VIGNETTE = 2f

/**
 * Home: the selected pet standing in its current scene, with the stat card, scene dots, welcome toast
 * and the action bar over it (the spec's layout A).
 *
 * [focusEntry] is the router's screen-entry key (see `RequestFocusOnEntry`): a new value gives PLAY
 * focus, and null (the screen is fading out) also turns off Back. OK is ignored for a moment after each
 * entry (see `rememberOkReady`), so an OK mashed through the end of a fight cannot fire PLAY; so are taps
 * and swipes.
 *
 * On a phone every button takes a tap, and a sideways swipe anywhere walks to the neighbouring scene
 * (see `swipeWalkDirection`), as the arrows do.
 *
 * The view model drives the pet through [UiState.petAnim]; this screen plays each request and reports
 * it with [onAnimFinished]. Everything else the pet does is local: idle glances, running out of one
 * scene and into the next, the refusal bubble, and sleeping through the night (see [UiState.asleep]): slumped and
 * held still, with Zs rising and a snore, until a press wakes it with a sleepy line ([UiState.wakeLine]).
 */
@Composable
fun HomeScreen(
    state: UiState,
    loader: AssetLoader,
    onCare: (CareAction) -> Unit,
    onWalk: (dir: Int) -> Unit,
    onFight: () -> Unit,
    onMoves: () -> Unit,
    onOpenStable: () -> Unit,
    onCloseStable: () -> Unit,
    onStablePick: (PetId) -> Unit,
    onAnimFinished: (token: Long) -> Unit,
    onToastDone: () -> Unit,
    onExit: () -> Unit,
    focusEntry: Any? = Unit,
) {
    val petId = state.game.selected ?: return
    val pet = state.game.pets[petId] ?: return
    val meal = mealStatusOf(pet, state.nowMs)
    val scenes = remember(loader, petId) { loader.scenes().getValue(petId) }
    val sceneIndex = pet.sceneIndex.mod(scenes.size)
    val scene = scenes[sceneIndex]
    val manifest = remember(loader, petId) { loader.manifest(petId) }
    val sceneTint = remember(scene) { hexColor(scene.tint) }
    // The light of the player's hour over the scene (see sceneLightFor). Read only inside the backdrop's content, so a
    // cross-fade redraws the scene and the killer, not the whole screen.
    val dayLight = dayLightAt(state.hour)
    val hourLight = sceneLightFor(dayLight)
    // The last light Home was in, which the day fades out in its own colour (see sceneLightTarget). Kept after each
    // composition, so the one that brings the day still has the light it is leaving.
    var lastLightColor by remember { mutableStateOf(hourLight?.color) }
    SideEffect { if (hourLight != null) lastLightColor = hourLight.color }
    val light by animateColorAsState(
        targetValue = sceneLightTarget(hourLight, lastLightColor),
        animationSpec = tween(SCENE_LIGHT_FADE_MS),
        label = "sceneLight",
    )
    // Night darkens the scene's edges more, on the light's own fade; read only while the backdrop draws.
    val vignette = animateFloatAsState(
        targetValue = if (dayLight == DayLight.NIGHT) NIGHT_VIGNETTE else 1f,
        animationSpec = tween(SCENE_LIGHT_FADE_MS),
        label = "sceneVignette",
    )

    val playFocus = remember { FocusRequester() }
    val badgeFocus = remember { FocusRequester() }
    // The stable's other killers, in roster order, and the one the badge is showing (the stable opens on them). A
    // killer in the stable is always visible, so the secret one shows here only once he has joined.
    val others = remember(state.game.pets, petId) { state.game.visiblePets().filter { it != petId }.mapNotNull { state.game.pets[it] } }
    val badgeShown = others.isNotEmpty() && state.toast == null
    var badgeShows by remember { mutableStateOf<PetId?>(null) }
    var badgeFocused by remember { mutableStateOf(false) }
    // The welcome back can take the corner from a focused badge: focus goes to Play rather than nowhere.
    LaunchedEffect(badgeShown) {
        if (!badgeShown && badgeFocused) runCatching { playFocus.requestFocus() }
    }
    // Focus goes back to the badge when the stable closes (to Play if the badge is gone).
    var stableWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.stableOpen) {
        if (state.stableOpen) {
            stableWasOpen = true
        } else if (stableWasOpen) {
            stableWasOpen = false
            runCatching { if (badgeShown) badgeFocus.requestFocus() else playFocus.requestFocus() }
        }
    }
    var lastWasFight by remember { mutableStateOf(false) }

    // Only the screen being entered handles Back: a Home that is fading out must never exit the app.
    GameBackHandler(enabled = focusEntry != null && !state.stableOpen, onBack = onExit)
    RequestFocusOnEntry(playFocus, focusEntry)
    val okReady = rememberOkReady(focusEntry)
    val latestOnWalk by rememberUpdatedState(onWalk)
    val gestureEdges by rememberUpdatedState(systemGestureEdges())

    // Warm the strips Home's actions play, so a first press starts at once. The run strips matter most:
    // the walk-out fade is timed from the press, the view model's scene change from the sprite's first
    // frame, and a cold decode between the two would leave an empty scene on screen.
    LaunchedEffect(loader, petId) {
        delay(800)
        val play = if (manifest.has(Anim.DANCE)) listOf(Anim.DANCE) else listOf(Anim.JUMP, Anim.WAVE)
        withContext(Dispatchers.IO) {
            for (anim in HOME_WARM_ANIMS + play) loader.strip(petId, anim)
            for (id in state.game.pets.keys) loader.head(id)
            for (art in PLAY_HAND_ART) loader.prop(art)
        }
    }

    // ---- what the pet is doing -------------------------------------------------------------------
    val scope = rememberCoroutineScope()
    // Where the pet stands (its centre, a fraction of the width): the middle, until it strolls off.
    val homeX = remember(petId) { Animatable(0.5f) }
    val walkX = remember { Animatable(0f) }
    val walkAlpha = remember { Animatable(1f) }
    val walkJob = remember { JobSlot() }
    var walk by remember { mutableStateOf<Walk?>(null) }
    var variety by remember { mutableStateOf<Local?>(null) }
    var stroll by remember { mutableStateOf<Local?>(null) }
    var bubble by remember { mutableStateOf<Bubble?>(null) }
    val geometry = remember { HomeGeometry() }
    // The stat card's right edge and this screen's left edge, in root pixels (0 until measured).
    var cardRightPx by remember { mutableFloatStateOf(0f) }
    var homeLeftPx by remember { mutableFloatStateOf(0f) }
    var showSceneTitle by remember { mutableStateOf(false) }
    // A play's gag: the glove's, from the press until the glove has gone, and the request whose gag has got to the
    // pet's turn to react. The glove moves the pet through [pose] (pokes, tickles, a shake).
    var playing by remember { mutableStateOf<Playing?>(null) }
    var reactedToken by remember { mutableStateOf<Long?>(null) }
    val pose = remember { SpritePose() }

    val request = state.petAnim
    LaunchedEffect(request?.token) {
        if (request == null) return@LaunchedEffect
        variety = null
        request.gag?.let { playing = Playing(it, request.token) }
        if (state.refuse != null) {
            val pet = state.game.selected?.let { state.game.pets[it] }
            bubble = Bubble(refusalText(state.refuse, lastWasFight, pet?.let(::gamesStillToTrain).orEmpty()))
        }
        val dir = when (request.anim) {
            Anim.RUN_RIGHT -> 1
            Anim.RUN_LEFT -> -1
            else -> 0
        }
        walkJob.cancel()
        if (dir == 0) {
            // Anything but a walk plays where the pet is: an arrival cut short stops right there, but never
            // outside the span it strolls over (on 4:3 the walk in from the left starts under the stat card).
            walk = null
            homeX.snapTo(geometry.range.clamp(homeX.value + walkX.value))
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
        // should still be fading when the new scene starts to cross-fade in rather than leave a gap. It
        // covers only what its stride pace allows in that time (a steady pace, so the feet don't skate).
        val outMs = cycle * 120 / 100
        val outDistance = walkDistance(outMs.toLong(), geometry.screenW, geometry.drawnHeightPx, geometry.walkSpeed)
        walkJob.job = scope.launch {
            launch { walkAlpha.animateTo(0f, tween(cycle * 70 / 100, delayMillis = cycle * 50 / 100)) }
            walkX.animateTo(dir * outDistance, tween(outMs, easing = LinearEasing))
        }
    }

    // The view model moved the pet to the next scene: run in from the other side, to the middle.
    LaunchedEffect(sceneIndex) {
        val out = walk ?: return@LaunchedEffect
        if (out.arriving) return@LaunchedEffect
        walkJob.cancel()
        val cycle = manifest.spec(out.anim).durationsMs.sum()
        walk = Walk(out.dir, out.anim, out.key, arriving = true)
        showSceneTitle = true
        val inbound = arrival(geometry.screenW, geometry.drawnHeightPx, geometry.walkSpeed, WALK_DISTANCE, ARRIVAL_MAX_MS)
        walkJob.job = scope.launch {
            homeX.snapTo(0.5f)
            walkX.snapTo(-out.dir * inbound.distance)
            walkAlpha.snapTo(0f)
            launch { walkAlpha.animateTo(1f, tween(min(cycle * 45 / 100, inbound.ms.toInt()))) }
            // At the stride pace, steady all the way, like a stroll.
            walkX.animateTo(0f, tween(inbound.ms.toInt(), easing = LinearEasing))
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
    // Asleep as the clock has it, once nothing else is on: bedtime coming in the middle of a play or a walk lets it
    // finish first (a held sprite would never report it over). Any press wakes it at once (see GameViewModel.woken).
    val asleep = state.asleep() && idle && playing == null
    // A hangover rocks the killer slowly from the head through its idle life (its glances and strolls too); a gag or a
    // walk stands it upright at once, and sleep stills it.
    val swaying = meal?.perk == MealPerk.HANGOVER && idle && playing == null && !asleep
    LaunchedEffect(swaying) {
        if (!swaying) return@LaunchedEffect
        try {
            val start = withFrameMillis { it }
            while (true) {
                val t = withFrameMillis { it } - start
                pose.swing.snapTo(HANGOVER_SWAY_DEG * sin(2f * PI.toFloat() * t / HANGOVER_SWAY_MS))
            }
        } finally {
            withContext(NonCancellable) { pose.swing.snapTo(0f) }
        }
    }
    // A press that woke the killer gets a sleepy line out of it, as a refusal gets its reason; when the same press is
    // refused, the reason says more and keeps the bubble. A line the killer said before this Home came up (the FEED
    // that woke it on the way to the pantry) is not said again on the way back.
    val wakeLineAtEntry = remember { state.wakeLine?.second }
    LaunchedEffect(state.wakeLine?.second) {
        val line = state.wakeLine ?: return@LaunchedEffect
        if (line.second == wakeLineAtEntry || state.refuse != null) return@LaunchedEffect
        bubble = Bubble(line.first)
    }
    LaunchedEffect(bubble) {
        if (bubble != null) {
            delay(BUBBLE_MS)
            bubble = null
        }
    }
    // Asleep, it snores, once a breath, until it wakes. The audio drops it while the app is in the background.
    val sounds = LocalSoundSink.current
    LaunchedEffect(asleep) {
        while (asleep) {
            sounds.sfx(SNORE_SFX)
            delay(SNORE_EVERY_MS)
        }
    }

    // Exactly one animation is on screen; the view model's request always wins. A walk keeps playing its
    // run under the request's token from the press until the pet has run into the next scene, so the run
    // never restarts or drops to IDLE at the edge between the request ending and the arrival starting.
    // A stroll is local too: its run plays under its own key and never reports to the view model.
    // A play's happy reaction waits for the glove's gag to get to it; until then the pet idles where it stands (or,
    // picked up and shaken, flails).
    val holding = request?.gag != null && reactedToken != request.token
    val shownAnim = when {
        holding && request?.gag == PlayGag.SHAKE -> Anim.WAVE
        holding -> Anim.IDLE
        else -> request?.anim ?: walk?.anim ?: stroll?.anim ?: variety?.anim ?: Anim.IDLE
    }
    val playKey: Any = if (holding) {
        HoldKey
    } else {
        request?.token ?: walk?.key ?: stroll ?: variety ?: if (asleep) SleepKey else IdleKey
    }
    val currentVariety = variety
    val onShownFinished: (() -> Unit)? = when {
        holding -> null
        request != null -> {
            { onAnimFinished(request.token) }
        }
        walk == null && stroll == null && currentVariety != null -> {
            { variety = null }
        }
        else -> null
    }

    // No background: the scene covers the screen, and the app root's Ink shows while it loads.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .ignoreOkUnless(okReady)
            // The view model turns a walk down while the pet is busy, as it does the arrows'.
            .pointerInput(Unit) {
                detectWalkSwipes(enabled = { okReady.value }, edges = { gestureEdges }, onWalk = { latestOnWalk(it) })
            }
            .onGloballyPositioned { homeLeftPx = it.positionInRoot().x },
    ) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val petHeightPx = screenH * HOME_PET_HEIGHT
        val cardWidth = (maxWidth * 0.27f).coerceIn(216.dp, 252.dp)
        val safe = safeInset()
        val inset = PaddingValues(horizontal = safe.horizontal, vertical = safe.vertical)
        val toastMax = toastMaxWidth(maxWidth, safe.horizontal, scenes.size)
        val density = LocalDensity.current
        val insetPx = with(density) { safe.horizontal.toPx() }
        val bodyScale = manifest.bodyScale
        val drawnHeightPx = petHeightPx * bodyScale
        val drawnCellW = drawnHeightPx * manifest.cell[0] / manifest.cell[1]
        val range = wanderRange(
            screenW = screenW,
            insetPx = insetPx,
            cardRightPx = insetPx + with(density) { cardWidth.toPx() },
            bodyHalfPx = drawnCellW * BODY_HALF_OF_CELL,
        )
        val walkSpeed = petId.spec.walkSpeed
        SideEffect {
            geometry.screenW = screenW
            geometry.drawnHeightPx = drawnHeightPx
            geometry.walkSpeed = walkSpeed
            geometry.range = range
        }
        // The bubble keeps between the stat card (as measured) and the right safe inset.
        val cardFallbackPx = insetPx + with(density) { cardWidth.toPx() }
        val bubbleGapPx = with(density) { BubbleCardGap.toPx() }
        val bubbleMinLeft = { (if (cardRightPx > 0f) cardRightPx - homeLeftPx else cardFallbackPx) + bubbleGapPx }

        // The pet's life while nothing else is going on: it idles, glances about (or waits, when hungry)
        // every 12–20 s, and every 8–15 s strolls a little way along the floor and stops. Anything the view
        // model asks for cancels this at once, leaving the pet wherever it got to; so does dropping off to sleep, and
        // the timers start afresh when it wakes.
        val latestRange by rememberUpdatedState(range)
        val latestScreenW by rememberUpdatedState(screenW)
        val latestDrawnHeight by rememberUpdatedState(drawnHeightPx)
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(idle, asleep, petId, lifecycleOwner) {
            if (!idle || asleep) return@LaunchedEffect
            // Only while the app is in the foreground. In the background the frame clock stops, so a stroll
            // left running would jump to its end on return; instead it stops where it is, and the pet's idle
            // life starts over (fresh timers) when the app comes back.
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    var untilLook = Random.nextLong(VARIETY_MIN_MS, VARIETY_MAX_MS + 1)
                    var untilStroll = Random.nextLong(STROLL_MIN_MS, STROLL_MAX_MS + 1)
                    while (true) {
                        val wait = min(untilLook, untilStroll)
                        delay(wait)
                        untilLook -= wait
                        untilStroll -= wait
                        if (untilStroll <= 0L) {
                            strollOnce(homeX, latestRange, latestScreenW, latestDrawnHeight, walkSpeed) { stroll = it }
                            untilStroll = Random.nextLong(STROLL_MIN_MS, STROLL_MAX_MS + 1)
                            untilLook = max(untilLook, SETTLE_MS)
                        } else {
                            val anim = when {
                                hungry -> Anim.WAIT
                                Random.nextBoolean() -> Anim.LOOK_A
                                else -> Anim.LOOK_B
                            }
                            variety = Local(anim)
                            val length = manifest.spec(anim).durationsMs.sumOf { it.coerceAtLeast(0) }.toLong()
                            withTimeoutOrNull(length + VARIETY_SLACK_MS) { snapshotFlow { variety }.first { it == null } }
                            variety = null
                            untilLook = Random.nextLong(VARIETY_MIN_MS, VARIETY_MAX_MS + 1)
                            untilStroll = max(untilStroll, SETTLE_MS)
                        }
                    }
                } finally {
                    // Stopped (a view-model action, or the app went to the background): the pet stays put.
                    stroll = null
                    variety = null
                }
            }
        }

        // Asleep, the killer slumps: it leans toward the floor about its feet and sinks a little, easing down, and
        // sits straight up the moment it wakes. It leans with tilt, not the hangover's swing, and the sway stands
        // aside while it sleeps (see swaying), so the two never pull at the pose together.
        LaunchedEffect(asleep) {
            if (!asleep) return@LaunchedEffect
            try {
                coroutineScope {
                    launch { pose.tilt.animateTo(SLUMP_DEG, tween(SLUMP_MS)) }
                    pose.lift.animateTo(-SLUMP_SINK * latestDrawnHeight, tween(SLUMP_MS))
                }
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    pose.tilt.snapTo(0f)
                    pose.lift.snapTo(0f)
                }
            }
        }

        SceneBackdrop(loader = loader, scene = scene, vignette = { vignette.value }) { floorY ->
            // The hour's light over the scene and under the killer; the bubble and the HUD come after it, unlit.
            Spacer(Modifier.fillMaxSize().drawBehind { drawRect(light) })
            // The killer, and a play's glove, stand in the same light.
            val tint = litTint(sceneTint, light)
            SpriteView(
                loader = loader,
                pet = petId,
                anim = shownAnim,
                heightPx = petHeightPx,
                tint = tint,
                tintStrength = scene.tintStrength,
                shadowOpacity = scene.shadowOpacity,
                pose = pose,
                // Asleep, its idle holds on the first frame (the playback starts over under SleepKey): out cold.
                timeScale = { if (asleep) 0f else 1f },
                playKey = playKey,
                onFinished = onShownFinished,
                modifier = Modifier
                    .placeAt(x = { (homeX.value + walkX.value) * screenW }, y = { floorY })
                    .graphicsLayer { alpha = walkAlpha.value },
            )
            if (asleep) {
                // Just over the head, where the bubble points, as the slump leans it about the feet and sinks it.
                val headAbove = drawnHeightPx * BUBBLE_ABOVE_FEET
                SleepZs(
                    headX = { (homeX.value + walkX.value) * screenW + headAbove * sin(pose.tilt.value.toRadians()) },
                    headY = { floorY - headAbove * cos(pose.tilt.value.toRadians()) - pose.lift.value },
                )
            }
            playing?.let { p ->
                key(p.token) {
                    val filter = remember(tint, scene.tintStrength) { spriteFilter(tint, scene.tintStrength, 0f, 1f, 0f) }
                    PlayHand(
                        gag = p.gag,
                        pet = petId,
                        loader = loader,
                        petX = (homeX.value + walkX.value) * screenW,
                        floorY = floorY,
                        petH = drawnHeightPx,
                        fullH = petHeightPx,
                        bodyHalf = drawnCellW * BODY_HALF_OF_CELL,
                        screenW = screenW,
                        pose = pose,
                        filter = filter,
                        onReact = { reactedToken = p.token },
                        onDone = { if (playing === p) playing = null },
                    )
                }
            }
            SpeechBubble(
                text = bubble?.text,
                headX = { (homeX.value + walkX.value) * screenW },
                bottomY = { floorY - drawnHeightPx * BUBBLE_ABOVE_FEET },
                minLeft = bubbleMinLeft,
                maxRight = screenW - insetPx,
            )
        }

        Box(Modifier.fillMaxSize().padding(inset)) {
            StatCard(
                pet = pet,
                meal = meal,
                asleep = asleep,
                width = cardWidth,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onGloballyPositioned { cardRightPx = it.boundsInRoot().right },
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
            // The stable's other killers, top right, once there are any: the welcome back has that corner first.
            AnimatedVisibility(
                visible = badgeShown,
                modifier = Modifier.align(Alignment.TopEnd),
                enter = fadeIn(tween(400)) + slideInHorizontally(tween(400)) { it / 3 },
                exit = fadeOut(tween(250)),
            ) {
                StableBadge(
                    others = others,
                    loader = loader,
                    onOpen = onOpenStable,
                    onShown = { badgeShows = it },
                    focusRequester = badgeFocus,
                    canFocus = !state.stableOpen,
                    modifier = Modifier.onFocusChanged { badgeFocused = it.hasFocus },
                )
            }
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
                onMoves = onMoves,
                playFocus = playFocus,
                upFocus = if (badgeShown) badgeFocus else null,
                modifier = Modifier.align(Alignment.BottomCenter),
                canFocus = !state.stableOpen,
            )
        }

        if (state.stableOpen) {
            StableMenu(game = state.game, first = badgeShows, loader = loader, onPick = onStablePick, onClose = onCloseStable)
        }
    }
}

/**
 * What the killer says when FIGHT or TRAIN is refused: why, and what to do about it ([toTrain]: the games it has still
 * to train at). Two lines of the bubble at most, at the largest font scale, so two games get the shorter form.
 */
internal fun refusalText(reason: RefuseReason, fight: Boolean, toTrain: List<TrainingGame>): String = when (reason) {
    RefuseReason.TOO_HUNGRY -> if (fight) "Too hungry to fight. Feed me!" else "Too hungry to train. Feed me!"
    RefuseReason.NEEDS_TRAINING -> when (toTrain.size) {
        0 -> "Train more first!"
        1 -> "Train at ${toTrain.single().title} first!"
        else -> "${toTrain.joinToString(" and ") { it.title }} first!"
    }
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

/** The last layout's sizes and the pet's pace, for the walks timed outside composition (main thread only). */
private class HomeGeometry {
    var screenW = 0f
    var drawnHeightPx = 0f
    var walkSpeed = 1f
    var range = WanderRange(0f, 1f)
}

/**
 * One stroll: picks a spot along the floor (see [wanderTarget]), runs there at [walkSpeed] (see
 * [wanderDurationMs]) showing the run toward it through [show], and stops. Cancelling it stops the pet where
 * it is. Purely local: nothing here reports to the view model.
 */
private suspend fun strollOnce(
    homeX: Animatable<Float, AnimationVector1D>,
    range: WanderRange,
    screenW: Float,
    drawnHeightPx: Float,
    walkSpeed: Float,
    show: (Local?) -> Unit,
) {
    val from = homeX.value
    val homeward = Random.nextFloat() < if (abs(from - 0.5f) > FAR_FROM_HOME) HOMEWARD_CHANCE_FAR else HOMEWARD_CHANCE
    val step = WANDER_STEP_MIN + Random.nextFloat() * (WANDER_STEP_MAX - WANDER_STEP_MIN)
    val target = wanderTarget(from, range, step, toCenter = homeward, preferRight = Random.nextBoolean())
    val ms = wanderDurationMs(abs(target - from) * screenW, drawnHeightPx, walkSpeed)
    if (ms <= 0L) return
    try {
        show(Local(if (target > from) Anim.RUN_RIGHT else Anim.RUN_LEFT))
        // A steady pace (no easing), so the run cycle's feet match the ground the whole way.
        homeX.animateTo(target, tween(ms.toInt(), easing = LinearEasing))
    } finally {
        show(null)
    }
}

private object IdleKey

/** The playback key while a play's gag is on: the pet idles (or flails), and its own animation starts fresh after. */
private object HoldKey

/** The playback key while the killer sleeps: its idle starts over as it drops off, so it holds the first frame. */
private object SleepKey

/** These degrees in radians. */
private fun Float.toRadians(): Float = this * PI.toFloat() / 180f

/** The [gag] a play started with its first request ([token]), showing until the glove has gone. */
@Immutable
private class Playing(val gag: PlayGag, val token: Long)

/** The coroutine running the current walk-out or walk-in, so the next one can cancel it. */
private class JobSlot {
    var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/** The bubble keeps at least this far right of the stat card. */
private val BubbleCardGap = 8.dp

/** The scene marker's diamonds: the current scene's is this wide, the others [SceneDot], [SceneDotGap] apart. */
private val ActiveSceneDot = 9.dp
private val SceneDot = 7.dp
private val SceneDotGap = 8.dp

/** The scene marker's gold tag pads its diamonds (and, after a walk, the scene's name) this much each side. */
private val MarkerPadding = 14.dp

/**
 * How wide the welcome-back toast (top right) may be on a [screenWidth]-wide screen: from the safe [inset]
 * in to a little short of the [sceneCount]-scene marker at the top centre. A long toast (the 6-day, 3-digit
 * kind) steps its text down to fit instead of covering the marker, at any font scale.
 */
private fun toastMaxWidth(screenWidth: Dp, inset: Dp, sceneCount: Int): Dp {
    val dotsHalf = ((SceneDot + SceneDotGap) * (sceneCount - 1) + ActiveSceneDot) / 2f
    return screenWidth / 2f - inset - dotsHalf - MarkerPadding - 16.dp
}

/**
 * The scene marker, top centre: a small gold-framed tag of diamonds, one per scene with the current one lit;
 * after a walk, the new scene's name slides out beside them for a few seconds.
 */
@Composable
private fun SceneMarker(count: Int, index: Int, title: String, showTitle: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .gameFrame(topEnd = 7.dp, bottomStart = 7.dp, inner = false)
            .height(28.dp)
            .padding(horizontal = MarkerPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SceneDotGap), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { i -> SceneDiamond(active = i == index) }
        }
        AnimatedVisibility(
            visible = showTitle,
            enter = fadeIn(tween(400)) + expandHorizontally(tween(300)),
            exit = fadeOut(tween(600)) + shrinkHorizontally(tween(600)),
        ) {
            Text(
                text = title.uppercase(),
                style = displayStyle(14.sp, tracking = 0.16f),
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun SceneDiamond(active: Boolean) {
    val size by animateDpAsState(if (active) ActiveSceneDot else SceneDot, tween(250), label = "dotSize")
    val color by animateColorAsState(if (active) SceneGold else TextHi.copy(alpha = 0.3f), tween(250), label = "dotColor")
    Box(
        Modifier
            .size(size)
            .drawBehind {
                if (active) softGlow(SceneGold, alpha = 0.4f, cornerRadius = 0f, spread = 5.dp, layers = 4)
            }
            .rotate(45f)
            .background(color),
    )
}

private val SceneGold = Color(0xFFF5C543)

