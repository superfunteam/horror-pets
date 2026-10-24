package team.superfun.horrorpets.ui.fight

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.hash01

// ---- each killer's colours ----

/**
 * One killer's colours on the VS card: [deep] in the far corners of its half, [mid] across it, [hot] in the burst
 * behind the fighter (the brightest), and [streak] for its speed streaks (white ones race among them).
 */
@Immutable
data class VsPalette(val deep: Color, val mid: Color, val hot: Color, val streak: Color)

/** [pet]'s VS card colours, each from its films. */
fun vsPalette(pet: PetId): VsPalette = when (pet) {
    // Camp Crystal Lake at sunset: blood red to orange, an amber glow.
    PetId.JASON -> VsPalette(deep = Color(0xFF7A1806), mid = Color(0xFFEE6612), hot = Color(0xFFFFC94D), streak = Color(0xFFFFE3A6))
    // The sweater: moss green, with a crimson boiler-room fire behind him.
    PetId.FREDDY -> VsPalette(deep = Color(0xFF111A06), mid = Color(0xFF55691C), hot = Color(0xFFF24A3A), streak = Color(0xFFD2E58F))
    // The Cenobites' violet, lit by cold steel.
    PetId.PINHEAD -> VsPalette(deep = Color(0xFF0D0620), mid = Color(0xFF4A2585), hot = Color(0xFFD9DCF5), streak = Color(0xFF9AAED8))
    // Black and white, cut with blood red.
    PetId.GHOSTFACE -> VsPalette(deep = Color(0xFF000000), mid = Color(0xFF2B2B31), hot = Color(0xFFF5F5F8), streak = Color(0xFFE3121F))
    // The Good Guys box: primary red, yellow and blue.
    PetId.CHUCKY -> VsPalette(deep = Color(0xFF520407), mid = Color(0xFFD3141D), hot = Color(0xFFFFD526), streak = Color(0xFF4A9BFF))
    // Halloween night: night blue, a jack-o'-lantern glow.
    PetId.MICHAEL -> VsPalette(deep = Color(0xFF040A1E), mid = Color(0xFF1A2B5E), hot = Color(0xFFFF8C1F), streak = Color(0xFFFFB44F))
    // Rust and Texas-sun gold.
    PetId.LEATHERFACE -> VsPalette(deep = Color(0xFF230B03), mid = Color(0xFF8C3712), hot = Color(0xFFF7BF3D), streak = Color(0xFFFFDA7A))
    // The open ocean: deep blue, white water.
    PetId.JAWS -> VsPalette(deep = Color(0xFF01122C), mid = Color(0xFF0C5DA0), hot = Color(0xFFCDF4FF), streak = Color(0xFF8EE0FF))
}

// ---- the speed streaks, as numbers ----

/**
 * The speed streaks on each half of the card: [COUNT] long lines racing outward, away from the seam, along a diagonal
 * that rises toward it. Each has its own lane across the half, length, speed and look, all fixed; where it is depends
 * only on the card's clock, so the streaks stand still whenever the clock does.
 */
internal object VsStreaks {
    const val COUNT = 34

    /** The quickest streak crosses its half in this long... */
    const val FASTEST_MS = 380L

    /** ...and the slowest in this long. */
    const val SLOWEST_MS = 820L

    /** They race along this angle, in degrees, rising toward the seam. */
    const val ANGLE_DEG = 17f

    /** How long streak [i] takes to cross its half, tail to tail. */
    fun crossMs(i: Int): Long = FASTEST_MS + ((SLOWEST_MS - FASTEST_MS) * hash01(i, 71)).toLong()

    /** Where streak [i] is at [t] ms: 0 with its head at the seam's side, 1 with its tail past the outer edge; wrapping. */
    fun progress(i: Int, t: Long): Float {
        val cross = crossMs(i)
        // Spread out from the start (a golden-ratio sequence), so they never set off in a bunch.
        val start = (i * 0.618034f) % 1f
        return ((t.mod(cross)).toFloat() / cross + start) % 1f
    }

    /** Streak [i]'s lane across the half, 0..1: every lane a different one, jittered within it. */
    fun lane(i: Int): Float = (((i * 11) % COUNT) + hash01(i, 73)) / COUNT

    /** Streak [i]'s length, as a share of the half's width. */
    fun length(i: Int): Float = 0.35f + 0.65f * hash01(i, 74)

