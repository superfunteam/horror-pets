package team.superfun.horrorpets.puppet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.TrainingGame

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
    fun theListOfKillersNeverNamesASecretOneButTheyStillWork() {
        for (text in listOf("pet bob", "fight", "fight shark")) {
            val reason = (parseCommand(text) as PuppetCommand.Invalid).reason
            for (secret in PetId.entries.filter { it.secret }) {
                assertFalse("$text: \"$reason\" names $secret", reason.contains(secret.name, ignoreCase = true))
            }
        }
        assertEquals(PuppetCommand.Pet(PetId.JAWS), parseCommand("pet jaws"))
        assertEquals(PuppetCommand.Fight(PetId.JAWS), parseCommand("fight jaws"))
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
    fun fakeVersionTakesAReleaseVersionOrOff() {
        assertEquals(PuppetCommand.FakeVersion("0.5.0"), parseCommand("fake_version 0.5.0"))
        assertEquals(PuppetCommand.FakeVersion("12.34.56"), parseCommand("  FAKE_VERSION   12.34.56 "))
        assertEquals(PuppetCommand.FakeVersion(null), parseCommand("fake_version off"))
        assertEquals(PuppetCommand.FakeVersion(null), parseCommand("fake_version OFF"))
        for (bad in listOf("fake_version", "fake_version v0.5.0", "fake_version 0.5", "fake_version 0.5.0-beta", "fake_version 0.0.0-dev",
            "fake_version 1.100.0", "fake_version soon", "fake_version 0.5.0 0.6.0")) {
            assertInvalid(bad, "MAJOR.MINOR.PATCH")
        }
    }

    @Test
    fun hourTakesAnHourOfTheDayOrOff() {
        assertEquals(PuppetCommand.FakeHour(22), parseCommand("hour 22"))
        assertEquals(PuppetCommand.FakeHour(0), parseCommand("hour 0"))
        assertEquals(PuppetCommand.FakeHour(23), parseCommand("  HOUR   23 "))
        assertEquals(PuppetCommand.FakeHour(null), parseCommand("hour off"))
        assertEquals(PuppetCommand.FakeHour(null), parseCommand("hour OFF"))
        assertEquals("a bare hour is the clock's own again", PuppetCommand.FakeHour(null), parseCommand("hour"))
        for (bad in listOf("hour 24", "hour -1", "hour 5.5", "hour noon", "hour 5 6", "hour off 5")) {
            assertInvalid(bad, "0 to 23")
        }
    }

    @Test
    fun checkUpdateIsBare() {
        assertEquals(PuppetCommand.CheckUpdate, parseCommand("check_update"))
        assertEquals(PuppetCommand.CheckUpdate, parseCommand("CHECK_UPDATE"))
        assertInvalid("check_update now", "no argument")
    }

    @Test
    fun bareCommandsTakeNoArgument() {
        assertInvalid("feed now", "no argument")
        assertInvalid("home please", "no argument")
        assertInvalid("unlock_all jason", "no argument")
    }

    @Test
    fun trainTakesAnOptionalGame() {
        assertEquals(PuppetCommand.Care(CareAction.TRAIN), parseCommand("train"))
        for (game in TrainingGame.entries) {
            assertEquals(PuppetCommand.Care(CareAction.TRAIN, game), parseCommand("train ${game.name.lowercase()}"))
        }
        assertEquals(PuppetCommand.Care(CareAction.TRAIN, TrainingGame.QUICK_DRAW), parseCommand("  TRAIN   Quick_Draw "))
        assertInvalid("train chess", "hide_and_seek")
        assertInvalid("train slash_says organ_drop", "one game")
        // Only train has games to choose from.
        assertInvalid("feed slash_says", "no argument")
        assertInvalid("play organ_drop", "no argument")
    }

    @Test
    fun scoreTakesANumber() {
        assertEquals(PuppetCommand.Score(8), parseCommand("score 8"))
        assertEquals(PuppetCommand.Score(0), parseCommand(" SCORE  0 "))
        assertEquals(PuppetCommand.Score(30), parseCommand("score 30"))
        for (bad in listOf("score", "score -1", "score 2.5", "score ten", "score 1 2", "score 99999999999")) {
            assertInvalid(bad, "whole number")
        }
    }

    @Test
    fun stockTakesOneOrMoreItems() {
        assertEquals(PuppetCommand.Stock(listOf(PantryItem.EGG)), parseCommand("stock egg"))
        assertEquals(
            PuppetCommand.Stock(listOf(PantryItem.SUN, PantryItem.SUN, PantryItem.RAW_STEAK)),
            parseCommand("  STOCK  sun  Sun raw_steak "),
        )
        for (item in PantryItem.entries) assertEquals(PuppetCommand.Stock(listOf(item)), parseCommand("stock ${item.id}"))
        assertInvalid("stock", "one or more")
        assertInvalid("stock egg spoon", "spoon")
        assertInvalid("stock spoon", "no pantry item")
        // Case does not matter, and an id has no dashes.
        assertEquals(PuppetCommand.Stock(listOf(PantryItem.RAW_STEAK)), parseCommand("stock RAW_STEAK"))
        assertInvalid("stock raw-steak", "raw-steak")
    }

    @Test
    fun cookTakesTwoItems() {
        assertEquals(PuppetCommand.Cook(PantryItem.GRILL, PantryItem.EGG), parseCommand("cook grill egg"))
        assertEquals(PuppetCommand.Cook(PantryItem.EGG, PantryItem.GRILL), parseCommand("  COOK   Egg  GRILL "))
        assertEquals("the same item twice is two of them", PuppetCommand.Cook(PantryItem.EGG, PantryItem.EGG), parseCommand("cook egg egg"))
        for (bad in listOf("cook", "cook grill", "cook grill egg pot")) assertInvalid(bad, "two item")
        assertInvalid("cook grill spoon", "spoon")
        assertInvalid("cook spoon grill", "spoon")
    }

    @Test
    fun serveTakesOneItem() {
        assertEquals(PuppetCommand.Serve(PantryItem.STEAK), parseCommand("serve steak"))
        assertEquals(PuppetCommand.Serve(PantryItem.CAMP_CRYSTAL_FISH_FRY), parseCommand(" SERVE  Camp_Crystal_Fish_Fry "))
        for (bad in listOf("serve", "serve steak egg")) assertInvalid(bad, "one item")
        assertInvalid("serve spoon", "spoon")
    }

    @Test
    fun anUnknownCommandListsTheCommands() {
        assertInvalid("dance", "unlock_all")
        assertInvalid("dance", "autoplay on|off")
        assertInvalid("dance", "hud on|off")
        assertInvalid("dance", "train [<game>]")
        assertInvalid("dance", "score <n>")
        assertInvalid("dance", "stock <id>")
        assertInvalid("dance", "cook <id> <id>")
        assertInvalid("dance", "serve <id>")
        assertInvalid("dance", "fake_version <x.y.z>|off")
        assertInvalid("dance", "check_update")
        assertInvalid("", "empty")
        // The invalid command keeps its text, for the log.
        assertEquals("dance wildly", (parseCommand("  dance wildly ") as PuppetCommand.Invalid).text)
    }
}
