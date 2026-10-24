package team.superfun.horrorpets.ui.pantry

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.placeAt

/**
 * How long the meal takes to land beside the killer, tossed from the board (or dropped from above the screen): the
 * `food_drop` whistle's plop lands with it.
 */
internal const val FOOD_FALL_MS = 600

/** After the plop, the bounce settles this long before the pet starts munching. */
internal const val LAND_SETTLE_MS = 160L

/** A toss arcs this much of the way's length above the straight line at its middle. */
private const val TOSS_ARC = 0.45f

/** How far through the munching each bite lands; the last one gobbles what is left. */
private val BITE_AT = listOf(0.17f, 0.5f, 0.83f)

private const val GOBBLE_MS = 220
private const val CRUMB_MS = 700

/** A bite mark's radius, as a fraction of the food's longer side. */
private const val BITE_R = 0.2f

/**
 * Where the two bite marks go on the side facing the pet, as fractions of the food's height: through its body,
 * above the low bits that stick out (a bone end, a plate's rim).
 */
private val BITE_ROWS = listOf(0.32f, 0.52f)

/** When the bites land, in ms after the pet starts munching for [munchMs]. */
internal fun biteTimesMs(munchMs: Long): List<Long> = BITE_AT.map { (munchMs * it).toLong() }

/**
 * Where the food lands, beside the pet standing at [petX] (a body [bodyHalf] wide each side of it): on the
 * pet's roomier side, kept inside the safe [inset]. Returns the food's centre and the side of it the pet is
 * on: -1 when the pet is to its left, +1 to its right.
 */
internal fun foodLanding(petX: Float, bodyHalf: Float, foodW: Float, screenW: Float, inset: Float): Pair<Float, Int> {
    val toward = if (petX <= screenW / 2f) 1 else -1
    val half = foodW / 2f
    val x = (petX + toward * (bodyHalf + half * 1.1f)).coerceIn(inset + half, max(inset + half, screenW - inset - half))
    return x to -toward
}

/**
 * Where a meal tossed from ([fromX], [fromBottom]) to ([toX], [toBottom]) is ([p], 0..1, of the way): its bottom
 * centre, moving steadily across and arcing over the top like a thrown thing ([TOSS_ARC] of the way's length above
 * the straight line at its middle).
 */
internal fun tossAt(fromX: Float, fromBottom: Float, toX: Float, toBottom: Float, p: Float): Pair<Float, Float> {
    val arc = TOSS_ARC * hypot(toX - fromX, toBottom - fromBottom)
    return (fromX + (toX - fromX) * p) to (fromBottom + (toBottom - fromBottom) * p - arc * 4f * p * (1f - p))
}

/** Crumbs for a picture with nothing solid to take a color from: toasty brown and crust. */
internal val CrumbFallback = listOf(Color(0xFF7A4A24), Color(0xFFD9A066))

/** A pixel this opaque or more is part of the food (its soft shadow fringe is not). */
private const val SOLID_ALPHA = 200

/** A color whose brightest channel is under this (of 255) is ink: the art's outlines and its darkest shading. */
private const val INK_BELOW = 48

/** The second crumb color is at least this far (in 0..1 RGB) from the first, else it is a shade of it. */
private const val CRUMBS_APART = 0.25f

/** The two colors [image]'s crumbs fly in, read from its pixels (see the [IntArray] one). Reads the whole picture. */
internal fun crumbColorsOf(image: ImageBitmap): List<Color> {
    val pixels = IntArray(image.width * image.height)
    image.readPixels(pixels)
    return crumbColorsOf(pixels, step = 2)
}

/**
 * The two colors a food's crumbs fly in, from its ARGB [pixels] (every [step]th): the commonest color of its solid
 * pixels, then the commonest one that is clearly different, or a shade of the first when there is none. Colors are
 * counted in coarse bins (eight levels a channel) and each bin gives its mean. Ink (outlines, the darkest shading)
 * only counts when there is nothing else, and a picture with nothing solid gets [CrumbFallback].
 */
internal fun crumbColorsOf(pixels: IntArray, step: Int = 1): List<Color> {
    val count = IntArray(512)
    val sums = LongArray(512 * 3)
    var i = 0
    while (i < pixels.size) {
        val p = pixels[i]
        i += step.coerceAtLeast(1)
        if ((p ushr 24) < SOLID_ALPHA) continue
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val bin = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
        count[bin]++
        sums[bin * 3] += r.toLong()
        sums[bin * 3 + 1] += g.toLong()
        sums[bin * 3 + 2] += b.toLong()
    }
    val bins = (0 until 512).filter { count[it] > 0 }.sortedByDescending { count[it] }
    if (bins.isEmpty()) return CrumbFallback
    fun mean(bin: Int): IntArray = IntArray(3) { (sums[bin * 3 + it] / count[bin]).toInt() }
    val colorful = bins.map(::mean).filter { it.max() >= INK_BELOW }
    val ranked = colorful.ifEmpty { bins.map(::mean) }
    val first = ranked.first()
    val second = ranked.drop(1).firstOrNull { apart(it, first) >= CRUMBS_APART } ?: shadeOf(first)
    return listOf(Color(first[0], first[1], first[2]), Color(second[0], second[1], second[2]))
}

