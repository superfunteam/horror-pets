package team.superfun.horrorpets.game.training

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/** Hide and Eek!'s tombstones: three in a row, at positions 0 (left), 1 and 2 (right). */
const val SEEK_STONES = 3

/** The tombstone designs the rounds cycle through (the game's art: rounded, cross, slab). */
private const val TOMB_DESIGNS = 3

/**
 * The tombstone design round [round] (from 1) shows: all three stones alike, so only the shuffle tells them apart, and
 * the next design each round (rounded, cross, slab, and round again).
 */
fun tombDesign(round: Int): Int = (round - 1) % TOMB_DESIGNS

/**
 * One round's shuffle: the mate hides behind the stone at [hideAt], then the [swaps] play in order, each exchanging the
 * stones at its two positions (the one leaving the first passing in front of the other), each taking [swapMs].
 */
data class Shuffle(val hideAt: Int, val swaps: List<Pair<Int, Int>>, val swapMs: Long) {
    init {
        require(hideAt in 0 until SEEK_STONES) { "no stone at $hideAt" }
        require(swaps.all { (a, b) -> a != b && a in 0 until SEEK_STONES && b in 0 until SEEK_STONES }) { "bad swaps $swaps" }
    }
}

/** How many swaps round [round] has: 3 in round 1, one more each round, up to 14 (from round 12). */
private fun swapCount(round: Int): Int = min(2 + round, 14)

/** How long each swap of round [round] takes, in ms: 600 in round 1, 12% less a round, down to 170 (from round 11). */
private fun swapMs(round: Int): Long = max(170L, (600 * 0.88.pow(round - 1)).roundToLong())

/**
 * Round [round]'s shuffle (from 1), dealt at random: the mate behind any of the three stones, then [swapCount] swaps,
 * each of two different positions (in either order, so either stone may pass in front), at [swapMs] each.
 */
fun shuffleFor(round: Int, rng: Random): Shuffle {
    require(round >= 1) { "rounds count from 1" }
    val swaps = List(swapCount(round)) {
        val a = rng.nextInt(SEEK_STONES)
        a to (a + 1 + rng.nextInt(SEEK_STONES - 1)) % SEEK_STONES
    }
    return Shuffle(rng.nextInt(SEEK_STONES), swaps, swapMs(round))
}

/** Where the mate's stone is once [shuffle]'s swaps are done: it moves with every swap it is in. */
fun mateAfter(shuffle: Shuffle): Int = shuffle.swaps.fold(shuffle.hideAt, ::afterSwap)

/** Where a stone at [at] is after [swap]: the other end of it, if it is in it. */
fun afterSwap(at: Int, swap: Pair<Int, Int>): Int = when (at) {
    swap.first -> swap.second
    swap.second -> swap.first
    else -> at
}
