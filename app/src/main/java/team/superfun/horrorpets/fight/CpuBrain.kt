package team.superfun.horrorpets.fight

import kotlin.random.Random
import team.superfun.horrorpets.model.spec

/**
 * Picks the CPU's move. Never RUN, and never SPECIAL unless the CPU's meter is full.
 *
 * 1. With a full meter, SPECIAL with probability 0.7.
 * 2. Otherwise, if the player has a habit, with probability 0.4 answer it: BLOCK against STRIKE,
 *    STRIKE against HEAVY, HEAVY against BLOCK. The habit is the most-used of STRIKE, HEAVY and BLOCK
 *    in [FightState.playerHistory] (SPECIAL has no answer, so it is not counted); a tie goes to the
 *    move the player used most recently.
 * 3. Otherwise a weighted pick: STRIKE 40, HEAVY 30, BLOCK 30.
 *
 * Dice, in order, each drawn only when its step is reached: a double for step 1 (only with a full
 * meter), a double for step 2 (only when there is a habit), then `nextInt(0, 100)` for step 3.
 */
fun cpuPick(s: FightState, rng: Random): Move {
    if (s.cpu.meter >= FightTuning.METER_MAX && rng.nextDouble() < FightTuning.CPU_SPECIAL_CHANCE) {
        return Move.SPECIAL
    }
    val habit = mostUsedMove(s.playerHistory)
    if (habit != null && rng.nextDouble() < FightTuning.CPU_COUNTER_CHANCE) {
        return answerTo(habit)
    }
    return weightedPick(rng)
}

/**
 * The CPU's move for the coming turn, picked by [cpuPick] before the player chooses, and the tell it shows.
 * A SPECIAL is always shown for what it is. A STRIKE, HEAVY or BLOCK is shown truthfully unless the CPU feints,
 * with probability 0.25 plus its passive's `feintBonus` (Ghostface's 0.2). A feint shows the move that answers
 * the real one, so the player who trusts it walks into what beats them: a STRIKE shows BLOCK (baiting a HEAVY,
 * which the STRIKE interrupts), a HEAVY shows STRIKE (baiting a BLOCK, which the HEAVY breaks), a BLOCK shows
 * HEAVY (baiting a STRIKE, which the BLOCK counters).
 *
 * Dice: [cpuPick]'s, then one double for the feint (none for a SPECIAL).
 */
fun cpuPlan(s: FightState, rng: Random): CpuPlan {
    val move = cpuPick(s, rng)
    if (move == Move.SPECIAL) return CpuPlan(move, move)
    val feints = rng.nextDouble() < FightTuning.CPU_FEINT_CHANCE + s.cpu.id.spec.passive.feintBonus
    return CpuPlan(move, if (feints) answerTo(move) else move)
}

/** [s] with the CPU's plan for its next turn ([cpuPlan]), or with none once the fight is over. */
fun planCpu(s: FightState, rng: Random): FightState =
    s.copy(cpuPlan = if (s.winner == null) cpuPlan(s, rng) else null)

private fun mostUsedMove(history: List<Move>): Move? {
    val counts = history.filter { it == Move.STRIKE || it == Move.HEAVY || it == Move.BLOCK }
        .groupingBy { it }
        .eachCount()
    val top = counts.values.maxOrNull() ?: return null
    return history.last { counts[it] == top }
}

private fun answerTo(habit: Move): Move = when (habit) {
    Move.STRIKE -> Move.BLOCK
    Move.HEAVY -> Move.STRIKE
    Move.BLOCK -> Move.HEAVY
    else -> error("No answer to $habit")
}

private fun weightedPick(rng: Random): Move {
    val strike = FightTuning.CPU_WEIGHT_STRIKE
    val heavy = FightTuning.CPU_WEIGHT_HEAVY
    val roll = rng.nextInt(0, strike + heavy + FightTuning.CPU_WEIGHT_BLOCK)
    return when {
        roll < strike -> Move.STRIKE
        roll < strike + heavy -> Move.HEAVY
        else -> Move.BLOCK
    }
}
