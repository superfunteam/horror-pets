package team.superfun.horrorpets.fight

import team.superfun.horrorpets.model.Attack
import team.superfun.horrorpets.model.Kit
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.SpecialEffect

enum class Side { PLAYER, CPU }

/**
 * The moves. A potion heals (see [FightState.playerPotions]); against the CPU only the player has any. RUN
 * forfeits: the player's through [resolveTurn], either side's in a PvP fight through [forfeit].
 */
enum class Move { STRIKE, HEAVY, BLOCK, SPECIAL, POTION, RUN }

/** The other side of the fight. */
fun Side.other(): Side = if (this == Side.PLAYER) Side.CPU else Side.PLAYER

/**
 * One combatant. [strength] and [mood] are snapshots taken when the fight is built, [meter] is the
 * special meter (0..[FightTuning.METER_MAX]) and [hp] runs from [maxHp] down to 0. [combo] is how many
 * turns in a row it has landed a hit without being hit, [bleed] how many more turns it bleeds (and [bleedShare]
 * of its max HP each), and [dazed] whether its next STRIKE, HEAVY or BLOCK fails (see [resolveTurn]). [kit] is the
 * attack it fights with in each slot (the starting ones unless it has learned and equipped others).
 */
data class Fighter(
    val id: PetId,
    val level: Int,
    val maxHp: Int,
    val hp: Int,
    val strength: Double,
    val mood: Int,
    val meter: Int,
    val combo: Int = 0,
    val bleed: Int = 0,
    val dazed: Boolean = false,
    val bleedShare: Double = FightTuning.BLEED_SHARE,
    val kit: Kit = Kit.base(id),
)

/** The attack [this] kit plays for [move]: a STRIKE is its light, a HEAVY its heavy, a SPECIAL its special. */
fun Kit.attackFor(move: Move): Attack? = when (move) {
    Move.STRIKE -> light
    Move.HEAVY -> heavy
    Move.SPECIAL -> special
    Move.BLOCK, Move.POTION, Move.RUN -> null
}

/**
 * How the player has fought so far, for the results screen and the battle rank: the damage it dealt (hits and
 * the bleeds it caused) and took (hits and bleeds), its landed [hits], whiffed [misses] and [stopped] attacks (a
 * STRIKE blocked, a HEAVY interrupted), how many of the hits
 * were [crits] and [specials], the hit points it [healed] (potions and drains), and its [bestCombo]: the longest run
 * of hits it landed turn after turn (each one part of its combo, as the hit's COMBO ×N said).
 */
data class FightStats(
    val damageDealt: Int = 0,
    val damageTaken: Int = 0,
    val hits: Int = 0,
    val misses: Int = 0,
    val stopped: Int = 0,
    val crits: Int = 0,
    val specials: Int = 0,
    val healed: Int = 0,
    val bestCombo: Int = 0,
)

/**
 * The fight so far. [turn] is the number of the turn about to be played (1 for a fresh fight),
 * [playerHistory] every move the player has chosen (the CPU reads it), [winner] is null while the
 * fight is on, and [playerRan] is true when the player forfeited ([cpuRan] when the other side did, which only a
 * PvP opponent can). [playerPotions] is how many potions the player has left ([FightTuning.POTIONS_PER_FIGHT]
 * to start) and [cpuPotions] the other side's (none for the CPU; a PvP opponent's own). [stats] tallies how the
 * player has fought, and [cpuStats] the other side.
 */
data class FightState(
    val player: Fighter,
    val cpu: Fighter,
    val turn: Int = 1,
    val playerHistory: List<Move> = emptyList(),
    val winner: Side? = null,
    val playerRan: Boolean = false,
    val playerPotions: Int = FightTuning.POTIONS_PER_FIGHT,
    val stats: FightStats = FightStats(),
    val cpuRan: Boolean = false,
    val cpuPotions: Int = 0,
    val cpuStats: FightStats = FightStats(),
) {
    /** How many turns have been played. */
    val turnsPlayed: Int get() = turn - 1

    /** How many potions the player has drunk. */
    val potionsUsed: Int get() = FightTuning.POTIONS_PER_FIGHT - playerPotions

    /**
     * The same fight from the other side: the fighters, their potions, forfeits and stats swapped, the winner
     * flipped. A PvP guest sees the host's fight this way, with itself on the left as the player. No move history
     * comes along (it is the CPU's).
     */
    fun mirrored(): FightState = copy(
        player = cpu,
        cpu = player,
        playerHistory = emptyList(),
        winner = winner?.other(),
        playerRan = cpuRan,
        cpuRan = playerRan,
        playerPotions = cpuPotions,
        cpuPotions = playerPotions,
        stats = cpuStats,
        cpuStats = stats,
    )
}

