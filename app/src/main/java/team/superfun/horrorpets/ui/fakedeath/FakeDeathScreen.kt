package team.superfun.horrorpets.ui.fakedeath

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.tv.material3.Text
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.beamLayers
import team.superfun.horrorpets.ui.common.drawBeam
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.safeInsetPadding
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextHi

// ---- the timeline, in ms from the first frame with the scene on screen ----

/** The tombstone and its sting run until here, then the picture tears apart. */
private const val GLITCH_AT = 3_000L

/** "GOTCHA." starts to fall and the pet rises out of the grave. */
private const val REVEAL_AT = 3_600L

/** "GOTCHA." lands (its scale reaches 1.0): the screen shakes, flashes red and the stone cracks. */
private const val IMPACT_AT = REVEAL_AT + 180L

/** "{NAME} NEVER DIES." with its voice line, just after the 0.7s "gotcha!" ends. */
private const val NEVER_DIES_AT = 4_400L

private const val TOTAL_MS = 6_500L

/** OK skips the rest, but only once the tombstone has had this long on screen. */
private const val SKIP_AFTER_MS = 2_000L

/** Starts the clock this long after entering even if the scene has not been decoded yet. */
private const val SCENE_WAIT_MS = 1_500L

/** One frame never advances the timeline more than this, so a stall (or the app being paused) slows it instead of skipping it. */
private const val MAX_STEP_MS = 250L

// ---- staging, as fractions of the screen height ----

/** Black laid over the scene: 0.65 shows it at 35% while the pet is "dead", then it lifts a little. */
private const val DARK_DEAD = 0.65f
private const val DARK_ALIVE = 0.45f

private const val STONE_HEIGHT = 0.43f
private const val STONE_WIDTH = 0.33f

/** The stone is sunk this far into the ground below the floor line. */
private const val STONE_SINK = 0.012f

/** The pet's whole cell, and how far in front of the stone (below the floor line) its feet are. */
private const val PET_HEIGHT = 0.42f
private const val PET_IN_FRONT = 0.025f

private const val RISE_MS = 260L

/**
 * The one-time fake death of [pet]: "R.I.P." on a tombstone in its darkened scene, a glitch, then
 * "GOTCHA." slams in, the pet pops back out of the grave and "{NAME} NEVER DIES." About 6.5s:
 *
 * - 0–3.0s: the pet's current scene darkened to 35%, a moonlit tombstone (`R.I.P.` / name) and the
 *   `tombstone` sting, while the camera slowly pushes in.
 * - 3.0–3.6s: the picture tears (sliced sideways, RGB split, flicker) with the `gotcha` sting.
 * - 3.6–6.5s: `GOTCHA.` falls in (scale 1.6 → 1.0) and lands with a shake and a red flash, the voice
 *   says "gotcha!", the stone cracks and the pet rises from the grave playing JUMP, then WAIT. At 4.4s
 *   `{NAME} NEVER DIES.` fades up with the pet's voice line.
 *
 * OK skips to the end after 2s. [onDone] is called exactly once, by the skip or at the end. Back does
 * nothing: the reveal has to be seen.
 *
 * Music: the router plays no track on this screen, so the pet's music fades out as it starts.
 */
