package team.superfun.horrorpets.ui.training

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.training.SLASH_SLOTS
import team.superfun.horrorpets.game.training.SlashRound
import team.superfun.horrorpets.game.training.SlashStep
import team.superfun.horrorpets.game.training.SlashTurn

class SlashSwipeTest {
    /** Three dots in a row, 100 apart, radius 10 (so the due one is hit within 14): slashed left to right. */
    private val dots = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(200f, 0f))
    private val r = 10f

    private fun turn(order: List<Int> = listOf(0, 1, 2)) = SlashTurn(SlashRound(round = 2, slots = listOf(0, 3, 6), order = order))

    /** Runs a finger through [points] (the first going down) and returns what each slash did, in order. */
    private fun swipe(turn: SlashTurn, vararg points: Pair<Float, Float>): List<Pair<Int, SlashStep>> {
        val inside = BooleanArray(SLASH_SLOTS)
        val steps = mutableListOf<Pair<Int, SlashStep>>()
        var last = Offset(points.first().first, points.first().second)
        for ((x, y) in points) {
            val p = Offset(x, y)
            swipeThrough(turn, dots, r, last, p, inside) { dot, step -> steps += dot to step }
            last = p
        }
        return steps
    }

    @Test
    fun oneStrokeSlashesEveryDotItCrossesInOrder() {
        // One move event from far left to far right, straight through all three.
        val steps = swipe(turn(), -50f to 0f, 250f to 0f)
        assertEquals(listOf(0 to SlashStep.RIGHT, 1 to SlashStep.RIGHT, 2 to SlashStep.ROUND_DONE), steps)
    }

    @Test
    fun aTapOnTheDueDotsHitCircleSlashesIt() {
        val turn = turn()
        assertEquals(listOf(0 to SlashStep.RIGHT), swipe(turn, 13f to 0f)) // within 1.4 radii
        assertEquals(emptyList<Pair<Int, SlashStep>>(), swipe(turn, 115f to 0f)) // just outside it
        assertEquals(1, turn.slashed)
    }

    @Test
    fun aWrongDotCountsOnlyWhenTouchedItself() {
        val turn = turn()
        // Due is dot 0; brushing dot 1's hit circle (12 off its centre, under 1.4 radii but outside the dot) is nothing,
        // and so is grazing its rim (9.7 off: inside it, but not by 0.95 of its radius).
        assertEquals(emptyList<Pair<Int, SlashStep>>(), swipe(turn, 112f to 30f, 112f to -30f))
        assertEquals(emptyList<Pair<Int, SlashStep>>(), swipe(turn, 109.7f to 30f, 109.7f to -30f))
        // Touching dot 1 itself ends it.
        assertEquals(listOf(1 to SlashStep.WRONG), swipe(turn, 105f to 0f))
        assertTrue(turn.over)
    }

    @Test
    fun aStraightSwipeFromDotToDotCrossesNoOtherOnTheRing() {
        // The ring a TV shows (its frame between the film's edges, 3:2), every slot dealt. From slot 255° to 320° (or
        // 220° to 285°), the line between grazes the dot between at 0.99 of its radius, so that must not count.
        val ring = SlashRing.of(w = 1500f, h = 1000f, floorY = 750f, petHeight = 460f)
        val slots = (0 until SLASH_SLOTS).toList()
        val dots = slots.map(ring::dot)
        for (from in slots) {
            for (to in slots) {
                if (to == from) continue
                // Due: [from], then [to], then the rest.
                val order = listOf(from, to) + slots.filter { it != from && it != to }
                val turn = SlashTurn(SlashRound(round = SLASH_SLOTS, slots = slots, order = order))
                val inside = BooleanArray(SLASH_SLOTS)
                val steps = mutableListOf<Pair<Int, SlashStep>>()
                swipeThrough(turn, dots, ring.dotR, dots[from], dots[from], inside) { dot, step -> steps += dot to step }
                swipeThrough(turn, dots, ring.dotR, dots[from], dots[to], inside) { dot, step -> steps += dot to step }
                assertEquals("$from to $to", listOf(from to SlashStep.RIGHT, to to SlashStep.RIGHT), steps)
            }
        }
    }

    @Test
    fun stayingInADotOrComingBackToItDoesNothing() {
        val turn = turn()
        // Down on dot 0, wiggle inside it, out to dot 1, back over dot 0: two slashes, nothing more.
        val steps = swipe(turn, 0f to 0f, 5f to 3f, -4f to -2f, 100f to 0f, 0f to 0f)
        assertEquals(listOf(0 to SlashStep.RIGHT, 1 to SlashStep.RIGHT), steps)
        assertEquals(2, turn.slashed)
    }

    @Test
    fun aWrongDotMidStrokeStopsTheStroke() {
        // Order 0, 2, 1: a stroke straight along the row reaches dot 1 before dot 2.
        val turn = turn(order = listOf(0, 2, 1))
        val steps = swipe(turn, -50f to 0f, 250f to 0f)
        assertEquals(listOf(0 to SlashStep.RIGHT, 1 to SlashStep.WRONG), steps)
    }

    @Test
    fun noTurnSlashesNothing() {
        val inside = BooleanArray(SLASH_SLOTS)
        swipeThrough(null, dots, r, Offset(-50f, 0f), Offset(250f, 0f), inside) { _, _ -> error("slashed with no turn") }
        swipeThrough(null, dots, r, Offset(0f, 0f), Offset(0f, 0f), inside) { _, _ -> error("slashed with no turn") }
        assertTrue(inside[0]) // but it knows the finger is in dot 0
    }
}
