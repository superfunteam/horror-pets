package team.superfun.horrorpets.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInputTest {
    @Test
    fun onlyANonTvWithATouchscreenTakesTouch() {
        assertTrue(isTouchDevice(television = false, touchscreen = true)) // a phone or tablet
        assertFalse(isTouchDevice(television = true, touchscreen = false)) // a TV
        assertFalse(isTouchDevice(television = true, touchscreen = true)) // a TV box that claims a touchscreen
        assertFalse(isTouchDevice(television = false, touchscreen = false)) // a box that isn't in TV mode
    }

    @Test
    fun touchInputNeedsATouchDeviceInTouchMode() {
        assertTrue(isTouchInput(touchDevice = true, inTouchMode = true))
        assertFalse(isTouchInput(touchDevice = true, inTouchMode = false)) // a phone driven by a gamepad
        assertFalse(isTouchInput(touchDevice = false, inTouchMode = true)) // a TV box that starts in touch mode
        assertFalse(isTouchInput(touchDevice = false, inTouchMode = false))
    }

    private fun walk(dx: Float, dy: Float = 0f, vx: Float = 0f) =
        swipeWalkDirection(dx, dy, vx, minDistance = 100f, minFlingVelocity = 1000f)

    @Test
    fun swipeLeftWalksToTheNextSceneAndRightToThePrevious() {
        assertEquals(1, walk(dx = -150f))
        assertEquals(-1, walk(dx = 150f))
        assertEquals(1, walk(dx = -100f)) // exactly far enough
    }

    @Test
    fun aShortSlowSwipeDoesNotWalk() {
        assertEquals(0, walk(dx = -60f, vx = -400f))
        assertEquals(0, walk(dx = 0f))
    }

    @Test
    fun aShortFlickWalks() {
        assertEquals(1, walk(dx = -40f, vx = -1500f))
        assertEquals(-1, walk(dx = 40f, vx = 1500f))
        // Not when the finger was let go heading back the other way.
        assertEquals(0, walk(dx = -40f, vx = 1500f))
    }

    @Test
    fun aMostlyVerticalSwipeDoesNotWalk() {
        assertEquals(0, walk(dx = -150f, dy = 120f))
        assertEquals(0, walk(dx = 150f, dy = -200f, vx = 3000f))
        assertEquals(1, walk(dx = -150f, dy = 90f)) // sideways enough
    }

    @Test
    fun touchesInTheSystemsEdgeStripsAreTheSystems() {
        // A 2400x1080 phone with gesture navigation: back-gesture strips down both sides, bar strips top and bottom.
        val edges = GestureEdges(left = 78f, top = 74f, right = 78f, bottom = 84f)
        val size = IntSize(2400, 1080)
        assertTrue(edges.contain(Offset(2f, 540f), size)) // a back swipe from the left
        assertTrue(edges.contain(Offset(2390f, 540f), size)) // and from the right
        assertTrue(edges.contain(Offset(1200f, 10f), size)) // pulling the status bar down
        assertTrue(edges.contain(Offset(1200f, 1070f), size)) // the home gesture
        assertFalse(edges.contain(Offset(1200f, 540f), size))
        assertFalse(edges.contain(Offset(78f, 74f), size)) // just inside
        assertFalse(edges.contain(Offset(2321f, 995f), size))
        // A TV has none.
        assertFalse(GestureEdges.None.contain(Offset(0f, 0f), IntSize(1920, 1080)))
    }

    @Test
    fun theSafeInsetIs5PercentUnlessTheSystemNeedsMore() {
        // A TV: no cutout, no gesture bar, so exactly 5% (the overscan inset).
        assertEquals(48f, safeInset(size = 960f, start = 0f, end = 0f), 1e-4f)
        assertEquals(27f, safeInset(size = 540f, start = 0f, end = 0f), 1e-4f)
        // A phone with its camera cutout on one side: both sides clear it, so centred layouts stay centred.
        assertEquals(63f, safeInset(size = 1200f, start = 63f, end = 0f), 1e-4f)
        assertEquals(63f, safeInset(size = 1200f, start = 0f, end = 63f), 1e-4f)
        // A cutout or gesture bar smaller than 5% changes nothing.
        assertEquals(60f, safeInset(size = 1200f, start = 20f, end = 12f), 1e-4f)
    }
}