@Composable
fun FakeDeathScreen(
    pet: PetId,
    game: GameState,
    loader: AssetLoader,
    onDone: () -> Unit,
    focusEntry: Any? = Unit,
) {
    val sounds = LocalSoundSink.current
    val scenes = remember(loader, pet) { loader.scenes().getValue(pet) }
    val scene = scenes[(game.pets[pet]?.sceneIndex ?: 0).mod(scenes.size)]
    val tint = remember(scene) { hexColor(scene.tint) }
    val name = pet.displayName.uppercase()
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val latestOnDone by rememberUpdatedState(onDone)

    // Elapsed ms on the timeline. Draw and layer blocks read it directly (no recomposition per frame);
    // the composition itself only follows [stage], which changes four times.
    val clock = remember(pet) { mutableLongStateOf(0L) }
    val stage by remember(pet) { derivedStateOf { stageAt(clock.longValue) } }
    val cracked by remember(pet) { derivedStateOf { clock.longValue >= IMPACT_AT } }
    // Values that only move for part of the timeline: as derived states, the layers that draw them are
    // only redrawn while they actually change, not on every tick of the clock.
    val darkness = remember(pet) { derivedStateOf { darknessAt(clock.longValue) } }
    val backlight = remember(pet) { derivedStateOf { backlightAt(clock.longValue) } }
    val glitchLevel = remember(pet) { derivedStateOf { glitchAt(clock.longValue) } }
    val flash = remember(pet) { derivedStateOf { flashAt(clock.longValue) } }
    var sceneShown by remember(pet) { mutableStateOf(false) }
    val done = remember(pet) { Latch() }

    fun finish() {
        if (done.set) return
        done.set = true
        latestOnDone()
    }

    BackHandler(enabled = focusEntry != null) { /* the reveal has to be seen; OK skips it after 2s */ }
    RequestFocusOnEntry(focus, focusEntry)

    // The pet's two strips are ready long before it rises.
    LaunchedEffect(loader, pet) {
        withContext(Dispatchers.IO) {
            loader.strip(pet, Anim.JUMP)
            loader.strip(pet, Anim.WAIT)
        }
    }

    LaunchedEffect(pet) {
        withTimeoutOrNull(SCENE_WAIT_MS) { snapshotFlow { sceneShown }.first { it } }
        val cues = listOf(
            Cue(0L) { sounds.sfx("tombstone") },
            Cue(GLITCH_AT) { sounds.sfx("gotcha") },
            Cue(REVEAL_AT) { sounds.voice(listOf("gotcha")) },
            Cue(NEVER_DIES_AT) { sounds.voice(listOf("${pet.name.lowercase()}_never_dies")) },
        )
        var next = 0
        var t = 0L
        var last = withFrameMillis { it }
        while (!done.set) {
            while (next < cues.size && t >= cues[next].atMs) cues[next++].play()
            if (t >= TOTAL_MS) {
                finish()
                break
            }
            val now = withFrameMillis { it }
            t += (now - last).coerceIn(0L, MAX_STEP_MS)
            last = now
            clock.longValue = t
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .clickable(interactionSource = interaction, indication = null) {
                if (focusEntry != null && clock.longValue >= SKIP_AFTER_MS) finish()
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val shakePx = with(density) { 14.dp.toPx() }
        val stoneW = with(density) { (h * STONE_WIDTH).toDp() }
        val stoneH = with(density) { (h * STONE_HEIGHT).toDp() }
        val inset = safeInsetPadding()

        // Everything shakes together when GOTCHA lands.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val t = clock.longValue
                    translationX = shakeAt(t, 0) * shakePx
                    translationY = shakeAt(t, 1) * shakePx
                },
        ) {
            // The scene and the grave: torn by the glitch, slowly pushed in while the pet is "dead".
            Box(
                Modifier
                    .fillMaxSize()
                    .glitch(intensity = { glitchLevel.value }, frame = { (clock.longValue / GLITCH_FRAME_MS).toInt() })
                    .graphicsLayer {
                        val s = pushInAt(clock.longValue)
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 0.62f)
                    },
            ) {
                SceneBackdrop(loader = loader, scene = scene) { floorY ->
                    LaunchedEffect(Unit) { sceneShown = true }
                    val feetY = floorY + h * PET_IN_FRONT

                    Backdrop(darkness = { darkness.value }, floorY = floorY, stoneW = h * STONE_WIDTH, stoneH = h * STONE_HEIGHT)
                    Tombstone(
                        name = name,
                        width = stoneW,
                        height = stoneH,
                        cracked = cracked,
                        tint = tint,
                        modifier = Modifier
                            .placeAt(x = { w / 2f }, y = { floorY + h * STONE_SINK })
                            .graphicsLayer {
                                val t = clock.longValue
                                alpha = (t / 600f).coerceIn(0f, 1f)
                                rotationZ = tiltAt(t)
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            },
                    )
                    GraveFront(
                        clock = { clock.longValue },
                        backlight = { backlight.value },
                        floorY = floorY,
                        stoneW = h * STONE_WIDTH,
                        petH = h * PET_HEIGHT,
                    )
                    if (stage >= Stage.REVEAL) {
                        RisingPet(
                            loader = loader,
                            pet = pet,
                            tint = tint,
                            tintStrength = scene.tintStrength,
                            shadowOpacity = scene.shadowOpacity,
                            heightPx = h * PET_HEIGHT,
                            x = w / 2f,
                            feetY = feetY,
                            clock = { clock.longValue },
                        )
                    }
                    Dirt(clock = { clock.longValue }, feetY = feetY, spread = h * STONE_WIDTH)
                }
            }

            // The red flash when GOTCHA lands, under the titles so they stay crisp.
            Spacer(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val a = flash.value
                        if (a > 0f) drawRect(Blood, alpha = a)
                    },
            )

            Box(Modifier.fillMaxSize().padding(inset)) {
                if (stage >= Stage.REVEAL) {
                    Gotcha(
                        Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer {
                                val t = clock.longValue - REVEAL_AT
                                val p = (t / (IMPACT_AT - REVEAL_AT).toFloat()).coerceIn(0f, 1f)
                                val s = lerp(1.6f, 1f, p * p) // accelerating into the impact
                                scaleX = s
                                scaleY = s
                                alpha = (t / 70f).coerceIn(0f, 1f)
                            },
                    )
                }
                if (stage >= Stage.NEVER_DIES) {
                    Tagline(
                        name = name,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer {
                                val p = easeOut(((clock.longValue - NEVER_DIES_AT) / 420f).coerceIn(0f, 1f))
                                alpha = p
                                translationY = (1f - p) * 22.dp.toPx()
                            },
                    )
                }
            }
        }
    }
}

