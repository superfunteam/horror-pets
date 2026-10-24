package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.model.Facing
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.spec
import androidx.compose.ui.unit.dp
import team.superfun.horrorpets.model.Attacks
import team.superfun.horrorpets.model.attackById
import team.superfun.horrorpets.model.attacksOf
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.Kit
import team.superfun.horrorpets.ui.theme.Amber

/** The help window's text: the move's hint in capitals, its parts split by Amber dots. */
class HelpTextTest {
    @Test
    fun hintsReadInCapitalsWithAmberDots() {
        val t = helpText("Quick hit  ·  interrupts a Heavy")
        assertEquals("QUICK HIT · INTERRUPTS A HEAVY", t.text)
        val dot = t.spanStyles.single()
        assertEquals(Amber, dot.item.color)
        assertEquals(" · ", t.text.substring(dot.start, dot.end))
    }

    @Test
    fun aHintWithoutDotsIsJustCapitals() {
        val t = helpText("Forfeit the fight")
        assertEquals("FORFEIT THE FIGHT", t.text)
        assertTrue(t.spanStyles.isEmpty())
    }

    @Test
    fun everyMovesHintHasTwoPartsWhateverAttackItSwings() {
        for (pet in PetId.entries) {
            for (tier in 0..3) {
                val kit = Kit(attacksOf(pet, Slot.LIGHT)[tier], attacksOf(pet, Slot.HEAVY)[tier], attacksOf(pet, Slot.SPECIAL)[tier])
                for (move in Move.entries) {
                    assertEquals("$pet tier $tier $move", 1, helpText(moveHint(move, kit)).spanStyles.size)
                }
            }
        }
    }

    @Test
    fun aHintNamesTheAttackAndWhatItAdds() {
        val crush = Kit.base(PetId.JASON).copy(heavy = attackById("jason_heavy_2")!!)
        assertEquals("Crystal Lake Crush  \u00b7  breaks a Block, a Strike cuts it off, 25% daze", moveHint(Move.HEAVY, crush))
        val hack = Kit.base(PetId.JASON).copy(light = attackById("jason_light_2")!!)
        assertEquals("Lakeside Hack  \u00b7  quick hit, interrupts a Heavy, +8% crit", moveHint(Move.STRIKE, hack))
        assertEquals("Machete Frenzy  \u00b7  never misses, bleeds 3 turns \u00d7 6%", moveHint(Move.SPECIAL, Kit.base(PetId.JASON)))
        assertEquals("Dream Claws  \u00b7  never misses, drains 50%", moveHint(Move.SPECIAL, Kit.base(PetId.FREDDY)))
    }

    @Test
    fun calloutsNameTheAttackUnderItsKindOfMove() {
        val kit = Kit.base(PetId.FREDDY).copy(special = attackById("freddy_special_3")!!)
        assertEquals("WELCOME TO PRIME TIME!", calloutText(Move.SPECIAL, kit))
        assertEquals("SPECIAL", calloutCaption(Move.SPECIAL))
        assertEquals("CLAW SWIPE!", calloutText(Move.STRIKE, kit))
        assertEquals("BLOCK", calloutText(Move.BLOCK, kit))
        assertEquals(null, calloutCaption(Move.BLOCK))
        // The longest name still fits a fighter's half of a 4:3 screen (0.4 of 720dp), at no less than 15sp.
        val longest = Attacks.maxBy { it.name.length }.name.uppercase() + "!"
        val size = calloutSize(longest, 288.dp)
        assertTrue(size.value >= 15f)
        assertTrue("it fits", 40f + longest.length * 0.56f * size.value <= 288f)
    }
}

/** Where the player stands: clear of the command window on any screen, never further out than 30%. */
class PlayerHomeTest {
    private val window = CommandWindowWidth.value

    @Test
    fun widescreenKeepsThePlayerAtThirtyPercent() {
        assertEquals(0.30f, playerHomeFraction(960f, 48f + window), 1e-4f)
    }

    @Test
    fun aWidePhoneKeepsThePlayerAtThirtyPercent() {
        // A 19.5:9 phone, its inset widened for a camera cutout.
        assertEquals(0.30f, playerHomeFraction(1170f, 64f + window), 1e-4f)
    }

    @Test
    fun aFourByThreeScreenMovesThePlayerClearOfTheWindow() {
        val menuRight = 36f + window // 5% of 720 + the window
        val home = playerHomeFraction(720f, menuRight)
        assertEquals(0.325f, home, 1e-3f) // (36 + 128 + 10 + 60) / 720
        // Leatherface's saw, the widest idle pose (59dp), still clears the window by 10dp.
        assertTrue(home * 720f - 59f >= menuRight + 10f)
    }

    @Test
    fun jawsSideOnStandsFurtherInSoHisTailClearsTheWindow() {
        // Turned to face right, his tail reaches back toward the window: 180 px of his 416 px cell, 205 dp tall.
        val reach = fighterReachDp(PetId.JAWS)
        assertEquals(180f / 416f * 540f * 0.38f, reach, 1f)
        val menuRight = 36f + window
        val home = playerHomeFraction(720f, menuRight, reach)
        assertTrue(home * 720f - reach >= menuRight + 10f - 1e-3f)
        assertTrue(home > playerHomeFraction(720f, menuRight))
        // On a widescreen he still stands at 30%, like everyone.
        assertEquals(0.30f, playerHomeFraction(960f, 48f + window, reach), 1e-4f)
        // Everyone else keeps the usual reach.
        for (pet in PetId.entries - PetId.JAWS) assertEquals("$pet", fighterReachDp(PetId.JASON), fighterReachDp(pet), 0f)
    }

    @Test
    fun theReachGoesByWhichWayTheArtFacesNotWhoItIs() {
        // Side-on art reaches further (nose or tail) than art that faces the camera, whichever killer it is.
        assertTrue(fighterReachDp(Facing.LEFT) > fighterReachDp(Facing.FRONT))
        assertEquals(60f, fighterReachDp(Facing.FRONT), 0f)
        for (pet in PetId.entries) assertEquals("$pet", fighterReachDp(pet.spec.facing), fighterReachDp(pet), 0f)
    }
}
