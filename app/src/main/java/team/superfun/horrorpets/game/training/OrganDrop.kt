package team.superfun.horrorpets.game.training

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/** How fast the killer runs along the floor, in play widths a second. */
const val RUN_SPEED = 0.9f

/** An organ reaching the catch line this far from the killer or nearer, either side, in play widths, is caught. */
const val CATCH_HALF = 0.075f

/** Where across the play area the organs fall and the killer runs, in play widths (0 the left edge, 1 the right). */
val DROP_RANGE: ClosedFloatingPointRange<Float> = 0.1f..0.9f

/** How many kinds of organ fall (the game's art: heart, brain, eyeball, bone, hand, kidney). */
const val ORGAN_KINDS = 6

/** Where the killer stands as the game starts, across the play area: the middle. */
const val START_X = 0.5f

/**
 * The first organ reaches the catch line this long into the game, having shown at the top a beat (400 ms) in: it falls
 * for [fallMs] of 0.
 */
const val FIRST_LAND_MS = 1_600L

/** Each drop lands this much short of the farthest the killer could run there from the last, in play widths. */
private const val REACH_MARGIN = 0.05f

/**
 * One organ falling: [organ] (an index into the organs' art), coming down at [x] across the play area (in play widths),
 * reaching the catch line [landMs] into the game. It shows at the top [fallMs] before that, at [landMs] − [fallMs].
 */
data class Drop(val organ: Int, val x: Float, val landMs: Long, val fallMs: Long)

/**
 * How long drop [index] (from 0) takes to fall from the top to the catch line, in ms: 1200 for the first, 1.5% less each
 * drop after, down to 900 (from the 19th on).
 */
fun fallMs(index: Int): Long = max(900L, (1_200 * 0.985.pow(index)).roundToLong())

/**
 * How long after the one before it drop [index] lands, in ms: 1500 at first, 5% less each drop, down to 420 (from the
 * 25th on). For the first seven drops the gap is no shorter than the fall, so each organ shows only once the one
 * before it has come down: one at a time. The falls shrink more slowly than the gaps, so from the 8th on they overlap,
 * more and more of them in the air at once (two at times, then three at times from about the 22nd).
 */
fun landGapMs(index: Int): Long = max(420L, (1_500 * 0.95.pow(index)).roundToLong())

/**
 * The drop after [prev] (none for the first), drop [index] of the game, dealt at random: the first lands
 * [FIRST_LAND_MS] in; each after it [landGapMs] after the one before. Each comes down somewhere the killer can run to
 * in time from where the last one landed (the first from [START_X]): no farther than [RUN_SPEED] times the time it has
 * to run for it, less a small margin. That is the time between the landings, or its fall when that is shorter (no one
 * runs for an organ before it shows). In [DROP_RANGE], and never the same organ as the last.
 */
fun nextDrop(prev: Drop?, index: Int, rng: Random): Drop {
    require(index >= 0) { "drops count from 0" }
    val from = prev?.x ?: START_X
    val landMs = if (prev == null) FIRST_LAND_MS else prev.landMs + landGapMs(index)
    val fall = fallMs(index)
    val runMs = min(landMs - (prev?.landMs ?: 0L), fall)
    val reach = RUN_SPEED * runMs / 1_000f - REACH_MARGIN
    val lo = max(DROP_RANGE.start, from - reach)
    val hi = min(DROP_RANGE.endInclusive, from + reach)
    val x = (lo + rng.nextFloat() * (hi - lo)).coerceIn(lo, hi)
    val organ = if (prev == null) rng.nextInt(ORGAN_KINDS) else (prev.organ + 1 + rng.nextInt(ORGAN_KINDS - 1)) % ORGAN_KINDS
    return Drop(organ, x, landMs, fall)
}

/** Whether an organ coming down at [dropX] is caught by the killer at [killerX]: within [CATCH_HALF] either side. */
fun caught(dropX: Float, killerX: Float): Boolean = abs(dropX - killerX) <= CATCH_HALF

/**
 * Which way the remote steers the killer: the arrows held down ([key] each going down and coming up), the one most
 * recently pressed winning while both are held. A held key's repeats (more downs) change nothing, so holding Right
 * under a newer Left keeps it running left. Up, Down and OK don't steer.
 */
class KeyHold {
    /** The directions held, the oldest press first. */
    private val held = ArrayList<Int>(2)

    /** A training key going [down] or coming up. */
    fun key(key: TrainingKey, down: Boolean) {
        val way = when (key) {
            TrainingKey.LEFT -> -1
            TrainingKey.RIGHT -> 1
            TrainingKey.UP, TrainingKey.DOWN, TrainingKey.OK -> return
        }
        if (!down) {
            held.remove(way)
        } else if (way !in held) {
            held += way
        }
    }

    /** Which way to run: −1 left, +1 right, 0 standing still (nothing held). */
    val direction: Int get() = held.lastOrNull() ?: 0

    /**
     * Forgets every key held, as if all were let go: for when a key's coming up may never arrive (the app went away
     * with it held). One still held picks up again with its next repeat.
     */
    fun clear() = held.clear()
}
