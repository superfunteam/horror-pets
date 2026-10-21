package team.superfun.horrorpets.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.assets.feedAnim
import team.superfun.horrorpets.assets.has
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.FEED_MUNCHES
import team.superfun.horrorpets.model.Food
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PlayGag
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.ui.UiState
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
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

private const val BUBBLE_MS = 2_000L

/** A run-out still waiting for its scene change after this long is abandoned (it never should be). */
private const val WALK_STUCK_MS = 1_500L

/**
 * Strips decoded ahead of time on Home, with the pet's feed animation and its PLAY: its DANCE when it has one,
 * else JUMP and WAVE (so at most 8 of the loader's 12 slots, with IDLE).
 */
private val HOME_WARM_ANIMS = listOf(Anim.RUN_RIGHT, Anim.RUN_LEFT, Anim.ATTACK, Anim.FAIL)

private const val SCENE_TITLE_MS = 3_200L

/** The refusal bubble's tail sits this far above the feet, as a fraction of the cell height (just over the head). */
private const val BUBBLE_ABOVE_FEET = 0.91f

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
 * scene and into the next, and the refusal bubble.
 */
@Composable
fun HomeScreen(
    state: UiState,
    loader: AssetLoader,
    onCare: (CareAction) -> Unit,
    onWalk: (dir: Int) -> Unit,
    onFight: () -> Unit,
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
    val scenes = remember(loader, petId) { loader.scenes().getValue(petId) }
    val sceneIndex = pet.sceneIndex.mod(scenes.size)
    val scene = scenes[sceneIndex]
    val manifest = remember(loader, petId) { loader.manifest(petId) }
    val tint = remember(scene) { hexColor(scene.tint) }

    val playFocus = remember { FocusRequester() }
    val badgeFocus = remember { FocusRequester() }
    // The stable's other killers, in roster order, and the one the badge is showing (the stable opens on them).
    val others = remember(state.game.pets, petId) { PetId.entries.filter { it != petId }.mapNotNull { state.game.pets[it] } }
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
            for (anim in HOME_WARM_ANIMS + play + manifest.feedAnim()) loader.strip(petId, anim)
            for (food in Food.entries) loader.food(food)
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
    // The food a feed dropped, from the press until its last crumbs land (see FoodDrop), and the request
    // whose food has landed: that request's munch waits for it.
    var feeding by remember { mutableStateOf<Feeding?>(null) }
    var landedToken by remember { mutableStateOf<Long?>(null) }
    // The same for a play: the glove's gag, from the press until the glove has gone, and the request whose gag
    // has got to the pet's turn to react. The glove moves the pet through [pose] (pokes, tickles, a shake).
    var playing by remember { mutableStateOf<Playing?>(null) }
    var reactedToken by remember { mutableStateOf<Long?>(null) }
    val pose = remember { SpritePose() }

    val request = state.petAnim
    LaunchedEffect(request?.token) {
        if (request == null) return@LaunchedEffect
        variety = null
        request.food?.let { feeding = Feeding(it, request.token) }
        request.gag?.let { playing = Playing(it, request.token) }
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
    LaunchedEffect(bubble) {
        if (bubble != null) {
            delay(BUBBLE_MS)
            bubble = null
        }
    }

    // Exactly one animation is on screen; the view model's request always wins. A walk keeps playing its
    // run under the request's token from the press until the pet has run into the next scene, so the run
    // never restarts or drops to IDLE at the edge between the request ending and the arrival starting.
    // A stroll is local too: its run plays under its own key and never reports to the view model.
    // A feed's first munch waits for its food to land, and a play's happy reaction for the glove's gag to get to
    // it; until then the pet idles where it stands (or, picked up and shaken, flails).
    val holdingGag = request?.gag != null && reactedToken != request.token
    val holding = holdingGag || (request?.food != null && landedToken != request.token)
    val shownAnim = when {
        holdingGag && request?.gag == PlayGag.SHAKE -> Anim.WAVE
        holding -> Anim.IDLE
        else -> request?.anim ?: walk?.anim ?: stroll?.anim ?: variety?.anim ?: Anim.IDLE
    }
    val playKey: Any = if (holding) HoldKey else request?.token ?: walk?.key ?: stroll ?: variety ?: IdleKey
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
        // model asks for cancels this at once, leaving the pet wherever it got to.
        val latestRange by rememberUpdatedState(range)
        val latestScreenW by rememberUpdatedState(screenW)
        val latestDrawnHeight by rememberUpdatedState(drawnHeightPx)
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(idle, petId, lifecycleOwner) {
            if (!idle) return@LaunchedEffect
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

        SceneBackdrop(loader = loader, scene = scene) { floorY ->
            SpriteView(
                loader = loader,
                pet = petId,
                anim = shownAnim,
                heightPx = petHeightPx,
                tint = tint,
                tintStrength = scene.tintStrength,
                shadowOpacity = scene.shadowOpacity,
                pose = pose,
                playKey = playKey,
                onFinished = onShownFinished,
                modifier = Modifier
                    .placeAt(x = { (homeX.value + walkX.value) * screenW }, y = { floorY })
                    .graphicsLayer { alpha = walkAlpha.value },
            )
            feeding?.let { f ->
                key(f.token) {
                    val munchMs = FEED_MUNCHES * manifest.spec(manifest.feedAnim()).durationsMs.sumOf { it.coerceAtLeast(0) }.toLong()
                    val filter = remember(tint, scene.tintStrength) { spriteFilter(tint, scene.tintStrength, 0f, 1f, 0f) }
                    FoodDrop(
                        food = f.food,
                        image = remember(f.food) { loader.food(f.food) },
                        petX = (homeX.value + walkX.value) * screenW,
                        bodyHalf = drawnCellW * BODY_HALF_OF_CELL,
                        screenW = screenW,
                        inset = insetPx,
                        floorY = floorY,
                        sizePx = petHeightPx * FOOD_SIZE,
                        munchMs = munchMs,
                        filter = filter,
                        onLanded = { landedToken = f.token },
                        onDone = { if (feeding === f) feeding = null },
                    )
                }
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
            RefusalBubble(
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

/**
 * The playback key while a feed's food is still falling or a play's gag is on: the pet idles (or flails), and its
 * own animation starts fresh after.
 */
private object HoldKey

/** The food [food] a feed dropped with its first request ([token]), showing until its last crumbs land. */
@Immutable
private class Feeding(val food: Food, val token: Long)

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

/** The bubble's tail: where it sits from the bubble's left edge when there is room, and its width. */
private val BubbleTailInset = 40.dp
private val BubbleTailWidth = 26.dp

/** Longer refusals wrap onto two lines rather than reach across the screen. */
private val BubbleMaxWidth = 330.dp

/** The bubble keeps at least this far right of the stat card. */
private val BubbleCardGap = 8.dp

private const val BUBBLE_POP_FROM = 0.6f
private const val BUBBLE_SHRINK_TO = 0.85f

/**
 * Bone-white speech bubble whose tail points down at the pet's head ([headX]), popping in when it refuses.
 * Its bottom (the tail's tip) is at [bottomY]. The box hangs right of the head where it can and slides left
 * near the right edge, always between [minLeft] (just right of the stat card) and [maxRight] (the right safe
 * inset); the tail is drawn under the head wherever the box ends up (see [bubbleLeft], [bubbleTailX]). The
 * position is read in layout and draw, and the pop grows from the tail, so nothing jumps as it fades out.
 */
@Composable
private fun RefusalBubble(text: String?, headX: () -> Float, bottomY: () -> Float, minLeft: () -> Float, maxRight: Float) {
    var lastText by remember { mutableStateOf("") }
    if (text != null) lastText = text
    val shown = text != null
    val fade = remember { Animatable(0f) }
    val pop = remember { Animatable(BUBBLE_POP_FROM) }
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown) {
            present = true
            pop.snapTo(BUBBLE_POP_FROM)
            launch { fade.animateTo(1f, tween(120)) }
            pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
        } else if (present) {
            launch { pop.animateTo(BUBBLE_SHRINK_TO, tween(220)) }
            fade.animateTo(0f, tween(220))
            present = false
        }
    }
    if (!present) return

    val density = LocalDensity.current
    val preferredTail = with(density) { (BubbleTailInset + BubbleTailWidth / 2).toPx() }
    val tailEdge = with(density) { (16.dp + BubbleTailWidth / 2).toPx() }
    val maxWidthPx = with(density) { BubbleMaxWidth.toPx() }
    val tailPath = remember { Path() }
    Column(
        Modifier
            .layout { measurable, constraints ->
                // Never wider than the gap it has to fit in (it wraps to a second line instead).
                val room = (maxRight - minLeft()).coerceAtLeast(0f)
                val p = measurable.measure(Constraints(maxWidth = min(maxWidthPx, room).roundToInt()))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val left = bubbleLeft(headX(), p.width.toFloat(), preferredTail, minLeft(), maxRight)
                    p.place(left.roundToInt(), (bottomY() - p.height).roundToInt())
                }
            }
            .graphicsLayer {
                alpha = fade.value
                scaleX = pop.value
                scaleY = pop.value
                // Pop from the tail, so the bubble grows out of the pet's head.
                val w = size.width.coerceAtLeast(1f)
                val left = bubbleLeft(headX(), w, preferredTail, minLeft(), maxRight)
                transformOrigin = TransformOrigin(bubbleTailX(headX(), left, w, tailEdge) / w, 1f)
            }
            .width(IntrinsicSize.Max),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    softGlow(Color.Black, alpha = 0.6f, cornerRadius = 16.dp.toPx(), spread = 14.dp, offsetY = 4.dp.toPx())
                }
                .background(BubbleWhite, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = lastText.uppercase(),
                style = displayStyle(28.sp, color = Ink, tracking = 0.05f, lineHeight = 30.sp),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val left = bubbleLeft(headX(), size.width, preferredTail, minLeft(), maxRight)
            val cx = bubbleTailX(headX(), left, size.width, tailEdge)
            val half = BubbleTailWidth.toPx() / 2f
            tailPath.reset()
            tailPath.moveTo(cx - half, 0f)
            tailPath.lineTo(cx + half, 0f)
            tailPath.lineTo(cx, size.height)
            tailPath.close()
            drawPath(tailPath, BubbleWhite)
        }
        // Room under the tail so it floats just clear of the head.
        Box(Modifier.height(6.dp))
    }
}

private val BubbleWhite = Color(0xFFF4F0EA)
