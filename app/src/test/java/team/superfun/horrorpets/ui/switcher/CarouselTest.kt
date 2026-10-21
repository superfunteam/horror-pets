package team.superfun.horrorpets.ui.switcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // On the 540-dp-tall canvas: 16:9 is 960 dp wide, 4:3 is 720 dp. The switcher's own MAX_PITCH.
        val max = MAX_PITCH.value
        val wide = carouselPitch(width = 960f, inset = 48f, maxPitch = max)
        assertEquals(max, wide, 1e-3f)
        // Two pitches out from the centre lands on the screen edge: the next pets peek in half-way.
        assertEquals(960f, 480f + 2 * wide, 1e-3f)

        val square = carouselPitch(width = 720f, inset = 36f, maxPitch = max)
        assertTrue("4:3 pitch $square", square < max)
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
    fun onlyThePetsNearTheSpotlightAreOnStage() {
        // The centre pet and two either side; anything further out is entirely off screen and not composed.
        assertEquals(listOf(0, 1, 2, 3, 4), (0..5).filter { carouselInView(it, 2f) })
        assertEquals(listOf(1, 2, 3, 4), (0..5).filter { carouselInView(it, 2.5f) }) // mid-slide
        assertEquals(listOf(3, 4, 5), (0..5).filter { carouselInView(it, 5.08f) }) // the end-of-row nudge
        // The allocation-free bounds the switcher composes agree with it, through a whole scroll and its nudges.
        for (step in -10..60) {
            val row = step / 10f
            val shown = (0..5).filter { carouselInView(it, row) }
            assertEquals("row $row", shown, (carouselFirstInView(row, 6)..carouselLastInView(row, 6)).toList())
        }
        // At the reach a full-size pet (half-width at most 60 dp at 0.75 scale) is past the 16:9 edge.
        val pitch = carouselPitch(960f, 48f, MAX_PITCH.value)
        assertTrue(carouselX(0, CAROUSEL_REACH, 480f, pitch) + 60f <= 0f)
        assertTrue(carouselX(5, 5f - CAROUSEL_REACH, 480f, pitch) - 60f >= 960f)
    }

    @Test
    fun theNextPetBeyondEachEndIsReadiedAhead() {
        assertEquals(listOf(5), carouselPrefetch(first = 0, last = 4, count = 6)) // at the start
        assertEquals(listOf(0, 5), carouselPrefetch(first = 1, last = 4, count = 6))
        assertEquals(listOf(0), carouselPrefetch(first = 1, last = 5, count = 6)) // at the end
        assertEquals(emptyList<Int>(), carouselPrefetch(first = 0, last = 4, count = 5)) // all on stage
        // Never more than 2, so with at most 5 on stage the switcher holds at most 7 strips.
        for (row in 0..50) {
            val r = row / 10f
            val f = carouselFirstInView(r, 6)
            val l = carouselLastInView(r, 6)
            assertTrue(carouselPrefetch(f, l, 6).size <= 2)
            assertTrue(l - f + 1 + carouselPrefetch(f, l, 6).size <= 7)
        }
    }

    @Test
    fun aWiderThan16x9StageKeepsThePetsAtItsEdgesOnStage() {
        val max = MAX_PITCH.value
        // 16:9 and 4:3 keep the reach they always had.
        assertEquals(CAROUSEL_REACH, carouselReach(960f, carouselPitch(960f, 48f, max)), 0f)
        assertEquals(CAROUSEL_REACH, carouselReach(720f, carouselPitch(720f, 36f, max)), 0f)
        // A 20:9 phone (2400x1080 is 1200 dp wide on the 540-dp canvas, with a 60 dp inset): a pet just past the
        // old reach still shows at the edge, so the reach grows until a pet at it is wholly off screen.
        val pitch = carouselPitch(1200f, 60f, max)
        val reach = carouselReach(1200f, pitch)
        assertTrue("reach $reach", reach > CAROUSEL_REACH)
        assertTrue(carouselX(0, 0f + reach, 600f, pitch) + 60f <= 0f + 1e-3f)
        assertTrue(carouselX(5, 5f - reach, 600f, pitch) - 60f >= 1200f - 1e-3f)
        // Mid-slide on 20:9 every pet is on stage; the bounds agree with carouselInView at that reach too.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), (0..5).filter { carouselInView(it, 2.5f, reach) })
        for (step in -10..60) {
            val row = step / 10f
            val shown = (0..5).filter { carouselInView(it, row, reach) }
            assertEquals("row $row", shown, (carouselFirstInView(row, 6, reach)..carouselLastInView(row, 6, reach)).toList())
        }
        assertEquals(CAROUSEL_REACH, carouselReach(960f, 0f), 0f) // not laid out yet
    }

    @Test
    fun aDraggedRowFollowsTheFingerAndGivesALittlePastTheEnds() {
        // Pets 400 px apart: a finger 200 px to the left brings the row half a pet on, toward the right.
        assertEquals(2.5f, carouselDragRow(start = 2f, dx = -200f, pitch = 400f, count = 6), 1e-6f)
        assertEquals(1.5f, carouselDragRow(start = 2f, dx = 200f, pitch = 400f, count = 6), 1e-6f)
        assertEquals(2f, carouselDragRow(start = 2f, dx = 0f, pitch = 400f, count = 6), 0f)
        // Past the first pet the row gives 30% of the travel, then stops.
        assertEquals(-0.15f, carouselDragRow(start = 0f, dx = 200f, pitch = 400f, count = 6), 1e-6f)
        assertEquals(-CAROUSEL_MAX_OVERDRAG, carouselDragRow(start = 0f, dx = 4000f, pitch = 400f, count = 6), 1e-6f)
        // And past the last.
        assertEquals(5.15f, carouselDragRow(start = 5f, dx = -200f, pitch = 400f, count = 6), 1e-6f)
        assertEquals(5f + CAROUSEL_MAX_OVERDRAG, carouselDragRow(start = 5f, dx = -4000f, pitch = 400f, count = 6), 1e-6f)
        // No pitch yet: the row stays put.
        assertEquals(2f, carouselDragRow(start = 2f, dx = -200f, pitch = 0f, count = 6), 0f)
    }

    @Test
    fun aSlowReleaseSettlesOnTheNearestPet() {
        assertEquals(2, carouselFlingTarget(row = 2.4f, velocity = 0f, count = 6))
        assertEquals(3, carouselFlingTarget(row = 2.6f, velocity = 0f, count = 6))
        assertEquals(3, carouselFlingTarget(row = 2.6f, velocity = -1f, count = 6)) // too slow to be a flick
        // Let go past either end: back onto the end pet.
        assertEquals(0, carouselFlingTarget(row = -0.3f, velocity = 0f, count = 6))
        assertEquals(5, carouselFlingTarget(row = 5.3f, velocity = 0f, count = 6))
    }

    @Test
    fun aFlickGoesOnAtLeastOnePetInItsDirectionAndFurtherWhenFaster() {
        // A small drag then a flick still moves on a whole pet, either way.
        assertEquals(3, carouselFlingTarget(row = 2f, velocity = CAROUSEL_FLICK, count = 6))
        assertEquals(3, carouselFlingTarget(row = 2.1f, velocity = 2f, count = 6))
        assertEquals(1, carouselFlingTarget(row = 2f, velocity = -2f, count = 6))
        // Flicked back toward where the drag started: it goes back there, not past it.
        assertEquals(2, carouselFlingTarget(row = 2.4f, velocity = -2f, count = 6))
        assertEquals(3, carouselFlingTarget(row = 2.6f, velocity = 2f, count = 6))
        // Hard flicks carry further, but the row still stops at both ends.
        assertEquals(4, carouselFlingTarget(row = 2f, velocity = 12f, count = 6))
        assertEquals(5, carouselFlingTarget(row = 2f, velocity = 40f, count = 6))
        assertEquals(0, carouselFlingTarget(row = 2f, velocity = -40f, count = 6))
        assertEquals(0, carouselFlingTarget(row = 0f, velocity = -5f, count = 6)) // flicked at the first pet
        assertEquals(0, carouselFlingTarget(row = 0f, velocity = 5f, count = 1))
    }

    @Test
    fun aTapLandsOnThePetNearestTheFinger() {
        // Row at pet 2, spotlight at x = 1200, pets 480 px apart.
        assertEquals(2, carouselTapTarget(x = 1200f, row = 2f, centerX = 1200f, pitch = 480f, count = 6))
        assertEquals(2, carouselTapTarget(x = 1400f, row = 2f, centerX = 1200f, pitch = 480f, count = 6)) // near the centre
        assertEquals(3, carouselTapTarget(x = 1700f, row = 2f, centerX = 1200f, pitch = 480f, count = 6))
        assertEquals(1, carouselTapTarget(x = 700f, row = 2f, centerX = 1200f, pitch = 480f, count = 6))
        assertEquals(0, carouselTapTarget(x = 200f, row = 2f, centerX = 1200f, pitch = 480f, count = 6))
        // Mid-slide the tap goes by where the pets are on screen now.
        assertEquals(3, carouselTapTarget(x = 1200f, row = 2.6f, centerX = 1200f, pitch = 480f, count = 6))
        // Nothing there: left of the first pet, right of the last, or before layout.
        assertNull(carouselTapTarget(x = 500f, row = 0f, centerX = 1200f, pitch = 480f, count = 6))
        assertNull(carouselTapTarget(x = 2000f, row = 5f, centerX = 1200f, pitch = 480f, count = 6))
        assertNull(carouselTapTarget(x = 1200f, row = 0f, centerX = 1200f, pitch = 0f, count = 6))
    }

    @Test
    fun quantizeSnapsToSteps() {
        assertEquals(0.5f, quantize(0.49f, 8), 1e-6f)
        assertEquals(1f, quantize(0.97f, 8), 1e-6f)
        assertEquals(0f, quantize(0.03f, 8), 1e-6f)
    }
}
