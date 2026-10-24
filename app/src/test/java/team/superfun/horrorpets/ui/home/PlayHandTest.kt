package team.superfun.horrorpets.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where PLAY's glove comes in from. */
class PlayHandTest {
    private val screenW = 1920f

    @Test
    fun theGloveComesFromThePetsRoomierSide() {
        assertEquals("from the right for a pet left of centre", 1, handSide(petX = 700f, screenW = screenW))
        assertEquals(1, handSide(petX = 960f, screenW = screenW))
        assertEquals("from the left for a pet right of centre", -1, handSide(petX = 1300f, screenW = screenW))
    }
}
