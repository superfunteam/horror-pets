package team.superfun.horrorpets.puppet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.model.PetId

class PuppetCommandTest {
    private fun assertInvalid(text: String, mentioning: String) {
        val parsed = parseCommand(text)
        assertTrue("$text parsed as $parsed", parsed is PuppetCommand.Invalid)
        val reason = (parsed as PuppetCommand.Invalid).reason
        assertTrue("$text: \"$reason\" should mention $mentioning", reason.contains(mentioning))
    }

    @Test
    fun aLineSplitsOnSemicolonsAndNewLinesAndDropsBlanks() {
        assertEquals(
            listOf("pet freddy", "fight pinhead", "autoplay on", "status"),
            splitCommands(" pet freddy; fight pinhead;; \n autoplay on \n\nstatus;"),
        )
        assertEquals(emptyList<String>(), splitCommands(" ; \n "))
    }

    @Test
    fun theBareCommands() {
        assertEquals(PuppetCommand.UnlockAll, parseCommand("unlock_all"))
        assertEquals(PuppetCommand.LearnAll, parseCommand("learn_all"))
        assertEquals(PuppetCommand.Care(CareAction.FEED), parseCommand("feed"))
        assertEquals(PuppetCommand.Care(CareAction.PLAY), parseCommand("play"))
        assertEquals(PuppetCommand.Care(CareAction.TRAIN), parseCommand("train"))
        assertEquals(PuppetCommand.Home, parseCommand("home"))
        assertEquals(PuppetCommand.Status, parseCommand("status"))
        assertEquals(PuppetCommand.MeterFull, parseCommand("meter full"))
    }

    @Test
    fun caseAndSpacesDoNotMatter() {
        assertEquals(PuppetCommand.Pet(PetId.FREDDY), parseCommand("  PET   Freddy "))
        assertEquals(PuppetCommand.Fight(PetId.GHOSTFACE), parseCommand("Fight GHOSTFACE"))
        assertEquals(PuppetCommand.Autoplay(true), parseCommand("AUTOPLAY On"))
        assertEquals(PuppetCommand.MeterFull, parseCommand("Meter FULL"))
        assertEquals(PuppetCommand.UnlockAll, parseCommand("UNLOCK_ALL"))
    }

    @Test
    fun everyKillerByName() {
        for (id in PetId.entries) {
            assertEquals(PuppetCommand.Pet(id), parseCommand("pet ${id.name.lowercase()}"))
            assertEquals(PuppetCommand.Fight(id), parseCommand("fight ${id.name.lowercase()}"))
        }
    }

    @Test
    fun aKillerThatDoesNotExistListsTheOnesThatDo() {
        assertInvalid("pet bob", "freddy")
        assertInvalid("fight", "pinhead")
        assertInvalid("pet freddy jason", "one killer")
    }

    @Test
    fun levelsRunOneToTheGamesMax() {
        assertEquals(PuppetCommand.Level(1), parseCommand("level 1"))
        assertEquals(PuppetCommand.Level(14), parseCommand("level 14"))
        assertEquals(PuppetCommand.Level(99), parseCommand("level 99"))
        for (bad in listOf("level 0", "level -3", "level 100", "level ten", "level 2.5", "level")) assertInvalid(bad, "1 to 99")
    }

    @Test
    fun autoplayIsOnOrOff() {
        assertEquals(PuppetCommand.Autoplay(true), parseCommand("autoplay on"))
        assertEquals(PuppetCommand.Autoplay(false), parseCommand("autoplay off"))
        assertInvalid("autoplay", "on or off")
        assertInvalid("autoplay maybe", "on or off")
    }

    @Test
    fun hudIsOnOrOff() {
        assertEquals(PuppetCommand.Hud(false), parseCommand("hud off"))
        assertEquals(PuppetCommand.Hud(true), parseCommand("hud on"))
        assertEquals(PuppetCommand.Hud(false), parseCommand("  HUD   Off "))
        assertInvalid("hud", "on or off")
        assertInvalid("hud hidden", "on or off")
        assertInvalid("hud off now", "on or off")
    }

    @Test
    fun everyMoveByName() {
        for (m in Move.entries) assertEquals(PuppetCommand.Pick(m), parseCommand("move ${m.name}"))
        assertEquals(PuppetCommand.Pick(Move.SPECIAL), parseCommand("move special"))
        assertInvalid("move dance", "strike")
        assertInvalid("move", "heavy")
    }

    @Test
    fun meterOnlyFills() {
        assertInvalid("meter", "full")
        assertInvalid("meter empty", "full")
    }

    @Test
    fun waitTakesSecondsOrHome() {
        assertEquals(PuppetCommand.Wait(2_500), parseCommand("wait 2.5"))
        assertEquals(PuppetCommand.Wait(600_000), parseCommand("wait 600"))
        assertEquals(PuppetCommand.WaitHome, parseCommand("wait HOME"))
        for (bad in listOf("wait", "wait 0", "wait -1", "wait 601", "wait soon", "wait NaN")) assertInvalid(bad, "seconds")
    }

    @Test
    fun bareCommandsTakeNoArgument() {
        assertInvalid("feed now", "no argument")
        assertInvalid("home please", "no argument")
        assertInvalid("unlock_all jason", "no argument")
    }

    @Test
    fun anUnknownCommandListsTheCommands() {
        assertInvalid("dance", "unlock_all")
        assertInvalid("dance", "autoplay on|off")
        assertInvalid("dance", "hud on|off")
        assertInvalid("", "empty")
        // The invalid command keeps its text, for the log.
        assertEquals("dance wildly", (parseCommand("  dance wildly ") as PuppetCommand.Invalid).text)
    }
}
