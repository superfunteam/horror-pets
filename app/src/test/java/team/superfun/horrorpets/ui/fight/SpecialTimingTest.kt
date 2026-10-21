package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.SpecialStyle

class SpecialTimingTest {
    @Test
    fun theShapeLandsWhenItsSlashHasCut() {
        // The dark closes in first; the blow (number, flash, HP drain) lands as the overhead slash finishes
        // its cut, a little past halfway through the effect, and well before it ends.
        val at = specialLandsAtMs(SpecialStyle.THE_SHAPE)
        assertEquals(((SHAPE_SLASH_START + SHAPE_SLASH_LIFE * SHAPE_SLASH_CUT) * SPECIAL_MS).toLong(), at)
        assertTrue("$at", at in (SPECIAL_MS / 2)..(SPECIAL_MS * 2 / 3))
    }

    @Test
    fun everyOtherSpecialLandsAtItsImpact() {
        for (style in SpecialStyle.entries - SpecialStyle.THE_SHAPE) assertEquals("$style", 0L, specialLandsAtMs(style))
    }
}
