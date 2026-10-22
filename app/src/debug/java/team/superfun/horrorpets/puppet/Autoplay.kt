package team.superfun.horrorpets.puppet

import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Move

/** The share of its max HP under which autoplay drinks a potion. */
const val AUTOPLAY_POTION_BELOW = 0.3

/** A move autoplay picks, and [why], for the log. */
data class AutoPick(val move: Move, val why: String)

/**
 * The move autoplay picks for the player in [fight] (from the player's side, as the fight screen shows it), the first
 * of these that applies:
 * 1. SPECIAL when the meter is full;
 * 2. POTION when HP is under [AUTOPLAY_POTION_BELOW] of the max and a potion is left;
 * 3. the answer to the CPU's tell (`fight.cpuPlan`, which may be a feint): BLOCK its STRIKE, STRIKE its HEAVY,
 *    HEAVY its BLOCK. A SPECIAL has no answer, and a PvP opponent shows no tell;
 * 4. STRIKE on odd turns and HEAVY on even ones, so the footage shows both.
 *
 * Always a move the player may pick right now (see [pickRefusal]) while the fight is on.
 */
fun autoplayPick(fight: FightState): AutoPick {
    val me = fight.player
    if (me.meter >= FightTuning.METER_MAX) return AutoPick(Move.SPECIAL, "meter full")
    if (fight.playerPotions > 0 && me.hp < me.maxHp * AUTOPLAY_POTION_BELOW) {
        return AutoPick(Move.POTION, "HP ${me.hp}/${me.maxHp}")
    }
    val tell = fight.cpuPlan?.tell
    val answer = when (tell) {
        Move.STRIKE -> Move.BLOCK
        Move.HEAVY -> Move.STRIKE
        Move.BLOCK -> Move.HEAVY
        else -> null
    }
    if (answer != null) return AutoPick(answer, "answers the ${tell?.name} tell")
    return AutoPick(if (fight.turn % 2 == 1) Move.STRIKE else Move.HEAVY, "turn ${fight.turn}")
}

/**
 * Why the player cannot pick [move] in [fight] right now, or null when it can: the view model's own rules (it
 * ignores any of these), said so a refused `move` command explains itself.
 */
fun pickRefusal(fight: FightState, move: Move): String? = when {
    fight.winner != null -> "the fight is over"
    move == Move.SPECIAL && fight.player.meter < FightTuning.METER_MAX ->
        "SPECIAL needs a full meter (${fight.player.meter}/${FightTuning.METER_MAX}): meter full fills it"
    move == Move.POTION && fight.playerPotions == 0 -> "no potions left"
    move == Move.POTION && fight.player.hp >= fight.player.maxHp -> "already at full health"
    else -> null
}
