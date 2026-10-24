package team.superfun.horrorpets.ui.title

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import team.superfun.horrorpets.model.PetId

class CreditsTest {
    @Test
    fun everyKillerButTheSecretOneIsCreditedOnceInTheRostersOrder() {
        // The credits name no secret killer: they are on the title screen, before anything is revealed.
        assertEquals(PetId.entries.filterNot { it.secret }, ArtCredits.map { it.pet })
        assertFalse(ArtCredits.any { it.pet.secret })
    }

    @Test
    fun usernamesAreBareHandles() {
        for (c in ArtCredits) {
            assertFalse("${c.pet}: no @, the dialog adds it", c.username.startsWith("@"))
            assertFalse("${c.pet}: one handle, no spaces", c.username.isBlank() || ' ' in c.username)
        }
    }
}
