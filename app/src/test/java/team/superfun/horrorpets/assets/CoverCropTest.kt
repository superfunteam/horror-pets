package team.superfun.horrorpets.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverCropTest {
    @Test
    fun wide16x9From3x2() {
        val c = coverCrop(1536, 1024, 1920, 1080)
        assertEquals(1.25f, c.scale, 1e-4f)
        assertEquals(-100f, c.offsetY, 1e-3f)
        assertEquals(0f, c.offsetX, 1e-3f)
    }

    @Test
    fun fourThreeCropsSides() {
        val c = coverCrop(1536, 1024, 1440, 1080)
        assertEquals(1.0547f, c.scale, 0.001f)
        assertTrue("offsetX ${c.offsetX}", c.offsetX < 0f)
        assertEquals(0f, c.offsetY, 1e-3f)
        // Centered: the same amount is cropped off both sides.
        assertEquals(1440f, 2 * c.offsetX + 1536 * c.scale, 1e-2f)
    }

    @Test
    fun crt640x480FloorStaysAboveInset() {
        val c = coverCrop(1536, 1024, 640, 480, floorY = 0.97f)
        assertTrue("floor at ${floorYOnScreen(c, 1024, 0.97f)}", floorYOnScreen(c, 1024, 0.97f) <= 432f)
        assertCovers(c, 1536, 1024, 640, 480)
    }

    @Test
    fun floorYOnScreenAppliesScaleAndOffset() {
        val c = Crop(scale = 1.25f, offsetX = 0f, offsetY = -100f)
        assertEquals(-100f + 0.5f * 1024 * 1.25f, floorYOnScreen(c, 1024, 0.5f), 1e-3f)
    }

    @Test
    fun floorAlreadyAboveTheInsetLeavesTheCenteredCropAlone() {
        val plain = coverCrop(1920, 1280, 1920, 1080)
        val withFloor = coverCrop(1920, 1280, 1920, 1080, floorY = 0.7531f)
        assertEquals(plain, withFloor)
        assertEquals(-100f, withFloor.offsetY, 1e-3f)
    }

    @Test
    fun floorIsPushedUpUsingTheOverflowWhenThereIsRoom() {
        // 16:9 from 3:2 overflows 200px vertically; floor 0.90 sits at 1052 centered, 972 needs 80px more.
        val c = coverCrop(1536, 1024, 1920, 1080, floorY = 0.90f)
        assertEquals(1.25f, c.scale, 1e-4f)
        assertEquals(-180f, c.offsetY, 0.05f)
        assertEquals(0.90f * 1080f, floorYOnScreen(c, 1024, 0.90f), 0.05f)
        assertTrue(floorYOnScreen(c, 1024, 0.90f) <= 0.90f * 1080f)
        assertCovers(c, 1536, 1024, 1920, 1080)
    }

    @Test
    fun pushUpNeverUncoversTheBottomEdge() {
        // 0.95 needs 144px of push but only 100px of overflow remains: the image would no longer cover.
        val c = coverCrop(1536, 1024, 1920, 1080, floorY = 0.95f)
        assertCovers(c, 1536, 1024, 1920, 1080)
        assertTrue(floorYOnScreen(c, 1024, 0.95f) <= 0.90f * 1080f)
    }

    @Test
    fun alwaysCoversAndKeepsTheFloorInsideTheInsetAcrossAspects() {
        val floors = listOf(0.5f, 0.7531f, 0.85f, 0.9f, 0.95f, 0.97f)
        val views = listOf(1920 to 1080, 1440 to 1080, 1280 to 720, 640 to 480, 1080 to 1080, 1080 to 1920)
        for (floorY in floors) for ((w, h) in views) {
            val c = coverCrop(1920, 1280, w, h, floorY)
            assertCovers(c, 1920, 1280, w, h)
            assertTrue(
                "floorY $floorY on ${w}x$h at ${floorYOnScreen(c, 1280, floorY)}",
                floorYOnScreen(c, 1280, floorY) <= 0.90f * h,
            )
        }
    }

    @Test
    fun anUnreachableFloorLineFallsBackToThePlainCrop() {
        // A floor at or below the image's bottom edge can never be lifted above 0.90*viewH while covering.
        assertEquals(coverCrop(1536, 1024, 1920, 1080), coverCrop(1536, 1024, 1920, 1080, floorY = 1.0f))
        assertEquals(coverCrop(1536, 1024, 1920, 1080), coverCrop(1536, 1024, 1920, 1080, floorY = Float.NaN))
    }

    private fun assertCovers(c: Crop, imgW: Int, imgH: Int, viewW: Int, viewH: Int) {
        val eps = 0.01f
        assertTrue("left edge ${c.offsetX}", c.offsetX <= eps)
        assertTrue("top edge ${c.offsetY}", c.offsetY <= eps)
        assertTrue("right edge ${c.offsetX + imgW * c.scale} < $viewW", c.offsetX + imgW * c.scale >= viewW - eps)
        assertTrue("bottom edge ${c.offsetY + imgH * c.scale} < $viewH", c.offsetY + imgH * c.scale >= viewH - eps)
    }
}
