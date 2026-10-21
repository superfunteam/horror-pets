package team.superfun.horrorpets.ui.switcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.tv.material3.Text
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.game.statusOf
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.SwitcherMode
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.StatusChip
import team.superfun.horrorpets.ui.common.beamLayers
import team.superfun.horrorpets.ui.common.drawBeam
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.rememberTouchTick
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.common.systemGestureEdges
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextLo

// ---- stage geometry, as fractions of the screen height ----

/** The line every pet's feet stand on: the stage floor the spotlights hit. */
private const val FLOOR = 0.66f

/** Where the back wall meets the stage floor, just behind the feet. */
private const val WALL_SEAM = 0.62f

/** The centred pet's whole sprite cell is this tall (the same on 4:3 and 16:9, and it is never cropped). */
private const val PET_HEIGHT = 0.40f

/** Top of the name / level / status block under each pet. */
private const val LABEL_TOP = 0.725f

/** The beams start just above the top edge, like lamps hanging in a rig, and fade out just past the floor. */
private const val BEAM_TOP = -0.04f
private const val BEAM_BOTTOM = 0.71f

/**
 * The most room a pet gets in the lineup, on the 540-dp canvas: on 16:9 that is the centre pet, a whole
 * neighbour each side and the next two peeking in at the edges (see [carouselPitch]).
 */
internal val MAX_PITCH = 240.dp

// ---- motion ----

/** The row slides this long (ease-out) to centre the next pet. */
private const val SLIDE_MS = 300

/** At either end the row nudges this far (in pets) toward the pet that isn't there, and settles back. */
private const val BUMP = 0.08f
private const val BUMP_MS = 220

// ---- the look of a pet waiting in the dark ----

private const val OFF_DIM = 0.62f
private const val OFF_SATURATION = 0.4f
private const val OFF_LABEL_SCALE = 0.86f

/** A pet's dim and color follow a slide in this many steps: its color filter is rebuilt per step, not per frame. */
private const val LIT_STEPS = 8

/**
 * Pet names under the spotlights; a long one steps down to fit the label's width. GHOSTFACE, the longest
 * name, fits a neighbour's label on 4:3 (the narrowest, see [carouselPitch]) at any font scale the game
 * honors (up to 1.15x, see `cappedFontScale`).
 */
private val NAME_SIZE = 40.sp
private val NAME_MIN_SIZE = 18.sp

/** Idle loops start this far apart, so the pets breathe out of step. */
private const val IDLE_STAGGER_MS = 230L

/**
 * "Choose your killer": the lineup on a dark stage, one row of pets standing idle and uncropped. The red
 * spotlight is fixed at the centre of the stage; left / right slide the whole row (300ms, ease-out) so the
 * next pet lands in it, and pets beyond the screen's edges slide in as the row moves. The row stops at the
 * first and last pet (a short nudge, no wrap). The centred pet is in full color with the bright label; the
 * others wait dimmed and desaturated in pale beams, a little smaller the further out they stand, their
 * labels fading out toward the edges. OK picks, Back cancels. A held left / right keeps scrolling (key
 * repeats move the row; OK and Back repeats are dropped by `MainActivity`), and `ui_move` plays only on
 * an actual move.
 *
 * On a phone the stage takes touch too: a tap on a pet slides it into the spotlight, a tap on the pet already
 * there picks it, and a drag slides the row under the finger and lets go onto the nearest pet (a flick
 * carries on to the next, or further), with the same nudge at either end. The back gesture is Back, and a
 * touch that starts in the system's gesture strips along the edges is left to the system.
 *
 * The same stage picks a fight opponent ([SwitcherMode.OPPONENT]), with the player's own pet left out.
 *
 * Where the row starts: on the current pet when switching ([SwitcherMode.SELECT]), and on the first pet
 * when onboarding or choosing an opponent. While onboarding there is nothing to go back to, so Back
 * leaves the app ([onExit]).
 *
 * [focusEntry] is the router's screen-entry key (see `RequestFocusOnEntry`): a new value takes focus and
 * puts the row back on its starting pet; null (the screen is fading out) turns off Back, the keys and touch.
 *
 * Every per-frame value (the row's position, the dust) is read in layout or draw, so a slide (or a drag)
 * never recomposes the stage; a pet's dim / color steps [LIT_STEPS] times per slide.
 *
 * Music: this screen's track is `switcher`, chosen by the router from the screen type.
 */
