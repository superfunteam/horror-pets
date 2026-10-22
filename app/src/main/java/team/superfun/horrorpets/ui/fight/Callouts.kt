package team.superfun.horrorpets.ui.fight

import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.Move
import kotlin.random.Random

/**
 * The moments the announcer reacts to in a fight, biggest first, each with how often it gets a call and the
 * lines it picks from (`audio/voice/<line>.wav`). A line may serve more than one moment.
 */
internal enum class Moment(val chance: Float, val lines: List<String>) {
    SPECIAL(1f, listOf("holy_shit", "unbelievable", "oh_my_god", "did_you_see_that", "special_move")),
    CRIT(1f, listOf("critical", "critical_hit", "right_on_the_money", "brutal", "holy_shit")),
    HEAVY(0.5f, listOf("big_one", "gotta_hurt", "what_a_hit", "devastating")),
    BLOCK(0.4f, listOf("blocked", "denied", "not_today", "what_a_block", "rejected")),
    MISS(0.3f, listOf("too_slow", "swing_and_a_miss", "whiffed", "nowhere_close")),
}

/** The moment [event] is, if the announcer reacts to it: a special, a crit, a landed HEAVY, a block or a whiff. */
internal fun momentOf(event: FightEvent): Moment? = when (event) {
    is FightEvent.Hit -> when {
        event.move == Move.SPECIAL -> Moment.SPECIAL
        event.crit -> Moment.CRIT
        event.move == Move.HEAVY -> Moment.HEAVY
        else -> null
    }
    is FightEvent.Blocked -> Moment.BLOCK
    is FightEvent.Miss -> Moment.MISS
    is FightEvent.Interrupted, is FightEvent.SpecialReady, is FightEvent.Heal, is FightEvent.Drain,
    is FightEvent.Bleed, is FightEvent.Ko -> null
}

/** The announcer's call for one turn: [line] is said as [event] lands. */
internal data class Callout(val event: FightEvent, val line: String)

/**
 * Picks the announcer's calls through one fight. A turn gets at most one, for its biggest [Moment] (the
 * first such event when two tie), with that moment's [Moment.chance], and never the line the call before it
 * used, so the reactions mix up. The turn that ends the fight gets none: its call is "Knockout!".
 */
internal class Callouts(private val rng: Random) {
    private var last: String? = null

    fun pick(events: List<FightEvent>, finishing: Boolean): Callout? {
        if (finishing) return null
        var best: Pair<FightEvent, Moment>? = null
        for (e in events) {
            val m = momentOf(e) ?: continue
            if (best == null || m < best.second) best = e to m
        }
        val (event, moment) = best ?: return null
        if (rng.nextFloat() >= moment.chance) return null
        val choices = moment.lines.filter { it != last }
        return Callout(event, choices[rng.nextInt(choices.size)]).also { last = it.line }
    }
}
