package team.superfun.horrorpets.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.model.PetId

/** What each killer says on each beat, and which beats a fight event is for the killer on either side of it. */
class KillerVoiceTest {
    @Test
    fun chuckySaysEveryBeat() {
        // The names the voice tool wrote (tools/generate_killer_voice.py), beat by beat.
        val spec = mapOf(
            Beat.IDLE to listOf("chucky_idle_1", "chucky_idle_2", "chucky_idle_3"),
            Beat.FEED to listOf("chucky_feed_1", "chucky_feed_2"),
            Beat.PLAY to listOf("chucky_play_1", "chucky_play_2"),
            Beat.ATTACK to listOf("chucky_attack_1", "chucky_attack_2"),
            Beat.HEAVY to listOf("chucky_heavy"),
            Beat.HURT to listOf("chucky_hurt_1", "chucky_hurt_2"),
            Beat.BLOCK to listOf("chucky_block"),
            Beat.SPECIAL to listOf("chucky_special"),
            Beat.WIN to listOf("chucky_win"),
            Beat.LOSE to listOf("chucky_lose"),
            Beat.MISS to listOf("chucky_miss"),
        )
        assertEquals("every beat has his lines", Beat.entries.toSet(), spec.keys)
        for (beat in Beat.entries) assertEquals(beat.name, spec.getValue(beat), killerLines(PetId.CHUCKY, beat))
    }

    @Test
    fun everyOtherKillerIsSilent() {
        for (pet in PetId.entries - PetId.CHUCKY) {
            for (beat in Beat.entries) assertEquals("$pet $beat", emptyList<String>(), killerLines(pet, beat))
        }
    }

    @Test
    fun beatsOfReadTheEventFromEachSide() {
        fun both(event: FightEvent) = beatsOf(event, Side.PLAYER) to beatsOf(event, Side.CPU)

        // A landed blow: the attacker's own beat for the move, the one it landed on is hurt.
        assertEquals(listOf(Beat.ATTACK) to listOf(Beat.HURT), both(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 10, crit = false)))
        assertEquals(listOf(Beat.HURT) to listOf(Beat.HEAVY), both(FightEvent.Hit(Side.CPU, Move.HEAVY, 18, crit = false)))
        assertEquals(listOf(Beat.SPECIAL) to listOf(Beat.HURT), both(FightEvent.Hit(Side.PLAYER, Move.SPECIAL, 40, crit = false)))
        // A crit is still the move it was, and a counter is a strike.
        assertEquals(listOf(Beat.HURT) to listOf(Beat.ATTACK), both(FightEvent.Hit(Side.CPU, Move.STRIKE, 20, crit = true)))
        assertEquals(
            listOf(Beat.ATTACK) to listOf(Beat.HURT),
            both(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 5, crit = false, counter = true)),
        )
        // A block is the blocker's, a whiff the one who swung: the other side has nothing to say.
        assertEquals(emptyList<Beat>() to listOf(Beat.BLOCK), both(FightEvent.Blocked(Side.CPU)))
        assertEquals(listOf(Beat.MISS) to emptyList<Beat>(), both(FightEvent.Miss(Side.PLAYER, Move.HEAVY)))
        assertEquals(emptyList<Beat>() to listOf(Beat.MISS), both(FightEvent.Miss(Side.CPU, Move.STRIKE, dazed = true)))
        // The knockout is the announcer's; potions, drains, bleeds, interrupts and a filled meter are no one's.
        for (quiet in listOf(
            FightEvent.Ko(Side.CPU),
            FightEvent.Heal(Side.PLAYER, 34),
            FightEvent.Drain(Side.CPU, 20),
            FightEvent.Bleed(Side.PLAYER, 6),
            FightEvent.Interrupted(Side.CPU),
            FightEvent.SpecialReady(Side.PLAYER),
        )) {
            assertEquals("$quiet", emptyList<Beat>() to emptyList<Beat>(), both(quiet))
        }
    }
}