    /** Streak [i]'s thickness in dp: most are fine lines, a few are broad bands. */
    fun thicknessDp(i: Int): Float = 2f + 13f * hash01(i, 75).pow(2.2f)

    /** Streak [i]'s opacity. */
    fun alpha(i: Int): Float = 0.35f + 0.5f * hash01(i, 76)

    /** Streak [i]'s colour: 0 the palette's streak colour, 1 white, 2 its hot colour. */
    fun kind(i: Int): Int = hash01(i, 77).let { if (it < 0.5f) 0 else if (it < 0.85f) 1 else 2 }
}

/** The burst behind each fighter turns this far, in degrees, [t] ms into the card: slowly, so it is felt, not seen. */
internal fun burstTurnDeg(t: Long): Float = t * BURST_TURN_PER_S / 1_000f

private const val BURST_TURN_PER_S = 7f

// ---- staging, as fractions of the screen ----

/** Where each fighter stands, and so where its half is hottest. */
internal const val VS_PLAYER_X = 0.25f
internal const val VS_OPPONENT_X = 0.75f

/** The fighters' bodies are about this far down: the burst's centre. */
private const val BURST_Y = 0.58f

/** The split leans: at the top it is this far right of centre, at the bottom as far left. */
internal const val VS_SEAM_LEAN = 0.07f

private const val RAYS = 22

// ---- the backdrop ----

/**
 * The card's two halves and the seam between them, drawn in code. Each half is its killer's [palette][VsPalette]:
 * hottest in a radial burst behind the fighter (its rays turning slowly), [VsStreaks] racing across it, halftone dots
 * thickening toward the white-hot diagonal seam. The halves sweep in from the sides ([VsTimeline.sweep]).
 *
 * [clock] is the card's time in ms: everything that moves reads it while drawing, so nothing recomposes per frame, and
 * when the card stops its clock nothing is redrawn. The still parts (the fields, the bursts, the halftone) are recorded
 * once per size and only moved.
 */
@Composable
internal fun VsBackdrop(left: VsPalette, right: VsPalette, clock: () -> Long, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        VsHalf(side = -1, palette = left, clock = clock)
        VsHalf(side = 1, palette = right, clock = clock)
        Seam(left, right, clock)
    }
}