/** What the composition shows; draw code reads the clock for everything in between. */
private enum class Stage { DEAD, GLITCH, REVEAL, NEVER_DIES }

private fun stageAt(t: Long): Stage = when {
    t >= NEVER_DIES_AT -> Stage.NEVER_DIES
    t >= REVEAL_AT -> Stage.REVEAL
    t >= GLITCH_AT -> Stage.GLITCH
    else -> Stage.DEAD
}

/** A sound timed on the timeline. */
private class Cue(val atMs: Long, val play: () -> Unit)

/** Whether [FakeDeathScreen] has called its `onDone` yet. Main thread only. */
private class Latch {
    var set = false
}

// ---- curves of the timeline ----

private fun easeOut(p: Float): Float = 1f - (1f - p) * (1f - p)

private fun easeInOut(p: Float): Float = if (p < 0.5f) 2f * p * p else 1f - 2f * (1f - p) * (1f - p)

/** The camera creeps in on the grave, then cuts back wide for the reveal. */
private fun pushInAt(t: Long): Float =
    if (t < REVEAL_AT) 1f + 0.06f * easeInOut((t / GLITCH_AT.toFloat()).coerceIn(0f, 1f)) else 1f

/** The stone leans a little, and lurches when the pet bursts out in front of it. */
private fun tiltAt(t: Long): Float {
    if (t < IMPACT_AT) return -2.5f
    val p = ((t - IMPACT_AT) / 260f).coerceIn(0f, 1f)
    // A small overshoot, then it settles.
    val back = 1f + 2.2f * (p - 1f) * (p - 1f) * (p - 1f) + 1.2f * (p - 1f) * (p - 1f)
    return lerp(-2.5f, -6.5f, back)
}

private fun darknessAt(t: Long): Float =
    if (t < REVEAL_AT) DARK_DEAD else lerp(DARK_DEAD, DARK_ALIVE, easeOut(((t - REVEAL_AT) / 700f).coerceIn(0f, 1f)))

/** -1..1 screen shake on [axis] (0 = x, 1 = y): fast jitter from the impact, dying away over 380ms. */
private fun shakeAt(t: Long, axis: Int): Float {
    val since = t - IMPACT_AT
    if (since < 0 || since >= 380) return 0f
    val fade = 1f - since / 380f
    return (hash01((since / 32).toInt(), 11 + axis) - 0.5f) * 2f * fade * fade
}

