package team.superfun.horrorpets.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.pantryItemById
import team.superfun.horrorpets.model.pantryItems

class SaveCodecTest {
    private val defaults = GameState()

    /** A version-2 save whose stable is [pets] (a JSON object body), selecting [selected]. */
    private fun v2(pets: String, selected: String? = null, extra: String = ""): String =
        """{"version":2${selected?.let { ",\"selected\":\"$it\"" } ?: ""}$extra,"pets":{$pets}}"""

    @Test
    fun aFreshGameHasNoStableAndNoStarter() {
        assertEquals(2, defaults.version)
        assertTrue(defaults.pets.isEmpty())
        assertNull(defaults.selected)
        assertNull(defaults.lastSeenMs)
    }

    @Test
    fun roundTrip() {
        val state = GameState(
            pets = mapOf(
                PetId.PINHEAD to PetState(PetId.PINHEAD),
                PetId.FREDDY to PetState(
                    id = PetId.FREDDY,
                    hunger = 61.5,
                    funLevel = 12.25,
                    strength = 47.0,
                    level = 4,
                    xp = 130,
                    moodBonus = -12.5,
                    wins = 3,
                    losses = 2,
                    sceneIndex = 2,
                    fakeDeathPending = true,
                ),
            ),
            selected = PetId.PINHEAD,
            lastSeenMs = 1_760_000_000_000L,
        )
        assertEquals(state, SaveCodec.decode(SaveCodec.encode(state)))
    }

    @Test
    fun attacksRoundTripAndWhatAPetMayNotHaveIsDropped() {
        val jason = PetState(
            PetId.JASON,
            level = 7,
            known = setOf("jason_light_1", "jason_heavy_2"),
            equipped = mapOf(Slot.LIGHT to "jason_light_1", Slot.HEAVY to "jason_heavy_2"),
        )
        val state = GameState(pets = mapOf(PetId.JASON to jason), selected = PetId.JASON)
        assertEquals(state, SaveCodec.decode(SaveCodec.encode(state)))

        // Another killer's, past its level, unknown ids, and an equipped attack it never learned.
        val messy = jason.copy(
            known = setOf("jason_light_1", "freddy_light_1", "jason_light_3", "who_knows"),
            equipped = mapOf(Slot.LIGHT to "jason_light_1", Slot.HEAVY to "jason_heavy_2", Slot.SPECIAL to "jason_light_1"),
        )
        val back = SaveCodec.decode(SaveCodec.encode(GameState(pets = mapOf(PetId.JASON to messy), selected = PetId.JASON)))!!
        val pet = back.pets.getValue(PetId.JASON)
        assertEquals(setOf("jason_light_1"), pet.known)
        assertEquals(mapOf(Slot.LIGHT to "jason_light_1"), pet.equipped)
    }

    @Test
    fun anOlderSaveLoadsWithTheStartingAttacks() {
        val pet = SaveCodec.decode(v2("\"JASON\":{\"level\":5}", selected = "JASON"))!!.pets.getValue(PetId.JASON)
        assertTrue(pet.known.isEmpty())
        assertTrue(pet.equipped.isEmpty())
    }

    @Test
    fun trainingBestRoundTrips() {
        val jason = PetState(PetId.JASON, trainingBest = mapOf("SLASH_SAYS" to 6, "ORGAN_DROP" to 41))
        val state = GameState(pets = mapOf(PetId.JASON to jason, PetId.FREDDY to PetState(PetId.FREDDY)), selected = PetId.JASON)
        val back = SaveCodec.decode(SaveCodec.encode(state))
        assertEquals(state, back)
        assertEquals(mapOf("SLASH_SAYS" to 6, "ORGAN_DROP" to 41), back.pets.getValue(PetId.JASON).trainingBest)
        assertTrue(back.pets.getValue(PetId.FREDDY).trainingBest.isEmpty())
    }

    @Test
    fun aSaveWithoutTrainingBestLoads() {
        // A save from before the training games: no key at all, and the version is still 2.
        val loaded = pet(PetId.JASON, """{"id":"JASON","level":5,"wins":2}""")
        assertTrue(loaded.trainingBest.isEmpty())
        assertEquals(5, loaded.level)
    }

