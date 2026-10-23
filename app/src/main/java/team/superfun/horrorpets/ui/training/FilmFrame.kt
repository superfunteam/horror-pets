package team.superfun.horrorpets.ui.training

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random
import team.superfun.horrorpets.ui.common.hash01

/** Each black film edge, as a share of the screen's width: the trailer reel's 150 px at 1920. */
internal const val FILM_EDGE_FRACTION = 150f / 1920f

/** The film's pitch, a frame and the black line under it, in heights of the frame (the play area's height). */
internal const val FILM_PITCH = 1.12f

/** The near-black of the film between and beside the frames. */
internal val FilmBlack = Color(0xFF080606)

/** The sprocket holes down each edge: four to a pitch, each 42% of the edge wide and 11% of a pitch tall. */
private const val HOLES_PER_PITCH = 4
private const val HOLE_WIDTH = 0.42f
private const val HOLE_HEIGHT = 0.11f
private const val HOLE_CORNER = 0.22f // of the hole's width
private val HoleCream = Color(0xFFCDC4B4)

/** The light through a hole streaming past is spread along its streak, but never fades below this. */
private const val HOLE_STREAK_MIN_ALPHA = 0.35f

/** Film's 24 frames a second: the grain and the flicker change this often. */
private const val FILM_FRAME_MS = 42L

/** Grain: a few frames of grey noise, stretched over the screen (soft, like the trailer's) and cycled. */
private const val GRAIN_FRAMES = 4
private const val GRAIN_W = 240
private const val GRAIN_H = 135
private const val GRAIN_SEED = 41

/** Kept light, so a game reads clearly through it: how much the grain shows, and the most a frame's flicker dims. */
private const val GRAIN_ALPHA = 0.05f
private const val FLICKER_DIM = 0.035f

/**
 * Training's film: [content] in a frame of film between black edges punched with sprocket holes down both sides, the
 * trailer's environments reel look. [run] is how far the film has run, in frames (pitches): the holes move with it,
 * streaking as it runs fast, and hold still while it does not. A light grain and a projector flicker play over
 * everything, edges included.
 *
 * [content] is laid out in the frame between the edges (the play area: the full height, the width less the two edges)
 * and clipped to it.
 */
@Composable
fun FilmFrame(run: Float, content: @Composable BoxScope.() -> Unit) {
    val runNow = rememberUpdatedState(run)
    val grain = remember { filmGrain() }
    // The film frame the grain and flicker are on; only the overlay's draw reads it.
    val filmFrame = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { filmFrame.intValue = ((it - start) / FILM_FRAME_MS).toInt() }
    }
    // The run at the last draw, for how far the holes moved since: their streak.
    val lastRun = remember { FloatArray(1) { Float.NaN } }

    Box(Modifier.fillMaxSize().background(FilmBlack), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(1f - 2f * FILM_EDGE_FRACTION).clipToBounds(), content = content)
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val w = size.width
                val h = size.height
                // The play area as fillMaxWidth rounds it, and the edges a hair wider so no seam shows between.
                val edge = ceil((w - (w * (1f - 2f * FILM_EDGE_FRACTION)).roundToInt()) / 2f)
                val pitch = h * FILM_PITCH
                val spacing = pitch / HOLES_PER_PITCH
                val holeW = edge * HOLE_WIDTH
                val holeH = pitch * HOLE_HEIGHT
                val corner = CornerRadius(holeW * HOLE_CORNER)
                val holeXs = floatArrayOf(edge / 2f - holeW / 2f, w - edge / 2f - holeW / 2f)
                val holes = ceil(h / spacing).toInt() + 1
                val grainSize = IntSize(ceil(w).toInt(), ceil(h).toInt())
                onDrawBehind {
                    drawRect(FilmBlack, size = Size(edge, h))
                    drawRect(FilmBlack, topLeft = Offset(w - edge, 0f), size = Size(edge, h))

                    val run = runNow.value
                    val previous = lastRun[0]
                    lastRun[0] = run
                    val flow = if (previous.isNaN()) 0f else abs(run - previous) * pitch
                    val streak = flow.coerceAtMost(spacing - holeH)
                    val alpha = (holeH / (holeH + streak)).coerceAtLeast(HOLE_STREAK_MIN_ALPHA)
                    // The holes ride up the screen as the film runs on.
                    val scrolled = run * pitch
                    val offset = scrolled - floor(scrolled / spacing) * spacing
                    for (k in 0 until holes) {
                        val y = spacing * (k + 0.5f) - holeH / 2f - offset
                        for (x in holeXs) {
                            drawRoundRect(HoleCream, Offset(x, y), Size(holeW, holeH + streak), corner, alpha = alpha)
                        }
                    }

                    val f = filmFrame.intValue
                    drawImage(
                        grain[f.mod(GRAIN_FRAMES)],
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(GRAIN_W, GRAIN_H),
                        dstSize = grainSize,
                        alpha = GRAIN_ALPHA,
                        filterQuality = FilterQuality.Low,
                    )
                    drawRect(Color.Black, alpha = FLICKER_DIM * hash01(f, 17))
                }
            },
        )
    }
}

/** [GRAIN_FRAMES] frames of grey noise, the same every time. */
private fun filmGrain(): List<ImageBitmap> {
    val rng = Random(GRAIN_SEED)
    return List(GRAIN_FRAMES) {
        val pixels = IntArray(GRAIN_W * GRAIN_H) {
            val v = rng.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Bitmap.createBitmap(pixels, GRAIN_W, GRAIN_H, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
