package team.superfun.horrorpets.pvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.fighter
import team.superfun.horrorpets.fight.mirroredEvents
import team.superfun.horrorpets.fight.startPvp
import team.superfun.horrorpets.model.PetId

/** Lockstep: both apps play each PvP turn the same way from the same moves and seed. */
class PvpMatchTest {
    private val host = fighter(id = PetId.JASON, hp = 100)
    private val guest = fighter(id = PetId.FREDDY, hp = 90)

    @Test
    fun bothAppsPlayATurnTheSameWay() {
        var s = startPvp(host, guest)
        repeat(4) {
            val a = resolvePvpTurn(s, Move.HEAVY, Move.STRIKE, seed = 77)
            val b = resolvePvpTurn(s, Move.HEAVY, Move.STRIKE, seed = 77)
            assertEquals(a, b)
            s = a.state
        }
    }

    @Test
    fun eachTurnRollsFreshDice() {
        val turns = (1..20).map { pvpRandom(77, it).nextDouble() }
        assertEquals(20, turns.toSet().size)
        assertNotEquals(pvpRandom(77, 1).nextDouble(), pvpRandom(78, 1).nextDouble(), 0.0)
    }

    @Test
    fun aRunIsAForfeitTheHostsFirst() {
        val s = startPvp(host, guest)
        val hostRan = resolvePvpTurn(s, Move.RUN, Move.STRIKE, seed = 1)
        assertEquals(Side.CPU, hostRan.state.winner)
        assertTrue(hostRan.state.playerRan)
        assertTrue(hostRan.events.isEmpty())

        val guestRan = resolvePvpTurn(s, Move.STRIKE, Move.RUN, seed = 1)
        assertEquals(Side.PLAYER, guestRan.state.winner)
        assertTrue(guestRan.state.cpuRan)

        assertEquals(Side.CPU, resolvePvpTurn(s, Move.RUN, Move.RUN, seed = 1).state.winner)
    }

    @Test
    fun aMoveTheSideCannotMakeIsABlock() {
        val s = startPvp(host, guest).copy(cpuPotions = 0)
        assertEquals(Move.BLOCK, legalMove(s, Side.PLAYER, Move.SPECIAL))
        assertEquals(Move.BLOCK, legalMove(s, Side.CPU, Move.POTION))
        assertEquals(Move.POTION, legalMove(s, Side.PLAYER, Move.POTION))
        val full = s.copy(cpu = s.cpu.copy(meter = FightTuning.METER_MAX))
        assertEquals(Move.SPECIAL, legalMove(full, Side.CPU, Move.SPECIAL))
        // And the turn never fails on one: an illegal special plays as a block.
        val r = resolvePvpTurn(s, Move.SPECIAL, Move.STRIKE, seed = 5)
        assertTrue(r.events.any { it is team.superfun.horrorpets.fight.FightEvent.Blocked })
    }

    @Test
    fun eachPlayerSeesTheFightFromItsOwnSide() {
        val hostSide = PvpMatch(Role.HOST, "r", "h", 9, host, guest)
        val guestSide = hostSide.copy(role = Role.GUEST, token = "g")
        val turn = resolvePvpTurn(hostSide.start(), Move.STRIKE, Move.HEAVY, seed = 9)
        assertEquals(PetId.JASON, hostSide.view(turn.state).player.id)
        assertEquals(PetId.FREDDY, guestSide.view(turn.state).player.id)
        assertEquals(turn.events, hostSide.viewEvents(turn.events))
        assertEquals(mirroredEvents(turn.events), guestSide.viewEvents(turn.events))
        assertEquals(Side.PLAYER, hostSide.side)
        assertEquals(Side.CPU, guestSide.side)
        assertEquals(Role.HOST, guestSide.them)
    }
}
