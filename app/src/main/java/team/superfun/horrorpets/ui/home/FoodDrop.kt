package team.superfun.horrorpets.ui.home

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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.superfun.horrorpets.model.Food
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.placeAt

/** How long the food falls from above the screen to the floor: the `food_drop` whistle's plop lands with it. */
internal const val FOOD_FALL_MS = 600

/**
 * The food's longer side, as a fraction of a full-size pet's height. The same next to half-height Chucky, who
 * gets a meal as big as everyone else's.
 */
internal const val FOOD_SIZE = 0.36f

/** After the plop, the bounce settles this long before the pet starts munching. */
private const val LAND_SETTLE_MS = 160L

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

/** The two colors a food's crumbs fly in. */
private fun crumbColors(food: Food): List<Color> = when (food) {
    Food.MEAT -> listOf(Color(0xFF7A2E14), Color(0xFFD27A3A))
    Food.DRUMSTICK -> listOf(Color(0xFFB4561C), Color(0xFFF0B060))
    Food.SPINACH -> listOf(Color(0xFF1F6B2A), Color(0xFF5DBB63))
    Food.CAKE -> listOf(Color(0xFFFFF4E6), Color(0xFFC1121F))
    Food.POPCORN -> listOf(Color(0xFFFFF6D5), Color(0xFFF2C14E))
    Food.BRAIN -> listOf(Color(0xFF8BD93F), Color(0xFFD4F58A))
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
 * The food a feed drops in beside the pet, from its fall to its last crumb.
 *
 * It falls from above the screen ([FOOD_FALL_MS], landing with the plop of the `food_drop` sound the view model
 * plays), squashes and bounces, and calls [onLanded]: the pet starts munching then, for [munchMs]. At each of
 * [biteTimesMs] the food loses a bite with a `bite` crunch and a puff of crumbs: a bite mark on the side facing
 * the pet, then another, and the last bite gobbles what is left. When the crumbs have landed it calls [onDone].
 *
 * [image] is drawn with its longer side [sizePx], through the scene's [filter] like the pets, its bottom on
 * [floorY] beside the pet at [petX] (see [foodLanding]).
 */
@Composable
internal fun FoodDrop(
    food: Food,
    image: ImageBitmap,
    petX: Float,
    bodyHalf: Float,
    screenW: Float,
    inset: Float,
    floorY: Float,
    sizePx: Float,
    munchMs: Long,
    filter: ColorFilter?,
    onLanded: () -> Unit,
    onDone: () -> Unit,
) {
    val sounds = LocalSoundSink.current
    val scale = sizePx / max(image.width, image.height)
    val w = image.width * scale
    val h = image.height * scale
    val (x, petSide) = remember(petX, w) { foodLanding(petX, bodyHalf, w, screenW, inset) }
    val spots = remember(image, petSide) { biteSpots(image, petSide) }
    val colors = remember(food) { crumbColors(food) }

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
        fall.animateTo(1f, tween(FOOD_FALL_MS, easing = FastOutLinearInEasing))
        launch {
            squash.snapTo(1f)
            squash.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 500f))
        }
        delay(LAND_SETTLE_MS)
        landed()
        var t = 0L
        val times = biteTimesMs(munchMs)
        for ((i, at) in times.withIndex()) {
            delay(at - t)
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
        delay(CRUMB_MS.toLong())
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
                        translationY = -(1f - fall.value) * (floorY + h * 1.2f)
                        scaleX = (1f + 0.18f * s) * g
                        scaleY = (1f - 0.22f * s) * g
                        rotationZ = wobble.value * 7f * petSide
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
