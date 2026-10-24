package team.superfun.horrorpets.ui.pantry

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.PantryItem.EGG
import team.superfun.horrorpets.model.PantryItem.GRILL
import team.superfun.horrorpets.model.PantryItem.RAW_STEAK

/** The cook show's timeline: the inputs meet, a poof, the dish pops up, then flies to the shelf; and a refusal's shake. */
class CookShowTest {
    @Test
    fun thePhasesComeInOrderAndTheShowIsShort() {
        val phases = (0L..CookTimeline.TOTAL_MS + 100).map { cookPhaseAt(it) }
        assertEquals(
            listOf(CookPhase.CONVERGE, CookPhase.POOF, CookPhase.REVEAL, CookPhase.FLY, CookPhase.DONE),
            phases.distinct(),
        )
        assertEquals("never back to an earlier phase", phases, phases.sortedBy { it.ordinal })
        assertTrue("about 1.2 to 1.6 s", CookTimeline.TOTAL_MS in 1_200L..1_600L)
        assertEquals(CookPhase.DONE, cookPhaseAt(CookTimeline.TOTAL_MS))
        assertEquals(CookPhase.FLY, cookPhaseAt(CookTimeline.TOTAL_MS - 1))
    }

    @Test
    fun somethingIsAlwaysOnShowUntilTheDishLands() {
        for (ms in 0 until CookTimeline.TOTAL_MS) {
            val inputs = convergeAt(ms) != null
            val poof = poofAt(ms) != null
            val dish = popAt(ms) != null
            assertTrue("at $ms ms", inputs || poof || dish)
            assertTrue("at $ms ms the inputs are gone when the dish shows", !(inputs && dish))
        }
        assertNull(convergeAt(CookTimeline.TOTAL_MS))
        assertNull(poofAt(CookTimeline.TOTAL_MS))
    }

    @Test
    fun theInputsMeetInTheMiddleThenThePoofCoversThem() {
        assertEquals(0f, convergeAt(0)!!, 0.001f)
        assertTrue(convergeAt(CookTimeline.CONVERGE_MS - 1)!! > 0.95f)
        // Eased in: they speed up as they come together.
        var last = -1f
        for (ms in 0 until CookTimeline.CONVERGE_MS) {
            val c = convergeAt(ms)!!
            assertTrue(c >= last)
            last = c
        }
        assertTrue(convergeAt(CookTimeline.CONVERGE_MS / 2)!! < 0.5f)
        assertNull(convergeAt(CookTimeline.CONVERGE_MS))
        assertEquals("the poof starts as they meet", 0f, poofAt(CookTimeline.CONVERGE_MS)!!, 0.001f)
        assertNull(poofAt(CookTimeline.CONVERGE_MS - 1))
        assertTrue("the dish pops up inside the smoke", poofAt(CookTimeline.REVEAL_AT) != null)
        // The screen plays the poof's sound as the poof shows (the view model only plays the way to cook's).
        assertEquals(0f, poofAt(CookTimeline.POOF_SOUND_MS)!!, 0.001f)
        assertNull(poofAt(CookTimeline.POOF_SOUND_MS - 1))
    }

    @Test
    fun theDishPopsPastItsSizeAndSettles() {
        assertNull(popAt(CookTimeline.REVEAL_AT - 1))
        val pops = (CookTimeline.REVEAL_AT until CookTimeline.TOTAL_MS).map { popAt(it)!! }
        assertTrue("it starts small", pops.first() < 0.5f)
        assertTrue("it overshoots", pops.max() > 1.05f)
        assertEquals("and settles at full size", 1f, popAt(CookTimeline.FLY_AT)!!, 0.001f)
    }

    @Test
    fun theDishFliesInAnArcToItsSlot() {
        assertEquals(0f, flyAt(CookTimeline.FLY_AT), 0.001f)
        assertEquals(0f, flyAt(0), 0.001f)
        assertEquals(1f, flyAt(CookTimeline.TOTAL_MS), 0.001f)
        assertEquals(1f, flyAt(CookTimeline.TOTAL_MS + 500), 0.001f)
        val from = Offset(500f, 300f)
        val to = Offset(300f, 700f)
        assertEquals(from, flightPoint(from, to, 0f, lift = 80f))
        assertEquals(to, flightPoint(from, to, 1f, lift = 80f))
        val mid = flightPoint(from, to, 0.5f, lift = 80f)
        assertEquals(400f, mid.x, 0.01f)
        assertEquals("lifted above the straight line at its middle", 500f - 80f, mid.y, 0.01f)
    }

    @Test
    fun eachInputConvergesFromThePlaceItStoodIn() {
        // Put on the board steak first, then the grill into the left place it left empty: the grill is on the left.
        assertEquals(listOf(1, 0), convergeFrom(listOf(RAW_STEAK, GRILL), listOf(GRILL, RAW_STEAK)))
        assertEquals(listOf(0, 1), convergeFrom(listOf(RAW_STEAK, GRILL), listOf(RAW_STEAK, GRILL)))
        // Two of the same: one from each place.
        assertEquals(listOf(0, 1), convergeFrom(listOf(EGG, EGG), listOf(EGG, EGG)))
        // Nothing known of the board (a cook too quick to draw): in order.
        assertEquals(listOf(0, 1), convergeFrom(listOf(RAW_STEAK, GRILL), emptyList()))
        assertEquals(listOf(0, 1), convergeFrom(listOf(RAW_STEAK, GRILL), listOf(EGG, null)))
    }

    @Test
    fun aRefusedCookShakesTheBoardAndStops() {
        assertEquals(0f, shakeAt(0), 0.001f)
        assertEquals(0f, shakeAt(SHAKE_MS), 0.001f)
        assertEquals(0f, shakeAt(SHAKE_MS + 200), 0.001f)
        assertEquals(0f, shakeAt(-5), 0.001f)
        val swings = (0 until SHAKE_MS).map { shakeAt(it) }
        assertTrue(swings.all { abs(it) <= 1f })
        assertTrue("it swings both ways", swings.any { it > 0.5f } && swings.any { it < -0.5f })
        assertTrue("and dies away", abs(shakeAt(SHAKE_MS - 30)) < 0.2f)
        assertTrue(SHAKE_MS in 250L..600L)
    }

    @Test
    fun thePoofIsDrawnFromAFixedSetOfPuffs() {
        val a = poofPuffs(seed = 3)
        assertEquals(a, poofPuffs(seed = 3))
        assertTrue(a.size >= 6)
        assertNotNull(a.firstOrNull { it.reach > 0f })
    }
}
