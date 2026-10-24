package team.superfun.horrorpets.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The secret killer: in the roster, but nowhere a player can see until the save says he is revealed (or he is in the
 * stable), and due as the challenger once every other killer is unlocked.
 */
class SecretTest {
    private val seven = PetId.entries.filterNot { it.secret }

    private fun stableOf(ids: List<PetId>) = GameState(pets = ids.associateWith { PetState(it) }, selected = ids.firstOrNull())

    @Test
    fun onlyJawsIsSecretAndHeComesLast() {
        assertEquals(listOf(PetId.JAWS), PetId.entries.filter { it.secret })
        assertEquals(PetId.JAWS, PetId.entries.last())
        assertEquals(7, seven.size)
    }

    @Test
    fun visibilityBeforeAndAfterTheReveal() {
        // A stable of seven: he is nowhere yet, and he is due.
        val all = stableOf(seven)
        assertEquals(7, all.visiblePets().size)
        assertEquals(seven, all.visiblePets())
        assertFalse(all.isVisible(PetId.JAWS))
        assertEquals(PetId.JAWS, all.challengerDue())

        // Revealed: eight, in roster order, and nothing more is due.
        val revealed = all.copy(revealed = setOf("JAWS"))
        assertEquals(8, revealed.visiblePets().size)
        assertEquals(PetId.entries, revealed.visiblePets())
        assertTrue(revealed.isVisible(PetId.JAWS))
        assertNull(revealed.challengerDue())

        // In the stable (beaten, or the puppet's `pet jaws`), he is visible even without the reveal.
        val joined = all.copy(pets = all.pets + (PetId.JAWS to PetState(PetId.JAWS)))
        assertTrue(joined.isVisible(PetId.JAWS))
        assertEquals(PetId.entries, joined.visiblePets())
        assertNull(joined.challengerDue())
    }

    @Test
    fun aFreshGameHasNoChallengerDue() {
        assertNull(GameState().challengerDue())
        assertEquals(seven, GameState().visiblePets())
        // Any one of the seven still to beat: not yet.
        for (missing in seven) assertNull("$missing", stableOf(seven - missing).challengerDue())
        // Revealed with some of the seven still locked: he is visible, and no entrance is due.
        val early = stableOf(listOf(PetId.JASON)).copy(revealed = setOf("JAWS"))
        assertEquals(PetId.entries, early.visiblePets())
        assertNull(early.challengerDue())
    }

    @Test
    fun theOtherKillersAreAlwaysVisible() {
        for (game in listOf(GameState(), stableOf(listOf(PetId.FREDDY)), stableOf(seven))) {
            for (id in seven) assertTrue("$id", game.isVisible(id))
        }
        // A name in `revealed` that is not secret changes nothing.
        assertEquals(seven, GameState(revealed = setOf("JASON")).visiblePets())
    }

    @Test
    fun theStarterPickIsTheSevenEvenAfterTheReveal() {
        assertEquals(seven, Starters)
        assertFalse(PetId.JAWS in Starters)
    }
}
