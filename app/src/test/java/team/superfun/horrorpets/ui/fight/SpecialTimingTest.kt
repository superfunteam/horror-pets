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

    /** The specials whose blow lands later in their effect: The Shape's slash, Jaws's tank (see OxygenTankTest). */
    private val lateLanders = setOf(SpecialStyle.THE_SHAPE, SpecialStyle.OXYGEN_TANK)

    @Test
    fun theOtherSpecialsSoundAtTheImpactAsBefore() {
        for (style in SpecialStyle.entries - lateLanders) {
            val land = specialLandsAtMs(style)
            for (crit in listOf(false, true)) assertEquals("$style crit=$crit", 0L, specialSoundAtMs(land, 420L, crit))
        }
    }

    @Test
    fun everyOtherSpecialLandsAtItsImpact() {
        for (style in SpecialStyle.entries - lateLanders) assertEquals("$style", 0L, specialLandsAtMs(style))
    }

    @Test
    fun sawDancesGlowFlickersAtMostEightTimesASecond() {
        // Photosensitivity: count the glow's peaks over the effect at a fine step.
        val steps = 10_000
        var peaks = 0
        for (i in 1 until steps - 1) {
            val a = sawFlicker((i - 1) / steps.toFloat())
            val b = sawFlicker(i / steps.toFloat())
            val c = sawFlicker((i + 1) / steps.toFloat())
            if (b > a && b >= c) peaks++
        }
        val perSecond = peaks * 1000f / SPECIAL_MS
        assertTrue("$peaks peaks in $SPECIAL_MS ms = $perSecond Hz", perSecond <= 8f)
        // It still flickers: an engine, not a steady lamp.
        assertTrue("$peaks peaks", peaks >= 3)
        for (i in 0..steps) assertTrue(sawFlicker(i / steps.toFloat()) in 0.5f..1f)
    }
}
