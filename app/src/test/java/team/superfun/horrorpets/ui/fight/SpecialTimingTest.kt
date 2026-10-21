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
    fun theShapesSoundStartsEarlySoItsHitLandsWithTheSlash() {
        val land = specialLandsAtMs(SpecialStyle.THE_SHAPE)
        // special_michael's stab is 420 ms into the clip: start it that much before the blow lands.
        assertEquals(land - 420L, specialSoundAtMs(land, hitMs = 420L, crit = false))
        // A crit plays the crit hit and the announcer instead, with the blow itself.
        assertEquals(land, specialSoundAtMs(land, hitMs = 420L, crit = true))
        // A clip whose hit comes later than the blow starts at the impact (never before it).
        assertEquals(0L, specialSoundAtMs(300L, hitMs = 750L, crit = false))
    }

    @Test
    fun theOtherSpecialsSoundAtTheImpactAsBefore() {
        for (style in SpecialStyle.entries - SpecialStyle.THE_SHAPE) {
            val land = specialLandsAtMs(style)
            for (crit in listOf(false, true)) assertEquals("$style crit=$crit", 0L, specialSoundAtMs(land, 420L, crit))
        }
    }

    @Test
    fun everyOtherSpecialLandsAtItsImpact() {
        for (style in SpecialStyle.entries - SpecialStyle.THE_SHAPE) assertEquals("$style", 0L, specialLandsAtMs(style))
    }
}
