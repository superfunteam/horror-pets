package team.superfun.horrorpets.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Slot

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
