package team.superfun.horrorpets.ui.training

import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingHudTest {
    @Test
    fun theHudsBestIsTheOldBestUntilTheScoreBeatsIt() {
        assertEquals(12, liveBest(best = 12, score = 0))
        assertEquals(12, liveBest(best = 12, score = 12))
        // A new best counts up in the corner as it is set, so it never says BEST 0 behind a card saying NEW BEST 12.
        assertEquals(13, liveBest(best = 12, score = 13))
        assertEquals(12, liveBest(best = 0, score = 12))
    }
}