/** What happened during a turn, in the order the fight screen should play it. */
sealed interface FightEvent {
    /**
     * [attacker] landed [damage]. A counter (a BLOCK that met a STRIKE) is reported as
     * `Hit(blocker, Move.STRIKE, ..., counter = true)`; otherwise [move] is the move that was played.
     * [combo] is the attacker's [Fighter.combo] coming into the turn: from 1 on this hit is part of a combo
     * (its `combo + 1`th clean hit in a row) and hit harder for it. [effect] is what its attack left behind (a
     * bleed, a drain or a daze; null for none, and always for a counter), and [shattered] how much of the target's
     * special meter it knocked off.
     */
    data class Hit(
        val attacker: Side,
        val move: Move,
        val damage: Int,
        val crit: Boolean,
        val counter: Boolean = false,
        val combo: Int = 0,
        val effect: SpecialEffect? = null,
        val shattered: Int = 0,
    ) : FightEvent

    /** [attacker]'s STRIKE or HEAVY whiffed: by the dice, or because [dazed] it stumbled. */
    data class Miss(val attacker: Side, val move: Move, val dazed: Boolean = false) : FightEvent

    /** [blocker] blocked a STRIKE (and counters, see [Hit]). */
    data class Blocked(val blocker: Side) : FightEvent

    /** [side]'s HEAVY was cut off by a STRIKE and never happened. */
    data class Interrupted(val side: Side) : FightEvent

    /** [side]'s special meter just reached [FightTuning.METER_MAX]. */
    data class SpecialReady(val side: Side) : FightEvent

    /** [side] drank a potion and got [amount] hit points back (what it actually restored, never past its max). */
    data class Heal(val side: Side, val amount: Int) : FightEvent

    /** [side]'s draining special gave it [amount] hit points back (never past its max, never 0). */
    data class Drain(val side: Side, val amount: Int) : FightEvent

    /** [side] bled [damage] at the end of the turn. */
    data class Bleed(val side: Side, val damage: Int) : FightEvent

    /** [loser] is out of hit points; the fight is over. */
    data class Ko(val loser: Side) : FightEvent
}

data class TurnResult(val state: FightState, val events: List<FightEvent>)

/** The same event seen from the other side (see [FightState.mirrored]). */
fun FightEvent.mirrored(): FightEvent = when (this) {
    is FightEvent.Hit -> copy(attacker = attacker.other())
    is FightEvent.Miss -> copy(attacker = attacker.other())
    is FightEvent.Blocked -> copy(blocker = blocker.other())
    is FightEvent.Interrupted -> copy(side = side.other())
    is FightEvent.SpecialReady -> copy(side = side.other())
    is FightEvent.Heal -> copy(side = side.other())
    is FightEvent.Drain -> copy(side = side.other())
    is FightEvent.Bleed -> copy(side = side.other())
    is FightEvent.Ko -> copy(loser = loser.other())
}

/**
 * A turn's [events] seen from the other side, in the order [resolveTurn] would have given them had that side
 * been the player: each event [mirrored], then the new player's group (everything its move caused), the
 * other's, the bleeds (the player's first) and the KO.
 */
fun mirroredEvents(events: List<FightEvent>): List<FightEvent> {
    val flipped = events.map { it.mirrored() }
    fun rank(e: FightEvent): Int = when (e) {
        is FightEvent.Bleed -> if (e.side == Side.PLAYER) 2 else 3
        is FightEvent.Ko -> 4
        else -> if (causedBy(e) == Side.PLAYER) 0 else 1
    }
    return flipped.sortedBy(::rank) // stable: each group keeps its own order
}

/** Whose move [e] is part of: an interrupt is the striker's, everything else its own side's. Not for bleeds or KOs. */
private fun causedBy(e: FightEvent): Side = when (e) {
    is FightEvent.Hit -> e.attacker
    is FightEvent.Miss -> e.attacker
    is FightEvent.Blocked -> e.blocker
    is FightEvent.Interrupted -> e.side.other()
    is FightEvent.SpecialReady -> e.side
    is FightEvent.Heal -> e.side
    is FightEvent.Drain -> e.side
    is FightEvent.Bleed -> e.side
    is FightEvent.Ko -> e.loser
}