    @Test
    fun unknownOrNegativeTrainingBestIsCleaned() {
        // A game a later build added is dropped (it would not be shown anyway); a negative score reads as 0.
        val json = """{"trainingBest":{"SLASH_SAYS":-4,"FUTURE_GAME":9,"ORGAN_DROP":12,"quick_draw":7}}"""
        assertEquals(mapOf("SLASH_SAYS" to 0, "ORGAN_DROP" to 12), pet(PetId.JASON, json).trainingBest)
        // A wrongly typed map is one bad field: that pet resets to defaults and stays unlocked.
        assertEquals(PetState(PetId.JASON), pet(PetId.JASON, """{"level":7,"trainingBest":{"SLASH_SAYS":"lots"}}"""))
    }

    @Test
    fun trainedGamesRoundTrips() {
        val jason = PetState(PetId.JASON, trainedGames = setOf("SLASH_SAYS", "QUICK_DRAW"))
        val state = GameState(pets = mapOf(PetId.JASON to jason, PetId.FREDDY to PetState(PetId.FREDDY)), selected = PetId.JASON)
        val back = SaveCodec.decode(SaveCodec.encode(state))
        assertEquals(state, back)
        assertEquals(setOf("SLASH_SAYS", "QUICK_DRAW"), back.pets.getValue(PetId.JASON).trainedGames)
        assertTrue(back.pets.getValue(PetId.FREDDY).trainedGames.isEmpty())
    }

    @Test
    fun aSaveWithoutTrainedGamesLoadsWithNone() {
        // A save from before the first-fight gate: no key at all, and the version is still 2.
        val loaded = pet(PetId.JASON, """{"id":"JASON","level":5,"wins":2,"trainingBest":{"SLASH_SAYS":4}}""")
        assertTrue(loaded.trainedGames.isEmpty())
        assertEquals(5, loaded.level)
        assertEquals(mapOf("SLASH_SAYS" to 4), loaded.trainingBest)
    }

    @Test
    fun unknownTrainedGamesAreDropped() {
        // A game a later build added is dropped; names are exact, so a wrongly cased one is not a game either.
        val json = """{"trainedGames":["SLASH_SAYS","FUTURE_GAME","organ_drop","ORGAN_DROP"]}"""
        assertEquals(setOf("SLASH_SAYS", "ORGAN_DROP"), pet(PetId.JASON, json).trainedGames)
        // A wrongly typed field is one bad field: that pet resets to defaults and stays unlocked.
        assertEquals(PetState(PetId.JASON), pet(PetId.JASON, """{"level":7,"trainedGames":"SLASH_SAYS"}"""))
    }

    @Test
    fun aFreshGameHasAnEmptyPantryAndNoFavoriteFound() {
        assertTrue(defaults.pantry.isEmpty())
        assertEquals(false, PetState(PetId.JASON).favoriteFound)
    }

    @Test
    fun pantryRoundTrips() {
        // Shelf order and repeats are kept: two eggs are two items.
        val shelf = listOf("grill", "raw_steak", "egg", "egg", "camp_crystal_fish_fry", "pile_of_slop")
        val state = GameState(pets = mapOf(PetId.JASON to PetState(PetId.JASON)), selected = PetId.JASON, pantry = shelf)
        val back = SaveCodec.decode(SaveCodec.encode(state))
        assertEquals(state, back)
        assertEquals(shelf, back.pantry)
        assertEquals(shelf.map { pantryItemById(it) }, back.pantryItems())
        // An empty pantry is a pantry too, and a pantry needs no killers.
        assertEquals(defaults, SaveCodec.decode(SaveCodec.encode(defaults)))
        assertEquals(GameState(pantry = shelf), SaveCodec.decode(SaveCodec.encode(GameState(pantry = shelf))))
    }

    @Test
    fun aSaveWithoutAPantryLoads() {
        // A save from before the pantry: no key at all, and the version is still 2.
        val decoded = SaveCodec.decode(v2(""""JASON":{"id":"JASON","level":5}""", selected = "JASON"))
        assertTrue(decoded.pantry.isEmpty())
        assertEquals(5, decoded.pets.getValue(PetId.JASON).level)
        assertEquals(PetId.JASON, decoded.selected)
        assertTrue(SaveCodec.decode("""{"version":2,"pantry":null}""").pantry.isEmpty())
    }

