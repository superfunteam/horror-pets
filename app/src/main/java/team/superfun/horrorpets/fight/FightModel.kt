package team.superfun.horrorpets.fight

import team.superfun.horrorpets.model.PetId

enum class Side { PLAYER, CPU }

/** The moves. POTION and RUN are the player's only: a potion heals (see [FightState.playerPotions]), RUN forfeits. */
enum class Move { STRIKE, HEAVY, BLOCK, SPECIAL, POTION, RUN }

/** The other side of the fight. */
fun Side.other(): Side = if (this == Side.PLAYER) Side.CPU else Side.PLAYER

/**
 * One combatant. [strength] and [mood] are snapshots taken when the fight is built, [meter] is the
 * special meter (0..[FightTuning.METER_MAX]) and [hp] runs from [maxHp] down to 0.
 */
data class Fighter(
    val id: PetId,
    val level: Int,
    val maxHp: Int,
    val hp: Int,
    val strength: Double,
    val mood: Int,
    val meter: Int,
)

/**
 * The fight so far. [turn] is the number of the turn about to be played (1 for a fresh fight),
 * [playerHistory] every move the player has chosen (the CPU reads it), [winner] is null while the
 * fight is on, and [playerRan] is true when the player forfeited. [playerPotions] is how many potions the
 * player has left ([FightTuning.POTIONS_PER_FIGHT] to start).
 */
data class FightState(
    val player: Fighter,
    val cpu: Fighter,
    val turn: Int = 1,
    val playerHistory: List<Move> = emptyList(),
    val winner: Side? = null,
    val playerRan: Boolean = false,
    val playerPotions: Int = FightTuning.POTIONS_PER_FIGHT,
)

/** What happened during a turn, in the order the fight screen should play it. */
sealed interface FightEvent {
    /**
     * [attacker] landed [damage]. A counter (a BLOCK that met a STRIKE) is reported as
     * `Hit(blocker, Move.STRIKE, ..., counter = true)`; otherwise [move] is the move that was played.
     */
    data class Hit(
        val attacker: Side,
        val move: Move,
        val damage: Int,
        val crit: Boolean,
        val counter: Boolean = false,
    ) : FightEvent

    /** [attacker]'s STRIKE or HEAVY whiffed. */
    data class Miss(val attacker: Side, val move: Move) : FightEvent

    /** [blocker] blocked a STRIKE (and counters, see [Hit]). */
    data class Blocked(val blocker: Side) : FightEvent

    /** [side]'s HEAVY was cut off by a STRIKE and never happened. */
    data class Interrupted(val side: Side) : FightEvent

    /** [side]'s special meter just reached [FightTuning.METER_MAX]. */
    data class SpecialReady(val side: Side) : FightEvent

    /** [side] drank a potion and got [amount] hit points back (what it actually restored, never past its max). */
    data class Heal(val side: Side, val amount: Int) : FightEvent

    /** [loser] is out of hit points; the fight is over. */
    data class Ko(val loser: Side) : FightEvent
}

data class TurnResult(val state: FightState, val events: List<FightEvent>)
