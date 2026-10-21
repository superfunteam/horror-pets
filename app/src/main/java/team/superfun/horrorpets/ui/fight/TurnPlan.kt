package team.superfun.horrorpets.ui.fight

import team.superfun.horrorpets.assets.AnimSpec
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.other

/** One step of a fight turn's playback, in the order the fight screen plays them. */
internal sealed interface Beat {
    /**
     * [attacker] steps in and swings; at the impact [event] lands on the other side. [event] is a
     * [FightEvent.Hit], a [FightEvent.Miss], or a [FightEvent.Blocked]: an absorbed STRIKE is still a
     * swing, into the blocker's shield (the blocker's counter is the next beat).
     */
    data class Lunge(val attacker: Side, val event: FightEvent) : Beat

    /** [side]'s HEAVY was cut off by the strike just played. */
    data class Interrupt(val side: Side) : Beat

    /** [side]'s special meter just filled. */
    data class Ready(val side: Side) : Beat
}

/**
 * The beats of one turn, from its [events] in engine order.
 *
 * The KO is not a beat: the finale (the loser falls, the winner celebrates) is keyed off [winner], which
 * is also right for a double KO, where the winner is left standing at 0 hp and has no event of its own.
 * A meter that fills on the finishing turn is not announced either: there is no next turn to use it in.
 */
internal fun beatsOf(events: List<FightEvent>, winner: Side?): List<Beat> = events.mapNotNull { event ->
    when (event) {
        is FightEvent.Hit -> Beat.Lunge(event.attacker, event)
        is FightEvent.Miss -> Beat.Lunge(event.attacker, event)
        is FightEvent.Blocked -> Beat.Lunge(event.blocker.other(), event)
        is FightEvent.Interrupted -> Beat.Interrupt(event.side)
        is FightEvent.SpecialReady -> if (winner == null) Beat.Ready(event.side) else null
        is FightEvent.Ko -> null
    }
}

/** Sound names to hand the [team.superfun.horrorpets.audio.SoundSink]: effects, then announcer lines. */
internal data class FightSounds(val sfx: List<String>, val voice: List<String>)

private val Silent = FightSounds(emptyList(), emptyList())

/**
 * The sounds of one turn's events, asked for one event at a time as the fight screen shows it, so each
 * lands with its animation. A hit is `hit`, `heavy_hit` or `special_<attacker's pet>`; a crit is `crit`
 * instead, and the announcer says `critical` with the turn's first crit only; a miss is `miss`, a block
 * `block`, and a KO has the announcer say `ko`. Interrupts and a filled meter are silent.
 *
 * Make one per turn: it remembers whether the announcer has already called a crit. [fight] is the state
 * after the turn, used to tell which pet a side is.
 */
internal class TurnSounds(private val fight: FightState) {
    private var criticalCalled = false

    fun of(event: FightEvent): FightSounds = when (event) {
        is FightEvent.Hit -> when {
            event.crit -> {
                val voice = if (criticalCalled) emptyList() else listOf("critical")
                criticalCalled = true
                FightSounds(listOf("crit"), voice)
            }
            event.move == Move.HEAVY -> FightSounds(listOf("heavy_hit"), emptyList())
            event.move == Move.SPECIAL -> {
                val attacker = if (event.attacker == Side.PLAYER) fight.player else fight.cpu
                FightSounds(listOf("special_${attacker.id.name.lowercase()}"), emptyList())
            }
            else -> FightSounds(listOf("hit"), emptyList())
        }

        is FightEvent.Miss -> FightSounds(listOf("miss"), emptyList())
        is FightEvent.Blocked -> FightSounds(listOf("block"), emptyList())
        is FightEvent.Ko -> FightSounds(emptyList(), listOf("ko"))
        is FightEvent.Interrupted, is FightEvent.SpecialReady -> Silent
    }
}

/**
 * When an ATTACK swing connects, in ms from its start: the start of its snap frame, the shortest one
 * (the blade or claw at full stretch). 0 for a strip with no timeline.
 */
internal fun impactMs(attack: AnimSpec): Long {
    val d = attack.durationsMs
    if (d.isEmpty()) return 0L
    var snap = 0
    for (i in d.indices) if (d[i] < d[snap]) snap = i
    return d.take(snap).sumOf { it.coerceAtLeast(0).toLong() }
}
