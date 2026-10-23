package team.superfun.horrorpets.game.training

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/** The places on Slasher Says' ring a dot can take, one for each of the eight tones. */
const val SLASH_SLOTS = 8

/**
 * Slasher Says' ring is an ellipse this many times as wide as it is tall (its x radius over its y radius, in pixels).
 * The game lays its dots out on it, and the test that every dot can be reached with the arrows checks this shape.
 */
const val SLASH_RING_ASPECT = 1.5f

/** Each slot's angle round the ring, in degrees: round the sides and over the top, none under the pet's chest. */
private val SLOT_ANGLES = floatArrayOf(150f, 185f, 220f, 255f, 285f, 320f, 355f, 30f)

/**
 * Where [slot] sits round the ring, in degrees (0 is right, clockwise, y down): from low on the left (150) up and over
 * the pet's head to low on the right (30), so no dot is ever behind its body or legs. Slot `i` plays tone `i`, so the
 * notes climb clockwise.
 */
fun slotAngle(slot: Int): Float = SLOT_ANGLES[slot]

/**
 * Where [slot] sits on a ring centred on ([cx], [cy]) with radii [rx] by [ry]: the point at [slotAngle] on that ellipse,
 * in the same units, y down. The game and the reachability test both place their dots with it.
 */
fun slotPosition(slot: Int, cx: Float, cy: Float, rx: Float, ry: Float): Pair<Float, Float> {
    val a = Math.toRadians(slotAngle(slot).toDouble())
    return (cx + rx * cos(a).toFloat()) to (cy + ry * sin(a).toFloat())
}

/** How many dots round [round] has: 2 in round 1, one more each round, up to all 8 slots. */
fun slashDots(round: Int): Int = min(round + 1, SLASH_SLOTS)

/**
 * One round of Slasher Says: its dots, dot `i` at slot [slots]`[i]` (the slots all different, and dot `i` sounds
 * `tone(slots[i])`), to be slashed in [order] (the dots' indexes, each once).
 */
data class SlashRound(val round: Int, val slots: List<Int>, val order: List<Int>) {
    init {
        require(slots.toSet().size == slots.size && slots.all { it in 0 until SLASH_SLOTS }) { "bad slots $slots" }
        require(order.sorted() == slots.indices.toList()) { "order $order is not every dot once" }
    }
}

/**
 * Round [round] (from 1) laid out afresh: [slashDots] dots on random slots of the ring, numbered round it, to be
 * slashed in a random order. Nothing carries over from the round before; the dots and the melody are new each time.
 */
fun nextSlashRound(round: Int, rng: Random): SlashRound {
    require(round >= 1) { "rounds count from 1" }
    val slots = (0 until SLASH_SLOTS).shuffled(rng).take(slashDots(round)).sorted()
    return SlashRound(round, slots, slots.indices.shuffled(rng))
}

/**
 * How round [round] is shown, in ms: how long each dot stays lit, and the gap before the next one lights. Both shrink
 * by a tenth a round, from 520 and 180, to floors of 220 and 80.
 */
fun slashShowMs(round: Int): Pair<Long, Long> {
    val speed = 0.9.pow(round - 1)
    return max(220L, (520 * speed).roundToLong()) to max(80L, (180 * speed).roundToLong())
}

/** What a slash did: the dot that was due (and the [ROUND_DONE] last one), the wrong one, or nothing at all. */
enum class SlashStep { RIGHT, ROUND_DONE, WRONG, IGNORED }

/** The player's go at [round]: the dots slashed one at a time, in its order, until it is done or a dot is wrong. */
class SlashTurn(val round: SlashRound) {
    /** How many dots have been slashed so far, all right. */
    var slashed: Int = 0
        private set

    /** Whether the turn is over: every dot slashed, or a wrong one. */
    var over: Boolean = false
        private set

    /**
     * The dot to slash next; once a wrong dot ends the turn, the one that was due then (the one to show the player).
     * Null when the round is done.
     */
    val due: Int? get() = round.order.getOrNull(slashed)

    /** The dots slashed so far, in the order they were (the slash trail's way through them). */
    val path: List<Int> get() = round.order.subList(0, slashed)

    /**
     * Slashes [dot] (an index into the round's dots). The [due] dot is [SlashStep.RIGHT], or [SlashStep.ROUND_DONE] when
     * it is the last; any other dot not yet slashed is [SlashStep.WRONG], which ends the turn. A dot already slashed
     * (a swipe going back over its own trail) and anything after the turn is over are [SlashStep.IGNORED].
     */
    fun slash(dot: Int): SlashStep {
        require(dot in round.slots.indices) { "no dot $dot" }
        if (over || dot in path) return SlashStep.IGNORED
        if (dot != due) {
            over = true
            return SlashStep.WRONG
        }
        slashed++
        if (slashed < round.order.size) return SlashStep.RIGHT
        over = true
        return SlashStep.ROUND_DONE
    }
}

/** How far off an arrow's direction a dot may be and still count as that way, in degrees. */
private const val AIM_CONE_DEGREES = 75.0

/**
 * The dot an arrow ([dir]) aims at next, from [from] (the aimed dot, or the pet's chest before the first aim): of the
 * [dots] (but [skip], the one aimed at now) within [AIM_CONE_DEGREES] of that way, the one with the lowest cost,
 * distance times `1 + 2 × angle off / 75°`, so a dot straight ahead beats a nearer one well off to the side. The first
 * of equals wins; null when no dot is that way (and for OK, which is no way).
 */
fun aimFrom(from: Pair<Float, Float>, dots: List<Pair<Float, Float>>, dir: TrainingKey, skip: Int?): Int? {
    val (ux, uy) = when (dir) {
        TrainingKey.UP -> 0.0 to -1.0
        TrainingKey.DOWN -> 0.0 to 1.0
        TrainingKey.LEFT -> -1.0 to 0.0
        TrainingKey.RIGHT -> 1.0 to 0.0
        TrainingKey.OK -> return null
    }
    var best: Int? = null
    var bestCost = Double.MAX_VALUE
    dots.forEachIndexed { i, (x, y) ->
        if (i == skip) return@forEachIndexed
        val dx = (x - from.first).toDouble()
        val dy = (y - from.second).toDouble()
        val distance = hypot(dx, dy)
        if (distance == 0.0) return@forEachIndexed
        val off = abs(Math.toDegrees(atan2(ux * dy - uy * dx, ux * dx + uy * dy)))
        if (off > AIM_CONE_DEGREES) return@forEachIndexed
        val cost = distance * (1.0 + 2.0 * off / AIM_CONE_DEGREES)
        if (cost < bestCost) {
            best = i
            bestCost = cost
        }
    }
    return best
}
