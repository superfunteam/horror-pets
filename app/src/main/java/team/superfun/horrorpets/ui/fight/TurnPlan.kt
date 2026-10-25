package team.superfun.horrorpets.ui.fight

import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AnimSpec
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.attackFor
import team.superfun.horrorpets.fight.other
import team.superfun.horrorpets.model.Attack
import team.superfun.horrorpets.model.Kit
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.SpecialStyle
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.ui.common.mirrorToFace

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

    /** A potion is tossed onto [side], who drinks it and gets [amount] hit points back. */
    data class Drink(val side: Side, val amount: Int) : Beat

    /** [side]'s draining special just gave it [amount] hit points back (it rides on the special's swing). */
    data class Drain(val side: Side, val amount: Int) : Beat

    /** [side] bleeds [damage] at the end of the turn, once every swing has played. */
    data class Bleed(val side: Side, val damage: Int) : Beat
}

/** Whether [this] beat rides on the swing before it (lands with or right after its impact) rather than playing on its own. */
internal val Beat.ridesOnLunge: Boolean get() = this is Beat.Interrupt || this is Beat.Ready || this is Beat.Drain

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
        is FightEvent.Heal -> Beat.Drink(event.side, event.amount)
        is FightEvent.Drain -> Beat.Drain(event.side, event.amount)
        is FightEvent.Bleed -> Beat.Bleed(event.side, event.damage)
        is FightEvent.Ko -> null
    }
}

/**
 * Sound names to hand the [team.superfun.horrorpets.audio.SoundSink]: effects, then announcer lines, then a killer's
 * own line ([quip], said only if the announcer is quiet then).
 */
internal data class FightSounds(val sfx: List<String>, val voice: List<String>, val quip: String? = null)

private val Silent = FightSounds(emptyList(), emptyList())

/**
 * The sounds of one turn's events, asked for one event at a time as the fight screen shows it, so each
 * lands with its animation. A hit sounds like its attacker's weapon: `hit_<attacker's pet>` for a strike (a
 * counter included) and `heavy_<attacker's pet>` for a heavy; a special is `special_<attacker's pet>`, and a
 * crit is `crit` instead of any of them. A miss is `miss`, a block `block`, a potion or a drain `potion`, a
 * bleed the plain `hit` (no one swung), and a KO is the `ko` boom with the announcer saying `ko`.
 * Interrupts and a filled meter are silent. The announcer's one reaction of the turn, if [callouts] picks
 * one from [events], is said with the event it reacts to, and so is a killer's one line of the turn, if [quips]
 * picks one (never in a turn the announcer has a call in, nor the one that ends the fight).
 *
 * Make one per turn, from the turn's [events]. [fight] is the state after the turn, used to tell which pet
 * a side is and whether the turn ended the fight.
 */
