package team.superfun.horrorpets.ui.training

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.Crop
import team.superfun.horrorpets.assets.SceneSpec
import team.superfun.horrorpets.assets.coverCrop
import team.superfun.horrorpets.game.training.DrawCall
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextHi

// ---- the spin, in game ms from the reel starting ----

/** Flat out until here, then slowing for 2.4 s to land on the game at [REEL_LAND_MS]. */
internal const val REEL_FULL_SPEED_MS = 1_600L
internal const val REEL_LAND_MS = 4_000L

/** "GET READY..." this long after landing, then the game starts. */
internal const val REEL_READY_MS = 800L

/** `reel_spin`'s length: it plays again every time it ends, until the reel lands. */
private const val REEL_SPIN_SOUND_MS = 2_000L

/** The white flash as the reel locks on its game. */
private const val REEL_FLASH_MS = 250L

/** About how fast the reel spins flat out, in frames a second (the exact speed makes it land on its game). */
private const val REEL_TOP_SPEED = 6f

/**
 * The reel's motion: the film runs [frames] (the games in the order they repeat down the strip) past the gate flat
 * out, then slows to a stop with its [landIndex]th game in the gate, [total] frames along, at [REEL_LAND_MS]. Frame
 * `k` along the strip is [gameAt] `k`; frame 0 is in the gate at the start.
 */
internal class ReelSpin(val frames: List<TrainingGame>, landIndex: Int) {
    init {
        require(landIndex in frames.indices) { "the reel must land on one of its frames" }
    }

    /** How many frames the film runs in all: about [REEL_TOP_SPEED]'s worth, ending on the [landIndex]th game. */
    val total: Int = run {
        var n = ceil(REEL_TOP_SPEED * RUN_SECONDS).toInt()
        while (n.mod(frames.size) != landIndex) n++
        n
    }

    /** Flat out, in frames a second: what runs [total] frames by [REEL_LAND_MS]. */
    private val topSpeed: Float = total / RUN_SECONDS

    /** The game on frame [frame] of the strip. */
    fun gameAt(frame: Int): TrainingGame = frames[frame.mod(frames.size)]

    /** How many frames the film has run by [ms]: flat out, then easing off (a cubic) to stop on [total]. */
    fun position(ms: Long): Float = when {
        ms <= 0L -> 0f
        ms < REEL_FULL_SPEED_MS -> topSpeed * ms / 1000f
        ms < REEL_LAND_MS -> {
            val u = (ms - REEL_FULL_SPEED_MS) / SLOWDOWN_MS.toFloat()
            val rest = 1f - u
            topSpeed * FULL_SECONDS + topSpeed * SLOWDOWN_SECONDS / 3f * (1f - rest * rest * rest)
        }
        else -> total.toFloat()
    }

    /** How fast the film runs at [ms], in frames a second. */
    fun speed(ms: Long): Float = when {
        ms < REEL_FULL_SPEED_MS -> topSpeed
        ms < REEL_LAND_MS -> {
            val rest = 1f - (ms - REEL_FULL_SPEED_MS) / SLOWDOWN_MS.toFloat()
            topSpeed * rest * rest
        }
        else -> 0f
    }

    /**
     * How smeared the frames look at [ms]: 1 (all motion blur) from 60% of top speed up, 0 (sharp) below a frame a
     * second.
     */
    fun blur(ms: Long): Float = ((speed(ms) - 1f) / (topSpeed * 0.6f - 1f)).coerceIn(0f, 1f)

    companion object {
        private const val SLOWDOWN_MS = REEL_LAND_MS - REEL_FULL_SPEED_MS
        private const val FULL_SECONDS = REEL_FULL_SPEED_MS / 1000f
        private const val SLOWDOWN_SECONDS = SLOWDOWN_MS / 1000f

        /**
         * Seconds' worth of top speed the whole run covers: flat out, plus a third of the slowdown (the cubic's area).
         */
        private const val RUN_SECONDS = FULL_SECONDS + SLOWDOWN_SECONDS / 3f

        /** The reel for [options], landing on [landOn] (added to the strip should it not be among them). */
        fun of(options: List<TrainingGame>, landOn: TrainingGame): ReelSpin {
            val frames = if (landOn in options) options else options + landOn
            return ReelSpin(frames, frames.indexOf(landOn))
        }
    }
}