private fun flashAt(t: Long): Float {
    val since = t - IMPACT_AT
    if (since < 0 || since >= 420) return 0f
    return 0.42f * (1f - since / 420f)
}

/** The red light that comes up behind the pet as it returns: a burst at the impact, then a glow. */
private fun backlightAt(t: Long): Float {
    val since = t - IMPACT_AT
    if (since < 0) return 0f
    val burst = 1f - (since / 900f).coerceIn(0f, 1f)
    return 0.55f + 0.45f * burst * burst
}

// ---- the glitch ----

/** The glitch picks new slices, offsets and flicker this often (a choppy, digital rhythm). */
private const val GLITCH_FRAME_MS = 50L
private const val GLITCH_SLICES = 7

private fun glitchAt(t: Long): Float {
    val f = (t / GLITCH_FRAME_MS).toInt()
    return when (t) {
        in GLITCH_AT until REVEAL_AT -> if (hash01(f, 3) < 0.2f) 0.12f else 0.5f + 0.5f * hash01(f, 4)
        in IMPACT_AT until IMPACT_AT + 90 -> 0.35f // a jolt as GOTCHA lands
        else -> 0f
    }
}

/** A color matrix that keeps only channel [c] (0 = red, 1 = green, 2 = blue) and alpha. */
private fun channelOnly(c: Int): ColorFilter =
    ColorFilter.colorMatrix(ColorMatrix(FloatArray(20).also { it[c * 5 + c] = 1f; it[18] = 1f }))

/**
 * Tears whatever this draws, at [intensity] (0 = untouched). It only redraws the content a few times:
 * - an RGB split: the red, green and blue channels drawn apart and added back together over black;
 * - torn slices: a few horizontal bands of the picture slid sideways;
 * - flicker: the odd white or black frame and a few bright scanlines.
 * [frame] picks the random layout, which holds for [GLITCH_FRAME_MS]; it is only read while glitching, so
 * an idle glitch never redraws on its own. The paints are built once, so a glitching frame allocates nothing.
 */
private fun Modifier.glitch(intensity: () -> Float, frame: () -> Int): Modifier = drawWithCache {
    val channels = List(3) { c ->
        Paint().apply {
            colorFilter = channelOnly(c)
            blendMode = BlendMode.Plus
        }
    }
    val bounds = Rect(Offset.Zero, size)
    val unit = 1.dp.toPx()
    onDrawWithContent {
        val k = intensity()
        if (k <= 0f) {
            drawContent()
            return@onDrawWithContent
        }
        val f = frame()
        val split = (4f + 10f * hash01(f, 1)) * unit * k
        val lift = if (hash01(f, 2) > 0.8f) (hash01(f, 5) - 0.5f) * 16f * unit * k else 0f

        drawRect(Color.Black)
        drawChannel(channels[0], bounds, -split, lift)
        drawChannel(channels[1], bounds, 0f, lift)
        drawChannel(channels[2], bounds, split, lift)

        for (i in 0 until GLITCH_SLICES) {
            if (hash01(f, 10 + i) > 0.2f + 0.6f * k) continue
            val top = hash01(f, 20 + i) * size.height
            val band = (0.012f + 0.085f * hash01(f, 30 + i)) * size.height
            val dx = (hash01(f, 40 + i) - 0.5f) * 2f * size.width * 0.08f * k
            clipRect(top = top, bottom = top + band) {
                translate(left = dx) { this@onDrawWithContent.drawContent() }
            }
        }

        val flicker = hash01(f, 60)
        if (flicker > 0.86f) drawRect(Color.White, alpha = 0.10f + 0.16f * k)
        if (flicker < 0.14f) drawRect(Color.Black, alpha = 0.55f * k)
        for (j in 0 until 3) {
            val y = hash01(f, 70 + j) * size.height
            val lineH = (1.5f + 3.5f * hash01(f, 80 + j)) * unit
            drawRect(Color.White, topLeft = Offset(0f, y), size = Size(size.width, lineH), alpha = (0.12f + 0.25f * hash01(f, 90 + j)) * k)
        }
    }
}

