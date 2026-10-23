package team.superfun.horrorpets.ui.training

import org.junit.Assert.assertEquals
import org.junit.Test

class ResultsCardTest {
    @Test
    fun strengthShowsTenthsOnlyWhenItHasThem() {
        assertEquals("6", strengthText(6.0)) // a score of 0: today's TRAIN
        assertEquals("10.5", strengthText(10.5)) // Slasher Says at 3 of 8
        assertEquals("18", strengthText(18.0)) // at par
        assertEquals("7.3", strengthText(7.25 + 1e-9))
        assertEquals("0", strengthText(0.0)) // strength already at the cap
    }
}