// ---- the look of a frame ----

/** How much a frame's scene is dimmed, so its game's picture and name stand out. */
private const val FRAME_DIM = 0.45f

/** The game's picture: centred this far down the frame, its size unit this share of the frame's height. */
private const val ART_Y = 0.42f
private const val ART_SIZE = 0.3f

/** The game's name: centred this far down the frame. */
private const val NAME_Y = 0.8f

/** The blurred frames are made this many times smaller than the play area, then blurred down their columns. */
private const val SMEAR_DOWNSCALE = 4
private const val SMEAR_RADIUS = 0.07f // of a frame's height, each pass
private const val SMEAR_PASSES = 2

/** The game's name, ghosted down the frame as it spins: this many copies over this share of the frame's height. */
private const val NAME_GHOSTS = 4
private const val NAME_GHOST_SPREAD = 0.22f

private val NameOutline = Color(0xFF0B0709)

/**
 * TRAIN's reel: a strip of film frames, one for each game on offer ([options]), each the pet's [scene] dimmed with a
 * picture of the game and its name. It runs past the gate flat out, smeared (`reel_spin` playing on a loop), slows
 * for 2.4 s and lands on [landOn] with `reel_stop` and a flash; "GET READY..." and the pet's [best] in it show, and
 * [REEL_READY_MS] later [onReady] starts the game. It plays on the training [clock], so it holds while that is paused.
 *
 * Its art loads first (the scene, the props, the smeared frames): [onLoaded] says when, and the spin starts then. The
 * shell keeps the clock paused until then, so the spin is at 0 when it starts. The frame in the gate fills the play
 * area exactly as the shell's scene behind it does, so once landed the game can take over under it.
 */