/** The left (-1) or right (1) half of the card, clipped to its side of the seam. */
private fun halfShape(side: Int) = GenericShape { size, _ ->
    val top = size.width * (0.5f + VS_SEAM_LEAN)
    val bottom = size.width * (0.5f - VS_SEAM_LEAN)
    if (side < 0) {
        moveTo(0f, 0f)
        lineTo(top, 0f)
        lineTo(bottom, size.height)
        lineTo(0f, size.height)
    } else {
        moveTo(top, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(bottom, size.height)
    }
    close()
}

@Composable
private fun VsHalf(side: Int, palette: VsPalette, clock: () -> Long) {
    val clipShape = remember(side) { halfShape(side) }
    val fighterX = if (side < 0) VS_PLAYER_X else VS_OPPONENT_X
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = side * (1f - VsTimeline.sweep(clock())) * size.width * 0.6f
                shape = clipShape
                clip = true
            },
    ) {
        // The field: deep at the corners, mid across, hot behind the fighter.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val centre = Offset(w * fighterX, h * BURST_Y)
                    val field = Brush.radialGradient(
                        0f to palette.hot,
                        0.05f to palette.hot,
                        0.17f to lerp(palette.hot, palette.mid, 0.5f),
                        0.36f to palette.mid,
                        1f to palette.deep,
                        center = centre,
                        radius = hypot(w * 0.5f, h) * 0.95f,
                    )
                    onDrawBehind { drawRect(field) }
                },
        )
        // The burst: rays from behind the fighter, turning slowly about him.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = -side * burstTurnDeg(clock())
                    transformOrigin = TransformOrigin(fighterX, BURST_Y)
                }
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val centre = Offset(w * fighterX, h * BURST_Y)
                    val reach = hypot(w, h)
                    val rays = Path().apply {
                        val step = 2f * PI.toFloat() / RAYS
                        for (i in 0 until RAYS) {
                            // Uneven widths, as drawn by hand.
                            val a = i * step + step * 0.2f * hash01(i, 61)
                            val b = a + step * (0.28f + 0.3f * hash01(i, 62))
                            moveTo(centre.x, centre.y)
                            lineTo(centre.x + cos(a) * reach, centre.y + sin(a) * reach)
                            lineTo(centre.x + cos(b) * reach, centre.y + sin(b) * reach)
                            close()
                        }
                    }
                    val light = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.42f),
                        0.3f to palette.hot.copy(alpha = 0.26f),
                        0.75f to palette.hot.copy(alpha = 0.06f),
                        1f to Color.Transparent,
                        center = centre,
                        radius = reach * 0.75f,
                    )
                    onDrawBehind { drawPath(rays, light) }
                },
        )
        // Halftone: dots in the deep colour, swelling toward the seam. Its own layer, recorded once: each size of dot
        // is a single draw.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val dots = halftone(size, side)
                    val paints = List(DOT_SIZES) { i ->
                        Paint().apply {
                            color = palette.deep
                            alpha = HALFTONE_ALPHA
                            strokeWidth = dotWidth(i, size)
                            strokeCap = StrokeCap.Round
                        }
                    }
                    onDrawBehind {
                        for (i in dots.indices) {
                            if (dots[i].isNotEmpty()) drawContext.canvas.drawRawPoints(PointMode.Points, dots[i], paints[i])
                        }
                    }
                },
        )
        // The streaks, racing outward; then a darker top and bottom, so the names read and the fighters stand. Its own
        // layer: this is the only part of the half that is drawn anew each frame.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val unit = 1.dp.toPx()
                    val a = VsStreaks.ANGLE_DEG * PI.toFloat() / 180f
                    // The direction they race (out and down, away from the seam) and across it.
                    val ux = side * cos(a)
                    val uy = sin(a)
                    val degrees = atan2(uy, ux) * 180f / PI.toFloat()
                    val halfW = w * (0.5f + VS_SEAM_LEAN)
                    val centre = Offset(if (side < 0) halfW / 2f else w - halfW / 2f, h / 2f)
                    val travel = halfW / 2f * cos(a) + h / 2f * sin(a)
                    val across = halfW / 2f * sin(a) + h / 2f * cos(a)
                    // A streak is a unit rectangle (x -1..0, y -0.5..0.5) fading in from its tail, scaled into place.
                    fun unitStreak(c: Color) = Brush.horizontalGradient(
                        0f to c.copy(alpha = 0f),
                        0.45f to c.copy(alpha = 0.6f),
                        1f to c,
                        startX = -1f,
                        endX = 0f,
                    )
                    val looks = arrayOf(unitStreak(palette.streak), unitStreak(Color.White), unitStreak(palette.hot))
                    // Only where they darken: the top quarter and the bottom fifth.
                    val topShade = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.42f), 1f to Color.Transparent, endY = h * 0.26f)
                    val bottomShade = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f), startY = h * 0.78f, endY = h)
                    onDrawBehind {
                        val t = clock()
                        for (i in 0 until VsStreaks.COUNT) {
                            val len = VsStreaks.length(i) * halfW
                            val head = -travel + VsStreaks.progress(i, t) * (2f * travel + len)
                            val lane = (VsStreaks.lane(i) * 2f - 1f) * across
                            val x = centre.x + ux * head - uy * lane
                            val y = centre.y + uy * head + ux * lane
                            withTransform({
                                translate(x, y)
                                rotate(degrees, Offset.Zero)
                                scale(len, VsStreaks.thicknessDp(i) * unit, Offset.Zero)
                            }) {
                                drawRect(looks[VsStreaks.kind(i)], Offset(-1f, -0.5f), Size(1f, 1f), alpha = VsStreaks.alpha(i))
                            }
                        }
                        drawRect(topShade, size = Size(w, h * 0.26f))
                        drawRect(bottomShade, topLeft = Offset(0f, h * 0.78f), size = Size(w, h * 0.22f))
                    }
                },
        )
    }
}

private const val HALFTONE_ALPHA = 0.5f

/** The halftone's size steps: each dot is drawn at the nearest of these, so a step is one draw call. */
private const val DOT_SIZES = 7

/** The halftone's dot spacing, of the screen's height. */
private const val DOT_PITCH = 0.021f

/** The biggest dot (at the seam), of its spacing. */
private const val DOT_MAX = 0.72f

/** The halftone's reach from the seam, of the screen's width (no more than [HALFTONE_REACH_BY_H] of its height). */
private const val HALFTONE_REACH = 0.21f
private const val HALFTONE_REACH_BY_H = 0.38f

private fun dotWidth(step: Int, size: Size): Float = size.height * DOT_PITCH * DOT_MAX * (step + 1) / DOT_SIZES

