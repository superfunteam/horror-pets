package team.superfun.horrorpets.ui.pantry

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.game.pantry.Reaction
import team.superfun.horrorpets.ui.FedShow

/** The hunger a meal took off, read out first among its gains. */
class HungerGainTest {
    private fun fed(before: Double, after: Double, xp: Int = 0) =
        FedShow(PantryItem.RAW_STEAK, Reaction.SHRUG, "It's... raw.", xp = xp, levelsGained = 0, hungerBefore = before, hungerAfter = after)

    @Test
    fun theHungerTakenOffComesFirstAndIsNoLoss() {
        val gains = gainsOf(fed(90.0, 55.0, xp = 4))
        assertEquals(listOf("-35 HUNGER", "+4 XP"), gains.map { it.text })
        assertEquals(listOf(false, false), gains.map { it.loss })
    }

    @Test
    fun nothingTakenOffReadsNothing() {
        assertEquals(emptyList<Gain>(), gainsOf(fed(20.0, 20.0)))
        assertEquals("no hunger known reads nothing", emptyList<Gain>(), gainsOf(fed(0.0, 0.0)))
        assertEquals(listOf("-1 HUNGER"), gainsOf(fed(40.6, 40.0)).map { it.text })
    }
}