/** Draws the content through [paint] (one color channel, added on) shifted by ([dx], [dy]). */
private fun ContentDrawScope.drawChannel(paint: Paint, bounds: Rect, dx: Float, dy: Float) {
    val canvas = drawContext.canvas
    canvas.saveLayer(bounds, paint)
    translate(left = dx, top = dy) { this@drawChannel.drawContent() }
    canvas.restore()
}

// ---- the grave ----

private val Moonlight = Color(0xFFCFDAFF)
private val Earth = Color(0xFF1B1410)
private val EarthRim = Color(0xFF4A3A2C)

/**
 * Behind the stone: the darkness over the scene, a heavy vignette, a cold moonbeam onto the grave, a
 * glow behind the stone's arch so it stands out of the dark, and its contact shadow. (The vignette only
 * darkens the edges, so drawing it under the central stone and pet looks the same as over them, and it
 * rides in this cached layer for free.)
 */
@Composable
private fun Backdrop(darkness: () -> Float, floorY: Float, stoneW: Float, stoneH: Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            // Soft overlapping fills that only change while the darkness lifts: render once into an
            // offscreen layer and let the renderer reuse it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val cx = size.width / 2f
                val beamTop = -size.height * 0.05f
                val beam = Brush.verticalGradient(
                    0f to Moonlight.copy(alpha = 0.20f),
                    0.7f to Moonlight.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                    startY = beamTop,
                    endY = floorY + size.height * 0.04f,
                )
                val beamLayers = beamLayers(beamTop, floorY + size.height * 0.04f, stoneW * 0.16f, stoneW * 1.05f)
                val glowCenter = Offset(cx, floorY - stoneH * 0.72f)
                val glow = Brush.radialGradient(
                    0f to Moonlight.copy(alpha = 0.26f),
                    0.5f to Moonlight.copy(alpha = 0.08f),
                    1f to Color.Transparent,
                    center = glowCenter,
                    radius = stoneW * 1.1f,
                )
                val vignette = Brush.radialGradient(
                    0.38f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.72f),
                    center = Offset(size.width / 2f, size.height * 0.55f),
                    radius = max(size.width, size.height) * 0.7f,
                )
                val shadowRadius = stoneW * 0.75f
                val shadow = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.7f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = shadowRadius,
                )
                onDrawBehind {
                    drawRect(Color.Black, alpha = darkness())
                    drawRect(vignette)
                    drawBeam(beamLayers, cx, beam, 1f)
                    drawCircle(glow, radius = stoneW * 1.1f, center = glowCenter)
                    translate(cx, floorY + size.height * STONE_SINK * 0.5f) {
                        scale(1f, 0.16f, pivot = Offset.Zero) { drawCircle(shadow, shadowRadius, Offset.Zero) }
                    }
                }
            },
    )
}

/**
 * In front of the stone: the grave's earth mound, the red light behind the returning pet, and a low
 * drifting mist.
 */
@Composable
private fun GraveFront(clock: () -> Long, backlight: () -> Float, floorY: Float, stoneW: Float, petH: Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val cx = size.width / 2f
                val moundRadius = stoneW * 0.95f
                val mound = Brush.radialGradient(
                    0f to Earth.copy(alpha = 0.95f),
                    0.65f to Earth.copy(alpha = 0.7f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = moundRadius,
                )
                val backlightCenter = Offset(cx, floorY - petH * 0.42f)
                val backlight = Brush.radialGradient(
                    0f to BloodGlow.copy(alpha = 0.6f),
                    0.45f to Blood.copy(alpha = 0.24f),
                    1f to Color.Transparent,
                    center = backlightCenter,
                    radius = petH * 0.8f,
                )
                val mistRadius = size.width * 0.42f
                val mist = Brush.radialGradient(
                    0f to Moonlight.copy(alpha = 0.15f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = mistRadius,
                )
                onDrawBehind {
                    val t = clock()
                    val light = backlight()
                    if (light > 0f) drawCircle(backlight, radius = petH * 0.8f, center = backlightCenter, alpha = light)
                    translate(cx, floorY + size.height * 0.035f) {
                        scale(1f, 0.2f, pivot = Offset.Zero) { drawCircle(mound, moundRadius, Offset.Zero) }
                    }
                    val seconds = t / 1000f
                    for (i in 0 until 2) {
                        val drift = sin(seconds * (0.35f + 0.2f * i) + i * 2.1f) * size.width * 0.07f
                        translate(cx + drift + (i - 0.5f) * size.width * 0.3f, floorY + size.height * (0.015f + 0.03f * i)) {
                            scale(1f, 0.13f, pivot = Offset.Zero) { drawCircle(mist, mistRadius, Offset.Zero) }
                        }
                    }
                }
            },
    )
}

