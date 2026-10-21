package team.superfun.horrorpets.ui.switcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.tv.material3.Text
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.game.statusOf
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.SwitcherMode
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SAFE_INSET_FRACTION
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.StatusChip
import team.superfun.horrorpets.ui.common.beamLayers
import team.superfun.horrorpets.ui.common.drawBeam
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.safeInsetPadding
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextLo

// ---- stage geometry, as fractions of the screen height (or width for the slot x positions) ----

/** The line every pet's feet stand on: the stage floor the spotlights hit. */
private const val FLOOR = 0.66f

/** Where the back wall meets the stage floor, just behind the feet. */
private const val WALL_SEAM = 0.62f

/** Each pet's whole sprite cell is this tall (the same on 4:3 and 16:9, and it is never cropped). */
private const val PET_HEIGHT = 0.36f

/** Top of the name / level / status block under each pet. */
private const val LABEL_TOP = 0.725f

/** The beams start just above the top edge, like lamps hanging in a rig, and fade out just past the floor. */
private const val BEAM_TOP = -0.04f
private const val BEAM_BOTTOM = 0.71f

/** Slot centres: four pets to pick from, or the three opponents left once the player's own pet is out. */
private val FOUR_SLOTS = listOf(0.14f, 0.38f, 0.62f, 0.86f)
private val THREE_SLOTS = listOf(0.20f, 0.50f, 0.80f)

// ---- the look of a pet waiting in the dark ----

private const val SPOT_MOVE_MS = 250
private const val OFF_DIM = 0.62f
private const val OFF_SATURATION = 0.4f
private const val OFF_LABEL_ALPHA = 0.5f
private const val OFF_LABEL_SCALE = 0.86f

/**
 * Pet names under the spotlights; a long one steps down to fit its slot. The floor is low enough that the
 * nine letters of GHOSTFACE fit the narrowest label (an edge slot on 4:3, see [labelWidthPx]) at any font
 * scale the game honors (up to 1.15x, see `cappedFontScale`).
 */
private val NAME_SIZE = 40.sp
private val NAME_MIN_SIZE = 18.sp

/** Idle loops start this far apart, so the four pets breathe out of step. */
private const val IDLE_STAGGER_MS = 230L

/**
 * "Choose your killer": every pet stands idle and uncropped in its own spotlight on a dark stage. The
 * pet under the moving red spotlight is in full color; the others wait dimmed and desaturated in pale,
 * faint beams. Left / right move the spotlight (250ms), OK picks, Back cancels.
 *
 * The same stage picks a fight opponent ([SwitcherMode.OPPONENT]): the player's own pet is left out and
 * the remaining three stand wider apart.
 *
 * Where the spotlight starts: on the current pet when switching ([SwitcherMode.SELECT]), and on the first
 * slot when onboarding or choosing an opponent. While onboarding there is nothing to go back to, so Back
 * leaves the app ([onExit]).
 *
 * [focusEntry] is the router's screen-entry key (see `RequestFocusOnEntry`): a new value takes focus and
 * puts the spotlight back on its starting pet; null (the screen is fading out) turns off Back and the keys.
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
    val pets = remember(mode, game.selected) {
        PetId.entries.filter { mode != SwitcherMode.OPPONENT || it != game.selected }
    }
    val slots = slotsFor(pets.size)
    val initial = when (mode) {
        SwitcherMode.SELECT -> pets.indexOf(game.selected).coerceAtLeast(0)
        SwitcherMode.ONBOARD, SwitcherMode.OPPONENT -> 0
    }
    var index by remember(mode) { mutableIntStateOf(initial) }
    val spot = remember(mode) { Animatable(initial.toFloat()) }
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val entered = focusEntry != null

    // Only the screen being entered handles Back: a switcher that is fading out must never act on it.
    BackHandler(enabled = entered) { if (mode == SwitcherMode.ONBOARD) onExit() else onBack() }
    RequestFocusOnEntry(focus, focusEntry)

    // The router reuses a leaving switcher when the player flips straight back to it: start over.
    LaunchedEffect(focusEntry) {
        if (focusEntry != null && index != initial) {
            index = initial
            spot.snapTo(initial.toFloat())
        }
    }
    LaunchedEffect(index) {
        spot.animateTo(index.toFloat(), tween(SPOT_MOVE_MS, easing = FastOutSlowInEasing))
    }

    // Time for the dust drifting in the spotlight; only the dust layer's draw reads it.
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { clock.longValue = it - start }
    }

    fun move(delta: Int) {
        val next = (index + delta).coerceIn(0, pets.lastIndex)
        if (next == index) return
        index = next
        sounds.sfx("ui_move")
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (!entered) return@onKeyEvent false
                when (event.key) {
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
        val pitch = if (slots.size > 1) (slots[1] - slots[0]) * w else w * 0.3f
        val insetPx = w * SAFE_INSET_FRACTION
        val density = LocalDensity.current

        Stage(slots = slots, cellH = h * PET_HEIGHT, spot = { spot.value })
        Dust(slots = slots, spot = { spot.value }, time = { clock.longValue })

        pets.forEachIndexed { i, pet ->
            key(pet) {
                PetSlot(
                    loader = loader,
                    pet = game.pets[pet] ?: PetState(pet),
                    focused = i == index,
                    x = slots[i] * w,
                    floorY = h * FLOOR,
                    labelY = h * LABEL_TOP,
                    cellH = h * PET_HEIGHT,
                    labelWidth = with(density) { labelWidthPx(slots[i] * w, w, pitch, insetPx).toDp() },
                    idleOffsetMs = i * IDLE_STAGGER_MS,
                )
            }
        }

        Column(
            Modifier.fillMaxSize().padding(safeInsetPadding()),
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

/**
 * How wide the label under a pet centred at [x] may be: most of a slot's [pitch], but never wider than
 * twice its distance to the nearer safe-inset edge, so an edge slot's name (GHOSTFACE on 4:3 or a CRT)
 * steps down to fit instead of running into the overscan. Labels are only ever scaled down (unfocused),
 * so the cap holds for the focused label too.
 */
