package team.superfun.horrorpets.ui.switcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselTest {
    @Test
    fun theSelectedPetSitsAtTheCentreAndTheRowShiftsWithIt() {
        // Scrolled to pet 2: pet 2 is centred, its neighbours a pitch either side.
        assertEquals(480f, carouselX(2, 2f, 480f, 240f), 0f)
        assertEquals(240f, carouselX(1, 2f, 480f, 240f), 0f)
        assertEquals(720f, carouselX(3, 2f, 480f, 240f), 0f)
        assertEquals(1200f, carouselX(5, 2f, 480f, 240f), 0f) // off screen, waiting to slide in
        // Halfway through a slide from 2 to 3, the whole row has moved half a pitch left.
        assertEquals(360f, carouselX(2, 2.5f, 480f, 240f), 0f)
        assertEquals(600f, carouselX(3, 2.5f, 480f, 240f), 0f)
    }

    @Test
    fun movesStopAtBothEndsWithoutWrapping() {
        assertEquals(1, carouselStep(0, 1, 6))
        assertEquals(0, carouselStep(0, -1, 6))
        assertEquals(5, carouselStep(5, 1, 6))
        assertEquals(4, carouselStep(5, -1, 6))
        assertEquals(0, carouselStep(0, 1, 1))
    }

    @Test
    fun neighboursShrinkWithDistance() {
        assertEquals(1f, carouselScale(0f), 1e-6f)
        assertEquals(0.85f, carouselScale(1f), 1e-6f)
        assertEquals(0.75f, carouselScale(2f), 1e-6f)
        assertEquals(0.75f, carouselScale(3.5f), 1e-6f)
        assertEquals(0.925f, carouselScale(0.5f), 1e-6f)
        assertEquals(0.85f, carouselScale(-1f), 1e-6f) // either side
        // Shrinks monotonically, so a slide never pops.
        var last = 2f
        for (i in 0..40) {
            val s = carouselScale(i / 10f)
            assertTrue(s <= last)
            last = s
        }
    }

    @Test
    fun onlyTheCentredPetIsLit() {
        assertEquals(1f, carouselLit(0f), 0f)
        assertEquals(0.5f, carouselLit(0.5f), 0f)
        assertEquals(0f, carouselLit(1f), 0f)
        assertEquals(0f, carouselLit(-2f), 0f)
    }

    @Test
    fun labelsFadeWithDistanceAndAreGoneAtTheEdges() {
        assertEquals(1f, carouselLabelAlpha(0f), 1e-6f)
        assertEquals(0.5f, carouselLabelAlpha(1f), 1e-6f)
        assertEquals(0f, carouselLabelAlpha(1.8f), 1e-6f)
        assertEquals(0f, carouselLabelAlpha(2f), 1e-6f)
        assertEquals(0.75f, carouselLabelAlpha(0.5f), 1e-6f)
    }

    @Test
    fun pitchShowsThreeAcrossOn16x9AndKeepsNeighbourLabelsInsideTheInsetOn4x3() {
        // On the 540-dp-tall canvas: 16:9 is 960 dp wide, 4:3 is 720 dp.
        val wide = carouselPitch(width = 960f, inset = 48f, maxPitch = 240f)
        assertEquals(240f, wide, 1e-3f)
        // Two pitches out from the centre lands on the screen edge: the next pets peek in half-way.
        assertEquals(960f, 480f + 2 * wide, 1e-3f)

        val square = carouselPitch(width = 720f, inset = 36f, maxPitch = 240f)
        assertTrue("4:3 pitch $square", square < 240f)
        // A neighbour's label (CAROUSEL_LABEL_SHARE of the pitch) stays inside the safe inset.
        val labelHalf = square * CAROUSEL_LABEL_SHARE / 2f
        assertTrue(360f - square - labelHalf >= 36f - 1e-3f)
        // And the pets two away are fully off screen.
        assertTrue(360f - 2 * square + square * 0.3f < 0f)
    }

    @Test
    fun theHaloFollowsTheBodyScaleOfWhoeverIsUnderTheSpotlight() {
        val bodies = listOf(1f, 1f, 1f, 1f, 0.5f, 1f)
        assertEquals(0.5f, bodyUnderSpot(bodies, 4f), 0f)
        assertEquals(0.75f, bodyUnderSpot(bodies, 3.5f), 1e-6f)
        assertEquals(1f, bodyUnderSpot(bodies, 0f), 0f)
        assertEquals(1f, bodyUnderSpot(bodies, -0.2f), 0f) // a bump past the first pet
        assertEquals(1f, bodyUnderSpot(bodies, 5.1f), 0f) // a bump past the last
        assertEquals(1f, bodyUnderSpot(emptyList(), 0f), 0f)
    }

    @Test
    fun quantizeSnapsToSteps() {
        assertEquals(0.5f, quantize(0.49f, 8), 1e-6f)
        assertEquals(1f, quantize(0.97f, 8), 1e-6f)
        assertEquals(0f, quantize(0.03f, 8), 1e-6f)
    }
}
