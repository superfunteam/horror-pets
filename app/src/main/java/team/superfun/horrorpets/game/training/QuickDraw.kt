package team.superfun.horrorpets.game.training

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/** The parts of Quick Draw's ring a call can light: the four wedges, as on a remote's D-pad, and the OK button inside. */
enum class DrawCall { UP, DOWN, LEFT, RIGHT, CENTER }

/** The remote's key that matches this part: its direction, or OK for the centre. */
fun DrawCall.key(): TrainingKey = when (this) {
    DrawCall.UP -> TrainingKey.UP
    DrawCall.DOWN -> TrainingKey.DOWN
    DrawCall.LEFT -> TrainingKey.LEFT
    DrawCall.RIGHT -> TrainingKey.RIGHT
    DrawCall.CENTER -> TrainingKey.OK
}

/** The part of the ring [key] presses (OK is the centre). */
fun drawCallOf(key: TrainingKey): DrawCall = when (key) {
    TrainingKey.UP -> DrawCall.UP
    TrainingKey.DOWN -> DrawCall.DOWN
    TrainingKey.LEFT -> DrawCall.LEFT
    TrainingKey.RIGHT -> DrawCall.RIGHT
    TrainingKey.OK -> DrawCall.CENTER
}

/** The shortest and the longest wait before a call, in ms. */
private const val WAIT_MIN_MS = 700L
private const val WAIT_MAX_MS = 2_200L

/** How long the ring stands dark before the next call, in ms: anywhere from 700 to 2200, so it can't be timed. */
fun drawWaitMs(rng: Random): Long = rng.nextLong(WAIT_MIN_MS, WAIT_MAX_MS + 1)

/**
 * How long a call stays open with [streak] draws already made, in ms: 1500 for the first, 8% less after each draw,
 * down to a floor of 350 (from the 18th draw on).
 */
fun drawWindowMs(streak: Int): Long = max(350L, (1_500 * 0.92.pow(streak)).roundToLong())

/**
 * The next call after the [recent] ones (the latest last): any of the five parts at random, but never one called the
 * last two times running, so no part is called three times in a row.
 */
fun nextCall(recent: List<DrawCall>, rng: Random): DrawCall {
    val last = recent.lastOrNull()
    val twice = last.takeIf { recent.size >= 2 && recent[recent.size - 2] == last }
    return DrawCall.entries.filter { it != twice }.random(rng)
}

/** How a call went: the part matched in time ([HIT]), the [WRONG] part, a press before the call ([EARLY]), or [LATE]. */
enum class DrawOutcome { HIT, WRONG, EARLY, LATE }

/**
 * Judges a press of [pressed], or no press at all (null, which the game asks about once the window is over), [sinceCallMs]
 * after [called] lit, its window being [windowMs]. Before anything is called ([called] null) any press is [EARLY].
 * After, the press comes in time up to the window's very end: the called part is a [HIT] and any other is [WRONG]. No
 * press, or any press after the window, is [LATE].
 */
fun judgeDraw(called: DrawCall?, pressed: DrawCall?, sinceCallMs: Long, windowMs: Long): DrawOutcome = when {
    called == null -> DrawOutcome.EARLY
    pressed == null || sinceCallMs > windowMs -> DrawOutcome.LATE
    pressed == called -> DrawOutcome.HIT
    else -> DrawOutcome.WRONG
}

/**
 * The part of a Quick Draw ring a tap lands on, ([dx], [dy]) from its centre (y down): the centre button within
 * [centerR] of the middle; past it, out to [outerR], the wedge whose quarter it is in (each wedge a quarter of the ring,
 * centred on its direction, a tap on the line between two going to the side wedge); null outside the ring.
 */
fun drawPartAt(dx: Float, dy: Float, centerR: Float, outerR: Float): DrawCall? {
    val r2 = dx * dx + dy * dy
    return when {
        r2 <= centerR * centerR -> DrawCall.CENTER
        r2 > outerR * outerR -> null
        abs(dx) >= abs(dy) -> if (dx > 0f) DrawCall.RIGHT else DrawCall.LEFT
        else -> if (dy > 0f) DrawCall.DOWN else DrawCall.UP
    }
}
