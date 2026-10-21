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

/** A stroll covers at least this much of the width, or the pet stays where it is: no shuffling on the spot. */
const val WANDER_MIN_MOVE = 0.06f

/** Walking pace: this many of the pet's drawn heights per second, which keeps its run cycle's feet from skating. */
const val WALK_HEIGHTS_PER_SECOND = 0.55f

/** Where the pet sits at home, and where "back toward the middle" heads. */
private const val HOME_X = 0.5f


/** The span the pet's centre may stroll over, as fractions of the screen width. */
data class WanderRange(val min: Float, val max: Float) {
    /** [x] brought inside this span: where a pet caught outside it (on its way into a scene) settles. */
    fun clamp(x: Float): Float = x.coerceIn(min, max)
}

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
 * - [toCenter]: back toward the middle by [step], stopping on it rather than walking past it (a pet less
 *   than [WANDER_MIN_MOVE] from the middle just strolls instead);
 * - otherwise [step] to the right ([preferRight]) or left, turning around when there is not even half a step
 *   of room that way, and stopping at the edge of [range].
 * A stroll that would cover less than [WANDER_MIN_MOVE] tries the other way; if that is short too, the
 * result is the start itself (no stroll). The spot is always inside [range].
 */
fun wanderTarget(from: Float, range: WanderRange, step: Float, toCenter: Boolean, preferRight: Boolean): Float {
    val start = from.coerceIn(range.min, range.max)
    val home = HOME_X.coerceIn(range.min, range.max)
    if (toCenter && abs(home - start) >= WANDER_MIN_MOVE) {
        return if (abs(home - start) <= step) home else start + if (home > start) step else -step
    }
    var dir = if (preferRight) 1f else -1f
    val room = if (dir > 0f) range.max - start else start - range.min
    if (room < step / 2f) dir = -dir
    for (way in floatArrayOf(dir, -dir)) {
        val target = (start + way * step).coerceIn(range.min, range.max)
        if (abs(target - start) >= WANDER_MIN_MOVE) return target
    }
    return start
}

/**
 * The pace of a pet drawn [drawnHeightPx] tall (its display scale included) at [walkSpeed] (the roster's
 * factor), in pixels a second: [WALK_HEIGHTS_PER_SECOND] drawn heights a second, so a half-height pet takes
 * half-length strides and covers ground at half the pace. Every walk on Home moves at this pace, so the
 * run cycle's feet never skate.
 */
fun walkPxPerSecond(drawnHeightPx: Float, walkSpeed: Float): Float = WALK_HEIGHTS_PER_SECOND * drawnHeightPx * walkSpeed

/** How long a stroll of [distancePx] takes at [walkPxPerSecond]. Zero for no walk or no pet. */
fun wanderDurationMs(distancePx: Float, drawnHeightPx: Float, walkSpeed: Float): Long {
    val pxPerSecond = walkPxPerSecond(drawnHeightPx, walkSpeed)
    if (distancePx <= 0f || pxPerSecond <= 0f) return 0L
    return (distancePx / pxPerSecond * 1000f).roundToLong()
}

/** How far, as a fraction of the [screenW]-wide screen, a pet walks in [ms] at [walkPxPerSecond]. */
fun walkDistance(ms: Long, screenW: Float, drawnHeightPx: Float, walkSpeed: Float): Float {
    if (screenW <= 0f || ms <= 0L) return 0f
    return walkPxPerSecond(drawnHeightPx, walkSpeed).coerceAtLeast(0f) * ms / 1000f / screenW
}

/** A scene walk's arrival: the pet starts [distance] (a fraction of the width) out and walks in for [ms]. */
data class Arrival(val distance: Float, val ms: Long)

/**
 * The arrival into the next scene at [walkPxPerSecond]: from [maxDistance] out when that takes no longer than
 * [maxMs], otherwise for [maxMs] from only as far out as the pace covers, so a slow walker (Chucky, Michael)
 * neither skates nor drags. A pet that cannot walk arrives in place.
 */
fun arrival(screenW: Float, drawnHeightPx: Float, walkSpeed: Float, maxDistance: Float, maxMs: Long): Arrival {
    val full = wanderDurationMs(maxDistance * screenW, drawnHeightPx, walkSpeed)
    if (full <= 0L) return Arrival(0f, 0L)
    if (full <= maxMs) return Arrival(maxDistance, full)
    return Arrival(walkDistance(maxMs, screenW, drawnHeightPx, walkSpeed), maxMs)
}