/** How far apart two RGB colors are, 0..1 (their largest channel difference). */
private fun apart(a: IntArray, b: IntArray): Float = (0..2).maxOf { abs(a[it] - b[it]) } / 255f

/** A shade of [rgb] for its second crumb: darker for a light color, lighter for a dark one. */
private fun shadeOf(rgb: IntArray): IntArray {
    val light = (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000 > 140
    return IntArray(3) { if (light) rgb[it] * 62 / 100 else rgb[it] + (255 - rgb[it]) * 45 / 100 }
}

/** One bite's spray of crumbs, flying out from [origin] (a point on the food, in fractions of its size). */
@Stable
private class CrumbBurst(val origin: Offset, big: Boolean, seed: Int, colors: List<Color>) {
    val progress = Animatable(0f)
    val crumbs: List<Crumb> = Random(seed).let { r ->
        List(if (big) 22 else 10) { i ->
            // Mostly up and out, like a messy eater's.
            val angle = Math.toRadians(r.nextDouble(-165.0, -15.0)).toFloat()
            val speed = r.nextFloat() * 0.7f + (if (big) 1.0f else 0.6f)
            Crumb(cos(angle) * speed, sin(angle) * speed, r.nextFloat() * 0.018f + 0.016f, colors[i % colors.size], i % 3 == 0)
        }
    }
}

/** A crumb's launch velocity and radius (in food sizes per second, and food sizes) and its look. */
private class Crumb(val vx: Float, val vy: Float, val radius: Float, val color: Color, val square: Boolean)

/**
 * A meal landing beside the killer, from its toss to its last crumb.
 *
 * It is tossed from [from] (its bottom centre, in px: where it stood on the pantry's board) in an arc to the floor,
 * or with no [from] falls from above the screen, for [FOOD_FALL_MS] (the plop of the `food_drop` sound, played as it
 * sets off, lands with it); it squashes and bounces, and calls [onLanded]: the pet starts munching then, for
 * [munchMs]. At each of [biteTimesMs] the food loses a bite with a `bite` crunch and a puff of [crumbs] (two colors,
 * see [crumbColorsOf]): a bite mark on the side facing the pet, then another, and the last bite gobbles what is left.
 * When the crumbs have landed it calls [onDone].
 *
 * [image] is drawn with its longer side [sizePx], through the scene's [filter] like the pets, its bottom on
 * [floorY] beside the pet at [petX] (see [foodLanding]). Its timed waits go through [wait] (the pantry's show clock,
 * which stops while the app is away, so no bite crunches off screen); its motion is frame-driven and stops with it.
 */
@Composable
internal fun FoodDrop(
    image: ImageBitmap,
    crumbs: List<Color>,
    petX: Float,
    bodyHalf: Float,
    screenW: Float,
    inset: Float,
    floorY: Float,
    sizePx: Float,
    munchMs: Long,
    filter: ColorFilter?,
    from: Offset?,
    wait: suspend (ms: Long) -> Unit,
    onLanded: () -> Unit,
    onDone: () -> Unit,
) {
    val sounds = LocalSoundSink.current
    val scale = sizePx / max(image.width, image.height)
    val w = image.width * scale
    val h = image.height * scale
    val (x, petSide) = remember(petX, w) { foodLanding(petX, bodyHalf, w, screenW, inset) }
    val spots = remember(image, petSide) { biteSpots(image, petSide) }
    val colors = crumbs

    val fall = remember { Animatable(0f) }
    // 1 is squashed flat on landing; the spring overshoots into a stretch and settles at 0.
    val squash = remember { Animatable(0f) }
    val wobble = remember { Animatable(0f) }
    val gone = remember { Animatable(0f) }
    val bites = remember { mutableStateListOf<Offset>() }
    val bursts = remember { mutableStateListOf<CrumbBurst>() }
    val landed by rememberUpdatedState(onLanded)
    val done by rememberUpdatedState(onDone)

    LaunchedEffect(Unit) {
        // A toss moves steadily (its arc is the parabola in tossAt); a drop speeds up as it falls.
        fall.animateTo(1f, tween(FOOD_FALL_MS, easing = if (from == null) FastOutLinearInEasing else LinearEasing))
        launch {
            squash.snapTo(1f)
            squash.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 500f))
        }
        wait(LAND_SETTLE_MS)
        landed()
        var t = 0L
        val times = biteTimesMs(munchMs)
        for ((i, at) in times.withIndex()) {
            wait(at - t)
            t = at
            sounds.sfx("bite")
            val last = i == times.lastIndex
            val burst = CrumbBurst(if (last) Offset(0.5f, 0.55f) else spots[i], big = last, seed = i, colors = colors)
            bursts += burst
            launch { burst.progress.animateTo(1f, tween(CRUMB_MS, easing = LinearEasing)) }
            if (last) {
                launch { gone.animateTo(1f, tween(GOBBLE_MS, easing = FastOutSlowInEasing)) }
            } else {
                bites += spots[i]
                launch {
                    wobble.snapTo(1f)
                    wobble.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 700f))
                }
            }
        }
        wait(CRUMB_MS.toLong())
        done()
    }

    val density = LocalDensity.current
    val left = x - w / 2f
    val top = floorY - h
    Box(Modifier.fillMaxSize()) {
        // The shadow it falls onto, and the crumbs, which fly beyond the food's own box.
        Canvas(Modifier.fillMaxSize()) {
            val f = fall.value
            val g = 1f - gone.value
            if (f > 0f && g > 0f) {
                val sw = w * 0.85f * g * (0.4f + 0.6f * f)
                val sh = w * 0.13f * g * (0.4f + 0.6f * f)
                drawOval(Color.Black, Offset(x - sw / 2f, floorY - sh / 2f), Size(sw, sh), alpha = 0.32f * f * f * f)
            }
            for (b in bursts) drawCrumbs(b, left, top, w, h, sizePx)
        }
        with(density) {
            Spacer(
                Modifier
                    .placeAt(x = { x }, y = { floorY })
                    .size(w.toDp(), h.toDp())
                    .graphicsLayer {
                        val s = squash.value
                        val g = 1f - gone.value
                        val f = fall.value
                        if (from == null) {
                            translationY = -(1f - f) * (floorY + h * 1.2f)
                        } else {
                            val (tx, ty) = tossAt(from.x, from.y, x, floorY, f)
                            translationX = tx - x
                            translationY = ty - floorY
                        }
                        scaleX = (1f + 0.18f * s) * g
                        scaleY = (1f - 0.22f * s) * g
                        // A tossed meal turns over a little in the air, and lands upright.
                        val spin = if (from == null) 0f else (1f - f) * 30f * petSide
                        rotationZ = wobble.value * 7f * petSide + spin
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        // Its own layer, so a bite clears the food and never the scene behind it.
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawBehind {
                        drawImage(
                            image,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            colorFilter = filter,
                        )
                        for (b in bites) drawBite(b)
                    },
            )
        }
    }
}

