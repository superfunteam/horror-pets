package team.superfun.horrorpets.ui.home

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WanderTest {
    // 1920x1080: a 96 px safe inset, the stat card's right edge at 600 px, a full-size pet's half-body 138 px.
    private val wide = wanderRange(screenW = 1920f, insetPx = 96f, cardRightPx = 600f, bodyHalfPx = 138f)

    @Test
    fun theBodyStaysClearOfTheStatCardAndInsideTheRightInset() {
        assertEquals((600f + 138f) / 1920f, wide.min, 1e-6f) // about 38%: the body's left edge at the card's right edge
        assertEquals((1920f - 96f - 138f) / 1920f, wide.max, 1e-6f) // about 88%
        assertTrue(wide.min in 0.36f..0.40f)
        assertTrue(wide.max in 0.85f..0.90f)
        assertTrue(0.5f in wide.min..wide.max)
    }

    @Test
    fun aSmallerBodyMayGoFurtherEitherWay() {
        val chucky = wanderRange(1920f, 96f, 600f, bodyHalfPx = 69f)
        assertTrue(chucky.max > wide.max)
        assertTrue(chucky.min < wide.min)
    }

    @Test
    fun on4x3TheRangeStillHoldsTheMiddle() {
        // 1440x1080: a 72 px inset and a 432 px card.
        val square = wanderRange(1440f, 72f, 72f + 432f, 138f)
        assertTrue(0.5f in square.min..square.max)
        assertTrue(square.max - square.min > 0.3f)
    }

    @Test
    fun aScreenTooNarrowToWanderPinsThePetToOneSpot() {
        val cramped = wanderRange(screenW = 600f, insetPx = 30f, cardRightPx = 300f, bodyHalfPx = 150f)
        assertEquals(cramped.min, cramped.max, 0f)
        assertEquals(cramped.min, wanderTarget(0.5f, cramped, 0.2f, toCenter = false, preferRight = true), 0f)
    }

    @Test
    fun aStrollGoesTheChosenWayByTheChosenStep() {
        assertEquals(0.65f, wanderTarget(0.5f, wide, 0.15f, toCenter = false, preferRight = true), 1e-6f)
        assertEquals(0.4f, wanderTarget(0.5f, wide, 0.1f, toCenter = false, preferRight = false), 1e-6f)
        // A step longer than the room left that way stops at the range's edge.
        assertEquals(wide.min, wanderTarget(0.5f, wide, 0.15f, toCenter = false, preferRight = false), 1e-6f)
    }

    @Test
    fun aStrollWithNoRoomAheadTurnsAround() {
        // At 0.84 there is only 0.04 to the right edge of the range: it heads left instead.
        assertEquals(0.69f, wanderTarget(0.84f, wide, 0.15f, toCenter = false, preferRight = true), 1e-6f)
        // Near the stat card, wanting to go left: right instead.
        assertEquals(0.55f, wanderTarget(0.4f, wide, 0.15f, toCenter = false, preferRight = false), 1e-6f)
    }

    @Test
    fun aStrollNeverLeavesTheRange() {
        val r = WanderRange(0.45f, 0.55f)
        for (from in listOf(0.3f, 0.45f, 0.5f, 0.55f, 0.9f)) for (step in listOf(0.1f, 0.16f, 0.22f)) {
            for (right in listOf(true, false)) for (center in listOf(true, false)) {
                val t = wanderTarget(from, r, step, center, right)
                assertTrue("$from $step $right $center -> $t", t in r.min..r.max)
            }
        }
    }

    @Test
    fun headingHomeWalksTowardTheMiddleWithoutOvershooting() {
        assertEquals(0.6f, wanderTarget(0.8f, wide, 0.2f, toCenter = true, preferRight = true), 1e-6f)
        assertEquals(0.5f, wanderTarget(0.6f, wide, 0.2f, toCenter = true, preferRight = true), 1e-6f)
        assertEquals(0.5f, wanderTarget(0.4f, wide, 0.2f, toCenter = true, preferRight = false), 1e-6f)
        // Already in the middle: it just takes a stroll.
        assertEquals(0.62f, wanderTarget(0.5f, wide, 0.12f, toCenter = true, preferRight = true), 1e-6f)
    }

    @Test
    fun theMiddleIsInsideTheRangeEvenWhenTheRangeIsOffCentre() {
        val right = WanderRange(0.6f, 0.9f)
        assertEquals(0.6f, wanderTarget(0.8f, right, 0.3f, toCenter = true, preferRight = true), 1e-6f)
    }

    @Test
    fun aPetOutsideTheRangeStartsFromItsEdge() {
        // The window shrank (4:3) with the pet standing further right than the new range allows.
        val narrow = WanderRange(0.35f, 0.8f)
        assertEquals(0.65f, wanderTarget(0.95f, narrow, 0.15f, toCenter = false, preferRight = true), 1e-6f)
    }

    @Test
    fun walkingSpeedMatchesTheStride() {
        // 0.55 drawn heights a second: a 500 px pet covers 275 px a second.
        assertEquals(1000L, wanderDurationMs(275f, drawnHeightPx = 500f, walkSpeed = 1f))
        // Chucky is drawn half as tall, so the same distance takes twice as long.
        assertEquals(2000L, wanderDurationMs(275f, drawnHeightPx = 250f, walkSpeed = 1f))
        // Michael walks at 0.6 of the pace.
        assertTrue(abs(wanderDurationMs(275f, 500f, 0.6f) - 1667L) <= 1L)
        assertEquals(0L, wanderDurationMs(275f, 0f, 1f))
        assertEquals(0L, wanderDurationMs(-5f, 500f, 1f))
    }
}
