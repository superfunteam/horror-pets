package team.superfun.horrorpets.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the sleeping killer's Zs rise, and how far along each one is. */
class SleepZsTest {

    /** The Zs [forEachSleepZ] gives, in its order. */
    private fun sleepZsAt(elapsedMs: Long, everyMs: Long = Z_EVERY_MS): List<Float> =
        buildList { forEachSleepZ(elapsedMs, everyMs) { add(it) } }

    private fun assertZs(expected: List<Float>, actual: List<Float>) {
        assertEquals("how many: $actual", expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-4f) }
    }

    @Test
    fun aZRisesEveryOnePointSixSecondsForTwo() {
        assertZs(listOf(0f), sleepZsAt(0L))
        assertZs(listOf(0.5f), sleepZsAt(1_000L))
        // The next one starts while the first is still going up: oldest first.
        assertZs(listOf(0.8f, 0f), sleepZsAt(1_600L))
        assertZs(listOf(0.9995f, 0.1995f), sleepZsAt(1_999L))
        // Two seconds up, the first is gone.
        assertZs(listOf(0.2f), sleepZsAt(2_000L))
        assertZs(listOf(0.8f, 0f), sleepZsAt(3_200L))
    }

    @Test
    fun neverMoreThanThreeAtOnce() {
        // Were they to come faster than they fade, the newest three show.
        assertZs(listOf(0.4f, 0.2f, 0f), sleepZsAt(1_200L, everyMs = 400L))
    }

    @Test
    fun nothingBeforeTheStart() {
        assertZs(emptyList(), sleepZsAt(-1L))
    }
}