@Composable
fun SwitcherScreen(
    mode: SwitcherMode,
    game: GameState,
    loader: AssetLoader,
    onPick: (PetId) -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit,
    focusEntry: Any? = Unit,
) {
    val sounds = LocalSoundSink.current
    val scope = rememberCoroutineScope()
    val pets = remember(mode, game.selected) {
        PetId.entries.filter { mode != SwitcherMode.OPPONENT || it != game.selected }
    }
    val bodies = remember(loader, pets) { pets.map { loader.manifest(it).bodyScale } }
    val initial = when (mode) {
        SwitcherMode.SELECT -> pets.indexOf(game.selected).coerceAtLeast(0)
        SwitcherMode.ONBOARD, SwitcherMode.OPPONENT -> 0
    }
    var index by remember(mode) { mutableIntStateOf(initial) }
    // Where the row is: the pet index under the spotlight, fractional while it slides.
    val scroll = remember(mode) { Animatable(initial.toFloat()) }
    val bump = remember { Animatable(0f) }
    // Read only in layout and draw. Remembered, so the cached stage and each pet's lit state keep one reader.
    val row = remember(scroll, bump) { { scroll.value + bump.value } }
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val entered = focusEntry != null
    // For the touch handler, which outlives a composition: always the current values.
    val latestEntered by rememberUpdatedState(entered)
    val latestPets by rememberUpdatedState(pets)
    val latestOnPick by rememberUpdatedState(onPick)
    val gestureEdges by rememberUpdatedState(systemGestureEdges())
    val pickTick = rememberTouchTick("ui_select")

    // Only the screen being entered handles Back: a switcher that is fading out must never act on it.
    GameBackHandler(enabled = entered) { if (mode == SwitcherMode.ONBOARD) onExit() else onBack() }
    RequestFocusOnEntry(focus, focusEntry)

    // The router reuses a leaving switcher when the player flips straight back to it: start over.
    LaunchedEffect(focusEntry) {
        if (focusEntry != null && index != initial) {
            index = initial
            scroll.snapTo(initial.toFloat())
        }
    }
    LaunchedEffect(index) {
        scroll.animateTo(index.toFloat(), tween(SLIDE_MS, easing = EaseOutCubic))
    }

    // Time for the dust drifting in the spotlight; only the dust layer's draw reads it.
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { clock.longValue = it - start }
    }

    fun move(delta: Int) {
        val next = carouselStep(index, delta, pets.size)
        if (next == index) {
            // The end of the row: nudge toward the pet that isn't there, and settle back.
            scope.launch {
                bump.animateTo(delta * BUMP, tween(BUMP_MS / 3, easing = FastOutSlowInEasing))
                bump.animateTo(0f, tween(BUMP_MS * 2 / 3, easing = FastOutSlowInEasing))
            }
            return
        }
        index = next
        sounds.sfx("ui_move")
    }

    /** Lets a dragged row go onto pet [target]: it slides there, even when that is the pet it started from. */
    fun settle(target: Int) {
        if (target != index) {
            index = target // LaunchedEffect(index) slides the row
        } else {
            scope.launch { scroll.animateTo(target.toFloat(), tween(SLIDE_MS, easing = EaseOutCubic)) }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (!entered) return@onKeyEvent false
                when (event.key) {
                    // Every key down, repeats included: a held D-pad scrolls along the row.
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) move(if (event.key == Key.DirectionLeft) -1 else 1)
                        true
                    }
                    // Nothing above or below: keep focus on the stage.
                    Key.DirectionUp, Key.DirectionDown -> true
                    else -> false
                }
            }
            // OK (or Enter) picks on key up, like every other button in the game.
            .clickable(interactionSource = interaction, indication = null) {
                if (entered) onPick(pets[index])
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val safe = safeInset()
        val pitchDp = carouselPitch(maxWidth.value, safe.horizontal.value, MAX_PITCH.value).dp
        val pitch = with(density) { pitchDp.toPx() }
        val cellH = h * PET_HEIGHT
        val labelWidth = pitchDp * CAROUSEL_LABEL_SHARE
        val reach = carouselReach(maxWidth.value, pitchDp.value)

        // Touch, under everything else on stage (none of which takes touches). It consumes every touch, so
        // the stage's clickable above only ever answers OK. The row's position changes only through `scroll`,
        // read in layout and draw, so a drag never recomposes the stage either.
        Spacer(
            // Keyed on `scroll` as well: a new mode (the router reusing a leaving switcher) brings a new row.
            Modifier.fillMaxSize().pointerInput(pitch, w, scroll) {
                val centerX = w / 2f
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    // A swipe in from the edge is the system's (see GestureEdges); and so is a tap there.
                    if (!latestEntered || gestureEdges.contain(down.position, size)) return@awaitEachGesture
                    val count = latestPets.size
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    val start = scroll.value
                    // The pet under the spotlight as the row moves: each new one ticks, like a D-pad step.
                    var spotlit = index
                    var dragging = false
                    var tapping = true
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        tracker.addPointerInputChange(change)
                        val moved = change.position - down.position
                        if (!change.pressed) {
                            if (dragging) {
                                val target = carouselFlingTarget(scroll.value, -tracker.calculateVelocity().x / pitch, count)
                                if (target != spotlit) sounds.sfx("ui_move")
                                settle(target)
                            } else if (tapping && latestEntered) {
                                val target = carouselTapTarget(down.position.x, row(), centerX, pitch, count)
                                when (target) {
                                    null -> Unit
                                    index -> {
                                        pickTick()
                                        latestOnPick(latestPets[target])
                                    }
                                    else -> {
                                        index = target
                                        sounds.sfx("ui_move")
                                    }
                                }
                            }
                            break
                        }
                        if (!dragging && tapping && moved.getDistance() > viewConfiguration.touchSlop) {
                            // Sideways it drags the row; any other way it is neither a drag nor a tap.
                            if (abs(moved.x) >= abs(moved.y)) dragging = true else tapping = false
                        }
                        if (dragging) {
                            val to = carouselDragRow(start, moved.x, pitch, count)
                            scope.launch { scroll.snapTo(to) }
                            val now = to.roundToInt().coerceIn(0, count - 1)
                            if (now != spotlit) {
                                spotlit = now
                                sounds.sfx("ui_move")
                            }
                        }
                    }
                }
            },
        )

        Stage(count = pets.size, bodies = bodies, pitch = pitch, cellH = cellH, row = row)
        Dust(pitch = pitch, time = { clock.longValue })

        // Only the pets near the spotlight are on stage: composed, their idle strips decoded. The bounds follow
        // the row but change only when it crosses a pet, so a slide recomposes this a couple of times at most.
        val first by remember(row, pets.size, reach) { derivedStateOf { carouselFirstInView(row(), pets.size, reach) } }
        val last by remember(row, pets.size, reach) { derivedStateOf { carouselLastInView(row(), pets.size, reach) } }
        // The next pet beyond each end is decoded ahead (not composed), so it draws from its first frame on
        // stage instead of popping in a frame or two late at the screen's edge.
        LaunchedEffect(loader, pets, first, last) {
            withContext(Dispatchers.IO) {
                for (i in carouselPrefetch(first, last, pets.size)) loader.strip(pets[i], Anim.IDLE)
            }
        }
        pets.forEachIndexed { i, pet ->
            if (i in first..last) key(pet) {
                LineupPet(
                    loader = loader,
                    pet = game.pets[pet] ?: PetState(pet),
                    i = i,
                    row = row,
                    centerX = w / 2f,
                    pitch = pitch,
                    floorY = h * FLOOR,
                    labelY = h * LABEL_TOP,
                    cellH = cellH,
                    labelWidth = labelWidth,
                    idleOffsetMs = i * IDLE_STAGGER_MS,
                )
            }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = safe.horizontal, vertical = safe.vertical),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = if (mode == SwitcherMode.OPPONENT) "CHOOSE YOUR OPPONENT" else "CHOOSE YOUR KILLER",
                style = displayStyle(56.sp, tracking = 0.12f, lineHeight = 56.sp)
                    .copy(shadow = TitleGlow, textAlign = TextAlign.Center),
                maxLines = 1,
                // "CHOOSE YOUR OPPONENT" is too wide for a 4:3 screen at 56sp: step down only as needed.
                autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = 56.sp, stepSize = 2.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            TitleRule()
        }
    }
}

