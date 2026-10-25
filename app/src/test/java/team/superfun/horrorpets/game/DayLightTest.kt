package team.superfun.horrorpets.game

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayLightTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val chicago = TimeZone.getTimeZone("America/Chicago")
    private val hour = Tuning.HOUR_MS

    /** The epoch ms of [hourOfDay]:[minute] UTC on [day] of [month] (1 is January), 2026. */
    private fun utcAt(month: Int, day: Int, hourOfDay: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2026, month - 1, day, hourOfDay, minute)
        }.timeInMillis

    @Test
    fun theHoursHaveTheirLight() {
        val expected = mapOf(
            4 to DayLight.NIGHT,
            5 to DayLight.DAWN,
            6 to DayLight.DAWN,
            7 to DayLight.DAY,
            17 to DayLight.DAY,
            18 to DayLight.DUSK,
            20 to DayLight.DUSK,
            21 to DayLight.NIGHT,
            23 to DayLight.NIGHT,
        )
        for ((h, light) in expected) assertEquals("$h:00", light, dayLightAt(h))
    }

    @Test
    fun sleepIsElevenToSix() {
        assertFalse(isSleepHour(22))
        assertTrue(isSleepHour(23))
        assertTrue(isSleepHour(0))
        assertTrue(isSleepHour(5))
        assertFalse(isSleepHour(6))
    }

    @Test
    fun localHourFollowsTheZone() {
        // July, so Chicago is on daylight time (UTC-5): 03:00 UTC on the 15th is 22:00 there on the 14th.
        val threeUtc = utcAt(7, 15, 3)
        assertEquals(3, localHour(threeUtc, utc))
        assertEquals(22, localHour(threeUtc, chicago))
    }

    @Test
    fun sleepHoursAcrossMidnight() {
        assertEquals(7.0, sleepHoursBetween(utcAt(7, 10, 22), utcAt(7, 11, 8), utc), 1e-9)
        // Two nights: 20:00 to 08:00 the day after next.
        assertEquals(14.0, sleepHoursBetween(utcAt(7, 10, 20), utcAt(7, 10, 20) + 36 * hour, utc), 1e-9)
        // The partial first and last hours count by the minute.
        assertEquals(1.0, sleepHoursBetween(utcAt(7, 10, 23, 30), utcAt(7, 11, 0, 30), utc), 1e-9)
        assertEquals(0.5, sleepHoursBetween(utcAt(7, 11, 5, 30), utcAt(7, 11, 6, 30), utc), 1e-9)
        assertEquals(0.0, sleepHoursBetween(utcAt(7, 10, 12), utcAt(7, 10, 13), utc), 0.0)
        // A clock that went backwards: nothing, never a negative count.
        assertEquals(0.0, sleepHoursBetween(utcAt(7, 11, 8), utcAt(7, 10, 22), utc), 0.0)
    }

    @Test
    fun aNightAcrossAClockChangeIsAsLongAsItReallyWas() {
        // Chicago falls back at 02:00 CDT on 2026-11-01, so 23:00 to 06:00 that night is eight real hours, from
        // 22:00 CDT (03:00 UTC) to 08:00 CST (14:00 UTC).
        assertEquals(8.0, sleepHoursBetween(utcAt(11, 1, 3), utcAt(11, 1, 14), chicago), 1e-9)
        // It springs forward at 02:00 CST on 2026-03-08: that night is six, from 22:00 CST (04:00 UTC) to 08:00 CDT
        // (13:00 UTC).
        assertEquals(6.0, sleepHoursBetween(utcAt(3, 8, 4), utcAt(3, 8, 13), chicago), 1e-9)
    }
}
