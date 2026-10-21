package team.superfun.horrorpets.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class SaveCodecTest {
    private val defaults = GameState()

    @Test
    fun roundTrip() {
        val state = GameState(
            pets = PetId.entries.associateWith { PetState(it) } + mapOf(
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
    fun roundTripOfDefaultsKeepsAllPets() {
        val decoded = SaveCodec.decode(SaveCodec.encode(defaults))
        assertEquals(defaults, decoded)
        assertEquals(PetId.entries.toSet(), decoded.pets.keys)
    }

    @Test
    fun encodeWritesDefaultedFields() {
        // encodeDefaults = true: a default-valued save still spells out version and stats.
        val json = SaveCodec.encode(defaults)
        assertTrue(json.contains("\"version\":1"))
        assertTrue(json.contains("\"hunger\":20.0"))
    }

    @Test
    fun nullGivesDefaults() {
        assertEquals(defaults, SaveCodec.decode(null))
    }

    @Test
    fun blankGivesDefaults() {
        assertEquals(defaults, SaveCodec.decode(""))
        assertEquals(defaults, SaveCodec.decode("   \n\t "))
    }

    @Test
    fun garbageGivesDefaults() {
        assertEquals(defaults, SaveCodec.decode("{not json"))
    }

    @Test
    fun wrongTypesGiveDefaults() {
        assertEquals(defaults, SaveCodec.decode("""{"version":"one"}"""))
        assertEquals(defaults, SaveCodec.decode("""{"pets":[1,2,3]}"""))
        // An unknown selection reads as none; with no pets object at all, every pet is a newcomer.
        assertEquals(defaults.copy(newcomers = PetId.entries.toSet()), SaveCodec.decode("""{"selected":"ZOMBIE"}"""))
        assertEquals(defaults, SaveCodec.decode("[]"))
        assertEquals(defaults, SaveCodec.decode("42"))
    }

    @Test
    fun unknownFieldsIgnored() {
        val json = """
            {
              "version": 1,
              "selected": "JASON",
              "mystery": {"nested": [1, 2, 3]},
              "pets": {
                "JASON": {"id": "JASON", "level": 3, "futureField": true}
              }
            }
        """.trimIndent()
        val decoded = SaveCodec.decode(json)
        assertEquals(PetId.JASON, decoded.selected)
        assertEquals(3, decoded.pets.getValue(PetId.JASON).level)
    }

    @Test
    fun missingPetFilledWithDefault() {
        val json = """{"version":1,"pets":{"JASON":{"id":"JASON","hunger":55.0,"level":2}}}"""
        val decoded = SaveCodec.decode(json)
        assertEquals(PetId.entries.toSet(), decoded.pets.keys)
        assertEquals(55.0, decoded.pets.getValue(PetId.JASON).hunger, 0.0)
        assertEquals(2, decoded.pets.getValue(PetId.JASON).level)
        assertEquals(PetState(PetId.FREDDY), decoded.pets.getValue(PetId.FREDDY))
        assertEquals(PetState(PetId.PINHEAD), decoded.pets.getValue(PetId.PINHEAD))
        assertEquals(PetState(PetId.GHOSTFACE), decoded.pets.getValue(PetId.GHOSTFACE))
        assertEquals(PetId.entries.toSet() - PetId.JASON, decoded.newcomers)
    }

    /** A save exactly as v0.1.0 (four pets) wrote it: every field spelled out, in declaration order. */
    private val v010Save = """
        {"version":1,"pets":{
        "JASON":{"id":"JASON","hunger":12.5,"funLevel":64.0,"strength":31.0,"level":3,"xp":120,"moodBonus":4.5,"wins":5,"losses":2,"sceneIndex":1,"fakeDeathPending":false},
        "FREDDY":{"id":"FREDDY","hunger":40.0,"funLevel":22.25,"strength":18.0,"level":2,"xp":15,"moodBonus":0.0,"wins":1,"losses":3,"sceneIndex":2,"fakeDeathPending":true},
        "PINHEAD":{"id":"PINHEAD","hunger":20.0,"funLevel":80.0,"strength":20.0,"level":1,"xp":0,"moodBonus":-10.0,"wins":0,"losses":1,"sceneIndex":0,"fakeDeathPending":false},
        "GHOSTFACE":{"id":"GHOSTFACE","hunger":88.0,"funLevel":5.0,"strength":61.0,"level":7,"xp":640,"moodBonus":30.0,"wins":12,"losses":9,"sceneIndex":2,"fakeDeathPending":false}},
        "selected":"GHOSTFACE","lastSeenMs":1761000000000}
    """.trimIndent()

    @Test
    fun aV010SaveGainsChuckyAndMichaelAndKeepsTheOriginalFour() {
        val decoded = SaveCodec.decode(v010Save)
        assertEquals(PetId.entries.toSet(), decoded.pets.keys)
        assertEquals(6, decoded.pets.size)
        assertEquals(PetState(PetId.CHUCKY), decoded.pets.getValue(PetId.CHUCKY))
        assertEquals(PetState(PetId.MICHAEL), decoded.pets.getValue(PetId.MICHAEL))
        assertEquals(
            PetState(PetId.JASON, 12.5, 64.0, 31.0, level = 3, xp = 120, moodBonus = 4.5, wins = 5, losses = 2, sceneIndex = 1),
            decoded.pets.getValue(PetId.JASON),
        )
        assertEquals(
            PetState(PetId.FREDDY, 40.0, 22.25, 18.0, level = 2, xp = 15, wins = 1, losses = 3, sceneIndex = 2, fakeDeathPending = true),
            decoded.pets.getValue(PetId.FREDDY),
        )
        assertEquals(PetState(PetId.PINHEAD, moodBonus = -10.0, losses = 1), decoded.pets.getValue(PetId.PINHEAD))
        assertEquals(
            PetState(PetId.GHOSTFACE, 88.0, 5.0, 61.0, level = 7, xp = 640, moodBonus = 30.0, wins = 12, losses = 9, sceneIndex = 2),
            decoded.pets.getValue(PetId.GHOSTFACE),
        )
        assertEquals(PetId.GHOSTFACE, decoded.selected)
        assertEquals(1_761_000_000_000L, decoded.lastSeenMs)
        assertEquals(1, decoded.version)
        // The pets the save did not have are marked as newcomers, so the time away does not touch them.
        assertEquals(setOf(PetId.CHUCKY, PetId.MICHAEL), decoded.newcomers)
        // Saved again, the new pets are written out and read back the same; the mark is never saved.
        val again = SaveCodec.decode(SaveCodec.encode(decoded))
        assertEquals(decoded.copy(newcomers = emptySet()), again)
        assertTrue(again.newcomers.isEmpty())
        assertTrue(SaveCodec.encode(decoded).contains("\"MICHAEL\""))
        assertFalse(SaveCodec.encode(decoded).contains("newcomers"))
    }

    @Test
    fun missingPetsFieldFilledWithDefaults() {
        val decoded = SaveCodec.decode("""{"version":1,"selected":"FREDDY"}""")
        assertEquals(PetId.FREDDY, decoded.selected)
        assertEquals(defaults.pets, decoded.pets)
        assertEquals(PetId.entries.toSet(), decoded.newcomers)
    }

    @Test
    fun futureVersionGivesDefaults() {
        val json = """{"version":2,"selected":"JASON","pets":{"JASON":{"id":"JASON","level":9}}}"""
        assertEquals(defaults, SaveCodec.decode(json))
    }

    private fun pet(id: PetId, json: String): PetState =
        SaveCodec.decode("""{"version":1,"pets":{"${id.name}":$json}}""").pets.getValue(id)

    @Test
    fun levelZeroCoercedToOne() {
        assertEquals(1, pet(PetId.JASON, """{"id":"JASON","level":0}""").level)
        assertEquals(1, pet(PetId.JASON, """{"id":"JASON","level":-7}""").level)
    }

    @Test
    fun levelAboveMaxCoercedTo99() {
        val p = pet(PetId.JASON, """{"id":"JASON","level":1000,"xp":999999}""")
        assertEquals(99, p.level)
        // xp stays below the cost of the next level (100 * 99).
        assertEquals(9_899, p.xp)
    }

    @Test
    fun negativeXpCoercedToZero() {
        assertEquals(0, pet(PetId.JASON, """{"id":"JASON","level":3,"xp":-50}""").xp)
    }

    @Test
    fun xpAtOrAboveNextLevelCostCoercedBelowIt() {
        // Level 3 -> 4 costs 300 xp, so the most a level-3 pet can hold is 299.
        assertEquals(299, pet(PetId.JASON, """{"id":"JASON","level":3,"xp":300}""").xp)
        assertEquals(299, pet(PetId.JASON, """{"id":"JASON","level":3,"xp":5000}""").xp)
        assertEquals(299, pet(PetId.JASON, """{"id":"JASON","level":3,"xp":299}""").xp)
    }

    @Test
    fun xpIsBoundedByTheCoercedLevel() {
        // Level 0 becomes 1 first, so the cap is 99, not -1.
        assertEquals(99, pet(PetId.JASON, """{"id":"JASON","level":0,"xp":500}""").xp)
    }

    @Test
    fun sceneIndexOutOfRangeCoerced() {
        assertEquals(0, pet(PetId.JASON, """{"id":"JASON","sceneIndex":-1}""").sceneIndex)
        assertEquals(2, pet(PetId.JASON, """{"id":"JASON","sceneIndex":3}""").sceneIndex)
        assertEquals(2, pet(PetId.JASON, """{"id":"JASON","sceneIndex":99}""").sceneIndex)
        assertEquals(1, pet(PetId.JASON, """{"id":"JASON","sceneIndex":1}""").sceneIndex)
    }

    @Test
    fun statsOutOfRangeCoerced() {
        val high = pet(
            PetId.JASON,
            """{"id":"JASON","hunger":250.5,"funLevel":101.0,"strength":1e9,"moodBonus":75.0}""",
        )
        assertEquals(100.0, high.hunger, 0.0)
        assertEquals(100.0, high.funLevel, 0.0)
        assertEquals(100.0, high.strength, 0.0)
        assertEquals(30.0, high.moodBonus, 0.0)

        val low = pet(
            PetId.JASON,
            """{"id":"JASON","hunger":-3.0,"funLevel":-0.5,"strength":-1e9,"moodBonus":-75.0}""",
        )
        assertEquals(0.0, low.hunger, 0.0)
        assertEquals(0.0, low.funLevel, 0.0)
        assertEquals(0.0, low.strength, 0.0)
        assertEquals(-30.0, low.moodBonus, 0.0)
    }

    @Test
    fun inRangeStatsAreKept() {
        val p = pet(PetId.JASON, """{"id":"JASON","hunger":0.0,"funLevel":100.0,"strength":42.5,"moodBonus":-30.0}""")
        assertEquals(0.0, p.hunger, 0.0)
        assertEquals(100.0, p.funLevel, 0.0)
        assertEquals(42.5, p.strength, 0.0)
        assertEquals(-30.0, p.moodBonus, 0.0)
    }

    @Test
    fun nanAndInfinityStatsSanitised() {
        val p = pet(
            PetId.JASON,
            """{"id":"JASON","hunger":NaN,"funLevel":NaN,"strength":Infinity,"moodBonus":NaN}""",
        )
        val defaults = PetState(PetId.JASON)
        assertEquals(defaults.hunger, p.hunger, 0.0)
        assertEquals(defaults.funLevel, p.funLevel, 0.0)
        assertEquals(100.0, p.strength, 0.0)
        assertEquals(0.0, p.moodBonus, 0.0)
        assertEquals(0.0, pet(PetId.JASON, """{"id":"JASON","hunger":-Infinity}""").hunger, 0.0)
        assertEquals(-30.0, pet(PetId.JASON, """{"id":"JASON","moodBonus":-Infinity}""").moodBonus, 0.0)
    }

    @Test
    fun negativeWinsAndLossesCoercedToZero() {
        val p = pet(PetId.JASON, """{"id":"JASON","wins":-4,"losses":-1}""")
        assertEquals(0, p.wins)
        assertEquals(0, p.losses)
        val q = pet(PetId.JASON, """{"id":"JASON","wins":7,"losses":2}""")
        assertEquals(7, q.wins)
        assertEquals(2, q.losses)
    }

    @Test
    fun petMissingIdRecoveredFromKey() {
        val json = """{"version":1,"pets":{"FREDDY":{"hunger":61.5,"level":4}}}"""
        val decoded = SaveCodec.decode(json)
        val freddy = decoded.pets.getValue(PetId.FREDDY)
        assertEquals(PetId.FREDDY, freddy.id)
        assertEquals(61.5, freddy.hunger, 0.0)
        assertEquals(4, freddy.level)
        assertEquals(PetState(PetId.JASON), decoded.pets.getValue(PetId.JASON))
    }

    @Test
    fun oneBadPetMissingIdDoesNotDiscardTheOthers() {
        val json = """
            {"version":1,"selected":"PINHEAD","lastSeenMs":1760000000000,
             "pets":{"JASON":{"level":2},"PINHEAD":{"id":"PINHEAD","wins":9}}}
        """.trimIndent()
        val decoded = SaveCodec.decode(json)
        assertEquals(PetId.PINHEAD, decoded.selected)
        assertEquals(1_760_000_000_000L, decoded.lastSeenMs)
        assertEquals(2, decoded.pets.getValue(PetId.JASON).level)
        assertEquals(9, decoded.pets.getValue(PetId.PINHEAD).wins)
    }

    @Test
    fun unknownPetKeyIsSkippedAndTheValidPetsKept() {
        // A save written by a later build that added a character.
        val json = """
            {"version":1,"selected":"FREDDY","lastSeenMs":1760000000000,
             "pets":{
               "JASON":{"id":"JASON","level":3,"wins":4},
               "PENNYWISE":{"id":"PENNYWISE","level":12,"hunger":5.0},
               "FREDDY":{"id":"FREDDY","hunger":61.5}
             }}
        """.trimIndent()
        val decoded = SaveCodec.decode(json)
        assertEquals(PetId.FREDDY, decoded.selected)
        assertEquals(1_760_000_000_000L, decoded.lastSeenMs)
        assertEquals(3, decoded.pets.getValue(PetId.JASON).level)
        assertEquals(4, decoded.pets.getValue(PetId.JASON).wins)
        assertEquals(61.5, decoded.pets.getValue(PetId.FREDDY).hunger, 0.0)
        assertEquals(PetState(PetId.PINHEAD), decoded.pets.getValue(PetId.PINHEAD))
        assertEquals(PetId.entries.toSet(), decoded.pets.keys)
    }

    @Test
    fun oneMalformedPetIsResetAndTheOthersKept() {
        val json = """
            {"version":1,"selected":"PINHEAD","lastSeenMs":1760000000000,
             "pets":{
               "JASON":{"id":"JASON","hunger":"lots","level":7},
               "FREDDY":{"id":"FREDDY","level":4},
               "PINHEAD":{"id":"PINHEAD","wins":9},
               "GHOSTFACE":42
             }}
        """.trimIndent()
        val decoded = SaveCodec.decode(json)
        assertEquals(PetId.PINHEAD, decoded.selected)
        assertEquals(1_760_000_000_000L, decoded.lastSeenMs)
        assertEquals(PetState(PetId.JASON), decoded.pets.getValue(PetId.JASON))
        assertEquals(PetState(PetId.GHOSTFACE), decoded.pets.getValue(PetId.GHOSTFACE))
        assertEquals(4, decoded.pets.getValue(PetId.FREDDY).level)
        assertEquals(9, decoded.pets.getValue(PetId.PINHEAD).wins)
        // A damaged entry is still a pet the player had: only the pets with no entry are newcomers.
        assertEquals(setOf(PetId.CHUCKY, PetId.MICHAEL), decoded.newcomers)
    }

    @Test
    fun unknownSelectedPetIsDroppedAndTheRestKept() {
        val json = """{"version":1,"selected":"PENNYWISE","lastSeenMs":1760000000000,"pets":{"JASON":{"level":3}}}"""
        val decoded = SaveCodec.decode(json)
        assertEquals(null, decoded.selected)
        assertEquals(1_760_000_000_000L, decoded.lastSeenMs)
        assertEquals(3, decoded.pets.getValue(PetId.JASON).level)
    }

    @Test
    fun idNormalisedToMapKey() {
        val json = """{"pets":{"JASON":{"id":"FREDDY","level":5}}}"""
        val decoded = SaveCodec.decode(json)
        val jason = decoded.pets.getValue(PetId.JASON)
        assertEquals(PetId.JASON, jason.id)
        assertEquals(5, jason.level)
        assertEquals(PetState(PetId.FREDDY), decoded.pets.getValue(PetId.FREDDY))
        for ((key, p) in decoded.pets) assertEquals(key, p.id)
    }

    @Test
    fun nullPetValueGivesDefaults() {
        // JASON's entry is there (null), so only the pets with no entry at all are newcomers.
        val nullJason = SaveCodec.decode("""{"pets":{"JASON":null}}""")
        assertEquals(defaults, nullJason.copy(newcomers = emptySet()))
        assertEquals(PetId.entries.toSet() - PetId.JASON, nullJason.newcomers)
        assertEquals(defaults, SaveCodec.decode("""{"pets":null}"""))
    }

    @Test
    fun truncatedPayloadGivesDefaults() {
        val full = SaveCodec.encode(GameState(selected = PetId.JASON, lastSeenMs = 1_760_000_000_000L))
        for (cut in listOf(1, full.length / 4, full.length / 2, full.length - 1)) {
            assertEquals("cut at $cut", defaults, SaveCodec.decode(full.substring(0, cut)))
        }
    }

    @Test
    fun decodeOrNullSignalsAWhollyUnreadableSave() {
        val full = SaveCodec.encode(GameState(selected = PetId.JASON, lastSeenMs = 1_760_000_000_000L))
        for (corrupt in listOf(
            "{not json",
            "[]",
            "42",
            full.substring(0, full.length / 2),
            """{"version":"one"}""",
            """{"pets":[1,2,3]}""",
            """{"pets":null}""",
            """{"lastSeenMs":"yesterday"}""",
            """{"version":2,"selected":"JASON"}""",
        )) {
            assertNull(corrupt, SaveCodec.decodeOrNull(corrupt))
            // decode() still never throws and falls back to a fresh game.
            assertEquals(corrupt, defaults, SaveCodec.decode(corrupt))
        }
    }

    @Test
    fun decodeOrNullReadsEverythingElse() {
        // No save yet is a first run, not a corrupt save.
        assertEquals(defaults, SaveCodec.decodeOrNull(null))
        assertEquals(defaults, SaveCodec.decodeOrNull(" "))
        val state = GameState(selected = PetId.PINHEAD, lastSeenMs = 1_760_000_000_000L)
        assertEquals(state, SaveCodec.decodeOrNull(SaveCodec.encode(state)))
        // One bad pet or an unknown one does not make the whole save unreadable.
        assertNotNull(SaveCodec.decodeOrNull("""{"version":1,"pets":{"JASON":42}}"""))
        assertNotNull(SaveCodec.decodeOrNull("""{"version":1,"pets":{"PENNYWISE":{"level":3}}}"""))
    }

    @Test
    fun versionBelowOneTreatedAsOne() {
        assertEquals(1, SaveCodec.decode("""{"version":0}""").version)
        assertEquals(1, SaveCodec.decode("""{"version":-5,"selected":"GHOSTFACE"}""").version)
        assertEquals(PetId.GHOSTFACE, SaveCodec.decode("""{"version":-5,"selected":"GHOSTFACE"}""").selected)
    }

    @Test
    fun decodedPetsAlwaysSatisfyInvariants() {
        val json = """
            {"pets":{
              "JASON":{"id":"JASON","level":-1,"xp":-1,"sceneIndex":-4,"wins":-1,"losses":-1,"hunger":-9.0},
              "FREDDY":{"level":500,"xp":2000000000,"sceneIndex":50,"funLevel":900.0,"strength":-900.0}
            }}
        """.trimIndent()
        val decoded = SaveCodec.decode(json)
        // Not the all-defaults fallback: the sanitised values themselves survive.
        assertEquals(99, decoded.pets.getValue(PetId.FREDDY).level)
        assertEquals(100.0, decoded.pets.getValue(PetId.FREDDY).funLevel, 0.0)
        for ((key, p) in decoded.pets) {
            assertEquals(key, p.id)
            assertTrue(p.level in 1..99)
            assertTrue(p.xp in 0 until 100 * p.level)
            assertTrue(p.sceneIndex in 0..2)
            assertTrue(p.hunger in 0.0..100.0)
            assertTrue(p.funLevel in 0.0..100.0)
            assertTrue(p.strength in 0.0..100.0)
            assertTrue(p.moodBonus in -30.0..30.0)
            assertTrue(p.wins >= 0 && p.losses >= 0)
        }
    }
}
