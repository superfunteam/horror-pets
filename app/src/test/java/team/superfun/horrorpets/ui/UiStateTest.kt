package team.superfun.horrorpets.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.Tuning

/** A reading of the clock at 23:30 (any will do: the hour is given beside it, as the view model reads it). */
private const val HALF_ELEVEN = 1_800_055_800_000L

class UiStateTest {

    @Test
    fun atHalfPastElevenTheKillerIsAsleep() {
        assertTrue(UiState(nowMs = HALF_ELEVEN, hour = 23).asleep())
    }

    @Test
    fun aWakingKeepsItUpUntilItRunsOut() {
        val woken = UiState(nowMs = HALF_ELEVEN, hour = 23, awakeUntilMs = HALF_ELEVEN + Tuning.AWAKE_MS)
        assertFalse(woken.asleep())
        assertFalse(woken.copy(nowMs = HALF_ELEVEN + Tuning.AWAKE_MS - 1).asleep())
        // The moment it runs out, it is back asleep.
        assertTrue(woken.copy(nowMs = HALF_ELEVEN + Tuning.AWAKE_MS).asleep())
    }

    @Test
    fun atSixItIsAwake() {
        assertTrue(UiState(nowMs = HALF_ELEVEN, hour = 5).asleep())
        assertFalse(UiState(nowMs = HALF_ELEVEN, hour = 6).asleep())
        assertFalse(UiState(nowMs = HALF_ELEVEN, hour = 22).asleep())
    }

    @Test
    fun aStateTheClockHasNotReadIsAwake() {
        // Noon until the first reading: a screen built before the save loads never shows a sleeping killer.
        assertFalse(UiState().asleep())
    }
}