@Composable
internal fun TrainingReel(
    options: List<TrainingGame>,
    landOn: TrainingGame,
    loader: AssetLoader,
    scene: SceneSpec,
    best: Int,
    clock: GameClock,
    onLoaded: () -> Unit,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spin = remember(options, landOn) { ReelSpin.of(options, landOn) }
    val sounds = LocalSoundSink.current
    val latestOnLoaded by rememberUpdatedState(onLoaded)
    val latestOnReady by rememberUpdatedState(onReady)

    var art by remember { mutableStateOf<ReelArt?>(null) }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var smears by remember { mutableStateOf<Map<TrainingGame, ImageBitmap>?>(null) }
    LaunchedEffect(loader, scene, spin) {
        art = withContext(Dispatchers.IO) { loadReelArt(loader, scene, spin.frames) }
    }
    LaunchedEffect(art, view) {
        val loaded = art ?: return@LaunchedEffect
        if (view.width <= 0 || view.height <= 0) return@LaunchedEffect
        smears = withContext(Dispatchers.Default) {
            try {
                spin.frames.distinct().associateWith { bakeSmear(loaded, it, view, scene.floorY) }
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "No smeared reel frames: out of memory", e)
                emptyMap() // the reel spins sharp
            }
        }
    }

    // The spin's sounds and its end, on the training clock, from the moment the art is in.
    val ready = smears != null
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        latestOnLoaded()
        var at = 0L
        while (at < REEL_LAND_MS) {
            sounds.sfx("reel_spin")
            at += REEL_SPIN_SOUND_MS
            clock.waitUntil(minOf(at, REEL_LAND_MS))
        }
        sounds.sfx("reel_stop")
        clock.waitUntil(REEL_LAND_MS + REEL_READY_MS)
        latestOnReady()
    }

    val landed by remember { derivedStateOf { clock.nowMs >= REEL_LAND_MS } }
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .fillMaxSize()
                .onSizeChanged { view = it }
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val shown = art
                    val crop = shown?.let { coverCrop(it.scene.width, it.scene.height, w.roundToInt(), h.roundToInt(), scene.floorY) }
                    val nameStyle = displayStyle(46.sp, tracking = 0.04f)
                    val names = spin.frames.distinct().associateWith {
                        measurer.measure(it.title.uppercase(), nameStyle, maxLines = 1, softWrap = false)
                    }
                    val nameGlow = Shadow(BloodGlow, Offset.Zero, blurRadius = 46.sp.toPx() * 0.55f)
                    val nameRim = Stroke(width = 4.dp.toPx(), join = StrokeJoin.Round)
                    onDrawBehind {
                        val t = clock.nowMs
                        val pos = spin.position(t)
                        val blur = spin.blur(t)
                        val baked = smears
                        val pitch = h * FILM_PITCH
                        drawRect(FilmBlack)
                        // Black film until the art is in, and the spin with it.
                        if (baked == null || shown == null || crop == null) return@onDrawBehind
                        // The frame in the gate (or leaving it upward) and the one coming up under it.
                        val first = floor(pos).toInt()
                        for (j in first..first + 1) {
                            val top = (j - pos) * pitch
                            if (top >= h || top + pitch <= 0f) continue
                            val game = spin.gameAt(j)
                            val smear = baked[game]
                            if (smear == null || blur < 1f) drawFramePicture(shown, game, crop, top, w, h)
                            if (smear != null && blur > 0f) {
                                drawImage(
                                    smear,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(smear.width, smear.height),
                                    dstOffset = IntOffset(0, top.roundToInt()),
                                    dstSize = IntSize(ceil(w).toInt(), ceil(pitch).toInt()),
                                    alpha = blur,
                                    filterQuality = FilterQuality.Low,
                                )
                            }
                            val name = names.getValue(game)
                            val at = Offset((w - name.size.width) / 2f, top + NAME_Y * h - name.size.height / 2f)
                            drawName(name, at, sharp = 1f - blur, glow = nameGlow, rim = nameRim, spread = NAME_GHOST_SPREAD * h * blur)
                        }
                        if (t >= REEL_LAND_MS) {
                            drawGatePointers(w, h)
                            val flash = 1f - (t - REEL_LAND_MS) / REEL_FLASH_MS.toFloat()
                            if (flash > 0f) drawRect(Color.White, alpha = 0.55f * flash)
                        }
                    }
                },
        )

        val safe = safeInset()
        GlowText(
            "TRAINING",
            displayStyle(30.sp, tracking = 0.06f, lineHeight = 32.sp),
            Modifier.align(Alignment.TopCenter).padding(top = safe.vertical),
        )
        if (landed) {
            BasicText(
                "BEST $best",
                style = displayStyle(20.sp, FontWeight.Bold, color = TrainingGold, tracking = 0.05f, halo = true),
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = safe.horizontal, top = safe.vertical + 4.dp),
            )
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = safe.vertical)) {
                BasicText(
                    "GET READY...",
                    style = displayStyle(18.sp, FontWeight.Bold, color = TrainingGold, tracking = 0.08f, halo = true),
                    maxLines = 1,
                )
            }
        }
    }
}

private const val TAG = "HorrorPets"

private suspend fun GameClock.waitUntil(ms: Long) = waitMs(ms - nowMs)

/** A frame's game name: outlined over a red glow when sharp, and ghosted down the frame by [spread] as it smears. */
private fun DrawScope.drawName(name: TextLayoutResult, at: Offset, sharp: Float, glow: Shadow, rim: Stroke, spread: Float) {
    if (sharp > 0f) {
        drawText(name, color = BloodGlow, topLeft = at, alpha = 0.6f * sharp, shadow = glow)
        drawText(name, color = NameOutline, topLeft = at, alpha = sharp, drawStyle = rim)
        drawText(name, color = TextHi, topLeft = at, alpha = sharp)
    }
    val ghosts = 1f - sharp
    if (ghosts <= 0f) return
    for (k in 0 until NAME_GHOSTS) {
        val dy = (k / (NAME_GHOSTS - 1f) - 0.5f) * spread
        drawText(name, color = TextHi, topLeft = at + Offset(0f, dy), alpha = 0.3f * ghosts)
    }
}

/** The slot machine's pointers: a gold arrow either side of the gate, pointing in at the game it landed on. */
private fun DrawScope.drawGatePointers(w: Float, h: Float) {
    val length = h * 0.075f
    for ((x, degrees) in listOf(length * 0.75f to 0f, w - length * 0.75f to 180f)) {
        val arrow = arrowPath(Offset(x, h / 2f), length, degrees)
        drawPath(arrow, BloodGlow, alpha = 0.35f, style = Stroke(length * 0.3f, join = StrokeJoin.Round))
        drawPath(arrow, TrainingGold)
        drawPath(arrow, NameOutline, style = Stroke(length * 0.05f, join = StrokeJoin.Round))
    }
}

