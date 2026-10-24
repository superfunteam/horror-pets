package team.superfun.horrorpets.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayGagTest {
    @Test
    fun nextPlayGagNeverRepeatsTheLastOne() {
        val rng = Random(5)
        var last: PlayGag? = null
        val done = List(200) { nextPlayGag(last, rng).also { g -> assertNotEquals(last, g); last = g } }
        assertEquals(PlayGag.entries.toSet(), done.toSet())
    }
}
