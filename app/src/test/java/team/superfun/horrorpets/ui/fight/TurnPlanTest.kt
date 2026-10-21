package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AnimSpec
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.SpecialStyle
import team.superfun.horrorpets.model.spec

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
    fun aDancersSpecialIsItsDanceAndEveryOtherSwingIsAnAttack() {
        val special = FightEvent.Hit(Side.PLAYER, Move.SPECIAL, 40, crit = false)
        assertEquals(Anim.DANCE, swingAnim(special, dancesSpecial = true))
        assertEquals(Anim.ATTACK, swingAnim(special, dancesSpecial = false))
        for (event in listOf(
            FightEvent.Hit(Side.CPU, Move.STRIKE, 10, crit = false),
            FightEvent.Hit(Side.CPU, Move.HEAVY, 35, crit = true),
            FightEvent.Hit(Side.CPU, Move.STRIKE, 8, crit = false, counter = true),
            FightEvent.Miss(Side.CPU, Move.HEAVY),
            FightEvent.Blocked(Side.PLAYER),
        )) {
            assertEquals("$event", Anim.ATTACK, swingAnim(event, dancesSpecial = true))
        }
    }

    @Test
    fun aDancedSpecialIsDancedOnlyWhenTheSpritesShipADance() {
        assertTrue(dancesSpecial(SpecialStyle.SAW_DANCE, hasDance = true))
        // Saw Dance on sprites without a dance strip: a plain ATTACK swing, never a missing strip.
        assertFalse(dancesSpecial(SpecialStyle.SAW_DANCE, hasDance = false))
        val special = FightEvent.Hit(Side.PLAYER, Move.SPECIAL, 40, crit = false)
        assertEquals(Anim.ATTACK, swingAnim(special, dancesSpecial(SpecialStyle.SAW_DANCE, hasDance = false)))
        // A special that is not danced swings its ATTACK even when the pet could dance.
        for (style in SpecialStyle.entries - SpecialStyle.SAW_DANCE) {
            assertFalse("$style", dancesSpecial(style, hasDance = true))
            assertEquals("$style", Anim.ATTACK, swingAnim(special, dancesSpecial(style, hasDance = true)))
        }
    }

    @Test
    fun onlyTheSawDanceIsDanced() {
        assertEquals(listOf(SpecialStyle.SAW_DANCE), SpecialStyle.entries.filter { it.danced })
        assertEquals(SpecialStyle.SAW_DANCE, PetId.LEATHERFACE.spec.special)
    }

    @Test
    fun aDanceLandsAThirdOfTheWayInAndIsDancedToTheEnd() {
        // Leatherface's dance: 90 x5 then 110, 550 ms. A third in (183 ms) is the third frame, the saw overhead.
        val dance = AnimSpec("dance.png", 6, listOf(90, 90, 90, 90, 90, 110), loop = false)
        assertEquals(180L, swingImpactMs(Anim.DANCE, dance))
        // The rest of his spin (370 ms) fits in the usual hold, so he finishes it before hopping back.
        assertEquals(AFTER_IMPACT_MS, swingHoldMs(Anim.DANCE, dance, impact = 180L))
        // A longer dance is danced to its end: 1200 ms, landing 400 ms in, holds the 800 ms left.
        val long = AnimSpec("dance.png", 6, List(6) { 200 }, loop = false)
        assertEquals(400L, swingImpactMs(Anim.DANCE, long))
        assertEquals(800L, swingHoldMs(Anim.DANCE, long, impact = 400L))
        assertEquals(0L, swingImpactMs(Anim.DANCE, AnimSpec("dance.png", 0, emptyList(), loop = false)))
    }

    @Test
    fun anAttackStillLandsOnItsSnapFrameAndCutsItsRecoveryShort() {
        val attack = AnimSpec("attack.png", 6, listOf(300, 140, 180, 75, 140, 360), loop = false)
        assertEquals(impactMs(attack), swingImpactMs(Anim.ATTACK, attack))
        assertEquals(AFTER_IMPACT_MS, swingHoldMs(Anim.ATTACK, attack, impact = 620L))
        assertEquals(420L, AFTER_IMPACT_MS)
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
        val sawDance = FightState(
            player = Fighter(PetId.LEATHERFACE, 1, 100, 100, 20.0, 50, 0),
            cpu = Fighter(PetId.MICHAEL, 1, 100, 100, 20.0, 50, 0),
        )
        assertEquals(listOf("special_leatherface"), TurnSounds(sawDance).of(FightEvent.Hit(Side.PLAYER, Move.SPECIAL, 40, crit = false)).sfx)
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
    fun koIsTheBoomAndTheAnnouncer() {
        val s = sounds(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 10, crit = false), FightEvent.Ko(Side.CPU))
        assertEquals(listOf("hit", "ko"), s.sfx)
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

/** Where the HUD's bars start when a screen is built in the middle of a turn's playback. */
class HudBeforeTurnTest {
    private val jason = Fighter(PetId.JASON, 1, maxHp = 100, hp = 60, strength = 20.0, mood = 50, meter = 50)

    @Test
    fun theDamageTakenIsGivenBack() {
        val events = listOf(
            FightEvent.Hit(Side.CPU, Move.STRIKE, 12, crit = false),
            FightEvent.Hit(Side.CPU, Move.HEAVY, 8, crit = true),
        )
        assertEquals(HudStart(hp = 80, meter = 50), hudBeforeTurn(jason, Side.PLAYER, events))
    }

    @Test
    fun hpNeverStartsAboveMax() {
        val events = listOf(FightEvent.Hit(Side.CPU, Move.HEAVY, 70, crit = false))
        assertEquals(100, hudBeforeTurn(jason, Side.PLAYER, events).hp)
    }

    @Test
    fun ownHitsAreNotDamageTakenButFillTheMeter() {
        // Jason landed a strike (and a counter): one meter cell each, which came after the turn started.
        val events = listOf(
            FightEvent.Hit(Side.PLAYER, Move.STRIKE, 10, crit = false),
            FightEvent.Blocked(Side.PLAYER),
            FightEvent.Hit(Side.PLAYER, Move.STRIKE, 5, crit = false, counter = true),
        )
        assertEquals(HudStart(hp = 60, meter = 0), hudBeforeTurn(jason, Side.PLAYER, events))
    }

    @Test
    fun theMeterNeverStartsBelowZero() {
        val events = listOf(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 10, crit = false))
        assertEquals(0, hudBeforeTurn(jason.copy(meter = 0), Side.PLAYER, events).meter)
    }

    @Test
    fun aSpecialStartedFromAFullMeter() {
        val events = listOf(FightEvent.Hit(Side.PLAYER, Move.SPECIAL, 40, crit = false))
        assertEquals(HudStart(hp = 60, meter = 100), hudBeforeTurn(jason.copy(meter = 0), Side.PLAYER, events))
    }

    @Test
    fun aQuietTurnChangesNothing() {
        val events = listOf(FightEvent.Miss(Side.CPU, Move.HEAVY), FightEvent.Interrupted(Side.PLAYER))
        assertEquals(HudStart(hp = 60, meter = 50), hudBeforeTurn(jason, Side.PLAYER, events))
    }
}
