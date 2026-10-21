package team.superfun.horrorpets.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePlacementTest {
    // 1280x960 at the 720-dp-wide 4:3 canvas (1.778 px/dp): the card's right edge at 252 dp, a 36 dp inset.
    private val px = 960f / 540f
    private val minLeft = 252f * px + 8f * px
    private val maxRight = 1280f - 36f * px
    private val tail = 53f * px // tail centre from the bubble's left edge when it hangs right of the head
    private val width = 330f * px // "TOO HUNGRY TO TRAIN" at font scale 1.15

    @Test
    fun inTheOpenTheBubbleHangsRightOfTheHead() {
        val head = 640f
        assertEquals(head - tail, bubbleLeft(head, width, tail, minLeft, maxRight), 1e-3f)
    }

    @Test
    fun theBubbleNeverReachesUnderTheStatCardNorPastTheRightInset() {
        for (head in listOf(minLeft, 460f, 560f, 640f, 720f, 860f, 940f, 1050f, 1150f, maxRight)) {
            val left = bubbleLeft(head, width, tail, minLeft, maxRight)
            assertTrue("head $head: left $left under the card", left >= minLeft - 1e-3f)
            assertTrue("head $head: right ${left + width} past the inset", left + width <= maxRight + 1e-3f)
        }
    }

    @Test
    fun theTailPointsAtTheHeadWhereverTheBubbleSits() {
        val edge = 16f * px + 13f * px
        for (head in listOf(560f, 640f, 860f, 1050f, 1150f)) {
            val left = bubbleLeft(head, width, tail, minLeft, maxRight)
            val tailX = bubbleTailX(head, left, width, edge)
            assertEquals("head $head", head, left + tailX, 1e-3f)
        }
        // Near the right inset the bubble slides left and the tail moves toward its right end.
        val right = bubbleLeft(1150f, width, tail, minLeft, maxRight)
        assertEquals(maxRight - width, right, 1e-3f)
        assertTrue(bubbleTailX(1150f, right, width, edge) > width / 2f)
    }

    @Test
    fun theTailStaysOnTheStraightEdge() {
        val edge = 29f
        assertEquals(edge, bubbleTailX(headX = 100f, left = 95f, width = 400f, edge = edge), 0f)
        assertEquals(400f - edge, bubbleTailX(headX = 600f, left = 95f, width = 400f, edge = edge), 0f)
    }

    @Test
    fun aBubbleWiderThanTheGapStartsAtTheCard() {
        // Should never happen (the bubble's width is capped to the gap), but the card still wins.
        assertEquals(minLeft, bubbleLeft(700f, maxRight - minLeft + 50f, tail, minLeft, maxRight), 1e-3f)
    }
}