internal class TurnSounds(
    private val fight: FightState,
    events: List<FightEvent> = emptyList(),
    callouts: Callouts? = null,
    quips: Quips? = null,
) {
    private var callout = callouts?.pick(events, finishing = fight.winner != null)

    // Picked after the callout: a turn the announcer has a call in is his alone.
    private var quip = quips?.pick(fight, events, callout)

    fun of(event: FightEvent): FightSounds {
        val sfx = when (event) {
            is FightEvent.Hit -> {
                val pet = (if (event.attacker == Side.PLAYER) fight.player else fight.cpu).id.name.lowercase()
                when {
                    event.crit -> "crit"
                    event.move == Move.HEAVY -> "heavy_$pet"
                    event.move == Move.SPECIAL -> "special_$pet"
                    else -> "hit_$pet"
                }
            }
            is FightEvent.Miss -> "miss"
            is FightEvent.Blocked -> "block"
            is FightEvent.Heal, is FightEvent.Drain -> "potion"
            is FightEvent.Bleed -> "hit"
            is FightEvent.Ko -> return FightSounds(listOf("ko"), listOf("ko"))
            is FightEvent.Interrupted, is FightEvent.SpecialReady -> return Silent
        }
        val call = callout?.takeIf { it.event == event }
        if (call != null) callout = null
        val line = quip?.takeIf { it.first == event }
        if (line != null) quip = null
        return FightSounds(listOf(sfx), listOfNotNull(call?.line), line?.second)
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
 * Whether a pet performs its special with its DANCE: its special [style] is danced (Saw Dance) and its sprites
 * ship a dance ([hasDance]). A danced special on sprites without one swings an ATTACK like any other.
 */
internal fun dancesSpecial(style: SpecialStyle, hasDance: Boolean): Boolean = style.danced && hasDance

/**
 * What an attacker plays for the swing that delivers [event]: the attack's [own] animation if it has one (see
 * [ownSwingAnim]: Jaws's Barrel Breach plays his barrels), else its DANCE for a special it dances ([dancesSpecial]:
 * its special is `danced` and its sprites ship a dance, as Leatherface's Saw Dance does), otherwise ATTACK.
 */
internal fun swingAnim(event: FightEvent, dancesSpecial: Boolean, own: Anim? = null): Anim = when {
    own != null -> own
    dancesSpecial && event is FightEvent.Hit && event.move == Move.SPECIAL -> Anim.DANCE
    else -> Anim.ATTACK
}

/**
 * The animation the attack behind [event]'s swing names for itself ([Attack.anim][team.superfun.horrorpets.model.Attack.anim]),
 * for an attacker with [kit] whose sprites ship what [ships] says: null for the usual swing. That is the attack
 * of the move it hit or missed with, or the STRIKE that hit a shield; a counter is a plain swing back, and so is an
 * attack that names no animation, or one its sprites don't ship (a manifest without it never asks for a missing strip).
 */
internal fun ownSwingAnim(event: FightEvent, kit: Kit, ships: (Anim) -> Boolean): Anim? {
    val attack = when (event) {
        is FightEvent.Hit -> if (event.counter) null else kit.attackFor(event.move)
        is FightEvent.Miss -> kit.attackFor(event.move)
        is FightEvent.Blocked -> kit.attackFor(Move.STRIKE)
        else -> null
    }
    return attack?.anim?.let(Anim::fromKey)?.takeIf(ships)
}

/**
 * What [attack]'s killer swings to land it, as a fight picks it for a hit with it ([swingAnim] and [ownSwingAnim]),
 * for sprites that ship what [ships] says: its own animation (Jaws's barrels and thrash), its DANCE for a danced
 * special, otherwise ATTACK. The MOVES and learn cards' preview plays it.
 */
internal fun attackSwing(attack: Attack, ships: (Anim) -> Boolean): Anim {
    val base = Kit.base(attack.pet)
    val (move, kit) = when (attack.slot) {
        Slot.LIGHT -> Move.STRIKE to base.copy(light = attack)
        Slot.HEAVY -> Move.HEAVY to base.copy(heavy = attack)
        Slot.SPECIAL -> Move.SPECIAL to base.copy(special = attack)
    }
    val hit = FightEvent.Hit(Side.PLAYER, move, damage = 0, crit = false)
    return swingAnim(hit, dancesSpecial(attack.pet.spec.special, ships(Anim.DANCE)), ownSwingAnim(hit, kit, ships))
}

/**
 * When a [swing] played from [spec] lands, in ms from its first frame. An ATTACK, and a move's own animation (Jaws's
 * thrash and barrels), lands on its snap frame (see [impactMs]). A DANCE has no snap: it lands at the start of the
 * frame on screen a third of the way through, once the wind-up is done (Leatherface's saw is overhead).
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
 * ATTACK, whose last frames only ease back to standing, and the rest of anything else (never less): a DANCE is
 * danced to its end, and a move's own animation plays out (Jaws's barrels sink again rather than vanish half-sunk).
 */
internal fun swingHoldMs(swing: Anim, spec: AnimSpec, impact: Long): Long {
    if (swing == Anim.ATTACK) return AFTER_IMPACT_MS
    val rest = spec.durationsMs.sumOf { it.coerceAtLeast(0).toLong() } - impact
    return maxOf(AFTER_IMPACT_MS, rest)
}

/**
 * Whether [pet] is drawn mirrored on [side] while it shows [anim], so that it faces the other fighter: see
 * [mirrorToFace] (Jaws, whose art faces left, turns round on the player's side). A run is never mirrored: it already
 * faces the way it goes (in at the other fighter, or off the screen).
 */
internal fun mirroredInFight(pet: PetId, side: Side, anim: Anim): Boolean {
    if (anim == Anim.RUN_LEFT || anim == Anim.RUN_RIGHT) return false
    return mirrorToFace(pet, onLeft = side == Side.PLAYER)
}

/** Where a side's HP bar and special meter stand, in hit points and meter points. */
internal data class HudStart(val hp: Int, val meter: Int)

/**
 * A side's HP and meter before the turn whose [events] left it as [f], as near as the events tell (what its
 * attacks fill and what was shattered off it included): the
 * damage it took (and bled) is given back and a potion's or a drain's heal taken back out (always within 0..max HP), the special it
 * used was a full meter, and each hit it landed (counters included) had not yet filled its cell. Only for a fight screen built in the middle of a
 * turn (the Activity was recreated while it played); otherwise the bars simply follow the turn.
 */
internal fun hudBeforeTurn(f: Fighter, side: Side, events: List<FightEvent>): HudStart {
    var taken = 0
    var healed = 0
    var gained = 0
    var shattered = 0
    var usedSpecial = false
    for (e in events) {
        if (e is FightEvent.Heal && e.side == side) healed += e.amount
        if (e is FightEvent.Drain && e.side == side) healed += e.amount
        if (e is FightEvent.Bleed && e.side == side) taken += e.damage
        if (e !is FightEvent.Hit) continue
        when {
            e.attacker != side -> {
                taken += e.damage
                shattered += e.shattered
            }
            e.move == Move.SPECIAL -> usedSpecial = true
            // A counter fills the usual amount; any other hit what its attack fills.
            e.counter -> gained += FightTuning.METER_PER_HIT
            else -> gained += f.kit.attackFor(e.move)?.meterGain ?: FightTuning.METER_PER_HIT
        }
    }
    val meter = if (usedSpecial) FightTuning.METER_MAX else (f.meter + shattered - gained).coerceIn(0, FightTuning.METER_MAX)
    return HudStart(hp = (f.hp + taken - healed).coerceIn(0, f.maxHp), meter = meter)
}
