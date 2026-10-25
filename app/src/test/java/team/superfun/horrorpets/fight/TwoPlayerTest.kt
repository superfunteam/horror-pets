package team.superfun.horrorpets.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightEvent.Bleed
import team.superfun.horrorpets.fight.FightEvent.Heal
import team.superfun.horrorpets.fight.FightEvent.Hit
import team.superfun.horrorpets.fight.FightEvent.Interrupted
import team.superfun.horrorpets.fight.FightEvent.Ko
import team.superfun.horrorpets.game.pantry.MealPerk
import team.superfun.horrorpets.game.pantry.MealStatus
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

/** What a PvP fight needs from the engine: potions on both sides, either side forfeiting, and the mirror. */
class TwoPlayerTest {
    private val host = fighter(id = PetId.JASON, hp = 100)
    private val guest = fighter(id = PetId.FREDDY, hp = 80)

    @Test
    fun aPvpFightGivesBothSidesTheirPotions() {
        val s = startPvp(host, guest)
        assertEquals(host, s.player)
        assertEquals(guest, s.cpu)
        assertEquals(FightTuning.POTIONS_PER_FIGHT, s.playerPotions)
        assertEquals(FightTuning.POTIONS_PER_FIGHT, s.cpuPotions)
    }

    @Test
    fun aPvpFightStripsAMealsStatusItself() {
        // Even from a caller that did not strip it (as pvpFighter does): no status, the usual potions, none of them
        // counted as drunk, and an empty meter, not a SUGAR RUSH's head start or a feast's third potion.
        val fed = buildFighter(PetState(PetId.JASON), MealStatus(MealPerk.SUGAR_RUSH, feast = true))
        assertEquals(FightTuning.PERK_START_METER, fed.meter)
        val s = startPvp(fed, guest.copy(status = MealStatus(MealPerk.BEEFED_UP)))
        assertNull(s.player.status)
        assertNull(s.cpu.status)
        assertEquals(FightTuning.POTIONS_PER_FIGHT, s.playerPotions)
        assertEquals(FightTuning.POTIONS_PER_FIGHT, s.cpuPotions)
        assertEquals(0, s.potionsUsed)
        assertEquals(0, s.player.meter)
        assertEquals(0, s.mirrored().potionsUsed)
        assertEquals(buildFighter(PetState(PetId.JASON)), s.player)
    }

    @Test
    fun theOtherSideCanDrinkWhenItHasPotions() {
        val s = startPvp(host, guest.copy(hp = 40))
        val r = resolveTurn(s, Move.BLOCK, Move.POTION, ScriptedRandom(listOf(0.0)))
        assertEquals(listOf<FightEvent>(Heal(Side.CPU, 40)), r.events)
        assertEquals(80, r.state.cpu.hp)
        assertEquals(1, r.state.cpuPotions)
        assertEquals(FightTuning.POTIONS_PER_FIGHT, r.state.playerPotions)
    }

    @Test
    fun theCpuStillHasNone() {
        assertEquals(0, stateOf().cpuPotions)
        assertThrows(IllegalArgumentException::class.java) {
            resolveTurn(stateOf(), Move.BLOCK, Move.POTION, ScriptedRandom(listOf(0.0)))
        }
    }

    @Test
    fun bothDrinkingRollThePlayersHealFirst() {
        val s = startPvp(host.copy(hp = 20), guest.copy(hp = 10))
        val r = resolveTurn(s, Move.POTION, Move.POTION, ScriptedRandom(listOf(0.0, 1.0)))
        // The player's roll (0.0) heals half its 100; the other's (1.0) all of its 80, capped at the 70 missing.
        assertEquals(listOf<FightEvent>(Heal(Side.PLAYER, 50), Heal(Side.CPU, 70)), r.events)
    }

    @Test
    fun aPotionHealsBeforeTheOtherSidesBlowWhicheverSideDrinks() {
        val s = startPvp(host, guest.copy(hp = 10))
        val r = resolveTurn(s, Move.HEAVY, Move.POTION, ScriptedRandom(listOf(0.5, 0.5, 0.99, 0.0)))
        // The guest heals 40 of its 80 first, then takes the HEAVY's 29: 10 + 40 - 29.
        assertEquals(21, r.state.cpu.hp)
        assertNull(r.state.winner)
    }

