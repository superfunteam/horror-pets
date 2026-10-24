package team.superfun.horrorpets.ui.attacks

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.SpecialStyle
import team.superfun.horrorpets.model.attacksOf
import team.superfun.horrorpets.ui.fight.SPECIAL_MS
import team.superfun.horrorpets.ui.fight.TANK_BLAST_MS
import team.superfun.horrorpets.ui.fight.TANK_FLIGHT_MS
import team.superfun.horrorpets.ui.fight.markPlan
import team.superfun.horrorpets.ui.fight.specialLandsAtMs
import team.superfun.horrorpets.ui.fight.specialMs

/** The MOVES and learn cards' preview plays an attack on the fight's own clock. */
class AttackPreviewTest {
    private fun special(pet: PetId, tier: Int) = attacksOf(pet, Slot.SPECIAL)[tier]

    @Test
    fun theOxygenTankPreviewRunsAsLongAsInAFight() {
        // Its flight and its blast, not the 950 ms every other special takes (which played it at double speed).
        val tank = previewTiming(special(PetId.JAWS, 0))
        assertEquals(TANK_FLIGHT_MS + TANK_BLAST_MS, tank.effectMs)
        assertEquals(specialMs(SpecialStyle.OXYGEN_TANK), tank.lengthMs)
    }

    @Test
    fun aSpecialsMarksLandWithItsBlowAsInAFight() {
        // Smile!'s bites come as the tank lands (780 ms), and the showing runs until they have faded.
        val smile = special(PetId.JAWS, 3)
        val t = previewTiming(smile)
        assertEquals(780, t.marksAtMs)
        assertEquals(maxOf(t.effectMs, 780 + markPlan(Slot.SPECIAL, 3)!!.ms), t.lengthMs)
        // The Shape's marks come with its slash.
        val michael = previewTiming(special(PetId.MICHAEL, 1))
        assertEquals(specialLandsAtMs(SpecialStyle.THE_SHAPE).toInt(), michael.marksAtMs)
        // Every other special's land at the start of its effect, as before.
        val jason = previewTiming(special(PetId.JASON, 2))
        assertEquals(0, jason.marksAtMs)
        assertEquals(SPECIAL_MS, jason.effectMs)
        assertEquals(maxOf(SPECIAL_MS, markPlan(Slot.SPECIAL, 2)!!.ms), jason.lengthMs)
    }

    @Test
    fun aStrikeOrHeavyIsJustItsMarks() {
        val heavy = attacksOf(PetId.JAWS, Slot.HEAVY)[2]
        assertEquals(PreviewTiming(effectMs = 0, marksAtMs = 0, lengthMs = markPlan(Slot.HEAVY, 2)!!.ms), previewTiming(heavy))
        // A starting strike leaves no marks at all.
        assertEquals(PreviewTiming(0, 0, 0), previewTiming(attacksOf(PetId.JAWS, Slot.LIGHT)[0]))
    }
}
