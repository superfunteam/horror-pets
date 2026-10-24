package team.superfun.horrorpets.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** How far the music ducks under what plays over it: the announcer, a special's riff, or both ([duckLevel]). */
class RiffTest {
    @Test fun theRiffDucksHarderThanAVoiceAndTheLowerWins() {
        assertEquals(1f, duckLevel(voice = false, riff = false))
        assertEquals(DUCK_LEVEL, duckLevel(voice = true, riff = false))
        assertEquals(RIFF_DUCK, duckLevel(voice = false, riff = true))
        assertEquals(minOf(DUCK_LEVEL, RIFF_DUCK), duckLevel(voice = true, riff = true))
    }
}
