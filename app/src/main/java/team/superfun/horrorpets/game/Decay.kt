package team.superfun.horrorpets.game

import java.util.TimeZone
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
 * [floorMs] is the earliest time the clock can really say: [Tuning.MIN_VALID_EPOCH_MS], or later when
 * more is known (the app passes the time it was first installed, because a TV stick without a clock
 * battery boots to its firmware's build date, which is after 2024 but before the install).
 * - A [nowMs] below [floorMs] is a not-yet-synced boot clock: nothing decays and `lastSeenMs` is left
 *   as it was.
 * - With no `lastSeenMs` (first run) nothing decays; `lastSeenMs` becomes [nowMs]. A persisted
 *   `lastSeenMs` below [floorMs] (a corrupt save, or one written under a stale clock) counts as no
 *   `lastSeenMs`, so it cannot turn the next valid launch into a 30-day jump and a fake death.
 * - A clock that went backwards counts as zero elapsed time and never moves `lastSeenMs` back.
 * - Elapsed time is capped at [Tuning.MAX_ELAPSED_MS]; `lastSeenMs` still advances to the real now.
 * - The hours of the stretch that fell in sleep hours on a clock in [zone] (see [sleepHoursBetween]) move hunger and
 *   fun at [Tuning.SLEEP_DECAY_SHARE] of their pace. The welcome back still counts the whole stretch.
 * - Decay is linear and clamped, so applying two intervals back to back equals applying their sum.
 * - After [Tuning.FAKE_DEATH_AFTER_MS] away, every pet fake-dies (applied after the decay). The strength
 *   penalty is skipped for a pet whose fake death is already pending (not yet viewed).
 * - A pet with [PetState.freshJoin] (one the save did not have yet) is left exactly as it is: it was not
 *   there for the time away. Once the time is applied (any [nowMs] at or above [floorMs]) the mark is
 *   cleared and from then on it decays like everyone else. Under a stale clock nothing is applied, so the
 *   mark stays (and is saved) for the real update.
 */
fun applyElapsed(
    state: GameState,
    nowMs: Long,
    floorMs: Long = Tuning.MIN_VALID_EPOCH_MS,
    zone: TimeZone = TimeZone.getDefault(),
): Pair<GameState, AwaySummary> {
    if (nowMs < floorMs) return state to AwaySummary(0L, null, 0, emptySet())

    val lastSeen = state.lastSeenMs?.takeIf { it >= floorMs }
    val newLastSeen = max(lastSeen ?: nowMs, nowMs)
    val elapsedMs = if (lastSeen == null) 0L else min(gapMs(nowMs, lastSeen), Tuning.MAX_ELAPSED_MS)
    if (elapsedMs == 0L) {
        return state.copy(pets = settled(state.pets), lastSeenMs = newLastSeen) to AwaySummary(0L, null, 0, emptySet())
    }

    val hours = elapsedMs.toDouble() / Tuning.HOUR_MS
    // The stretch applied ends now and is elapsedMs long: under the cap it starts after lastSeen, not at it.
    val sleepHours = sleepHoursBetween(nowMs - elapsedMs, nowMs, zone)
    var pets = state.pets.mapValues { (_, p) -> if (p.freshJoin) p else decay(p, hours, sleepHours) }

    val fakeDeath = if (elapsedMs >= Tuning.FAKE_DEATH_AFTER_MS) pets.filterValues { !it.freshJoin }.keys else emptySet()
    if (fakeDeath.isNotEmpty()) pets = pets.mapValues { (id, p) -> if (id in fakeDeath) fakeDie(p) else p }

    val next = state.copy(pets = settled(pets), lastSeenMs = newLastSeen)
    return next to summarize(state, next, elapsedMs, fakeDeath)
}

/** [pets] with every fresh-join mark cleared: the time away has been applied. */
private fun settled(pets: Map<PetId, PetState>): Map<PetId, PetState> =
    if (pets.values.none { it.freshJoin }) pets else pets.mapValues { (_, p) -> p.copy(freshJoin = false) }

/** now - lastSeen, floored at 0 and saturating instead of overflowing on a corrupt lastSeen. */
private fun gapMs(nowMs: Long, lastSeenMs: Long): Long =
    try {
        max(0L, Math.subtractExact(nowMs, lastSeenMs))
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

/**
 * [p] after [hours] of time away, [sleepHours] of them (at most [hours]) asleep: hunger and fun move at
 * [Tuning.SLEEP_DECAY_SHARE] of their pace through the sleep, strength and the mood bonus at their full pace throughout.
 */
internal fun decay(p: PetState, hours: Double, sleepHours: Double): PetState {
    // A sleeping hour counts for SLEEP_DECAY_SHARE of a waking one.
    val hungerHours = hours - sleepHours * (1 - Tuning.SLEEP_DECAY_SHARE)
    return p.copy(
        hunger = clampStat(p.hunger + Tuning.HUNGER_PER_HOUR * hungerHours),
        funLevel = clampStat(p.funLevel - Tuning.FUN_PER_HOUR * hungerHours),
        strength = clampStat(p.strength - Tuning.STRENGTH_PER_HOUR * hours),
        moodBonus = moveTowardZero(clampMoodBonus(p.moodBonus), Tuning.MOOD_BONUS_DECAY_PER_HOUR * hours),
    )
}

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
