package team.superfun.horrorpets.ui.title

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import team.superfun.horrorpets.model.PetId

class CreditsTest {
    @Test
    fun everyKillerIsCreditedOnceInTheRostersOrder() {
        assertEquals(PetId.entries.toList(), ArtCredits.map { it.pet })
    }

    @Test
    fun usernamesAreBareHandles() {
        for (c in ArtCredits) {
            assertFalse("${c.pet}: no @, the dialog adds it", c.username.startsWith("@"))
            assertFalse("${c.pet}: one handle, no spaces", c.username.isBlank() || ' ' in c.username)
        }
    }
}