/**
 * The pet climbing out of the grave: it rises from below the earth in [RISE_MS] (clipped at the ground,
 * so it really comes up out of it) while it plays JUMP, then stands there on WAIT.
 */
@Composable
private fun RisingPet(
    loader: AssetLoader,
    pet: PetId,
    tint: Color,
    tintStrength: Float,
    shadowOpacity: Float,
    heightPx: Float,
    x: Float,
    feetY: Float,
    clock: () -> Long,
) {
    var jumped by remember(pet) { mutableStateOf(false) }
    val groundSlackPx = with(LocalDensity.current) { 3.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                // The broken earth it climbs out of, in front of it, so the cut-off at the ground reads
                // as a hole rather than a clipping line. It sinks away once the pet is out.
                val lipRadius = heightPx * 0.34f
                val lip = Brush.radialGradient(
                    0f to Earth,
                    0.55f to Earth.copy(alpha = 0.92f),
                    0.8f to EarthRim.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = lipRadius,
                )
                onDrawWithContent {
                    val since = clock() - REVEAL_AT
                    if (since < RISE_MS) {
                        clipRect(bottom = feetY + groundSlackPx) { this@onDrawWithContent.drawContent() }
                    } else {
                        drawContent()
                    }
                    val lipAlpha = 1f - ((since - RISE_MS) / 320f).coerceIn(0f, 1f)
                    if (lipAlpha > 0f) {
                        translate(x, feetY + groundSlackPx * 2f) {
                            scale(1f, 0.16f, pivot = Offset.Zero) { drawCircle(lip, lipRadius, Offset.Zero, alpha = lipAlpha) }
                        }
                    }
                }
            },
    ) {
        SpriteView(
            loader = loader,
            pet = pet,
            anim = if (jumped) Anim.WAIT else Anim.JUMP,
            heightPx = heightPx,
            tint = tint,
            tintStrength = tintStrength,
            shadowOpacity = shadowOpacity,
            onFinished = if (jumped) null else ({ jumped = true }),
            modifier = Modifier
                .placeAt(x = { x }, y = { feetY })
                .graphicsLayer {
                    val p = ((clock() - REVEAL_AT) / RISE_MS.toFloat()).coerceIn(0f, 1f)
                    translationY = (1f - easeOut(p)) * heightPx * 0.95f
                },
        )
    }
}

private val DirtColors = listOf(Color(0xFF2A1E17), Color(0xFF4A3A2E), Color(0xFF6E5643))
private const val DIRT_BITS = 26
private const val DIRT_LIFE_S = 0.9f

/** Earth thrown up as the pet bursts out: a few dozen clods for 0.9s, nothing the rest of the time. */
@Composable
private fun Dirt(clock: () -> Long, feetY: Float, spread: Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val unit = 1.dp.toPx()
                val gravity = size.height * 3.4f
                onDrawBehind {
                    val s = (clock() - REVEAL_AT) / 1000f
                    if (s in 0f..DIRT_LIFE_S) {
                        for (i in 0 until DIRT_BITS) {
                            val x0 = size.width / 2f + (hash01(i, 1) - 0.5f) * spread * 0.7f
                            val vx = (hash01(i, 2) - 0.5f) * size.height * 1.3f
                            val vy = -(0.35f + 0.65f * hash01(i, 3)) * size.height * 1.2f
                            val y = feetY + vy * s + 0.5f * gravity * s * s
                            if (y > feetY + unit * 6f) continue // landed again
                            // Irregular clods: each its own width, height and shade.
                            val bw = (3f + 6f * hash01(i, 4)) * unit
                            val bh = bw * (0.5f + 0.6f * hash01(i, 6))
                            drawOval(
                                color = DirtColors[i % DirtColors.size],
                                topLeft = Offset(x0 + vx * s - bw / 2f, y - bh / 2f),
                                size = Size(bw, bh),
                                alpha = 1f - s / DIRT_LIFE_S,
                            )
                        }
                    }
                }
            },
    )
}

