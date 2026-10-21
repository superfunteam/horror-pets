package team.superfun.horrorpets.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import team.superfun.horrorpets.model.AwaySummary
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

/**
 * Applies the wall-clock time since [GameState.lastSeenMs] to every pet.
 *
 * - A [nowMs] below [Tuning.MIN_VALID_EPOCH_MS] is a not-yet-synced boot clock: nothing decays and
 *   `lastSeenMs` is left as it was.
 * - With no `lastSeenMs` (first run) nothing decays; `lastSeenMs` becomes [nowMs]. A persisted
 *   `lastSeenMs` below [Tuning.MIN_VALID_EPOCH_MS] (a corrupt or unsynced-clock save) counts as no
 *   `lastSeenMs`, so it cannot turn the next valid launch into a 30-day jump and a fake death.
 * - A clock that went backwards counts as zero elapsed time and never moves `lastSeenMs` back.
 * - Elapsed time is capped at [Tuning.MAX_ELAPSED_MS]; `lastSeenMs` still advances to the real now.
 * - Decay is linear and clamped, so applying two intervals back to back equals applying their sum.
 * - After [Tuning.FAKE_DEATH_AFTER_MS] away, every pet fake-dies (applied after the decay). The strength
 *   penalty is skipped for a pet whose fake death is already pending (not yet viewed).
 */
fun applyElapsed(state: GameState, nowMs: Long): Pair<GameState, AwaySummary> {
    if (nowMs < Tuning.MIN_VALID_EPOCH_MS) return state to AwaySummary(0L, null, 0, emptySet())

    val lastSeen = state.lastSeenMs?.takeIf { it >= Tuning.MIN_VALID_EPOCH_MS }
    val newLastSeen = max(lastSeen ?: nowMs, nowMs)
    val elapsedMs = if (lastSeen == null) 0L else min(gapMs(nowMs, lastSeen), Tuning.MAX_ELAPSED_MS)
    if (elapsedMs == 0L) {
        return state.copy(lastSeenMs = newLastSeen) to AwaySummary(0L, null, 0, emptySet())
    }

    val hours = elapsedMs.toDouble() / Tuning.HOUR_MS
    var pets = state.pets.mapValues { (_, p) -> decay(p, hours) }

    val fakeDeath = if (elapsedMs >= Tuning.FAKE_DEATH_AFTER_MS) pets.keys.toSet() else emptySet()
    if (fakeDeath.isNotEmpty()) pets = pets.mapValues { (_, p) -> fakeDie(p) }

    val next = state.copy(pets = pets, lastSeenMs = newLastSeen)
    return next to summarize(state, next, elapsedMs, fakeDeath)
}

/** now - lastSeen, floored at 0 and saturating instead of overflowing on a corrupt lastSeen. */
private fun gapMs(nowMs: Long, lastSeenMs: Long): Long =
    try {
        max(0L, Math.subtractExact(nowMs, lastSeenMs))
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

private fun decay(p: PetState, hours: Double): PetState = p.copy(
    hunger = clampStat(p.hunger + Tuning.HUNGER_PER_HOUR * hours),
    funLevel = clampStat(p.funLevel - Tuning.FUN_PER_HOUR * hours),
    strength = clampStat(p.strength - Tuning.STRENGTH_PER_HOUR * hours),
    moodBonus = moveTowardZero(clampMoodBonus(p.moodBonus), Tuning.MOOD_BONUS_DECAY_PER_HOUR * hours),
)

/** Closed-form move toward 0 that stops at 0, so decaying a then b equals decaying a + b. */
private fun moveTowardZero(v: Double, amount: Double): Double =
    if (v >= 0.0) max(0.0, v - amount) else min(0.0, v + amount)

/**
 * The strength penalty applies once per un-viewed fake death: a pet that is already pending (decay
 * keeps the flag) still gets the hunger/fun reset but is not penalized a second time.
 */
private fun fakeDie(p: PetState): PetState = p.copy(
    fakeDeathPending = true,
    hunger = Tuning.FAKE_DEATH_HUNGER,
    funLevel = Tuning.FAKE_DEATH_FUN,
    strength = if (p.fakeDeathPending) p.strength else clampStat(p.strength * Tuning.FAKE_DEATH_STRENGTH_FACTOR),
)

private fun summarize(before: GameState, after: GameState, elapsedMs: Long, fakeDeath: Set<PetId>): AwaySummary {
    val id = before.selected ?: PetId.JASON
    val old = before.pets[id]
    val new = after.pets[id]
    if (old == null || new == null) return AwaySummary(elapsedMs, null, 0, fakeDeath)

    val deltas = listOf(
        "Hunger" to new.hunger - old.hunger,
        "Fun" to new.funLevel - old.funLevel,
        "Strength" to new.strength - old.strength,
    )
    // On a tie the earlier entry wins (Hunger, Fun, Strength).
    val (label, delta) = deltas.maxBy { abs(it.second) }
    val rounded = delta.roundToInt()
    return if (rounded == 0) {
        AwaySummary(elapsedMs, null, 0, fakeDeath)
    } else {
        AwaySummary(elapsedMs, label, rounded, fakeDeath)
    }
}
