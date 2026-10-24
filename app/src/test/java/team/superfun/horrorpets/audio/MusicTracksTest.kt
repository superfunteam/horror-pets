package team.superfun.horrorpets.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which switches between tracks pick the new one up where the old one is ([keepsPlace]). */
class MusicTracksTest {
    @Test fun onlyTheFightTracksKeepTheirPlace() {
        assertTrue(keepsPlace("fight", "fight_intense"))
        assertFalse("back down to the plain fight starts it over", keepsPlace("fight_intense", "fight"))
        assertFalse(keepsPlace("fight", "fight"))
        assertFalse(keepsPlace("switcher", "fight"))
        assertFalse(keepsPlace("switcher", "fight_intense"))
        assertFalse(keepsPlace(null, "fight_intense"))
        assertFalse(keepsPlace("fight", null))
        assertFalse(keepsPlace(null, null))
    }
}
