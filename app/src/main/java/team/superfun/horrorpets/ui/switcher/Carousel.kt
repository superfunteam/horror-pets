package team.superfun.horrorpets.ui.switcher

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pet carousel's geometry, kept free of Compose so it can be tested. The lineup is one row of pets a
 * fixed pitch apart; the row is "scrolled" to a fractional pet index, and whichever pet sits at that index
 * is centred under the fixed spotlight. Distances are in pets (1 = one pitch from the centre).
 */

/** A label is this share of the pitch wide, so neighbouring labels never touch. */
const val CAROUSEL_LABEL_SHARE = 0.9f

/** Sizes: full under the spotlight, then smaller with each pet of distance, down to the last value. */
private const val NEIGHBOUR_SCALE = 0.85f
private const val FAR_SCALE = 0.75f

/** The label of a neighbour one pet away is this faint; by [LABEL_GONE_AT] pets away it has faded out. */
private const val NEIGHBOUR_LABEL_ALPHA = 0.5f
private const val LABEL_GONE_AT = 1.8f

/** The x of pet [i] when the row is scrolled to [scroll], with the spotlight at [centerX] and pets [pitch] apart. */
fun carouselX(i: Int, scroll: Float, centerX: Float, pitch: Float): Float = centerX + (i - scroll) * pitch

/** The pet a move of [delta] from [index] lands on: the row stops at both ends, it never wraps. */
fun carouselStep(index: Int, delta: Int, count: Int): Int = (index + delta).coerceIn(0, (count - 1).coerceAtLeast(0))

/** How big a pet [distance] pets from the centre is drawn: 1 at the centre, 0.85 next to it, 0.75 further out. */
fun carouselScale(distance: Float): Float {
    val d = abs(distance)
    return when {
        d <= 1f -> lerp(1f, NEIGHBOUR_SCALE, d)
        d <= 2f -> lerp(NEIGHBOUR_SCALE, FAR_SCALE, d - 1f)
        else -> FAR_SCALE
    }
}

/** How much of the spotlight a pet [distance] pets from the centre gets: 1 under it, 0 a pet away and beyond. */
fun carouselLit(distance: Float): Float = (1f - abs(distance)).coerceIn(0f, 1f)

/** The opacity of the label under a pet [distance] pets from the centre: full, half for a neighbour, then gone. */
fun carouselLabelAlpha(distance: Float): Float {
    val d = abs(distance)
    return when {
        d <= 1f -> lerp(1f, NEIGHBOUR_LABEL_ALPHA, d)
        d < LABEL_GONE_AT -> lerp(NEIGHBOUR_LABEL_ALPHA, 0f, (d - 1f) / (LABEL_GONE_AT - 1f))
        else -> 0f
    }
}

/**
 * The distance between two pets on a [width]-wide stage with a [inset] safe inset (any one unit, dp in the
 * app): [maxPitch], which on 16:9 shows the centre pet, a full neighbour each side and the next pets peeking
 * in at the edges; narrower where a neighbour's label ([CAROUSEL_LABEL_SHARE] of the pitch) would otherwise
 * cross the safe inset, which on 4:3 leaves the centre pet and its two neighbours.
 */
fun carouselPitch(width: Float, inset: Float, maxPitch: Float): Float =
    min(maxPitch, (width / 2f - inset) / (1f + CAROUSEL_LABEL_SHARE / 2f)).coerceAtLeast(0f)

/**
 * The display scale of the body under the spotlight when the row is at [spot]: the centred pet's, blended
 * between two pets mid-slide, so the spotlight's halo sits on a half-height pet as well as a full one.
 */
fun bodyUnderSpot(bodies: List<Float>, spot: Float): Float {
    if (bodies.isEmpty()) return 1f
    val s = spot.coerceIn(0f, bodies.lastIndex.toFloat())
    val i = floor(s).toInt().coerceAtMost(bodies.lastIndex)
    val next = (i + 1).coerceAtMost(bodies.lastIndex)
    return lerp(bodies[i], bodies[next], s - i)
}

/** [v] (0..1) snapped to [steps] steps, so a look that follows a slide changes a handful of times, not every frame. */
fun quantize(v: Float, steps: Int): Float = (v * steps).roundToInt() / steps.toFloat()

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
