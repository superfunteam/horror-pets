package team.superfun.horrorpets.ui.attacks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetFixtures
import team.superfun.horrorpets.assets.parseManifest
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.SpecialStyle
import team.superfun.horrorpets.model.attacksOf
import team.superfun.horrorpets.ui.fight.AFTER_IMPACT_MS
import team.superfun.horrorpets.ui.fight.SPECIAL_MS
import team.superfun.horrorpets.ui.fight.STEP_BACK_MS
import team.superfun.horrorpets.ui.fight.STEP_IN_MS
import team.superfun.horrorpets.ui.fight.TANK_BLAST_MS
import team.superfun.horrorpets.ui.fight.TANK_FLIGHT_MS
import team.superfun.horrorpets.ui.fight.markPlan
import team.superfun.horrorpets.ui.fight.specialLandsAtMs
import team.superfun.horrorpets.ui.fight.specialMs
import team.superfun.horrorpets.ui.fight.swingImpactMs

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

    // ---- the attacker ----

    private fun manifest(pet: PetId) = parseManifest(AssetFixtures.manifest(pet.name.lowercase()))

    private fun lungeOf(pet: PetId, slot: Slot, tier: Int): PreviewLunge {
        val attack = attacksOf(pet, slot)[tier]
        return previewLunge(attack, manifest(pet))
    }

    @Test
    fun theAttackerRunsInSwingsAndHopsBack() {
        val lunge = lungeOf(PetId.JASON, Slot.LIGHT, 2)
        assertEquals(Anim.ATTACK, lunge.swing)
        assertEquals(Anim.IDLE, lunge.animAt(0f))
        assertEquals(0f, lunge.stepAt(0f), 0f)
        // It runs in, as a fighter does, and swings once it is all the way in.
        assertEquals(Anim.RUN_RIGHT, lunge.animAt(lunge.swingAtMs - STEP_IN_MS + 1f))
        assertEquals(Anim.ATTACK, lunge.animAt(lunge.swingAtMs.toFloat()))
        assertEquals(1f, lunge.stepAt(lunge.swingAtMs.toFloat()), 0f)
        assertEquals(1f, lunge.stepAt(lunge.impactAtMs.toFloat()), 0f)
        // An ATTACK's recovery is cut short as in a fight: it hops back AFTER_IMPACT_MS after the blow.
        assertEquals(lunge.impactAtMs + AFTER_IMPACT_MS.toInt(), lunge.backAtMs)
        assertEquals(Anim.IDLE, lunge.animAt(lunge.backAtMs.toFloat()))
        assertTrue(lunge.hopAt(lunge.backAtMs + STEP_BACK_MS / 2f) > 0.9f)
        assertEquals(0f, lunge.stepAt(lunge.backAtMs + STEP_BACK_MS.toFloat()), 0f)
        assertEquals(0f, lunge.hopAt(lunge.backAtMs + STEP_BACK_MS.toFloat()), 0f)
        // Standing still again for the rest, and at the start of the next showing.
        assertEquals(Anim.IDLE, lunge.animAt(lunge.lengthMs - 1f))
        assertEquals(0f, lunge.hopAt(lunge.lengthMs - 1f), 0f)
    }

    @Test
    fun theEffectLandsWhenTheSwingConnects() {
        val jason = manifest(PetId.JASON)
        val lunge = lungeOf(PetId.JASON, Slot.HEAVY, 3)
        // The blow lands on the ATTACK's snap frame, timed from its first frame, as a fight lands it.
        assertEquals(lunge.swingAtMs + swingImpactMs(Anim.ATTACK, jason.spec(Anim.ATTACK)).toInt(), lunge.impactAtMs)
        assertEquals(0f, lunge.sinceImpactAt(lunge.impactAtMs.toFloat()), 0f)
        assertTrue(lunge.sinceImpactAt(lunge.impactAtMs - 1f) < 0f)
        // And the showing lasts until its marks have faded, then rests.
        val marks = markPlan(Slot.HEAVY, 3)!!.ms
        assertTrue(lunge.lengthMs >= lunge.impactAtMs + marks + 500)
        assertTrue(lunge.lengthMs <= lunge.impactAtMs + marks + 1_000)
    }

    @Test
    fun aSpecialPlaysOutBeforeTheRest() {
        // The tank flies and blows up long after the hop back: the showing waits for it.
        val tank = lungeOf(PetId.JAWS, Slot.SPECIAL, 0)
        assertEquals(Anim.ATTACK, tank.swing)
        assertTrue(tank.lengthMs > tank.impactAtMs + TANK_FLIGHT_MS + TANK_BLAST_MS)
        // Leatherface dances his special, landing a third of the way through it, and dances it to its end.
        val leatherface = manifest(PetId.LEATHERFACE)
        val saw = lungeOf(PetId.LEATHERFACE, Slot.SPECIAL, 2)
        assertEquals(Anim.DANCE, saw.swing)
        assertEquals(saw.swingAtMs + swingImpactMs(Anim.DANCE, leatherface.spec(Anim.DANCE)).toInt(), saw.impactAtMs)
        val danced = leatherface.spec(Anim.DANCE).durationsMs.sum()
        assertEquals(saw.swingAtMs + maxOf(danced, saw.impactAtMs - saw.swingAtMs + AFTER_IMPACT_MS.toInt()), saw.backAtMs)
        assertTrue(saw.lengthMs > saw.impactAtMs + SPECIAL_MS)
    }

    @Test
    fun barrelsAndThrashPlayInTheirOwnAnimationsToTheEnd() {
        val jaws = manifest(PetId.JAWS)
        for ((tier, anim) in listOf(2 to Anim.BARRELS, 3 to Anim.THRASH)) {
            val lunge = lungeOf(PetId.JAWS, Slot.HEAVY, tier)
            assertEquals(anim, lunge.swing)
            assertEquals(anim, lunge.animAt(lunge.impactAtMs.toFloat()))
            // Played out, never cut short: the barrels sink again before he hops back.
            assertTrue(lunge.backAtMs >= lunge.swingAtMs + jaws.spec(anim).durationsMs.sum())
        }
        // The barrels' 1050 ms run past the usual hold after their blow (320 ms in), so he hops back as they end.
        val breach = lungeOf(PetId.JAWS, Slot.HEAVY, 2)
        assertEquals(breach.swingAtMs + 1_050, breach.backAtMs)
        // His other heavies swing the usual ATTACK.
        assertEquals(Anim.ATTACK, lungeOf(PetId.JAWS, Slot.HEAVY, 1).swing)
    }

    @Test
    fun theAttackerStandsLeftOfTheTargetAndBothFit() {
        // A TV card's preview (about 400 x 92 dp at 2x), and a narrow one.
        for ((w, h) in listOf(800f to 184f, 300f to 184f, 1100f to 184f, 600f to 68f)) {
            val s = previewStage(w, h)
            assertTrue(s.homeX < s.targetX)
            // Stepped in, it still stands short of the target, and the pair sits in the middle.
            assertTrue(s.homeX + s.stepPx < s.targetX)
            assertEquals(w / 2f, (s.homeX + s.targetX) / 2f, 0.01f)
            // Its cell, about as wide as it is tall, stays inside the preview; its feet are on the floor near the bottom.
            assertTrue(s.homeX - s.cellH * 0.47f >= 0f)
            assertTrue(s.targetX + s.cellH * 0.47f <= w)
            assertTrue(s.floor < h && s.floor - s.cellH * 0.98f >= 0f)
            assertTrue(s.targetY > 0f && s.targetY < s.floor)
        }
    }
}