/**
 * A bite mark at [at] (fractions of the food): a scallop of three overlapping circles cleared out of the food,
 * stacked along the edge the way teeth leave it.
 */
private fun DrawScope.drawBite(at: Offset) {
    val r = BITE_R * max(size.width, size.height)
    val c = Offset(at.x * size.width, at.y * size.height)
    drawCircle(Color.Black, r * 0.62f, c + Offset(0f, -0.58f * r), blendMode = BlendMode.Clear)
    drawCircle(Color.Black, r * 0.72f, c, blendMode = BlendMode.Clear)
    drawCircle(Color.Black, r * 0.62f, c + Offset(0f, 0.58f * r), blendMode = BlendMode.Clear)
}

/** [b]'s crumbs at its progress: thrown from the bite, falling under gravity, fading and shrinking as they go. */
private fun DrawScope.drawCrumbs(b: CrumbBurst, left: Float, top: Float, w: Float, h: Float, sizePx: Float) {
    val p = b.progress.value
    if (p >= 1f) return
    val t = p * CRUMB_MS / 1000f
    val ox = left + b.origin.x * w
    val oy = top + b.origin.y * h
    val alpha = 1f - p * p
    for (c in b.crumbs) {
        val cx = ox + c.vx * sizePx * t
        val cy = oy + (c.vy * t + 1.6f * t * t) * sizePx
        val r = c.radius * sizePx * (1f - 0.4f * p)
        if (c.square) {
            drawRect(c.color, Offset(cx - r, cy - r), Size(2f * r, 2f * r), alpha = alpha)
        } else {
            drawCircle(c.color, r, Offset(cx, cy), alpha = alpha)
        }
    }
}

/**
 * The two bite marks' centres (fractions of the picture), on the side facing the pet ([petSide]): on each of
 * [BITE_ROWS], the first solid pixel in from that side, nudged inward so the bite takes a real mouthful.
 * A row with nothing solid in it (an odd shape) bites the middle.
 */
private fun biteSpots(image: ImageBitmap, petSide: Int): List<Offset> {
    val width = image.width
    val row = IntArray(width)
    val inward = 0.4f * BITE_R * max(image.width, image.height) / width
    return BITE_ROWS.map { yFrac ->
        image.readPixels(row, startX = 0, startY = (yFrac * (image.height - 1)).roundToInt(), width = width, height = 1)
        val xs = if (petSide < 0) 0 until width else (width - 1) downTo 0
        val edge = xs.firstOrNull { (row[it] ushr 24) > 160 }
        if (edge == null) Offset(0.5f, yFrac) else Offset(edge.toFloat() / width - petSide * inward, yFrac)
    }
}