/** A red glow behind the title, as if the stage lights caught it. */
private val TitleGlow = Shadow(color = Blood.copy(alpha = 0.7f), offset = Offset.Zero, blurRadius = 30f)

/** A short Blood rule under the title that fades out at both ends. */
@Composable
private fun TitleRule() {
    Spacer(
        Modifier
            .size(width = 220.dp, height = 3.dp)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                softGlow(BloodGlow, alpha = 0.35f, cornerRadius = r.x, spread = 8.dp, layers = 5)
                drawRoundRect(brush = RuleBrush, cornerRadius = r)
            },
    )
}

private val RuleBrush = Brush.horizontalGradient(
    0f to Color.Transparent,
    0.3f to Blood,
    0.5f to BloodGlow,
    0.7f to Blood,
    1f to Color.Transparent,
)

/**
 * Pet [i] of the lineup with the row at [row]: the idle sprite, feet on the floor at its place in the row,
 * and its name, level and status underneath. Under the spotlight it is full size and in full color; further
 * out it is smaller (feet still on the floor), dimmed and desaturated, and its label fades away. Its place,
 * size and label are read in layout and draw; its dim and color change in [LIT_STEPS] steps.
 */
@Composable
private fun LineupPet(
    loader: AssetLoader,
    pet: PetState,
    i: Int,
    row: () -> Float,
    centerX: Float,
    pitch: Float,
    floorY: Float,
    labelY: Float,
    cellH: Float,
    labelWidth: Dp,
    idleOffsetMs: Long,
) {
    val lit by remember(i, row) { derivedStateOf { quantize(carouselLit(i - row()), LIT_STEPS) } }
    SpriteView(
        loader = loader,
        pet = pet.id,
        anim = Anim.IDLE,
        heightPx = cellH,
        tint = null,
        tintStrength = 0f,
        dim = OFF_DIM * (1f - lit),
        saturation = lerp(OFF_SATURATION, 1f, lit),
        shadowOpacity = 0.8f,
        loopOffsetMs = idleOffsetMs,
        modifier = Modifier
            .placeAt(x = { carouselX(i, row(), centerX, pitch) }, y = { floorY })
            .graphicsLayer {
                val s = carouselScale(i - row())
                scaleX = s
                scaleY = s
                // Shrink toward the feet, so every pet keeps standing on the floor.
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
    )
    Column(
        Modifier
            .placeAt(x = { carouselX(i, row(), centerX, pitch) }, y = { labelY }, pivotY = 0f)
            .width(labelWidth)
            .graphicsLayer {
                val d = i - row()
                alpha = carouselLabelAlpha(d)
                val s = lerp(OFF_LABEL_SCALE, 1f, carouselLit(d))
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A fixed-height row, so a name that steps down to fit (GHOSTFACE on 4:3) keeps the rows below aligned.
        Box(Modifier.height(with(LocalDensity.current) { NAME_SIZE.toDp() }), contentAlignment = Alignment.Center) {
            BasicText(
                text = pet.id.displayName.uppercase(),
                style = displayStyle(NAME_SIZE, tracking = 0.06f, lineHeight = NAME_SIZE).copy(textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = NAME_MIN_SIZE, maxFontSize = NAME_SIZE, stepSize = 1.sp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(text = "LV ${pet.level}", style = displayStyle(22.sp, color = TextLo, tracking = 0.12f, lineHeight = 22.sp))
        Spacer(Modifier.height(8.dp))
        StatusChip(statusOf(pet))
    }
}

// ---- the stage: back wall, floor, beams, pools, dust ----

/** Pale tungsten for the waiting pets' beams, and the red of the spotlight. */
private val WarmLight = Color(0xFFFFF0DC)
private val WarmPool = Color(0xFFFFEBD2)
private val RedLight = Color(0xFFFF5A50)
private val RedPool = Color(0xFFFF786E)
private val Mote = Color(0xFFFFC2B8)

/** A light pool on the floor is this flat (height / width). */
private const val POOL_ASPECT = 0.24f

/** The beams of the dark slots and the spotlight, as overall strengths. */
private const val OFF_BEAM = 0.55f
private const val OFF_POOL = 0.5f

private const val MOTES = 22

/**
 * The stage: a back wall and floor, a faint red haze where they meet, a pale beam and pool over every pet
 * in the lineup (moving with the row, [pitch] apart, and fading as a pet slides into the spotlight), and the
 * red spotlight fixed at the centre (beam, pool, and a halo behind the pet, sized to its body).
 *
 * It is a lot of soft, overlapping fill, so it renders into its own offscreen layer, which the renderer
 * reuses until the row moves: while the row rests (nearly always) a frame costs one full-screen blit, not
 * a dozen gradient fills. [row] is only read while drawing, so a slide never recomposes.
 */
@Composable
private fun Stage(count: Int, bodies: List<Float>, pitch: Float, cellH: Float, row: () -> Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val seam = h * WALL_SEAM
                val floorY = h * FLOOR
                val beamTop = h * BEAM_TOP
                val beamBottom = h * BEAM_BOTTOM
                val bottomHalf = min(pitch * 0.47f, h * 0.25f)
                val topHalf = bottomHalf * 0.2f
                val poolRadius = min(pitch * 0.46f, h * 0.22f)

                val wall = Brush.verticalGradient(
                    0f to Color(0xFF030304),
                    1f to Color(0xFF0F0D12),
                    startY = 0f,
                    endY = seam,
                )
                val floor = Brush.verticalGradient(
                    0f to Color(0xFF1A151D),
                    0.3f to Color(0xFF100D12),
                    1f to Color(0xFF050407),
                    startY = seam,
                    endY = h,
                )
                val haze = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.6f to Color(0x2E5A1218),
                    1f to Color.Transparent,
                    startY = seam - h * 0.16f,
                    endY = seam + h * 0.05f,
                )
                val paleBeam = beamBrush(WarmLight, 0.20f, 0.07f, beamTop, beamBottom)
                val redBeam = beamBrush(RedLight, 0.34f, 0.11f, beamTop, beamBottom)
                val palePool = poolBrush(WarmPool, 0.30f, poolRadius)
                val redPool = poolBrush(RedPool, 0.50f, poolRadius)
                val haloRadius = cellH * 0.6f
                val halo = Brush.radialGradient(
                    0f to BloodGlow.copy(alpha = 0.26f),
                    0.5f to BloodGlow.copy(alpha = 0.09f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = haloRadius,
                )
                val lens = Brush.radialGradient(
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.6f),
                    center = Offset(cx, h * 0.5f),
                    radius = max(w, h) * 0.72f,
                )
                val seamLine = Size(w, 2.dp.toPx())
                // Every beam has the same shape, so its layers are built once around x = 0 and translated.
                val beam = beamLayers(beamTop, beamBottom, topHalf, bottomHalf)

                onDrawBehind {
                    drawRect(wall, size = Size(w, seam))
                    drawRect(floor, topLeft = Offset(0f, seam), size = Size(w, h - seam))
                    drawRect(haze)
                    drawRect(Color.White.copy(alpha = 0.04f), topLeft = Offset(0f, seam), size = seamLine)

                    val r = row()
                    // Each pet's own pale light, moving with the row, gives way as it slides into the red one.
                    for (i in 0 until count) {
                        val x = carouselX(i, r, cx, pitch)
                        if (x < -bottomHalf || x > w + bottomHalf) continue
                        val covered = carouselLit(i - r)
                        drawBeam(beam, x, paleBeam, OFF_BEAM * (1f - covered))
                        drawPool(palePool, poolRadius, x, floorY, OFF_POOL * (1f - covered))
                    }

                    drawBeam(beam, cx, redBeam, 1f)
                    drawPool(redPool, poolRadius, cx, floorY, 1f)
                    // The halo sits behind the body under the spotlight: lower and smaller for a half-height pet.
                    val body = bodyUnderSpot(bodies, r)
                    translate(cx, floorY - cellH * body * 0.45f) {
                        scale(body, pivot = Offset.Zero) { drawCircle(halo, radius = haloRadius, center = Offset.Zero) }
                    }

                    drawRect(lens)
                }
            },
    )
}

/**
 * Dust drifting up through the spotlight at the centre. It changes every frame, so it lives in its own light
 * layer (a couple of dozen small dots) instead of invalidating the cached [Stage].
 */
@Composable
private fun Dust(pitch: Float, time: () -> Long) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val h = size.height
                val bottomHalf = min(pitch * 0.47f, h * 0.25f)
                val topHalf = bottomHalf * 0.2f
                val unit = 1.dp.toPx()
                onDrawBehind {
                    drawMotes(time(), size.width / 2f, h * FLOOR, h * BEAM_TOP, h * BEAM_BOTTOM, topHalf, bottomHalf, unit)
                }
            },
    )
}