private fun labelWidthPx(x: Float, w: Float, pitch: Float, insetPx: Float): Float =
    min(pitch * 0.94f, 2f * (min(x, w - x) - insetPx)).coerceAtLeast(0f)

private fun slotsFor(count: Int): List<Float> = when (count) {
    4 -> FOUR_SLOTS
    3 -> THREE_SLOTS
    else -> List(count) { (it + 0.5f) / count }
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
 * One pet on its mark: the idle sprite with its feet at ([x], [floorY]), and its name, level and status
 * underneath. Under the spotlight it is in full color; otherwise dimmed, desaturated, its label faded.
 */
@Composable
private fun PetSlot(
    loader: AssetLoader,
    pet: PetState,
    focused: Boolean,
    x: Float,
    floorY: Float,
    labelY: Float,
    cellH: Float,
    labelWidth: Dp,
    idleOffsetMs: Long,
) {
    val lit by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(SPOT_MOVE_MS, easing = FastOutSlowInEasing),
        label = "slotLit",
    )
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
        modifier = Modifier.placeAt(x = { x }, y = { floorY }),
    )
    Column(
        Modifier
            .placeAt(x = { x }, y = { labelY }, pivotY = 0f)
            .width(labelWidth)
            .graphicsLayer {
                alpha = lerp(OFF_LABEL_ALPHA, 1f, lit)
                val s = lerp(OFF_LABEL_SCALE, 1f, lit)
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
 * The stage: a back wall and floor, a faint red haze where they meet, a pale beam and pool over every
 * dark slot, and the red spotlight (beam, pool and a halo behind the pet) at [spot], a slot index that
 * slides between slots.
 *
 * It is a lot of soft, overlapping fill, so it renders into its own offscreen layer, which the renderer
 * reuses until [spot] changes: while the spotlight rests (nearly always) a frame costs one full-screen
 * blit, not a dozen gradient fills. [spot] is only read while drawing, so a move never recomposes.
 */
@Composable
private fun Stage(slots: List<Float>, cellH: Float, spot: () -> Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val seam = h * WALL_SEAM
                val floorY = h * FLOOR
                val beamTop = h * BEAM_TOP
                val beamBottom = h * BEAM_BOTTOM
                val pitch = if (slots.size > 1) (slots[1] - slots[0]) * w else w * 0.3f
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
                val halo = Brush.radialGradient(
                    0f to BloodGlow.copy(alpha = 0.26f),
                    0.5f to BloodGlow.copy(alpha = 0.09f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = cellH * 0.6f,
                )
                val lens = Brush.radialGradient(
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.6f),
                    center = Offset(w / 2f, h * 0.5f),
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

                    val s = spot()
                    // A slot's own pale light gives way as the red spotlight slides over it.
                    for ((i, fx) in slots.withIndex()) {
                        val covered = (1f - abs(s - i)).coerceIn(0f, 1f)
                        drawBeam(beam, fx * w, paleBeam, OFF_BEAM * (1f - covered))
                        drawPool(palePool, poolRadius, fx * w, floorY, OFF_POOL * (1f - covered))
                    }

                    val spotX = slotX(slots, s) * w
                    drawBeam(beam, spotX, redBeam, 1f)
                    drawPool(redPool, poolRadius, spotX, floorY, 1f)
                    translate(spotX, floorY - cellH * 0.45f) { drawCircle(halo, radius = cellH * 0.6f, center = Offset.Zero) }

                    drawRect(lens)
                }
            },
    )
}

/**
 * Dust drifting up through the spotlight. It changes every frame, so it lives in its own light layer
 * (a couple of dozen small dots) instead of invalidating the cached [Stage].
 */
@Composable
private fun Dust(slots: List<Float>, spot: () -> Float, time: () -> Long) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val h = size.height
                val w = size.width
                val pitch = if (slots.size > 1) (slots[1] - slots[0]) * w else w * 0.3f
                val bottomHalf = min(pitch * 0.47f, h * 0.25f)
                val topHalf = bottomHalf * 0.2f
                val unit = 1.dp.toPx()
                onDrawBehind {
                    val spotX = slotX(slots, spot()) * w
                    drawMotes(time(), spotX, h * FLOOR, h * BEAM_TOP, h * BEAM_BOTTOM, topHalf, bottomHalf, unit)
                }
            },
    )
}

/** The x (fraction of the width) of a spotlight at [spot], a slot index that may sit between two slots. */
private fun slotX(slots: List<Float>, spot: Float): Float {
    if (slots.size == 1) return slots[0]
    val i = floor(spot).toInt().coerceIn(0, slots.size - 2)
    return lerp(slots[i], slots[i + 1], (spot - i).coerceIn(0f, 1f))
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
