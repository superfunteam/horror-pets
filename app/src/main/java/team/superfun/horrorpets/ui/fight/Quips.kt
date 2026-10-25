package team.superfun.horrorpets.ui.fight

import team.superfun.horrorpets.audio.beatsOf
import team.superfun.horrorpets.audio.killerLines
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
     * The turn's one killer line and the event it is said with, or null. The first of [events] that a killer with
     * lines has a beat in (see [beatsOf]; the player's side first when both have) gets it, with [chance], one roll a
     * turn: a no there leaves the turn quiet. The event the announcer has the [callout] for is his, and so is the
     * knockout. Never the line picked last time, unless it is the beat's only one.
     */
    fun pick(fight: FightState, events: List<FightEvent>, callout: Callout?): Pair<FightEvent, String>? {
        for (event in events) {
            // The announcer speaks as these land (his call, "Knockout!"): a killer's line there would only be dropped.
            if (event == callout?.event || event is FightEvent.Ko) continue
            val lines = linesFor(fight, event) ?: continue
            if (rng.nextFloat() >= chance) return null
            val choices = lines.filter { it != last }.ifEmpty { lines }
            return (event to choices[rng.nextInt(choices.size)]).also { last = it.second }
        }
        return null
    }

    /** What the first side (the player's, then the CPU's) whose killer has lines for its beat in [event] may say; or null. */
    private fun linesFor(fight: FightState, event: FightEvent): List<String>? =
        listOf(Side.PLAYER to fight.player.id, Side.CPU to fight.cpu.id).firstNotNullOfOrNull { (side, pet) ->
            beatsOf(event, side).flatMap { killerLines(pet, it) }.ifEmpty { null }
        }
}
