package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HP count keeps one width all fight, so the name beside it is sized once. */
class FightHudTest {
    @Test
    fun theCountReservesRoomForEveryHpItCanShow() {
        val widest = hpCountCandidates(122)
        // Every count with as many digits as the max, each in every digit, plus the full count itself.
        assertEquals((0..9).map { d -> "$d$d$d/122" } + "122/122", widest)
        for (hp in listOf(0, 7, 99, 100, 111, 122)) {
            val shown = "$hp/122"
            assertTrue(shown, widest.any { it.length >= shown.length })
        }
    }

    @Test
    fun aSmallMaxStillReservesItsOwnDigits() {
        assertEquals((0..9).map { d -> "$d$d/68" } + "68/68", hpCountCandidates(68))
        assertEquals((0..9).map { d -> "$d/1" } + "1/1", hpCountCandidates(1))
        assertEquals((0..9).map { d -> "$d/0" } + "0/0", hpCountCandidates(0))
    }
}
