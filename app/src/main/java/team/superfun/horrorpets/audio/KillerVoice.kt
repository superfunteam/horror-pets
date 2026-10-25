package team.superfun.horrorpets.audio

import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.model.PetId

/**
 * The moments a killer may say something. At Home: [IDLE] now and then, a [FEED], a [PLAY]. In a fight: its own blow
 * landing ([ATTACK] for a strike or a counter, [HEAVY], [SPECIAL]), a blow it takes ([HURT]), a strike it [BLOCK]s and
 * a swing it [MISS]es. Once the fight is over: [WIN] or [LOSE].
 */
enum class VoiceBeat { IDLE, FEED, PLAY, ATTACK, HEAVY, HURT, BLOCK, SPECIAL, WIN, LOSE, MISS }

/**
 * Chucky's lines (`audio/voice/<name>.wav`, made by `tools/generate_killer_voice.py`), by beat. He is the only killer
 * with a voice so far; another gets one by adding a table like this to [killerLines].
 */
private val CHUCKY: Map<VoiceBeat, List<String>> = mapOf(
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

/**
 * What [pet] may say on [beat], to pick one from: voice clip names, or none for a killer with nothing to say (every
 * killer but Chucky, for now), so a caller never needs to know who talks.
 */
fun killerLines(pet: PetId, beat: VoiceBeat): List<String> = when (pet) {
    PetId.CHUCKY -> CHUCKY[beat].orEmpty()
    else -> emptyList()
}

/**
 * The beats [side]'s killer has in [event]: its own landed blow is its [VoiceBeat.ATTACK] (a strike, a counter
 * included), [VoiceBeat.HEAVY] or [VoiceBeat.SPECIAL] (a crit is still the move it was); a blow landed on it is
 * [VoiceBeat.HURT]; a strike it blocked is its [VoiceBeat.BLOCK] and a swing it whiffed its [VoiceBeat.MISS]. Nothing
 * else gets a line: the knockout is the announcer's, and potions, drains, bleeds, interrupts and a filled meter pass
 * unremarked.
 *
 * Named apart from the fight screen's own `Beat` and `beatsOf` (the steps of a turn's playback), which share its
 * package with the code that calls this.
 */
fun voiceBeatsOf(event: FightEvent, side: Side): List<VoiceBeat> = when (event) {
    is FightEvent.Hit -> when {
        side != event.attacker -> listOf(VoiceBeat.HURT)
        event.move == Move.HEAVY -> listOf(VoiceBeat.HEAVY)
        event.move == Move.SPECIAL -> listOf(VoiceBeat.SPECIAL)
        else -> listOf(VoiceBeat.ATTACK)
    }
    is FightEvent.Blocked -> if (side == event.blocker) listOf(VoiceBeat.BLOCK) else emptyList()
    is FightEvent.Miss -> if (side == event.attacker) listOf(VoiceBeat.MISS) else emptyList()
    is FightEvent.Interrupted, is FightEvent.SpecialReady, is FightEvent.Heal, is FightEvent.Drain,
    is FightEvent.Bleed, is FightEvent.Ko -> emptyList()
}