    @Test
    fun eitherSideCanForfeit() {
        val s = startPvp(host, guest)
        val hostRan = forfeit(s, Side.PLAYER)
        assertEquals(Side.CPU, hostRan.winner)
        assertTrue(hostRan.playerRan)
        assertFalse(hostRan.cpuRan)

        val guestRan = forfeit(s, Side.CPU)
        assertEquals(Side.PLAYER, guestRan.winner)
        assertTrue(guestRan.cpuRan)
        assertFalse(guestRan.playerRan)
        assertEquals("nothing else changes", s.copy(winner = Side.PLAYER, cpuRan = true), guestRan)

        assertThrows(IllegalStateException::class.java) { forfeit(guestRan, Side.PLAYER) }
    }

    @Test
    fun aForfeitWinIsNotRanked() {
        assertNull(rankScore(forfeit(startPvp(host, guest), Side.CPU)))
    }

    @Test
    fun bothSidesStatsAreTallied() {
        val r = resolveTurn(startPvp(host, guest), Move.STRIKE, Move.STRIKE, ScriptedRandom.attacks(Roll(), Roll()))
        assertEquals(FightStats(damageDealt = 16, damageTaken = 16, hits = 1, bestCombo = 1), r.state.stats)
        assertEquals(FightStats(damageDealt = 16, damageTaken = 16, hits = 1, bestCombo = 1), r.state.cpuStats)

        val blocked = resolveTurn(startPvp(host, guest), Move.BLOCK, Move.STRIKE, ScriptedRandom.attacks(Roll()))
        assertEquals("the guest's strike was stopped", 1, blocked.state.cpuStats.stopped)
        assertEquals(0, blocked.state.stats.stopped)
    }

    @Test
    fun theMirrorSwapsTheSidesAndUndoesItself() {
        val s = startPvp(host, guest).copy(
            turn = 4,
            playerPotions = 1,
            cpuPotions = 2,
            stats = FightStats(hits = 3),
            cpuStats = FightStats(hits = 1),
            winner = Side.PLAYER,
            cpuRan = true,
        )
        val m = s.mirrored()
        assertEquals(guest, m.player)
        assertEquals(host, m.cpu)
        assertEquals(4, m.turn)
        assertEquals(Side.CPU, m.winner)
        assertTrue(m.playerRan)
        assertFalse(m.cpuRan)
        assertEquals(2, m.playerPotions)
        assertEquals(1, m.cpuPotions)
        assertEquals(FightStats(hits = 1), m.stats)
        assertEquals(s, m.mirrored())
    }

    @Test
    fun aMirroredTurnPlaysTheNewPlayersMoveFirst() {
        // The host strikes and cuts off the guest's HEAVY; both were bleeding, and the guest falls.
        val events = listOf(
            Hit(Side.PLAYER, Move.STRIKE, 16, crit = false),
            Interrupted(Side.CPU),
            Bleed(Side.PLAYER, 6),
            Bleed(Side.CPU, 5),
            Ko(Side.CPU),
        )
        assertEquals(
            listOf(
                Hit(Side.CPU, Move.STRIKE, 16, crit = false),
                Interrupted(Side.PLAYER),
                Bleed(Side.PLAYER, 5),
                Bleed(Side.CPU, 6),
                Ko(Side.PLAYER),
            ),
            mirroredEvents(events),
        )
        // A trade: the guest's hit comes first once it is the player.
        val trade = listOf(Hit(Side.PLAYER, Move.STRIKE, 16, crit = false), Hit(Side.CPU, Move.HEAVY, 29, crit = false))
        assertEquals(
            listOf(Hit(Side.PLAYER, Move.HEAVY, 29, crit = false), Hit(Side.CPU, Move.STRIKE, 16, crit = false)),
            mirroredEvents(trade),
        )
    }
}
