package team.superfun.horrorpets.ui.fight

import team.superfun.horrorpets.audio.killerLines
import team.superfun.horrorpets.audio.voiceBeatsOf
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.Side
import kotlin.random.Random

/**
 * Picks the killers' own lines through one fight (see [killerLines]), the way [Callouts] picks the announcer's. Make
 * one per fight, as the fight screen does: it remembers the last line, so none is said twice running.
 */
internal class Quips(private val rng: Random, private val chance: Float = 0.5f) {
    private var last: String? = null

    /**
     * The turn's one killer line and the event it is said with, or null.
     *
     * A turn the announcer speaks in is his alone: one with a [callout], and the one that ends the fight (its
     * "Knockout!"), get none, before any roll. Otherwise the first of [events] that a killer with lines has a beat in
     * (see [voiceBeatsOf]; the player's side first when both have) gets it, with [chance], one roll a turn: a no there
     * leaves the turn quiet. Never the line picked last time, unless it is the beat's only one.
     */
    fun pick(fight: FightState, events: List<FightEvent>, callout: Callout?): Pair<FightEvent, String>? {
        // A line on an earlier event would hold his call up behind it in the queue, so he gets the turn to himself.
        if (callout != null || fight.winner != null) return null
        for (event in events) {
            val lines = linesFor(fight, event) ?: continue
            if (rng.nextFloat() >= chance) return null
            val choices = lines.filter { it != last }.ifEmpty { lines }
            return (event to choices[rng.nextInt(choices.size)]).also { last = it.second }
        }
        return null
    }

    /** What the first side (the player's, then the CPU's) with lines for its beat in [event] may say; or null. */
    private fun linesFor(fight: FightState, event: FightEvent): List<String>? =
        listOf(Side.PLAYER to fight.player.id, Side.CPU to fight.cpu.id).firstNotNullOfOrNull { (side, pet) ->
            voiceBeatsOf(event, side).flatMap { killerLines(pet, it) }.ifEmpty { null }
        }
}
