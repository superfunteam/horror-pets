package team.superfun.horrorpets.pvp

import kotlin.random.Random
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.TurnResult
import team.superfun.horrorpets.fight.forfeit
import team.superfun.horrorpets.fight.mirroredEvents
import team.superfun.horrorpets.fight.resolveTurn
import team.superfun.horrorpets.fight.startPvp

/**
 * A PvP room both players are in, from this player's side ([role]): the relay's [room] and this player's
 * [token] there, the room's shared [seed], and the two fighters as they came in.
 *
 * Both apps keep the same canonical fight, the host as PLAYER and the guest as CPU, and play each turn of it
 * the same way ([resolvePvpTurn]); each shows it from its own side ([view]).
 */
data class PvpMatch(
    val role: Role,
    val room: String,
    val token: String,
    val seed: Int,
    val host: Fighter,
    val guest: Fighter,
) {
    /** This player's side of the canonical fight. */
    val side: Side get() = if (role == Role.HOST) Side.PLAYER else Side.CPU

    /** The other player's role. */
    val them: Role get() = if (role == Role.HOST) Role.GUEST else Role.HOST

    /** The fight both apps start from. */
    fun start(): FightState = startPvp(host, guest)

    /** The canonical fight as this player sees it: as is for the host, mirrored for the guest. */
    fun view(canonical: FightState): FightState = if (role == Role.HOST) canonical else canonical.mirrored()

    /** A canonical turn's events as this player sees them. */
    fun viewEvents(events: List<FightEvent>): List<FightEvent> = if (role == Role.HOST) events else mirroredEvents(events)
}

/** The dice for turn [turn] of the room seeded [seed]: the same on both apps, and fresh each turn. */
fun pvpRandom(seed: Int, turn: Int): Random = Random((seed.toLong() shl 20) xor turn.toLong())

/**
 * [move] as [side] may play it in [s]: a SPECIAL without a full meter or a POTION with none left becomes a
 * BLOCK (which does nothing unless it meets a STRIKE). A well-behaved app never sends either, but both apps
 * must agree on what a bad one does, and must never fail on it.
 */
fun legalMove(s: FightState, side: Side, move: Move): Move {
    val me = if (side == Side.PLAYER) s.player else s.cpu
    val potions = if (side == Side.PLAYER) s.playerPotions else s.cpuPotions
    return when {
        move == Move.SPECIAL && me.meter < FightTuning.METER_MAX -> Move.BLOCK
        move == Move.POTION && potions <= 0 -> Move.BLOCK
        else -> move
    }
}

/**
 * Plays one turn of a PvP fight, exactly as the other app will: the host's and the guest's moves made legal
 * ([legalMove]), then a RUN is a forfeit (the host's first if both ran), otherwise the turn resolves with
 * [pvpRandom]'s dice for it.
 */
fun resolvePvpTurn(canonical: FightState, hostMove: Move, guestMove: Move, seed: Int): TurnResult {
    val host = legalMove(canonical, Side.PLAYER, hostMove)
    val guest = legalMove(canonical, Side.CPU, guestMove)
    return when {
        host == Move.RUN -> TurnResult(forfeit(canonical, Side.PLAYER), emptyList())
        guest == Move.RUN -> TurnResult(forfeit(canonical, Side.CPU), emptyList())
        else -> resolveTurn(canonical, host, guest, pvpRandom(seed, canonical.turn))
    }
}
