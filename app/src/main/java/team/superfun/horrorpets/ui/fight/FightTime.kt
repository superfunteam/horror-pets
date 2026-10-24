package team.superfun.horrorpets.ui.fight

import kotlin.math.floor
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Move

/**
 * How long a blow freezes both fighters as it lands (the fight's clock stops, see `FightStage.timeScale`):
 * [FightTuning.HIT_STOP_HEAVY_MS] for a HEAVY, a special, a crit or a counter, [FightTuning.HIT_STOP_STRIKE_MS] for
 * any other hit, and 0 for anything that is not a hit (a miss, a block).
 */
internal fun hitStopFor(event: FightEvent): Long {
    if (event !is FightEvent.Hit) return 0L
    val big = event.move == Move.HEAVY || event.move == Move.SPECIAL || event.crit || event.counter
    return if (big) FightTuning.HIT_STOP_HEAVY_MS else FightTuning.HIT_STOP_STRIKE_MS
}

/** Whether [event] is the blow that empties a bar standing at [defenderHp]: a hit for at least that much. */
internal fun koBlow(event: FightEvent, defenderHp: Int): Boolean =
    event is FightEvent.Hit && event.damage >= defenderHp

/**
 * When a blow's parts land, in ms after the swing's impact: its sound at [soundAtMs] (the landing, later than the
 * impact for a special whose blow lands partway through its effect), then the fight's clock stops for [stopMs], and
 * the blow itself (its number, flash, shake and HP drain) comes as time resumes, at [blowAtMs].
 */
internal data class ImpactSchedule(val soundAtMs: Long, val stopMs: Long, val blowAtMs: Long)

/** The schedule of a blow that lands [landMs] after the impact and stops the clock for [stopMs] (see [hitStopFor]). */
internal fun impactSchedule(landMs: Long, stopMs: Long): ImpactSchedule =
    ImpactSchedule(soundAtMs = landMs, stopMs = stopMs, blowAtMs = landMs + stopMs)

/**
 * A clock that runs at a varying speed: each [advance] adds a step of real time scaled by the speed of the moment, so
 * at 0 it holds and at 0.3 it crawls. Fractions of a millisecond carry over, so many short slow steps add up.
 */
internal class ScaledClock {
    private var elapsed = 0.0

    /** Moves on [dtMs] of real time at [scale] speed, and returns the scaled time passed so far, in whole ms. */
    fun advance(dtMs: Long, scale: Float): Long {
        elapsed += dtMs * scale.toDouble()
        return floor(elapsed).toLong()
    }
}
