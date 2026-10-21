package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.AnimSpec
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.model.PetId

class TurnPlanTest {
    @Test
    fun hitsAndMissesAreLungesByTheirAttacker() {
        val hit = FightEvent.Hit(Side.PLAYER, Move.STRIKE, 10, crit = false)
        val miss = FightEvent.Miss(Side.CPU, Move.HEAVY)
        assertEquals(
            listOf(Beat.Lunge(Side.PLAYER, hit), Beat.Lunge(Side.CPU, miss)),
            beatsOf(listOf(hit, miss), winner = null),
        )
    }

    @Test
    fun aBlockIsTheStrikersSwingIntoTheShieldThenTheCounter() {
        val blocked = FightEvent.Blocked(Side.PLAYER)
        val counter = FightEvent.Hit(Side.PLAYER, Move.STRIKE, 5, crit = false, counter = true)
        assertEquals(
            listOf(Beat.Lunge(Side.CPU, blocked), Beat.Lunge(Side.PLAYER, counter)),
            beatsOf(listOf(blocked, counter), winner = null),
        )
    }

    @Test
    fun anInterruptFollowsTheStrikeThatCausedIt() {
        val miss = FightEvent.Miss(Side.CPU, Move.STRIKE)
        val cut = FightEvent.Interrupted(Side.PLAYER)
        assertEquals(
            listOf(Beat.Lunge(Side.CPU, miss), Beat.Interrupt(Side.PLAYER)),
            beatsOf(listOf(miss, cut), winner = null),
        )
    }

    @Test
    fun specialReadyIsShownWhileTheFightGoesOn() {
        val hit = FightEvent.Hit(Side.CPU, Move.STRIKE, 10, crit = false)
        assertEquals(
            listOf(Beat.Lunge(Side.CPU, hit), Beat.Ready(Side.CPU)),
            beatsOf(listOf(hit, FightEvent.SpecialReady(Side.CPU)), winner = null),
        )
    }

    @Test
    fun specialReadyIsSkippedOnTheFinishingTurnAndTheKoIsLeftToTheFinale() {
        val hit = FightEvent.Hit(Side.PLAYER, Move.STRIKE, 30, crit = false)
        val beats = beatsOf(listOf(hit, FightEvent.SpecialReady(Side.PLAYER), FightEvent.Ko(Side.CPU)), winner = Side.PLAYER)
        assertEquals(listOf(Beat.Lunge(Side.PLAYER, hit)), beats)
    }

    @Test
    fun theImpactIsTheStartOfTheSnapFrame() {
        // Jason's and Freddy's real attack timings: both connect 620ms in.
        assertEquals(620L, impactMs(AnimSpec("attack.png", 6, listOf(300, 140, 180, 75, 140, 360), loop = false)))
        assertEquals(620L, impactMs(AnimSpec("attack.png", 8, listOf(320, 130, 170, 65, 80, 130, 150, 420), loop = false)))
        assertEquals(0L, impactMs(AnimSpec("attack.png", 0, emptyList(), loop = false)))
    }

    @Test
    fun noEventsNoBeats() {
        assertTrue(beatsOf(emptyList(), winner = Side.CPU).isEmpty())
    }
}

/** The sounds each event plays at the moment the fight screen shows it. */
class TurnSoundsTest {
    private val fight = FightState(
        player = Fighter(PetId.JASON, 1, 100, 100, 20.0, 50, 0),
        cpu = Fighter(PetId.FREDDY, 1, 100, 100, 20.0, 50, 0),
    )

    /** One turn's events through one [TurnSounds], flattened in order. */
    private fun sounds(vararg events: FightEvent): FightSounds {
        val turn = TurnSounds(fight)
        val each = events.map(turn::of)
        return FightSounds(each.flatMap { it.sfx }, each.flatMap { it.voice })
    }

    @Test
    fun plainHitsAndHeavyHits() {
        val s = sounds(
            FightEvent.Hit(Side.PLAYER, Move.STRIKE, 10, crit = false),
            FightEvent.Hit(Side.CPU, Move.HEAVY, 18, crit = false),
        )
        assertEquals(listOf("hit", "heavy_hit"), s.sfx)
        assertTrue(s.voice.isEmpty())
    }

    @Test
    fun counterHitIsAPlainHit() {
        val s = sounds(FightEvent.Blocked(Side.PLAYER), FightEvent.Hit(Side.PLAYER, Move.STRIKE, 5, crit = false, counter = true))
        assertEquals(listOf("block", "hit"), s.sfx)
    }

    @Test
    fun specialsUseTheAttackersPetSound() {
        assertEquals(listOf("special_jason"), sounds(FightEvent.Hit(Side.PLAYER, Move.SPECIAL, 40, crit = false)).sfx)
        assertEquals(listOf("special_freddy"), sounds(FightEvent.Hit(Side.CPU, Move.SPECIAL, 40, crit = false)).sfx)
    }

    @Test
    fun critsPlayCritAndTheAnnouncerOncePerTurn() {
        val s = sounds(
            FightEvent.Hit(Side.PLAYER, Move.STRIKE, 20, crit = true),
            FightEvent.Hit(Side.CPU, Move.HEAVY, 30, crit = true),
        )
        assertEquals(listOf("crit", "crit"), s.sfx)
        assertEquals(listOf("critical"), s.voice)
    }

    @Test
    fun theAnnouncerSaysCriticalWithTheFirstCritNotLater() {
        val turn = TurnSounds(fight)
        assertTrue(turn.of(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 10, crit = false)).voice.isEmpty())
        assertEquals(listOf("critical"), turn.of(FightEvent.Hit(Side.CPU, Move.STRIKE, 20, crit = true)).voice)
    }

    @Test
    fun eachTurnAnnouncesItsOwnCrit() {
        val crit = FightEvent.Hit(Side.PLAYER, Move.STRIKE, 20, crit = true)
        assertEquals(listOf("critical"), TurnSounds(fight).of(crit).voice)
        assertEquals(listOf("critical"), TurnSounds(fight).of(crit).voice)
    }

    @Test
    fun missesAndBlocks() {
        assertEquals(listOf("miss"), sounds(FightEvent.Miss(Side.CPU, Move.HEAVY)).sfx)
        assertEquals(listOf("block"), sounds(FightEvent.Blocked(Side.CPU)).sfx)
    }

    @Test
    fun koIsAnnounced() {
        val s = sounds(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 10, crit = false), FightEvent.Ko(Side.CPU))
        assertEquals(listOf("hit"), s.sfx)
        assertEquals(listOf("ko"), s.voice)
    }

    @Test
    fun interruptsAndSpecialReadyAreSilent() {
        val s = sounds(FightEvent.Interrupted(Side.CPU), FightEvent.SpecialReady(Side.PLAYER))
        assertTrue(s.sfx.isEmpty())
        assertTrue(s.voice.isEmpty())
    }

    @Test
    fun soundsKeepEventOrder() {
        val s = sounds(
            FightEvent.Miss(Side.PLAYER, Move.STRIKE),
            FightEvent.Hit(Side.CPU, Move.STRIKE, 9, crit = false),
            FightEvent.Interrupted(Side.PLAYER),
        )
        assertEquals(listOf("miss", "hit"), s.sfx)
    }
}
