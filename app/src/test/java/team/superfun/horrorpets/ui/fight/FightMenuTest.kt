package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.model.PetId
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
    fun everyMovesHintHasTwoParts() {
        for (pet in PetId.entries) {
            for (move in Move.entries) {
                assertEquals("$pet $move", 1, helpText(moveHint(move, pet)).spanStyles.size)
            }
        }
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
}