// ---- the tombstone ----

private val StoneTop = Color(0xFF88848F)
private val StoneMid = Color(0xFF57535F)
private val StoneBase = Color(0xFF2A2730)
private val Engraving = Color(0xFF141217)
private const val BLOTCHES = 8
private val CrackInk = Color(0xFF121014)

/** Letters cut into the stone: dark, with light catching their lower edge. */
@Composable
private fun engravedStyle(size: TextUnit, tracking: Float) =
    displayStyle(size, color = Engraving, tracking = tracking, lineHeight = size).copy(
        shadow = Shadow(
            color = Color.White.copy(alpha = 0.26f),
            offset = Offset(0f, with(LocalDensity.current) { 1.5.dp.toPx() }),
            blurRadius = 0f,
        ),
        textAlign = TextAlign.Center,
    )

/**
 * A rounded-top headstone drawn in Compose: moonlit grey stone with a bevelled inner border, mottling,
 * moss at its foot and `R.I.P.` / [name] cut into it. [cracked] adds a crack down the arch.
 */
@Composable
private fun Tombstone(name: String, width: Dp, height: Dp, cracked: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width, height)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val arch = CornerRadius(w * 0.5f, w * 0.44f)
                val foot = CornerRadius(4.dp.toPx())
                val outline = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, h, arch, arch, foot, foot))
                }
                val inset = 12.dp.toPx()
                val innerArch = CornerRadius(arch.x - inset, arch.y - inset)
                val inner = Path().apply {
                    addRoundRect(RoundRect(inset, inset, w - inset, h - inset * 2.2f, innerArch, innerArch, foot, foot))
                }
                val body = Brush.verticalGradient(0f to StoneTop, 0.5f to StoneMid, 1f to StoneBase)
                // Weathering: soft dark blotches, kept well inside the silhouette so none needs clipping.
                val blotches = List(BLOTCHES) { i ->
                    val radius = w * (0.14f + 0.10f * hash01(i, 7))
                    val center = Offset(w * (0.27f + 0.46f * hash01(i, 8)), h * (0.26f + 0.58f * hash01(i, 9)))
                    val dark = i % 3 != 0
                    Brush.radialGradient(
                        0f to (if (dark) Color.Black else Color.White).copy(alpha = if (dark) 0.3f else 0.07f),
                        1f to Color.Transparent,
                        center = center,
                        radius = radius,
                    ) to Pair(center, radius)
                }
                val side = Brush.horizontalGradient(
                    0f to Color.White.copy(alpha = 0.12f),
                    0.35f to Color.Transparent,
                    0.7f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.4f),
                )
                val moss = Brush.verticalGradient(0.74f to Color.Transparent, 1f to Color(0x99182014))
                // Rounded volume: the face falls off into shadow toward its edges, lit from the upper left.
                val volume = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.5f),
                    center = Offset(w * 0.42f, h * 0.36f),
                    radius = h * 0.78f,
                )
                // Sunk into the earth: the last stretch of the stone darkens into the ground.
                val sunk = Brush.verticalGradient(0.86f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
                val rim = Brush.verticalGradient(
                    0f to Moonlight.copy(alpha = 0.6f),
                    0.3f to Moonlight.copy(alpha = 0f),
                )
                val crack = crackPath(w, h)
                val bevel = Stroke(width = 2.5.dp.toPx())
                val rimStroke = Stroke(width = 3.dp.toPx())
                val crackStroke = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Miter)
                val crackLight = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Miter)
                val nudge = 1.5.dp.toPx()
                onDrawBehind {
                    drawPath(outline, body)
                    for ((brush, spot) in blotches) drawCircle(brush, radius = spot.second, center = spot.first)
                    drawPath(outline, side)
                    drawPath(outline, tint, alpha = 0.16f)
                    drawPath(outline, volume)
                    drawPath(outline, moss)
                    drawPath(outline, sunk)
                    translate(top = nudge) { drawPath(inner, Color.White.copy(alpha = 0.12f), style = bevel) }
                    drawPath(inner, Color.Black.copy(alpha = 0.38f), style = bevel)
                    drawPath(outline, rim, style = rimStroke)
                    if (cracked) {
                        translate(left = nudge * 0.7f, top = nudge * 0.7f) { drawPath(crack, Color.White.copy(alpha = 0.16f), style = crackLight) }
                        drawPath(crack, CrackInk, style = crackStroke)
                    }
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = height * 0.2f, start = width * 0.13f, end = width * 0.13f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // One line whatever the stone's width: on a narrow stone it steps down rather than wrap.
            BasicText(
                text = "R.I.P.",
                style = engravedStyle(54.sp, tracking = 0.08f),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = 54.sp, stepSize = 1.sp),
            )
            Spacer(Modifier.height(10.dp))
            Spacer(
                Modifier
                    .size(width = width * 0.42f, height = 3.dp)
                    .drawBehind {
                        drawRect(Engraving.copy(alpha = 0.75f))
                        drawRect(Color.White.copy(alpha = 0.22f), topLeft = Offset(0f, size.height), size = Size(size.width, 1.5.dp.toPx()))
                    },
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                text = name,
                style = engravedStyle(38.sp, tracking = 0.06f),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 38.sp, stepSize = 1.sp),
            )
        }
    }
}

