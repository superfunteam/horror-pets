package team.superfun.horrorpets.ui.home

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How an idle pet strolls about its scene on Home, kept free of Compose so it can be tested. Positions are
 * the pet's centre as fractions of the screen width.
 */

/** A stroll is this far, as a fraction of the screen width (the caller picks within it). */
const val WANDER_STEP_MIN = 0.10f
const val WANDER_STEP_MAX = 0.22f

/** Walking pace: this many of the pet's drawn heights per second, which keeps its run cycle's feet from skating. */
const val WALK_HEIGHTS_PER_SECOND = 0.55f

/** Where the pet sits at home, and where "back toward the middle" heads. */
private const val HOME_X = 0.5f

/** Closer to the middle than this, heading back to it is just another stroll. */
private const val AT_HOME = 0.02f

/** The span the pet's centre may stroll over, as fractions of the screen width. */
data class WanderRange(val min: Float, val max: Float)

/**
 * Where a pet may stroll on a [screenW]-wide Home: its body ([bodyHalfPx] either side of its centre) stays
 * right of the stat card's right edge ([cardRightPx]), so it never walks under the glass card, and inside the
 * right safe inset ([insetPx]). For a full-size pet at 16:9 that is about 38%–88% of the width (a half-height
 * Chucky's slimmer body gets a little more), and on 4:3 about 45%–85%. A screen too narrow for both pins
 * the pet to the middle of what is left.
 */
fun wanderRange(screenW: Float, insetPx: Float, cardRightPx: Float, bodyHalfPx: Float): WanderRange {
    val min = (cardRightPx + bodyHalfPx) / screenW
    val max = (screenW - insetPx - bodyHalfPx) / screenW
    if (min <= max) return WanderRange(min, max)
    val middle = (min + max) / 2f
    return WanderRange(middle, middle)
}

/**
 * The next spot for a pet standing at [from] (clamped into [range] first, in case the screen changed):
 * - [toCenter]: back toward the middle by [step], stopping on it rather than walking past it (a pet already
 *   in the middle just strolls);
 * - otherwise [step] to the right ([preferRight]) or left, turning around when there is not even half a step
 *   of room that way.
 * The spot is always inside [range].
 */
fun wanderTarget(from: Float, range: WanderRange, step: Float, toCenter: Boolean, preferRight: Boolean): Float {
    val start = from.coerceIn(range.min, range.max)
    val home = HOME_X.coerceIn(range.min, range.max)
    if (toCenter && abs(home - start) > AT_HOME) {
        return if (abs(home - start) <= step) home else start + if (home > start) step else -step
    }
    var dir = if (preferRight) 1f else -1f
    val room = if (dir > 0f) range.max - start else start - range.min
    if (room < step / 2f) dir = -dir
    return (start + dir * step).coerceIn(range.min, range.max)
}

/**
 * How long a stroll of [distancePx] takes for a pet drawn [drawnHeightPx] tall (its display scale included)
 * at [walkSpeed] (the roster's factor): [WALK_HEIGHTS_PER_SECOND] drawn heights a second, so a half-height
 * pet takes half-length strides and covers ground at half the pace. Zero for no walk or no pet.
 */
fun wanderDurationMs(distancePx: Float, drawnHeightPx: Float, walkSpeed: Float): Long {
    val pxPerSecond = WALK_HEIGHTS_PER_SECOND * drawnHeightPx * walkSpeed
    if (distancePx <= 0f || pxPerSecond <= 0f) return 0L
    return (distancePx / pxPerSecond * 1000f).roundToLong()
}
