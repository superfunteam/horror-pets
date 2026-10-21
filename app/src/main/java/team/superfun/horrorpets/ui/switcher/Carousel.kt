package team.superfun.horrorpets.ui.switcher

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
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

/**
 * How far from the spotlight (in pets) a pet is still on stage, on 16:9 and narrower. Beyond it a pet is wholly
 * off screen even on 16:9 (where pets two away peek in at the edges), so the switcher neither composes it nor
 * decodes its strip. A wider screen reaches further, see [carouselReach].
 */
const val CAROUSEL_REACH = 2.5f

/** The most a pet's body reaches either side of its place in the row (dp), at the size it has two pets out. */
private const val PET_HALF_WIDTH = 60f

/**
 * How far from the spotlight (in pets) a pet is still on stage on a [width]-wide stage with pets [pitch] apart
 * (dp): [CAROUSEL_REACH], or more on a screen wider than 16:9 (a 20:9 phone), whose edges show pets that 16:9
 * leaves off screen.
 */
fun carouselReach(width: Float, pitch: Float): Float =
    if (pitch <= 0f) CAROUSEL_REACH else max(CAROUSEL_REACH, (width / 2f + PET_HALF_WIDTH) / pitch)

/** Whether pet [i] is on stage with the row at [row]: less than [reach] (see [carouselReach]) from the spotlight. */
fun carouselInView(i: Int, row: Float, reach: Float = CAROUSEL_REACH): Boolean = abs(i - row) < reach

/** The first of [count] pets on stage with the row at [row] (see [carouselInView]); allocation-free. */
fun carouselFirstInView(row: Float, count: Int, reach: Float = CAROUSEL_REACH): Int =
    (floor(row - reach).toInt() + 1).coerceIn(0, (count - 1).coerceAtLeast(0))

/** The last of [count] pets on stage with the row at [row] (see [carouselInView]); allocation-free. */
fun carouselLastInView(row: Float, count: Int, reach: Float = CAROUSEL_REACH): Int =
    (ceil(row + reach).toInt() - 1).coerceIn(0, (count - 1).coerceAtLeast(0))

/**
 * The pets just outside the window [first]..[last] of [count]: the next one beyond each end, whose strips are
 * decoded ahead so they draw at once when the row brings them on stage.
 */
fun carouselPrefetch(first: Int, last: Int, count: Int): List<Int> = listOf(first - 1, last + 1).filter { it in 0 until count }

/** [v] (0..1) snapped to [steps] steps, so a look that follows a slide changes a handful of times, not every frame. */
fun quantize(v: Float, steps: Int): Float = (v * steps).roundToInt() / steps.toFloat()

// ---- touch ----

/** Dragged past either end, the row gives this share of the finger's travel... */
private const val OVERDRAG_GIVE = 0.3f

/** ...and never more than this (in pets), then settles back when let go, like the D-pad's nudge. */
const val CAROUSEL_MAX_OVERDRAG = 0.35f

/**
 * Where the row is while a finger drags it: the drag started with the row at [start], and the finger has
 * since moved [dx] (px, negative to the left) with pets [pitch] px apart. The row follows the finger, so a
 * drag to the left brings in the pets on the right; past either end of the row it gives a little and stops.
 */
fun carouselDragRow(start: Float, dx: Float, pitch: Float, count: Int): Float {
    if (pitch <= 0f) return start
    val raw = start - dx / pitch
    val last = (count - 1).coerceAtLeast(0).toFloat()
    return when {
        raw < 0f -> -min(-raw * OVERDRAG_GIVE, CAROUSEL_MAX_OVERDRAG)
        raw > last -> last + min((raw - last) * OVERDRAG_GIVE, CAROUSEL_MAX_OVERDRAG)
        else -> raw
    }
}

/** A drag let go at this speed or more (pets per second) is a flick: the row carries on to another pet. */
const val CAROUSEL_FLICK = 1.5f

/** A flick carries the row on as far as it would go in this long at the speed it was let go at. */
private const val FLICK_CARRY_S = 0.15f

/**
 * The pet a drag settles on when the finger lets go with the row at [row], moving at [velocity] pets per
 * second (positive toward the end of the row). A slow release settles on the nearest pet; a flick goes on
 * at least to the next pet in its direction, further the faster it was. The row stops at both ends.
 */
fun carouselFlingTarget(row: Float, velocity: Float, count: Int): Int {
    val carried = (row + velocity * FLICK_CARRY_S).roundToInt()
    val target = when {
        velocity >= CAROUSEL_FLICK -> max(carried, floor(row).toInt() + 1)
        velocity <= -CAROUSEL_FLICK -> min(carried, ceil(row).toInt() - 1)
        else -> row.roundToInt()
    }
    return target.coerceIn(0, (count - 1).coerceAtLeast(0))
}

/**
 * The pet a tap at [x] (px) is on, with the row at [row], the spotlight at [centerX] and pets [pitch] px
 * apart: the pet whose place in the row is nearest, or null when that place is past either end of the row.
 */
fun carouselTapTarget(x: Float, row: Float, centerX: Float, pitch: Float, count: Int): Int? {
    if (pitch <= 0f) return null
    return (row + (x - centerX) / pitch).roundToInt().takeIf { it in 0 until count }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
