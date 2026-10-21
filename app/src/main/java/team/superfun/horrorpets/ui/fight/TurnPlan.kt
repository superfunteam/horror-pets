package team.superfun.horrorpets.ui.fight

import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AnimSpec
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Fighter
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
 * `block`, and a KO is the `ko` boom with the announcer saying `ko`. Interrupts and a filled meter are silent.
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
        is FightEvent.Ko -> FightSounds(listOf("ko"), listOf("ko"))
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

/** The attacker hops back this long after the impact, cutting the end of its swing's recovery short. */
internal const val AFTER_IMPACT_MS = 420L

/**
 * What an attacker plays for the swing that delivers [event]: its DANCE for a special it dances
 * ([dancesSpecial]: its special is `danced` and its sprites ship a dance, as Leatherface's Saw Dance does),
 * otherwise ATTACK.
 */
internal fun swingAnim(event: FightEvent, dancesSpecial: Boolean): Anim =
    if (dancesSpecial && event is FightEvent.Hit && event.move == Move.SPECIAL) Anim.DANCE else Anim.ATTACK

/**
 * When a [swing] played from [spec] lands, in ms from its first frame. An ATTACK lands on its snap frame
 * (see [impactMs]). A DANCE has no snap: it lands at the start of the frame on screen a third of the way
 * through, once the wind-up is done (Leatherface's saw is overhead).
 */
internal fun swingImpactMs(swing: Anim, spec: AnimSpec): Long {
    if (swing != Anim.DANCE) return impactMs(spec)
    val third = spec.durationsMs.sumOf { it.coerceAtLeast(0).toLong() } / 3
    var start = 0L
    for (d in spec.durationsMs) {
        val end = start + d.coerceAtLeast(0)
        if (end > third) return start
        start = end
    }
    return start
}

/**
 * How long the attacker keeps swinging after the [impact] before it hops back: [AFTER_IMPACT_MS] for an
 * ATTACK, whose last frames only ease back to standing, and the rest of a DANCE (never less), which is
 * danced to its end.
 */
internal fun swingHoldMs(swing: Anim, spec: AnimSpec, impact: Long): Long {
    if (swing != Anim.DANCE) return AFTER_IMPACT_MS
    val rest = spec.durationsMs.sumOf { it.coerceAtLeast(0).toLong() } - impact
    return maxOf(AFTER_IMPACT_MS, rest)
}

/** Where a side's HP bar and special meter stand, in hit points and meter points. */
internal data class HudStart(val hp: Int, val meter: Int)

/**
 * A side's HP and meter before the turn whose [events] left it as [f], as near as the events tell: the
 * damage it took is given back (never above max HP), the special it used was a full meter, and each hit it
 * landed (counters included) had not yet filled its cell. Only for a fight screen built in the middle of a
 * turn (the Activity was recreated while it played); otherwise the bars simply follow the turn.
 */
internal fun hudBeforeTurn(f: Fighter, side: Side, events: List<FightEvent>): HudStart {
    var taken = 0
    var landed = 0
    var usedSpecial = false
    for (e in events) {
        if (e !is FightEvent.Hit) continue
        when {
            e.attacker != side -> taken += e.damage
            e.move == Move.SPECIAL -> usedSpecial = true
            else -> landed++
        }
    }
    val meter = if (usedSpecial) FightTuning.METER_MAX else (f.meter - FightTuning.METER_PER_HIT * landed).coerceAtLeast(0)
    return HudStart(hp = (f.hp + taken).coerceAtMost(f.maxHp), meter = meter)
}