    @Test
    fun unknownPantryIdsAreDropped() {
        // An item a later build added, a made-up one, a wrongly cased id (ids are lowercase), an empty one and
        // entries that are not strings at all: all dropped, the rest kept in shelf order.
        val json = v2(
            """"JASON":{"level":2}""",
            selected = "JASON",
            extra = ""","pantry":["grill","future_dish","egg","RAW_STEAK","",7,null,true,["milk"],{"id":"milk"},"milk","egg"]""",
        )
        val decoded = SaveCodec.decode(json)
        assertEquals(listOf("grill", "egg", "milk", "egg"), decoded.pantry)
        assertEquals(2, decoded.pets.getValue(PetId.JASON).level)
        assertNotNull(SaveCodec.decodeOrNull(json))
    }

    @Test
    fun anOversizedPantryIsTrimmed() {
        // The first PANTRY_MAX items are kept, in order.
        assertEquals(40, Tuning.PANTRY_MAX)
        val ids = PantryItem.entries.map { it.id }
        val tooMany = (ids + ids).take(Tuning.PANTRY_MAX + 20)
        val json = """{"version":2,"pantry":${tooMany.joinToString(",", "[", "]") { "\"$it\"" }}}"""
        assertEquals(tooMany.take(Tuning.PANTRY_MAX), SaveCodec.decode(json).pantry)
        // Unknown ids do not take up room: the cap counts items that are kept.
        val padded = List(Tuning.PANTRY_MAX) { "mystery" } + ids.take(Tuning.PANTRY_MAX + 5)
        val paddedJson = """{"version":2,"pantry":${padded.joinToString(",", "[", "]") { "\"$it\"" }}}"""
        assertEquals(ids.take(Tuning.PANTRY_MAX), SaveCodec.decode(paddedJson).pantry)
        // Exactly full is left alone.
        val full = ids.take(Tuning.PANTRY_MAX)
        assertEquals(full, SaveCodec.decode(SaveCodec.encode(GameState(pantry = full))).pantry)
    }

    @Test
    fun aWronglyTypedPantryKeepsThePets() {
        // A pantry that is not a list reads as an empty shelf; it never costs the player their killers.
        for (bad in listOf("\"grill\"", "{\"grill\":1}", "42", "true", "{}", "null")) {
            val json = v2(""""JASON":{"level":3,"wins":4},"FREDDY":{"level":2}""", selected = "FREDDY", extra = ""","pantry":$bad,"lastSeenMs":1760000000000""")
            val decoded = SaveCodec.decodeOrNull(json)
            assertNotNull(bad, decoded)
            decoded!!
            assertTrue(bad, decoded.pantry.isEmpty())
            assertEquals(bad, setOf(PetId.JASON, PetId.FREDDY), decoded.pets.keys)
            assertEquals(bad, 3, decoded.pets.getValue(PetId.JASON).level)
            assertEquals(bad, PetId.FREDDY, decoded.selected)
            assertEquals(bad, 1_760_000_000_000L, decoded.lastSeenMs)
        }
    }

    @Test
    fun theRestOfTheSaveStillMustBeReadable() {
        // The pantry is read on its own, but a wrongly typed version or lastSeenMs still makes the save unusable.
        assertNull(SaveCodec.decodeOrNull("""{"version":2,"pantry":["grill"],"lastSeenMs":"yesterday"}"""))
        assertNull(SaveCodec.decodeOrNull("""{"version":3,"pantry":["grill"]}"""))
        // The same goes for the reveal, read on its own too.
        assertNull(SaveCodec.decodeOrNull("""{"version":2,"revealed":["JAWS"],"lastSeenMs":"yesterday"}"""))
        assertEquals(defaults, SaveCodec.decode("""{"version":1,"pantry":["grill"]}"""))
    }