// ---- the frames' pictures ----

/**
 * What the reel's frames are drawn from: the pet's [scene], the [tomb] Hide and Seek shows (and its shadow), the
 * [organs] Organ Drop does.
 */
private class ReelArt(val scene: ImageBitmap, val tomb: ImageBitmap?, val organs: List<ImageBitmap>) {
    val tombShadow = tombShadow()
}

/** The organs on Organ Drop's frame: the heart, the brain and the eyeball (indexes into [ORGAN_ART]). */
private val ReelOrgans = listOf(0, 1, 2)

/** Decodes the art for [games]' frames on [scene] (call it off the main thread). */
private fun loadReelArt(loader: AssetLoader, scene: SceneSpec, games: List<TrainingGame>): ReelArt = ReelArt(
    scene = loader.sceneBitmap(scene),
    tomb = if (TrainingGame.HIDE_AND_SEEK in games) loader.prop(TOMBSTONE_ART[0]) else null,
    organs = if (TrainingGame.ORGAN_DROP in games) ReelOrgans.map { loader.prop(ORGAN_ART[it]) } else emptyList(),
)

/**
 * One frame [top] px down the reel, [w] x [h]: the scene placed by [crop] (as the shell's scene behind is), dimmed,
 * with [game]'s picture on it. Clipped to the frame.
 */
private fun DrawScope.drawFramePicture(art: ReelArt, game: TrainingGame, crop: Crop, top: Float, w: Float, h: Float) {
    clipRect(0f, top, w, top + h) {
        drawImage(
            art.scene,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(art.scene.width, art.scene.height),
            dstOffset = IntOffset(crop.offsetX.roundToInt(), (top + crop.offsetY).roundToInt()),
            // Rounded up, as the scene behind is, so the landed frame sits exactly on it.
            dstSize = IntSize(ceil(art.scene.width * crop.scale).toInt() + 1, ceil(art.scene.height * crop.scale).toInt() + 1),
            filterQuality = FilterQuality.High,
        )
        drawRect(Color.Black, Offset(0f, top), Size(w, h), alpha = FRAME_DIM)
        drawGameArt(game, art, Offset(w / 2f, top + ART_Y * h), ART_SIZE * h)
    }
}

/**
 * [game]'s picture around [c], [s] its size unit (about half its height): Slash Says' glowing dots and a slash
 * through them, Quick Draw's D-pad ring with its call lit, a Hide and Seek tombstone, Organ Drop's falling organs.
 */
private fun DrawScope.drawGameArt(game: TrainingGame, art: ReelArt, c: Offset, s: Float) {
    when (game) {
        TrainingGame.SLASH_SAYS -> drawSlashDots(c, s)
        TrainingGame.QUICK_DRAW -> drawDrawRing(DrawRingShapes(c, 0.82f * s), lit = DrawCall.UP, pop = 1f, timeLeft = 0.7f, ok = null)
        TrainingGame.HIDE_AND_SEEK -> art.tomb?.let { drawTombstone(it, art.tombShadow, Offset(c.x, c.y + 0.81f * s), 1.75f * s) }
        TrainingGame.ORGAN_DROP -> art.organs.forEachIndexed { i, organ ->
            val (dx, dy, size) = OrganSpots[i]
            val at = Offset(c.x + dx * s, c.y + dy * s)
            val height = size * s
            // A speed streak above each one as it falls.
            drawLine(
                Color.White,
                Offset(at.x, at.y - height * 1.2f),
                Offset(at.x, at.y - height * 0.45f),
                strokeWidth = height * 0.3f,
                cap = StrokeCap.Round,
                alpha = 0.16f,
            )
            drawProp(organ, at, height)
        }
    }
}

/** Where Organ Drop's frame puts each of [ReelOrgans]: x and y from the centre, and height, in size units. */
private val OrganSpots = listOf(Triple(-0.8f, -0.35f, 0.78f), Triple(0.72f, -0.55f, 0.84f), Triple(0.02f, 0.45f, 0.62f))

