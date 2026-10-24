package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.SpecialStyle
import team.superfun.horrorpets.model.spec

/** Jaws's Oxygen Tank: the tank's flight from his mouth, its landing on the sound's explosion, and the blast. */
class OxygenTankTest {
    private val tank = SpecialStyle.OXYGEN_TANK

    @Test
    fun theTankLandsAsHisSoundExplodes() {
        // special_jaws explodes 780 ms in. The tank lands, and the blow (number, flash, HP drain, daze) with it, then.
        val land = specialLandsAtMs(tank)
        assertEquals(780L, land)
        assertEquals(PetId.JAWS.spec.specialHitMs, land)
        assertEquals(TANK_FLIGHT_MS.toLong(), land)
        assertEquals(tank, PetId.JAWS.spec.special)
        // So the clip starts at the impact, as the tank leaves his mouth: its hiss and whoosh are the flight.
        assertEquals(0L, specialSoundAtMs(land, PetId.JAWS.spec.specialHitMs, crit = false))
        // A crit's sting plays with the blast instead, as for every special.
        assertEquals(land, specialSoundAtMs(land, PetId.JAWS.spec.specialHitMs, crit = true))
    }

    @Test
    fun theEffectRunsOnPastTheLandingForTheBlast() {
        assertEquals(TANK_FLIGHT_MS + TANK_BLAST_MS, specialMs(tank))
        // The blast gets most of a second to burn and smoke, not the 170 ms a 950 ms effect would leave it.
        assertTrue("$TANK_BLAST_MS", TANK_BLAST_MS in 900..1400)
        for (style in SpecialStyle.entries - tank) assertEquals("$style", SPECIAL_MS, specialMs(style))
    }

    @Test
    fun theTankFliesOnTheEffectsClockAndLandsAt780() {
        assertEquals(0f, tankFlight(0f), 0f)
        assertEquals(0.5f, tankFlight(390f), 1e-6f)
        assertEquals(1f, tankFlight(780f), 0f)
        assertEquals(1f, tankFlight(1500f), 0f)
        // The blast starts the moment it lands and has burnt out by the end of the effect.
        assertTrue(tankBlast(779f) < 0f)
        assertEquals(0f, tankBlast(780f), 0f)
        assertEquals(1f, tankBlast(specialMs(tank).toFloat()), 1e-6f)
        // The effect's clock runs 0..1 over its whole length: the landing is that far in.
        assertEquals(780f, tankMsAt(TANK_FLIGHT_MS / specialMs(tank).toFloat()), 0.5f)
    }

    @Test
    fun theTankArcsFromHisMouthToTheTarget() {
        val (fx, fy, tx, ty, arc) = listOf(400f, 300f, 700f, 260f, 120f)
        assertEquals(fx, tankX(fx, tx, 0f), 0f)
        assertEquals(fy, tankY(fy, ty, arc, 0f), 0f)
        assertEquals(tx, tankX(fx, tx, 1f), 0f)
        assertEquals(ty, tankY(fy, ty, arc, 1f), 0f)
        // Half way, it is [arc] above the straight line's middle: a lob, not a throw.
        assertEquals((fx + tx) / 2f, tankX(fx, tx, 0.5f), 1e-3f)
        assertEquals((fy + ty) / 2f - arc, tankY(fy, ty, arc, 0.5f), 1e-3f)
        // It always moves on toward the target, and rides above the straight line all the way.
        var lastX = fx
        for (i in 1 until 100) {
            val f = i / 100f
            val x = tankX(fx, tx, f)
            assertTrue("$f", x > lastX)
            lastX = x
            val line = fy + (ty - fy) * f
            assertTrue("$f", tankY(fy, ty, arc, f) < line)
        }
        // Thrown right to left (Jaws as the CPU), it travels left.
        assertTrue(tankX(700f, 400f, 0.6f) < tankX(700f, 400f, 0.4f))
    }

    @Test
    fun aShortThrowStillLobsHigh() {
        // On a widescreen the lob rises with the distance; on a 4:3 screen, where he stands close, it still goes up and
        // drops onto the target rather than hanging in front of its face.
        val cellH = 205f
        assertEquals(TANK_ARC * 300f, tankArc(300f, cellH), 1e-3f)
        assertEquals(tankArc(-300f, cellH), tankArc(300f, cellH), 0f)
        assertTrue(tankArc(110f, cellH) >= 0.4f * cellH)
        assertTrue(tankArc(110f, cellH) > TANK_ARC * 110f)
    }

    @Test
    fun itSpinsTheWayItFlies() {
        assertEquals(0f, tankSpinDeg(0f, dir = 1f), 0f)
        assertTrue(tankSpinDeg(1f, dir = 1f) >= 360f) // at least a full turn on the way
        assertEquals(-tankSpinDeg(0.7f, dir = 1f), tankSpinDeg(0.7f, dir = -1f), 0f)
    }

    @Test
    fun itLeavesFromHisMouthAheadOfHim() {
        // His mouth is ahead of his feet, toward the other fighter, and below the middle of his body.
        val cellH = 400f
        assertTrue(tankMouthX(500f, cellH, dir = 1f) > 500f)
        assertTrue(tankMouthX(500f, cellH, dir = -1f) < 500f)
        assertEquals(500f - tankMouthX(500f, cellH, -1f), tankMouthX(500f, cellH, 1f) - 500f, 1e-3f)
        val y = tankMouthY(floorY = 800f, cellH = cellH)
        assertTrue(y < 800f)
        assertTrue(y > 800f - cellH * 0.45f)
    }

    @Test
    fun theFireballFlaresRollsAndBurnsDownToSmoke() {
        // It swells fast, then keeps rolling out more slowly.
        assertEquals(0f, fireballReach(0f), 0f)
        assertTrue(fireballReach(0.15f) > 0.6f)
        assertTrue(fireballReach(1f) >= fireballReach(0.5f))
        // White-hot at the burst, burnt out well before the end.
        assertEquals(1f, fireballHeat(0f), 0f)
        assertEquals(0f, fireballHeat(0.8f), 0f)
        // The smoke takes over as it burns down, then clears by the end.
        assertEquals(0f, smokeAlpha(0f), 0f)
        assertTrue(smokeAlpha(0.4f) > 0.4f)
        assertEquals(0f, smokeAlpha(1f), 1e-6f)
        for (i in 0..100) {
            val b = i / 100f
            assertTrue("$b", fireballHeat(b) in 0f..1f)
            assertTrue("$b", smokeAlpha(b) in 0f..1f)
        }
    }

    @Test
    fun theBlastShakesTheScreenHarderThanACrit() {
        val jolt = specialJolt(tank)
        assertNotNull(jolt)
        jolt!!
        assertTrue("${jolt.shakeMs}", jolt.shakeMs >= 600)
        assertTrue("${jolt.flashAlpha}", jolt.flashAlpha >= 0.7f)
        assertTrue("${jolt.flashMs}", jolt.flashMs in 300..700)
        for (style in SpecialStyle.entries - tank) assertNull("$style", specialJolt(style))
    }
}