/**
 * The halftone dots on the [side] half of a screen of [size]: a staggered grid near the seam, each dot's size falling
 * off with its distance from the seam. Returned as [DOT_SIZES] arrays of centres (x, y, x, y...), smallest dots first.
 */
private fun halftone(size: Size, side: Int): List<FloatArray> {
    val w = size.width
    val h = size.height
    val pitch = h * DOT_PITCH
    val reach = min(w * HALFTONE_REACH, h * HALFTONE_REACH_BY_H)
    val top = w * (0.5f + VS_SEAM_LEAN)
    val bottom = w * (0.5f - VS_SEAM_LEAN)
    // Horizontal distance to the seam, to the distance across it.
    val slant = h / sqrt(h * h + (top - bottom) * (top - bottom))
    val bands = List(DOT_SIZES) { ArrayList<Offset>() }
    var row = 0
    var y = pitch / 2f
    while (y < h + pitch) {
        val seamX = top + (bottom - top) * (y / h)
        val stagger = if (row % 2 == 0) 0f else pitch / 2f
        // This row's dots on the grid, from the seam out to the halftone's reach on this side.
        val from = if (side < 0) seamX - reach else seamX
        var x = floor((from - stagger) / pitch) * pitch + stagger
        while (x <= from + reach) {
            val d = side * (x - seamX) * slant
            if (d >= 0f && d < reach) {
                val k = (1f - d / reach).pow(1.3f)
                val step = (k * DOT_SIZES).toInt().coerceAtMost(DOT_SIZES - 1)
                if (k * DOT_SIZES >= 0.6f) bands[step] += Offset(x, y)
            }
            x += pitch
        }
        y += pitch * 0.866f
        row++
    }
    return bands.map { band -> FloatArray(band.size * 2) { if (it % 2 == 0) band[it / 2].x else band[it / 2].y } }
}

/**
 * The seam: a white-hot band down the diagonal split, each side tinted with its half's hot colour, around a crisp white
 * line edged dark. It fades in as the halves meet and flares as VS and FIGHT! land.
 */
@Composable
private fun Seam(left: VsPalette, right: VsPalette, clock: () -> Long) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val unit = 1.dp.toPx()
                val top = Offset(w * (0.5f + VS_SEAM_LEAN), -unit * 4f)
                val bottom = Offset(w * (0.5f - VS_SEAM_LEAN), h + unit * 4f)
                val mid = Offset(w / 2f, h / 2f)
                // The unit normal to the seam, pointing into the right half.
                val dx = bottom.x - top.x
                val dy = bottom.y - top.y
                val len = hypot(dx, dy)
                val normal = Offset(dy / len, -dx / len)
                val reach = min(w * 0.11f, h * 0.2f)
                // The band the glow covers, and nothing beyond it: run on past both edges so its corners are off screen.
                val along = Offset(dx / len, dy / len) * reach
                val a = top - along
                val b = bottom + along
                val band = Path().apply {
                    moveTo(a.x - normal.x * reach, a.y - normal.y * reach)
                    lineTo(a.x + normal.x * reach, a.y + normal.y * reach)
                    lineTo(b.x + normal.x * reach, b.y + normal.y * reach)
                    lineTo(b.x - normal.x * reach, b.y - normal.y * reach)
                    close()
                }
                val glow = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.22f to left.hot.copy(alpha = 0.32f),
                    0.42f to Color.White.copy(alpha = 0.7f),
                    0.5f to Color.White.copy(alpha = 0.95f),
                    0.58f to Color.White.copy(alpha = 0.7f),
                    0.78f to right.hot.copy(alpha = 0.32f),
                    1f to Color.Transparent,
                    start = mid - normal * reach,
                    end = mid + normal * reach,
                )
                onDrawBehind {
                    val t = clock()
                    val sweep = VsTimeline.sweep(t)
                    if (sweep <= 0f) return@onDrawBehind
                    val flare = VsTimeline.flashAt(t)
                    drawPath(band, glow, alpha = (0.75f + 0.25f * flare) * sweep)
                    drawLine(Color.Black, top, bottom, strokeWidth = (9f + 6f * flare) * unit, alpha = 0.5f * sweep, cap = StrokeCap.Butt)
                    drawLine(Color.White, top, bottom, strokeWidth = (4f + 4f * flare) * unit, alpha = sweep, cap = StrokeCap.Butt)
                }
            },
    )
}