/** [image] [height] tall, centred on [center]. */
private fun DrawScope.drawProp(image: ImageBitmap, center: Offset, height: Float) {
    val width = height * image.width / image.height
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset((center.x - width / 2f).roundToInt(), (center.y - height / 2f).roundToInt()),
        dstSize = IntSize(width.roundToInt(), height.roundToInt()),
        filterQuality = FilterQuality.High,
    )
}

/**
 * Slash Says' dots, around and above, in the game's colours ([SlashDotColors]); three are lit, in slash order, and
 * slashed through.
 */
private val SlashAngles = floatArrayOf(205f, 250f, 295f, 340f, 25f, 155f)
private val SlashLit = listOf(1, 2, 4)

private fun DrawScope.drawSlashDots(c: Offset, s: Float) {
    val r = 0.12f * s
    val dots = SlashAngles.map {
        val a = Math.toRadians(it.toDouble())
        Offset(c.x + 1.0f * s * cos(a).toFloat(), c.y + 0.66f * s * sin(a).toFloat())
    }
    val trail = Path().apply {
        SlashLit.forEachIndexed { i, dot -> if (i == 0) moveTo(dots[dot].x, dots[dot].y) else lineTo(dots[dot].x, dots[dot].y) }
    }
    drawSlashTrail(trail, r)
    dots.forEachIndexed { i, p ->
        val lit = i in SlashLit
        val color = SlashDotColors[i]
        drawSlashDot(p, r, color, light = if (lit) 1f else 0f, glow = if (lit) slashDotGlow(color, r) else null)
    }
}

// ---- the smear ----

/**
 * [game]'s frame as the reel shows it spinning flat out: the frame and the black line under it (a whole pitch) at a
 * [SMEAR_DOWNSCALE]th of the [view]'s size, blurred down its columns, wrapping round, so the tiles run on into each
 * other as one smear. Software drawing on a bitmap of its own: safe off the main thread.
 */
private fun bakeSmear(art: ReelArt, game: TrainingGame, view: IntSize, floorY: Float): ImageBitmap {
    val w = (view.width / SMEAR_DOWNSCALE).coerceAtLeast(1)
    val frameH = (view.height / SMEAR_DOWNSCALE).coerceAtLeast(1)
    val h = (frameH * FILM_PITCH).roundToInt().coerceAtLeast(frameH + 1)
    val bitmap = ImageBitmap(w, h)
    val crop = coverCrop(art.scene.width, art.scene.height, w, frameH, floorY)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
        drawRect(FilmBlack)
        drawFramePicture(art, game, crop, top = 0f, w = w.toFloat(), h = frameH.toFloat())
    }
    val pixels = IntArray(w * h)
    bitmap.readPixels(pixels)
    val radius = (frameH * SMEAR_RADIUS).roundToInt()
    repeat(SMEAR_PASSES) { smearColumns(pixels, w, h, radius) }
    bitmap.asAndroidBitmap().setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap
}

/**
 * Blurs opaque ARGB [pixels] ([width] x [height], row by row) down their columns in place: each becomes the average of
 * the [radius] pixels above it, itself and the [radius] below, the bottom row running on round to the top.
 */
internal fun smearColumns(pixels: IntArray, width: Int, height: Int, radius: Int) {
    if (radius <= 0 || height < 2) return
    val span = 2 * radius + 1
    val column = IntArray(height)
    for (x in 0 until width) {
        for (y in 0 until height) column[y] = pixels[y * width + x]
        var r = 0
        var g = 0
        var b = 0
        for (k in -radius..radius) {
            val p = column[k.mod(height)]
            r += p shr 16 and 0xFF
            g += p shr 8 and 0xFF
            b += p and 0xFF
        }
        for (y in 0 until height) {
            pixels[y * width + x] =
                (0xFF shl 24) or ((r + span / 2) / span shl 16) or ((g + span / 2) / span shl 8) or ((b + span / 2) / span)
            val leaving = column[(y - radius).mod(height)]
            val coming = column[(y + radius + 1).mod(height)]
            r += (coming shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            g += (coming shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            b += (coming and 0xFF) - (leaving and 0xFF)
        }
    }
}
