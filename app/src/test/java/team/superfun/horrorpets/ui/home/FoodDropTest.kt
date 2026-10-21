package team.superfun.horrorpets.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a feed's food lands beside the pet, and when its bites fall. */
class FoodDropTest {
    private val screenW = 1920f
    private val inset = 96f

    @Test
    fun aPetLeftOfCentreGetsItsFoodOnItsRight() {
        val (x, petSide) = foodLanding(petX = 800f, bodyHalf = 60f, foodW = 160f, screenW = screenW, inset = inset)
        assertEquals(800f + 60f + 88f, x, 0.01f)
        assertEquals("the pet is on the food's left", -1, petSide)
    }

    @Test
    fun aPetRightOfCentreGetsItsFoodOnItsLeft() {
        val (x, petSide) = foodLanding(petX = 1300f, bodyHalf = 60f, foodW = 160f, screenW = screenW, inset = inset)
        assertEquals(1300f - 60f - 88f, x, 0.01f)
        assertEquals(1, petSide)
    }

    @Test
    fun theFoodStaysInsideTheSafeInset() {
        val (x, _) = foodLanding(petX = 950f, bodyHalf = 900f, foodW = 160f, screenW = screenW, inset = inset)
        assertEquals(screenW - inset - 80f, x, 0.01f)
        val (narrow, _) = foodLanding(petX = 10f, bodyHalf = 0f, foodW = 160f, screenW = 200f, inset = 96f)
        assertTrue("never past the left inset, even on a screen too narrow for both", narrow >= 96f + 80f)
    }

    @Test
    fun theGloveComesFromThePetsRoomierSide() {
        assertEquals("from the right for a pet left of centre", 1, handSide(petX = 700f, screenW = screenW))
        assertEquals(1, handSide(petX = 960f, screenW = screenW))
        assertEquals("from the left for a pet right of centre", -1, handSide(petX = 1300f, screenW = screenW))
    }

    @Test
    fun bitesSpreadAcrossTheMunching() {
        assertEquals(listOf(306L, 900L, 1494L), biteTimesMs(1_800L))
        assertEquals(listOf(0L, 0L, 0L), biteTimesMs(0L))
    }
}
