package team.superfun.horrorpets.ui.pantry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

/** The pantry's line of the tastes its killer has shown. */
class TastesTest {
    /** Michael loves sweet, is fine with savory and hates gross (see `tasteOf`). */
    private fun michael(vararg learned: String) = PetState(PetId.MICHAEL, tastesLearned = learned.toSet())

    @Test
    fun namesTheLovedAndTheHatedFlavorsOnceShown() {
        assertEquals("LOVES SWEET", tastesLine(michael("SWEET")))
        assertEquals("HATES GROSS", tastesLine(michael("GROSS")))
        assertEquals("LOVES SWEET · HATES GROSS", tastesLine(michael("SWEET", "GROSS")))
        assertEquals("loves first, whatever order they were learned in", "LOVES SWEET · HATES GROSS", tastesLine(michael("GROSS", "SWEET")))
        // Jason's love is savory: the line is the killer's own.
        assertEquals("LOVES SAVORY", tastesLine(PetState(PetId.JASON, tastesLearned = setOf("SAVORY"))))
    }

    @Test
    fun saysNothingUntilALoveOrAHateIsShown() {
        assertNull(tastesLine(michael()))
        // The "fine" flavor is never named, even when it is in the set.
        assertNull(tastesLine(michael("SAVORY")))
        assertEquals("LOVES SWEET · HATES GROSS", tastesLine(michael("SAVORY", "SWEET", "GROSS")))
    }
}
