package team.superfun.horrorpets.fight

import kotlin.random.Random
import team.superfun.horrorpets.model.PetId

/**
 * One attack's three dice, in the order the engine draws them (see [resolveTurn]): the miss roll,
 * the damage-variance roll, the crit roll. The defaults are "lands, no variance, no crit".
 */
data class Roll(
    val miss: Double = NO_MISS,
    val variance: Double = NEUTRAL,
    val crit: Double = NO_CRIT,
) {
    companion object {
        /** Above every miss chance in play (the largest is 0.15 + 0.07). */
        const val NO_MISS = 0.99

        /** Variance 0.5 means exactly x1.0 (0.0 is x0.85, 1.0 is x1.15). */
        const val NEUTRAL = 0.5

        /** Above every crit chance in play. */
        const val NO_CRIT = 0.99
    }
}

/**
 * A [Random] that replays queued values and fails loudly on anything unscripted, so a test also
 * pins how many dice the engine draws. The engine may only call [nextDouble] and [nextInt] with a
 * range; anything built on [nextBits] throws.
 */
class ScriptedRandom(doubles: List<Double> = emptyList(), ints: List<Int> = emptyList()) : Random() {
    private val doubles = ArrayDeque(doubles)
    private val ints = ArrayDeque(ints)

    val remainingDoubles: Int get() = doubles.size
    val remainingInts: Int get() = ints.size

    override fun nextBits(bitCount: Int): Int = throw AssertionError("unscripted nextBits($bitCount)")

    override fun nextDouble(): Double = doubles.removeFirstOrNull() ?: throw AssertionError("script is out of doubles")

    override fun nextInt(from: Int, until: Int): Int {
        val v = ints.removeFirstOrNull() ?: throw AssertionError("script is out of ints")
        check(v in from until until) { "scripted int $v is outside $from until $until" }
        return v
    }

    companion object {
        /** A script for the attacks of one or more turns, three dice per attack. */
        fun attacks(vararg rolls: Roll) = ScriptedRandom(rolls.flatMap { listOf(it.miss, it.variance, it.crit) })
    }
}

/** A fighter with test-friendly defaults: strength 64 gives a base damage of exactly 16. */
fun fighter(
    id: PetId = PetId.JASON,
    level: Int = 1,
    hp: Int = 100,
    maxHp: Int = hp,
    strength: Double = 64.0,
    mood: Int = 0,
    meter: Int = 0,
    combo: Int = 0,
) = Fighter(id, level, maxHp, hp, strength, mood, meter, combo)

fun stateOf(
    player: Fighter = fighter(),
    cpu: Fighter = fighter(),
    turn: Int = 1,
    history: List<Move> = emptyList(),
    winner: Side? = null,
    playerRan: Boolean = false,
) = FightState(player, cpu, turn, history, winner, playerRan)
