package team.superfun.horrorpets.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.model.Facing
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.spec

/** One rule for which way a killer's art is turned, wherever it stands across from another (fight, VS card, cut-in). */
class FacingTest {
    @Test
    fun onlyJawsArtFacesLeft() {
        assertEquals(Facing.LEFT, PetId.JAWS.spec.facing)
        for (pet in PetId.entries - PetId.JAWS) assertEquals("$pet", Facing.FRONT, pet.spec.facing)
    }

    @Test
    fun artIsMirroredOnlyWhereItWouldFaceAway() {
        // Left-facing art on the left of the screen would look away from the other side: it turns round. On the
        // right it already faces in. Art that faces the camera is never turned.
        for (pet in PetId.entries) for (onLeft in listOf(true, false)) {
            val expected = pet.spec.facing == Facing.LEFT && onLeft
            assertEquals("$pet onLeft=$onLeft", expected, mirrorToFace(pet, onLeft))
        }
    }
}
