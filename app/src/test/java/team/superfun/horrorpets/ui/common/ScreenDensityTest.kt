package team.superfun.horrorpets.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenDensityTest {
    @Test
    fun verifiedConfigsKeepTheirDensity() {
        assertEquals(2.0f, designDensityFor(1080)!!, 1e-4f) // 1920x1080 and 1440x1080 at 320 dpi
        assertEquals(1.0f, designDensityFor(540)!!, 1e-4f) // 720x540 at 160 dpi
        assertEquals(213f / 160f, designDensityFor(720)!!, 3e-3f) // 1280x720 at 213 dpi
    }

    @Test
    fun the4x3Crt1280x960ScalesLikeA1440x1080Screen() {
        assertEquals(960 / 540f, designDensityFor(960)!!, 1e-4f)
    }

    @Test
    fun unmeasuredHeightLeavesTheDeviceDensityAlone() {
        assertNull(designDensityFor(0))
        assertNull(designDensityFor(-1))
    }

    @Test
    fun fontScaleUpToTheCapIsKept() {
        assertEquals(1.0f, cappedFontScale(1.0f), 0f)
        assertEquals(1.15f, cappedFontScale(1.15f), 0f)
    }

    @Test
    fun fontScaleAboveTheCapIsCappedAt1Point15() {
        assertEquals(1.15f, cappedFontScale(1.3f), 0f) // the Onn stick's "large text" setting
        assertEquals(1.15f, cappedFontScale(2.0f), 0f)
        assertEquals(MAX_FONT_SCALE, cappedFontScale(Float.MAX_VALUE), 0f)
    }

    @Test
    fun fontScaleBelowOneIsKept() {
        assertEquals(0.85f, cappedFontScale(0.85f), 0f)
        assertEquals(0.5f, cappedFontScale(0.5f), 0f)
    }
}
