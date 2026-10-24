package team.superfun.horrorpets.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaledClockTest {
    @Test fun aScaledClockHoldsAtZeroAndCrawlsAtATenth() {
        val c = ScaledClock()
        assertEquals(0L, c.advance(100, 0f))
        assertEquals(10L, c.advance(100, 0.1f))
        assertEquals(110L, c.advance(100, 1f))
        c.advance(5, 0.3f) // 1.5 ms: the half carries
        assertEquals("remainders carry", 113L, c.advance(5, 0.3f))
    }
}
