package team.superfun.horrorpets.ui.fight

import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side

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

/**
 * Whether [event], [attacker]'s blow on a bar standing at [defenderHp], is the knockout: a hit for at least that
 * much by the side that wins the fight ([winner]). A bar emptied by a blow that a heal later in the turn undoes (no
 * winner), or by the loser's blow in a double K.O., is not.
 */
internal fun koBlow(event: FightEvent, defenderHp: Int, attacker: Side, winner: Side?): Boolean =
    winner == attacker && event is FightEvent.Hit && event.damage >= defenderHp

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
 * Whether either fighter in [s] is nearly done: down to strictly under [FightTuning.NEARLY_DONE_SHARE] of its max HP.
 * The first turn that leaves one there turns the fight intense (`Screen.Fight.intense`), and the music hardens.
 */
internal fun nearlyDone(s: FightState): Boolean {
    val share = FightTuning.NEARLY_DONE_SHARE
    return s.player.hp < s.player.maxHp * share || s.cpu.hp < s.cpu.maxHp * share
}
