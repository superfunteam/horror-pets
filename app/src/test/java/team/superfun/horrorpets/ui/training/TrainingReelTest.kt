package team.superfun.horrorpets.ui.training

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.TrainingGame

class TrainingReelTest {
    private val withMate = TrainingGame.entries.toList()
    private val alone = listOf(TrainingGame.SLASH_SAYS, TrainingGame.ORGAN_DROP)

    @Test
    fun theReelLandsOnTheChosenGame() {
        for (options in listOf(withMate, alone)) {
            for (game in options) {
                val spin = ReelSpin.of(options, game)
                val landed = spin.position(REEL_LAND_MS)
                assertEquals("$options -> $game", spin.total.toFloat(), landed, 1e-4f)
                assertEquals("$options -> $game", game, spin.gameAt(landed.roundToInt()))
                assertEquals(landed, spin.position(REEL_LAND_MS + 10_000), 0f) // and stays there
                assertEquals(0f, spin.speed(REEL_LAND_MS), 1e-4f)
            }
        }
    }

    @Test
    fun theReelStartsFastAndOnlyEverSlows() {
        val spin = ReelSpin.of(withMate, TrainingGame.HIDE_AND_SEEK)
        assertEquals(0f, spin.position(0), 0f)
        assertTrue(spin.speed(0) >= 6f)
        var lastPosition = 0f
        var lastSpeed = Float.MAX_VALUE
        for (t in 0L..REEL_LAND_MS step 10) {
            val p = spin.position(t)
            val v = spin.speed(t)
            assertTrue("ran backwards at $t", p >= lastPosition)
            assertTrue("sped up at $t", v <= lastSpeed + 1e-4f)
            lastPosition = p
            lastSpeed = v
        }
        assertEquals(1f, spin.blur(0), 0f) // flat out it is all smear
        assertEquals(0f, spin.blur(REEL_LAND_MS), 0f) // landed it is sharp
    }

    @Test
    fun aGameMissingFromTheOptionsStillGetsAFrame() {
        val spin = ReelSpin.of(alone, TrainingGame.QUICK_DRAW)
        assertEquals(TrainingGame.QUICK_DRAW, spin.gameAt(spin.total))
    }

    @Test
    fun smearingBlursDownTheColumnsAndWrapsAround() {
        // One column of 10 rows, black but for a white row at the top: a radius of 1 spreads it over the rows either
        // side, the one above being the bottom row (the film is a loop).
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val pixels = IntArray(10) { if (it == 0) white else black }
        smearColumns(pixels, width = 1, height = 10, radius = 1)
        val grey = 0xFF555555.toInt() // 255 / 3
        assertEquals(grey, pixels[9])
        assertEquals(grey, pixels[0])
        assertEquals(grey, pixels[1])
        for (y in 2..8) assertEquals("row $y", black, pixels[y])
    }

    @Test
    fun smearingLeavesAFlatPictureFlat() {
        val colour = 0xFF336699.toInt()
        val pixels = IntArray(3 * 7) { colour }
        smearColumns(pixels, width = 3, height = 7, radius = 2)
        for (p in pixels) assertEquals(colour, p)
    }
}