    @Test
    fun recipesFoundRoundTrip() {
        val state = GameState(pets = mapOf(PetId.JASON to PetState(PetId.JASON)), selected = PetId.JASON, recipesFound = setOf("fried_egg", "steak"))
        val json = SaveCodec.encode(state)
        assertTrue(json, json.contains("\"recipesFound\":["))
        assertTrue("no new save version", json.contains("\"version\":2"))
        assertEquals(state, SaveCodec.decode(json))
        assertTrue(defaults.recipesFound.isEmpty())
        assertTrue("a save from before the field", SaveCodec.decode(v2(""""JASON":{"level":5}""", selected = "JASON")).recipesFound.isEmpty())
        // Unknown ids, a way to cook and a botch (none of them a dish) are dropped; not a list is nothing found, pets kept.
        val odd = SaveCodec.decode(v2(""""JASON":{"level":5}""", selected = "JASON", extra = ""","recipesFound":["fried_egg","grill","pile_of_slop",7,"zzz"]"""))
        assertEquals(setOf("fried_egg"), odd.recipesFound)
        val notAList = SaveCodec.decode(v2(""""JASON":{"level":5}""", selected = "JASON", extra = ""","recipesFound":"fried_egg""""))
        assertTrue(notAList.recipesFound.isEmpty())
        assertEquals(5, notAList.pets.getValue(PetId.JASON).level)
    }

    @Test
    fun revealedRoundTrips() {
        val state = GameState(
            pets = PetId.entries.filterNot { it.secret }.associateWith { PetState(it) },
            selected = PetId.MICHAEL,
            revealed = setOf("JAWS"),
        )
        val json = SaveCodec.encode(state)
        assertTrue(json, json.contains("\"revealed\":[\"JAWS\"]"))
        assertTrue("the reveal is no new save version", json.contains("\"version\":2"))
        assertEquals(state, SaveCodec.decode(json))
        // Nothing revealed is the default, and a save from before the secret (no key at all) has nothing revealed.
        assertTrue(defaults.revealed.isEmpty())
        assertTrue(SaveCodec.decode(v2(""""JASON":{"level":5}""", selected = "JASON")).revealed.isEmpty())
        // He joins the stable like anyone: a save with him in it keeps him.
        val withHim = state.copy(pets = state.pets + (PetId.JAWS to PetState(PetId.JAWS, level = 4)), selected = PetId.JAWS)
        assertEquals(withHim, SaveCodec.decode(SaveCodec.encode(withHim)))
    }

    @Test
    fun aWronglyTypedRevealedKeepsThePets() {
        // A reveal that is not a list reads as nothing revealed; it never costs the player their killers.
        for (bad in listOf("\"JAWS\"", "{\"JAWS\":true}", "42", "true", "{}", "null")) {
            val json = v2(""""JASON":{"level":3,"wins":4},"FREDDY":{"level":2}""", selected = "FREDDY", extra = ""","revealed":$bad,"lastSeenMs":1760000000000""")
            val decoded = SaveCodec.decodeOrNull(json)
            assertNotNull(bad, decoded)
            decoded!!
            assertTrue(bad, decoded.revealed.isEmpty())
            assertEquals(bad, setOf(PetId.JASON, PetId.FREDDY), decoded.pets.keys)
            assertEquals(bad, 3, decoded.pets.getValue(PetId.JASON).level)
            assertEquals(bad, PetId.FREDDY, decoded.selected)
            assertEquals(bad, 1_760_000_000_000L, decoded.lastSeenMs)
        }
    }

    @Test
    fun unknownRevealedNamesAreDropped() {
        // A killer a later build added, a wrongly cased name, an empty one and entries that are not strings at all.
        val json = v2(
            """"JASON":{"level":2}""",
            selected = "JASON",
            extra = ""","revealed":["PENNYWISE","jaws","",7,null,true,["JAWS"],{"id":"JAWS"},"JAWS","JAWS"]""",
        )
        val decoded = SaveCodec.decode(json)
        assertEquals(setOf("JAWS"), decoded.revealed)
        assertEquals(2, decoded.pets.getValue(PetId.JASON).level)
        assertEquals(emptySet<String>(), SaveCodec.decode(v2(""""JASON":{}""", extra = ""","revealed":["PENNYWISE"]""")).revealed)
    }

    @Test
    fun favoriteFoundRoundTrips() {
        val jason = PetState(PetId.JASON, favoriteFound = true)
        val state = GameState(pets = mapOf(PetId.JASON to jason, PetId.FREDDY to PetState(PetId.FREDDY)), selected = PetId.JASON)
        val back = SaveCodec.decode(SaveCodec.encode(state))
        assertEquals(state, back)
        assertTrue(back.pets.getValue(PetId.JASON).favoriteFound)
        assertEquals(false, back.pets.getValue(PetId.FREDDY).favoriteFound)
        // A save from before the pantry: no key at all, so no favorite has been found.
        val old = pet(PetId.JASON, """{"id":"JASON","level":5,"wins":2}""")
        assertEquals(false, old.favoriteFound)
        assertEquals(5, old.level)
        // A wrongly typed flag is one bad field: that pet resets to defaults and stays unlocked.
        assertEquals(PetState(PetId.JASON), pet(PetId.JASON, """{"level":7,"favoriteFound":"yes"}"""))
    }

    @Test
    fun mealFieldsRoundTripAndAreCleaned() {
        val jason = PetState(PetId.JASON, lastMeal = "steak", lastMealAtMs = 1_760_000_000_000L, tastesLearned = setOf("SAVORY", "GROSS"))
        val state = GameState(pets = mapOf(PetId.JASON to jason, PetId.FREDDY to PetState(PetId.FREDDY)), selected = PetId.JASON)
        val back = SaveCodec.decode(SaveCodec.encode(state))
        assertEquals(state, back)
        assertEquals("steak", back.pets.getValue(PetId.JASON).lastMeal)
        assertEquals(1_760_000_000_000L, back.pets.getValue(PetId.JASON).lastMealAtMs)
        assertEquals(setOf("SAVORY", "GROSS"), back.pets.getValue(PetId.JASON).tastesLearned)
        // A pet from 0.8.2 has none of the fields: it has eaten nothing and learned nothing.
        val old = pet(PetId.JASON, """{"id":"JASON","level":5,"wins":2,"favoriteFound":true}""")
        assertNull(old.lastMeal)
        assertNull(old.lastMealAtMs)
        assertEquals(emptySet<String>(), old.tastesLearned)
        assertEquals(5, old.level)
        assertEquals(PetState(PetId.JASON), pet(PetId.JASON, """{}"""))
        // A meal this build does not know (a later build's dish) is dropped; a real one, even a botch or a raw food, is kept.
        assertNull(pet(PetId.JASON, """{"level":5,"lastMeal":"nonsense","lastMealAtMs":1760000000000}""").lastMeal)
        assertEquals("pile_of_slop", pet(PetId.JASON, """{"lastMeal":"pile_of_slop"}""").lastMeal)
        assertEquals("grill", pet(PetId.JASON, """{"lastMeal":"grill"}""").lastMeal)
        // A meal eaten before any real clock could say so is no meal at all: both fields go, as a save with none.
        for (at in listOf(Long.MIN_VALUE, -1L, 0L, Tuning.MIN_VALID_EPOCH_MS - 1)) {
            val bad = pet(PetId.JASON, """{"level":5,"lastMeal":"steak","lastMealAtMs":$at}""")
            assertNull("$at", bad.lastMeal)
            assertNull("$at", bad.lastMealAtMs)
            assertEquals(5, bad.level)
        }
        val earliest = pet(PetId.JASON, """{"lastMeal":"steak","lastMealAtMs":${Tuning.MIN_VALID_EPOCH_MS}}""")
        assertEquals("steak", earliest.lastMeal)
        assertEquals(Tuning.MIN_VALID_EPOCH_MS, earliest.lastMealAtMs)
        // Only flavors this build knows are kept, by exact name.
        assertEquals(setOf("SWEET"), pet(PetId.JASON, """{"tastesLearned":["SWEET","salty"]}""").tastesLearned)
        assertEquals(setOf("SWEET"), pet(PetId.JASON, """{"tastesLearned":["SWEET","sweet","SOUR"]}""").tastesLearned)
        // A wrongly typed field is one bad field: that pet resets to defaults and stays unlocked.
        assertEquals(PetState(PetId.JASON), pet(PetId.JASON, """{"level":7,"lastMeal":7}"""))
        assertEquals(PetState(PetId.JASON), pet(PetId.JASON, """{"level":7,"tastesLearned":"SWEET"}"""))
    }

    @Test
    fun roundTripOfAFreshGame() {
        assertEquals(defaults, SaveCodec.decode(SaveCodec.encode(defaults)))
    }

    @Test
    fun encodeWritesDefaultedFields() {
        // encodeDefaults = true: a default-valued save still spells out its version and stats.
        val json = SaveCodec.encode(GameState(pets = mapOf(PetId.JASON to PetState(PetId.JASON)), selected = PetId.JASON))
        assertTrue(json.contains("\"version\":2"))
        assertTrue(json.contains("\"hunger\":20.0"))
    }

    @Test
    fun nullBlankAndGarbageGiveAFreshGame() {
        assertEquals(defaults, SaveCodec.decode(null))
        assertEquals(defaults, SaveCodec.decode(""))
        assertEquals(defaults, SaveCodec.decode("   \n\t "))
        assertEquals(defaults, SaveCodec.decode("{not json"))
        assertEquals(defaults, SaveCodec.decode("[]"))
        assertEquals(defaults, SaveCodec.decode("42"))
        assertEquals(defaults, SaveCodec.decode("""{"version":"two"}"""))
        assertEquals(defaults, SaveCodec.decode("""{"version":2,"pets":[1,2,3]}"""))
    }

    /** A save exactly as v0.1.0 (four pets) wrote it. */
    private val v010Save = """
        {"version":1,"pets":{
        "JASON":{"id":"JASON","hunger":12.5,"funLevel":64.0,"strength":31.0,"level":3,"xp":120,"moodBonus":4.5,"wins":5,"losses":2,"sceneIndex":1,"fakeDeathPending":false},
        "GHOSTFACE":{"id":"GHOSTFACE","hunger":88.0,"funLevel":5.0,"strength":61.0,"level":7,"xp":640,"moodBonus":30.0,"wins":12,"losses":9,"sceneIndex":2,"fakeDeathPending":false}},
        "selected":"GHOSTFACE","lastSeenMs":1761000000000}
    """.trimIndent()

    /** A save as v0.3.0 wrote it: version 1, all seven killers, the fresh-join mark. */
    private val v030Save = """
        {"version":1,"pets":{
        "JASON":{"id":"JASON","hunger":12.5,"funLevel":64.0,"strength":31.0,"level":3,"xp":120,"moodBonus":4.5,"wins":5,"losses":2,"sceneIndex":1,"fakeDeathPending":false,"freshJoin":false},
        "LEATHERFACE":{"id":"LEATHERFACE","hunger":20.0,"funLevel":80.0,"strength":20.0,"level":1,"xp":0,"moodBonus":0.0,"wins":0,"losses":0,"sceneIndex":0,"fakeDeathPending":false,"freshJoin":true}},
        "selected":"LEATHERFACE","lastSeenMs":1777000000000}
    """.trimIndent()

    @Test
    fun everyOlderSaveStartsOver() {
        // Everyone starts over with one starter: a version-1 save (v0.1.0 to v0.3.0) reads as a fresh game, and
        // as a usable one, so the new game is saved over it.
        for (old in listOf(v010Save, v030Save, """{"version":1,"selected":"JASON"}""", """{"version":0}""", """{"version":-5}""")) {
            assertEquals(old, defaults, SaveCodec.decodeOrNull(old))
            assertEquals(old, defaults, SaveCodec.decode(old))
        }
    }

    @Test
    fun theStableIsOnlyTheKillersInTheSave() {
        val decoded = SaveCodec.decode(v2(""""JASON":{"id":"JASON","hunger":55.0,"level":2}""", selected = "JASON"))
        assertEquals(setOf(PetId.JASON), decoded.pets.keys)
        assertEquals(55.0, decoded.pets.getValue(PetId.JASON).hunger, 0.0)
        assertEquals(2, decoded.pets.getValue(PetId.JASON).level)
        assertEquals(PetId.JASON, decoded.selected)
    }

    @Test
    fun aSelectionOutsideTheStableFallsBackToTheFirstKillerInIt() {
        // Never none while the stable has killers: none would reopen the starter pick and hand out a free killer.
        val two = """"MICHAEL":{"level":3},"FREDDY":{"level":2}"""
        assertEquals(PetId.FREDDY, SaveCodec.decode(v2(two, selected = "JASON")).selected)
        assertEquals(PetId.FREDDY, SaveCodec.decode(v2(two, selected = "PENNYWISE")).selected)
        assertEquals(PetId.FREDDY, SaveCodec.decode(v2(two)).selected)
        assertEquals(PetId.MICHAEL, SaveCodec.decode(v2(two, selected = "MICHAEL")).selected)
        // With an empty stable there is no one to select: the game starts at the starter pick.
        assertNull(SaveCodec.decode("""{"version":2,"selected":"JASON"}""").selected)
    }

    @Test
    fun unknownFieldsIgnored() {
        val json = v2(""""JASON": {"id": "JASON", "level": 3, "futureField": true}""", selected = "JASON", extra = ""","mystery":{"nested":[1,2,3]}""")
        val decoded = SaveCodec.decode(json)
        assertEquals(PetId.JASON, decoded.selected)
        assertEquals(3, decoded.pets.getValue(PetId.JASON).level)
    }

    @Test
    fun futureVersionIsUnreadable() {
        val json = """{"version":3,"selected":"JASON","pets":{"JASON":{"id":"JASON","level":9}}}"""
        assertNull(SaveCodec.decodeOrNull(json))
        assertEquals(defaults, SaveCodec.decode(json))
    }

    @Test
    fun unknownPetKeyIsSkippedAndTheValidPetsKept() {
        // A save written by a later build that added a character.
        val decoded = SaveCodec.decode(
            v2(
                """"JASON":{"id":"JASON","level":3,"wins":4},"PENNYWISE":{"id":"PENNYWISE","level":12},"FREDDY":{"hunger":61.5}""",
                selected = "FREDDY",
                extra = ""","lastSeenMs":1760000000000""",
            ),
        )
        assertEquals(setOf(PetId.JASON, PetId.FREDDY), decoded.pets.keys)
        assertEquals(PetId.FREDDY, decoded.selected)
        assertEquals(1_760_000_000_000L, decoded.lastSeenMs)
        assertEquals(4, decoded.pets.getValue(PetId.JASON).wins)
        assertEquals(61.5, decoded.pets.getValue(PetId.FREDDY).hunger, 0.0)
    }

    @Test
    fun oneMalformedPetIsResetButStaysUnlocked() {
        val decoded = SaveCodec.decode(
            v2(""""JASON":{"id":"JASON","hunger":"lots","level":7},"FREDDY":{"level":4},"GHOSTFACE":42,"PINHEAD":null""", selected = "FREDDY"),
        )
        assertEquals(setOf(PetId.JASON, PetId.FREDDY, PetId.GHOSTFACE, PetId.PINHEAD), decoded.pets.keys)
        assertEquals(PetState(PetId.JASON), decoded.pets.getValue(PetId.JASON))
        assertEquals(PetState(PetId.GHOSTFACE), decoded.pets.getValue(PetId.GHOSTFACE))
        assertEquals(PetState(PetId.PINHEAD), decoded.pets.getValue(PetId.PINHEAD))
        assertEquals(4, decoded.pets.getValue(PetId.FREDDY).level)
    }

    @Test
    fun idNormalisedToMapKey() {
        val decoded = SaveCodec.decode(v2(""""JASON":{"id":"FREDDY","level":5}"""))
        assertEquals(setOf(PetId.JASON), decoded.pets.keys)
        assertEquals(PetId.JASON, decoded.pets.getValue(PetId.JASON).id)
        assertEquals(5, decoded.pets.getValue(PetId.JASON).level)
    }

    @Test
    fun truncatedPayloadIsUnreadable() {
        val full = SaveCodec.encode(
            GameState(pets = mapOf(PetId.JASON to PetState(PetId.JASON)), selected = PetId.JASON, lastSeenMs = 1_760_000_000_000L),
        )
        for (cut in listOf(1, full.length / 4, full.length / 2, full.length - 1)) {
            assertNull("cut at $cut", SaveCodec.decodeOrNull(full.substring(0, cut)))
            assertEquals("cut at $cut", defaults, SaveCodec.decode(full.substring(0, cut)))
        }
    }

    @Test
    fun decodeOrNullReadsEverythingElse() {
        // No save yet is a first run, not a corrupt save.
        assertEquals(defaults, SaveCodec.decodeOrNull(null))
        assertEquals(defaults, SaveCodec.decodeOrNull(" "))
        val state = GameState(pets = mapOf(PetId.PINHEAD to PetState(PetId.PINHEAD)), selected = PetId.PINHEAD)
        assertEquals(state, SaveCodec.decodeOrNull(SaveCodec.encode(state)))
        // One bad pet or an unknown one does not make the whole save unreadable.
        assertNotNull(SaveCodec.decodeOrNull(v2(""""JASON":42""")))
        assertNotNull(SaveCodec.decodeOrNull(v2(""""PENNYWISE":{"level":3}""")))
        assertNotNull(SaveCodec.decodeOrNull("""{"version":2,"pets":null}"""))
        assertNull(SaveCodec.decodeOrNull("""{"version":2,"lastSeenMs":"yesterday"}"""))
    }

    private fun pet(id: PetId, json: String): PetState = SaveCodec.decode(v2("\"${id.name}\":$json")).pets.getValue(id)

    @Test
    fun levelAndXpAreClamped() {
        assertEquals(1, pet(PetId.JASON, """{"level":0}""").level)
        assertEquals(1, pet(PetId.JASON, """{"level":-7}""").level)
        val max = pet(PetId.JASON, """{"level":1000,"xp":999999}""")
        assertEquals(99, max.level)
        assertEquals(9_899, max.xp) // below the cost of the next level (100 * 99)
        assertEquals(0, pet(PetId.JASON, """{"level":3,"xp":-50}""").xp)
        assertEquals(299, pet(PetId.JASON, """{"level":3,"xp":5000}""").xp)
        assertEquals(99, pet(PetId.JASON, """{"level":0,"xp":500}""").xp) // the cap follows the clamped level
    }

    @Test
    fun sceneIndexWinsAndLossesAreClamped() {
        assertEquals(0, pet(PetId.JASON, """{"sceneIndex":-1}""").sceneIndex)
        assertEquals(2, pet(PetId.JASON, """{"sceneIndex":99}""").sceneIndex)
        assertEquals(1, pet(PetId.JASON, """{"sceneIndex":1}""").sceneIndex)
        val p = pet(PetId.JASON, """{"wins":-4,"losses":-1}""")
        assertEquals(0, p.wins)
        assertEquals(0, p.losses)
    }

    @Test
    fun statsAreClampedAndNonFiniteOnesRepaired() {
        val high = pet(PetId.JASON, """{"hunger":250.5,"funLevel":101.0,"strength":1e9,"moodBonus":75.0}""")
        assertEquals(100.0, high.hunger, 0.0)
        assertEquals(100.0, high.funLevel, 0.0)
        assertEquals(100.0, high.strength, 0.0)
        assertEquals(30.0, high.moodBonus, 0.0)
        val low = pet(PetId.JASON, """{"hunger":-3.0,"funLevel":-0.5,"strength":-1e9,"moodBonus":-75.0}""")
        assertEquals(0.0, low.hunger, 0.0)
        assertEquals(-30.0, low.moodBonus, 0.0)
        val odd = pet(PetId.JASON, """{"hunger":NaN,"funLevel":NaN,"strength":Infinity,"moodBonus":NaN}""")
        assertEquals(PetState(PetId.JASON).hunger, odd.hunger, 0.0)
        assertEquals(PetState(PetId.JASON).funLevel, odd.funLevel, 0.0)
        assertEquals(100.0, odd.strength, 0.0)
        assertEquals(0.0, odd.moodBonus, 0.0)
    }
}
