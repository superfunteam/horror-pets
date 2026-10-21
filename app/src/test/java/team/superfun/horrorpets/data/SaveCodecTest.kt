package team.superfun.horrorpets.data

import org.junit.Assert.assertEquals
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
                    sceneIndex = 5,
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
        assertEquals(true, json.contains("\"version\":1"))
        assertEquals(true, json.contains("\"hunger\":20.0"))
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
        assertEquals(defaults, SaveCodec.decode("""{"selected":"ZOMBIE"}"""))
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
    }

    @Test
    fun missingPetsFieldFilledWithDefaults() {
        val decoded = SaveCodec.decode("""{"version":1,"selected":"FREDDY"}""")
        assertEquals(PetId.FREDDY, decoded.selected)
        assertEquals(defaults.pets, decoded.pets)
    }

    @Test
    fun futureVersionGivesDefaults() {
        val json = """{"version":2,"selected":"JASON","pets":{"JASON":{"id":"JASON","level":9}}}"""
        assertEquals(defaults, SaveCodec.decode(json))
    }
}