/** A jagged crack from the top of the arch down toward the middle, with one branch. */
private fun crackPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.60f, h * 0.0f)
    lineTo(w * 0.55f, h * 0.07f)
    lineTo(w * 0.62f, h * 0.14f)
    lineTo(w * 0.56f, h * 0.22f)
    lineTo(w * 0.63f, h * 0.31f)
    lineTo(w * 0.58f, h * 0.40f)
    moveTo(w * 0.62f, h * 0.14f)
    lineTo(w * 0.72f, h * 0.18f)
    lineTo(w * 0.79f, h * 0.26f)
}

// ---- the titles ----

private val GotchaFill = Brush.verticalGradient(listOf(Color(0xFFFF5560), Color(0xFFB80F1C)))
private val GotchaSize = 112.sp

/** Room around the word for its red bloom, so the bloom never runs past the composable's bounds. */
private val BloomPadH = 72.dp
private val BloomPadV = 34.dp

/**
 * "GOTCHA." in blood red with a dark drop shadow, over a soft red bloom. The bloom is drawn as an
 * ellipse filling this composable (a large text-shadow blur gets cut off at the text's bounds), and the
 * padding that makes room for it is taken back with an offset so the word still sits at the top inset.
 */
@Composable
private fun Gotcha(modifier: Modifier = Modifier) {
    Box(
        modifier
            .offset(y = -BloomPadV)
            .drawWithCache {
                val r = size.width / 2f
                val bloom = Brush.radialGradient(
                    0f to BloodGlow.copy(alpha = 0.40f),
                    0.45f to Blood.copy(alpha = 0.16f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = r,
                )
                onDrawBehind {
                    translate(size.width / 2f, size.height / 2f) {
                        scale(1f, size.height / size.width, pivot = Offset.Zero) { drawCircle(bloom, r, Offset.Zero) }
                    }
                }
            }
            .padding(horizontal = BloomPadH, vertical = BloomPadV),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "GOTCHA.",
            style = displayStyle(GotchaSize, tracking = 0.04f, lineHeight = GotchaSize)
                .copy(brush = GotchaFill, shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 5f), blurRadius = 14f)),
        )
    }
}

/** "{NAME} NEVER DIES." along the bottom, like a poster's tagline. */
@Composable
private fun Tagline(name: String, modifier: Modifier = Modifier) {
    BasicText(
        text = "$name NEVER DIES.",
        style = displayStyle(46.sp, color = TextHi, tracking = 0.12f, halo = true, lineHeight = 46.sp)
            .copy(textAlign = TextAlign.Center),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = 46.sp, stepSize = 1.sp),
        modifier = modifier.fillMaxWidth(),
    )
}
