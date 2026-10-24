package team.superfun.horrorpets.ui.attacks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.attacksOf

/** On MOVES, a locked attack keeps its secret: the card and the slot say only that it is there, and when it unlocks. */
class LockedCardTest {
    private val heavies = attacksOf(PetId.JASON, Slot.HEAVY)
    private val specials = attacksOf(PetId.JASON, Slot.SPECIAL)

    @Test
    fun aTierIsLockedUntilThePetReachesItsLevel() {
        // Jason's heavies unlock at 1, 3, 7 and 12.
        val jason = PetState(PetId.JASON, level = 7)
        assertEquals(listOf(false, false, false, true), heavies.map { isLocked(jason, it) })
        assertEquals(Standing.LOCKED, standingOf(jason, heavies[3]))
        assertEquals(Standing.READY, standingOf(jason, heavies[2]))
        // A starting attack never is.
        assertFalse(isLocked(PetState(PetId.JASON, level = 1), heavies[0]))
        assertTrue(isLocked(PetState(PetId.JASON, level = 11), heavies[3]))
        assertFalse(isLocked(PetState(PetId.JASON, level = 12), heavies[3]))
    }

    @Test
    fun aLockedCardShowsNoNameNoNumbersAndNoPreview() {
        val final = heavies[3]
        val face = cardFace(final, vs = heavies[0], locked = true)
        assertEquals(LOCKED_TEXT, face.name)
        assertFalse(face.preview)
        // It still says what slot it is and when it unlocks.
        assertEquals("HEAVY  ·  UNLOCKS AT LEVEL 12", face.sub)
        // The same five stats, every one a mystery: no value, no power bar, nothing better or worse.
        assertEquals(statLines(final, null).map { it.label }, face.stats.map { it.label })
        for (line in face.stats) {
            assertEquals(LOCKED_TEXT, line.value)
            assertEquals(null, line.bar)
            assertEquals(Change.NONE, line.change)
        }
        // Nothing on it names the move or what it does.
        val shown = listOf(face.name, face.sub) + face.stats.map { it.value }
        assertTrue(shown.none { final.name.uppercase() in it.uppercase() })
        assertTrue(shown.none { (effectText(final) ?: "Bleeds") in it })
    }

    @Test
    fun aLockedSpecialHidesItsEffectToo() {
        val face = cardFace(specials[3], vs = specials[0], locked = true)
        assertEquals(LOCKED_TEXT, face.name)
        assertEquals("SPECIAL  ·  UNLOCKS AT LEVEL 14", face.sub)
        assertTrue(face.stats.all { it.value == LOCKED_TEXT })
    }

    @Test
    fun anUnlockedCardShowsTheAttackAsBefore() {
        val swipe = attacksOf(PetId.JASON, Slot.LIGHT)[1]
        val face = cardFace(swipe, vs = attacksOf(PetId.JASON, Slot.LIGHT)[0])
        assertEquals(swipe.name, face.name)
        assertEquals("LIGHT  ·  LEVEL 2", face.sub)
        assertTrue(face.preview)
        assertEquals(statLines(swipe, attacksOf(PetId.JASON, Slot.LIGHT)[0]), face.stats)
        assertEquals("HEAVY  ·  STARTING ATTACK", cardFace(heavies[0], vs = null).sub)
    }
}