/** A beam's light: strongest at the lamp, thinning toward the floor, gone just past it. */
private fun beamBrush(color: Color, top: Float, mid: Float, startY: Float, endY: Float): Brush = Brush.verticalGradient(
    0f to color.copy(alpha = top),
    0.6f to color.copy(alpha = mid),
    1f to Color.Transparent,
    startY = startY,
    endY = endY,
)

/** A pool of light on the floor, drawn around the origin (see [drawPool]). */
private fun poolBrush(color: Color, alpha: Float, radius: Float): Brush = Brush.radialGradient(
    0f to color.copy(alpha = alpha),
    0.55f to color.copy(alpha = alpha * 0.22f),
    1f to Color.Transparent,
    center = Offset.Zero,
    radius = radius,
)

/** An elliptical pool of light centred on ([cx], [cy]); [brush] is built around the origin by [poolBrush]. */
private fun DrawScope.drawPool(brush: Brush, radius: Float, cx: Float, cy: Float, alpha: Float) {
    if (alpha <= 0.005f) return
    translate(cx, cy) {
        scale(scaleX = 1f, scaleY = POOL_ASPECT, pivot = Offset.Zero) {
            drawCircle(brush, radius = radius, center = Offset.Zero, alpha = alpha)
        }
    }
}

/**
 * Dust drifting up through the spotlight: each mote has a fixed lane, speed and twinkle from [hash01],
 * so nothing is allocated or stored per frame.
 */
