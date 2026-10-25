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
            VoiceBeat.IDLE to listOf("chucky_idle_1", "chucky_idle_2", "chucky_idle_3"),
            VoiceBeat.FEED to listOf("chucky_feed_1", "chucky_feed_2"),
            VoiceBeat.PLAY to listOf("chucky_play_1", "chucky_play_2"),
            VoiceBeat.ATTACK to listOf("chucky_attack_1", "chucky_attack_2"),
            VoiceBeat.HEAVY to listOf("chucky_heavy"),
            VoiceBeat.HURT to listOf("chucky_hurt_1", "chucky_hurt_2"),
            VoiceBeat.BLOCK to listOf("chucky_block"),
            VoiceBeat.SPECIAL to listOf("chucky_special"),
            VoiceBeat.WIN to listOf("chucky_win"),
            VoiceBeat.LOSE to listOf("chucky_lose"),
            VoiceBeat.MISS to listOf("chucky_miss"),
        )
        assertEquals("every beat has his lines", VoiceBeat.entries.toSet(), spec.keys)
        for (beat in VoiceBeat.entries) assertEquals(beat.name, spec.getValue(beat), killerLines(PetId.CHUCKY, beat))
    }

    @Test
    fun everyOtherKillerIsSilent() {
        for (pet in PetId.entries - PetId.CHUCKY) {
            for (beat in VoiceBeat.entries) assertEquals("$pet $beat", emptyList<String>(), killerLines(pet, beat))
        }
    }

    @Test
    fun beatsOfReadTheEventFromEachSide() {
        fun both(event: FightEvent) = voiceBeatsOf(event, Side.PLAYER) to voiceBeatsOf(event, Side.CPU)

        // A landed blow: the attacker's own beat for the move, the one it landed on is hurt.
        assertEquals(listOf(VoiceBeat.ATTACK) to listOf(VoiceBeat.HURT), both(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 10, crit = false)))
        assertEquals(listOf(VoiceBeat.HURT) to listOf(VoiceBeat.HEAVY), both(FightEvent.Hit(Side.CPU, Move.HEAVY, 18, crit = false)))
        assertEquals(listOf(VoiceBeat.SPECIAL) to listOf(VoiceBeat.HURT), both(FightEvent.Hit(Side.PLAYER, Move.SPECIAL, 40, crit = false)))
        // A crit is still the move it was, and a counter is a strike.
        assertEquals(listOf(VoiceBeat.HURT) to listOf(VoiceBeat.ATTACK), both(FightEvent.Hit(Side.CPU, Move.STRIKE, 20, crit = true)))
        assertEquals(
            listOf(VoiceBeat.ATTACK) to listOf(VoiceBeat.HURT),
            both(FightEvent.Hit(Side.PLAYER, Move.STRIKE, 5, crit = false, counter = true)),
        )
        // A block is the blocker's, a whiff the one who swung: the other side has nothing to say.
        assertEquals(emptyList<VoiceBeat>() to listOf(VoiceBeat.BLOCK), both(FightEvent.Blocked(Side.CPU)))
        assertEquals(listOf(VoiceBeat.MISS) to emptyList<VoiceBeat>(), both(FightEvent.Miss(Side.PLAYER, Move.HEAVY)))
        assertEquals(emptyList<VoiceBeat>() to listOf(VoiceBeat.MISS), both(FightEvent.Miss(Side.CPU, Move.STRIKE, dazed = true)))
        // The knockout is the announcer's; potions, drains, bleeds, interrupts and a filled meter are no one's.
        for (quiet in listOf(
            FightEvent.Ko(Side.CPU),
            FightEvent.Heal(Side.PLAYER, 34),
            FightEvent.Drain(Side.CPU, 20),
            FightEvent.Bleed(Side.PLAYER, 6),
            FightEvent.Interrupted(Side.CPU),
            FightEvent.SpecialReady(Side.PLAYER),
        )) {
            assertEquals("$quiet", emptyList<VoiceBeat>() to emptyList<VoiceBeat>(), both(quiet))
        }
    }
}
