package team.superfun.horrorpets.ui.pantry

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a meal lands beside the killer, when its bites fall, and the colors its crumbs fly in. */
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
    fun bitesSpreadAcrossTheMunching() {
        assertEquals(listOf(306L, 900L, 1494L), biteTimesMs(1_800L))
        assertEquals(listOf(0L, 0L, 0L), biteTimesMs(0L))
    }

    @Test
    fun theTossArcsFromTheBoardToTheFloor() {
        // From where it stood to where it lands, over the top: higher than both ends on the way.
        assertEquals(200f to 300f, tossAt(fromX = 200f, fromBottom = 300f, toX = 100f, toBottom = 500f, p = 0f))
        assertEquals(100f to 500f, tossAt(fromX = 200f, fromBottom = 300f, toX = 100f, toBottom = 500f, p = 1f))
        val (midX, midY) = tossAt(fromX = 200f, fromBottom = 300f, toX = 100f, toBottom = 500f, p = 0.3f)
        assertEquals(170f, midX, 0.01f)
        assertTrue("it rises before it falls", midY < 300f)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun near(expected: Color, actual: Color) {
        val close = abs(expected.red - actual.red) < 0.08f && abs(expected.green - actual.green) < 0.08f &&
            abs(expected.blue - actual.blue) < 0.08f
        assertTrue("expected about $expected, was $actual", close)
    }

    @Test
    fun theCrumbsAreTheFoodsTwoMainColors() {
        // 60% tomato red, 30% leaf green, 10% clear: red first, then green.
        val red = argb(255, 200, 40, 30)
        val green = argb(255, 60, 160, 60)
        val pixels = IntArray(100) { i -> if (i < 60) red else if (i < 90) green else 0 }
        val colors = crumbColorsOf(pixels)
        assertEquals(2, colors.size)
        near(Color(red), colors[0])
        near(Color(green), colors[1])
    }

    @Test
    fun clearPixelsAndInkyOutlinesAreNotCrumbs() {
        // A mostly see-through picture with a thick black outline round a little cheese.
        val cheese = argb(255, 240, 200, 60)
        val ink = argb(255, 10, 8, 12)
        val pixels = IntArray(1_000) { i ->
            when {
                i < 500 -> argb(30, 250, 0, 0) // nearly clear: shadow fringes, never crumbs
                i < 800 -> ink
                else -> cheese
            }
        }
        val colors = crumbColorsOf(pixels)
        near(Color(cheese), colors[0])
        colors.forEach { assertTrue("no red from the clear fringe: $it", !(it.red > 0.8f && it.green < 0.3f)) }
    }

    @Test
    fun aOneColorFoodStillGetsTwoShades() {
        val milk = argb(255, 235, 235, 225)
        val colors = crumbColorsOf(IntArray(64) { milk })
        assertEquals(2, colors.size)
        near(Color(milk), colors[0])
        assertNotEquals("a second shade, not the same color twice", colors[0], colors[1])
    }

    @Test
    fun aPictureWithNothingSolidFallsBackToCrumbBrown() {
        val colors = crumbColorsOf(IntArray(16) { 0 })
        assertEquals(2, colors.size)
        assertEquals(CrumbFallback, colors)
        assertEquals(CrumbFallback, crumbColorsOf(IntArray(0)))
    }

    @Test
    fun anInkOnlyFoodKeepsItsInk() {
        // Something all dark (a burnt crisp): its crumbs are dark, not the fallback.
        val char = argb(255, 30, 22, 18)
        val colors = crumbColorsOf(IntArray(50) { char })
        near(Color(char), colors[0])
    }
}