private fun DrawScope.drawMotes(
    timeMs: Long,
    cx: Float,
    floorY: Float,
    beamTop: Float,
    beamBottom: Float,
    topHalf: Float,
    bottomHalf: Float,
    unit: Float,
) {
    val seconds = timeMs / 1000f
    val rise = floorY - max(beamTop, 0f)
    for (i in 0 until MOTES) {
        val speed = 0.025f + 0.035f * hash01(i, 1)
        val p = (hash01(i, 2) + seconds * speed) % 1f
        val y = floorY - p * rise
        val along = ((y - beamTop) / (beamBottom - beamTop)).coerceIn(0f, 1f)
        val half = lerp(topHalf, bottomHalf, along)
        val sway = sin(seconds * (0.5f + hash01(i, 4)) + i * 1.7f) * 5f * unit
        val x = cx + (hash01(i, 3) - 0.5f) * 1.6f * half + sway
        val twinkle = 0.55f + 0.45f * sin(seconds * (1.3f + hash01(i, 6)) + i * 2.3f)
        val fade = sin(p * PI.toFloat()) // fade in off the floor, out toward the lamp
        drawCircle(Mote, radius = (1.3f + 1.7f * hash01(i, 5)) * unit, center = Offset(x, y), alpha = 0.5f * twinkle * fade)
    }
}
