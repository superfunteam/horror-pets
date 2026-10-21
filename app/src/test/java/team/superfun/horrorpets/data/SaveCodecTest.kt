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

    /** The pets marked as joining fresh (the save had no entry for them). */
    private val GameState.fresh: Set<PetId> get() = pets.filterValues { it.freshJoin }.keys

    /** This state with every fresh-join mark cleared. */
    private fun GameState.settled(): GameState = copy(pets = pets.mapValues { (_, p) -> p.copy(freshJoin = false) })

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
        val zombie = SaveCodec.decode("""{"selected":"ZOMBIE"}""")
        assertEquals(defaults, zombie.settled())
        assertEquals(PetId.entries.toSet(), zombie.fresh)
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
        // The missing ones join at their defaults, marked fresh.
        assertEquals(PetState(PetId.FREDDY, freshJoin = true), decoded.pets.getValue(PetId.FREDDY))
        assertEquals(PetState(PetId.PINHEAD, freshJoin = true), decoded.pets.getValue(PetId.PINHEAD))
        assertEquals(PetState(PetId.GHOSTFACE, freshJoin = true), decoded.pets.getValue(PetId.GHOSTFACE))
        assertEquals(PetId.entries.toSet() - PetId.JASON, decoded.fresh)
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

    /** The killers v0.1.0 did not have: every one of them joins a v0.1.0 save fresh. */
    private val sinceV010 = setOf(PetId.CHUCKY, PetId.MICHAEL, PetId.LEATHERFACE)

    @Test
    fun aV010SaveGainsTheNewKillersAndKeepsTheOriginalFour() {
        val decoded = SaveCodec.decode(v010Save)
        assertEquals(PetId.entries.toSet(), decoded.pets.keys)
        assertEquals(7, decoded.pets.size)
        for (id in sinceV010) assertEquals("$id", PetState(id, freshJoin = true), decoded.pets.getValue(id))
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
        // The pets the save did not have join fresh, so the time away does not touch them.
        assertEquals(sinceV010, decoded.fresh)
        // Saved again before the time away was applied (a stale clock), the mark is written out and kept.
        val again = SaveCodec.decode(SaveCodec.encode(decoded))
        assertEquals(decoded, again)
        assertEquals(sinceV010, again.fresh)
        assertTrue(SaveCodec.encode(decoded).contains("\"MICHAEL\""))
        assertTrue(SaveCodec.encode(decoded).contains("\"freshJoin\":true"))
    }

    /** A save exactly as v0.2.0 (six pets, with the fresh-join mark) wrote it, Chucky mid-join under a stale clock. */
    private val v020Save = """
        {"version":1,"pets":{
        "JASON":{"id":"JASON","hunger":12.5,"funLevel":64.0,"strength":31.0,"level":3,"xp":120,"moodBonus":4.5,"wins":5,"losses":2,"sceneIndex":1,"fakeDeathPending":false,"freshJoin":false},
        "FREDDY":{"id":"FREDDY","hunger":40.0,"funLevel":22.25,"strength":18.0,"level":2,"xp":15,"moodBonus":0.0,"wins":1,"losses":3,"sceneIndex":2,"fakeDeathPending":true,"freshJoin":false},
        "PINHEAD":{"id":"PINHEAD","hunger":20.0,"funLevel":80.0,"strength":20.0,"level":1,"xp":0,"moodBonus":-10.0,"wins":0,"losses":1,"sceneIndex":0,"fakeDeathPending":false,"freshJoin":false},
        "GHOSTFACE":{"id":"GHOSTFACE","hunger":88.0,"funLevel":5.0,"strength":61.0,"level":7,"xp":640,"moodBonus":30.0,"wins":12,"losses":9,"sceneIndex":2,"fakeDeathPending":false,"freshJoin":false},
        "CHUCKY":{"id":"CHUCKY","hunger":20.0,"funLevel":80.0,"strength":20.0,"level":1,"xp":0,"moodBonus":0.0,"wins":0,"losses":0,"sceneIndex":0,"fakeDeathPending":false,"freshJoin":true},
        "MICHAEL":{"id":"MICHAEL","hunger":55.0,"funLevel":41.0,"strength":44.0,"level":4,"xp":210,"moodBonus":-3.0,"wins":6,"losses":4,"sceneIndex":1,"fakeDeathPending":false,"freshJoin":false}},
        "selected":"MICHAEL","lastSeenMs":1777000000000}
    """.trimIndent()

    @Test
    fun aV020SaveGainsLeatherfaceFreshAndKeepsTheSix() {
        val decoded = SaveCodec.decode(v020Save)
        assertEquals(PetId.entries.toSet(), decoded.pets.keys)
        assertEquals(7, decoded.pets.size)
        // Leatherface was not in the save: he joins at his defaults, marked fresh.
        assertEquals(PetState(PetId.LEATHERFACE, freshJoin = true), decoded.pets.getValue(PetId.LEATHERFACE))
        // The six are kept exactly, Chucky's own fresh-join mark (a v0.1.0 upgrade not yet applied) included.
        assertEquals(setOf(PetId.CHUCKY, PetId.LEATHERFACE), decoded.fresh)
        assertEquals(
            PetState(PetId.MICHAEL, 55.0, 41.0, 44.0, level = 4, xp = 210, moodBonus = -3.0, wins = 6, losses = 4, sceneIndex = 1),
            decoded.pets.getValue(PetId.MICHAEL),
        )
        assertEquals(
            PetState(PetId.GHOSTFACE, 88.0, 5.0, 61.0, level = 7, xp = 640, moodBonus = 30.0, wins = 12, losses = 9, sceneIndex = 2),
            decoded.pets.getValue(PetId.GHOSTFACE),
        )
        assertEquals(
            PetState(PetId.FREDDY, 40.0, 22.25, 18.0, level = 2, xp = 15, wins = 1, losses = 3, sceneIndex = 2, fakeDeathPending = true),
            decoded.pets.getValue(PetId.FREDDY),
        )
        assertEquals(PetId.MICHAEL, decoded.selected)
        assertEquals(1_777_000_000_000L, decoded.lastSeenMs)
        // Saved again before the time away was applied, Leatherface's mark is written out and kept.
        val again = SaveCodec.decode(SaveCodec.encode(decoded))
        assertEquals(decoded, again)
        assertTrue(SaveCodec.encode(decoded).contains("\"LEATHERFACE\""))
    }

    @Test
    fun missingPetsFieldFilledWithDefaults() {
        val decoded = SaveCodec.decode("""{"version":1,"selected":"FREDDY"}""")
        assertEquals(PetId.FREDDY, decoded.selected)
        assertEquals(defaults.pets, decoded.settled().pets)
        assertEquals(PetId.entries.toSet(), decoded.fresh)
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
        assertEquals(PetState(PetId.JASON, freshJoin = true), decoded.pets.getValue(PetId.JASON))
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
        assertEquals(PetState(PetId.PINHEAD, freshJoin = true), decoded.pets.getValue(PetId.PINHEAD))
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
        // A damaged entry is still a pet the player had: only the pets with no entry join fresh.
        assertEquals(setOf(PetId.CHUCKY, PetId.MICHAEL, PetId.LEATHERFACE), decoded.fresh)
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
        assertEquals(PetState(PetId.FREDDY, freshJoin = true), decoded.pets.getValue(PetId.FREDDY))
        for ((key, p) in decoded.pets) assertEquals(key, p.id)
    }

    @Test
    fun nullPetValueGivesDefaults() {
        // JASON's entry is there (null), so only the pets with no entry at all join fresh.
        val nullJason = SaveCodec.decode("""{"pets":{"JASON":null}}""")
        assertEquals(defaults, nullJason.settled())
        assertEquals(PetId.entries.toSet() - PetId.JASON, nullJason.fresh)
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
