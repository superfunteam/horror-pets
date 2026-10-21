package team.superfun.horrorpets.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTest {
    @Test
    fun fourPetsWithDisplayNames() {
        assertEquals(
            listOf("Jason", "Freddy", "Pinhead", "Ghostface"),
            PetId.entries.map { it.displayName },
        )
    }

    @Test
    fun defaultGameStateHasEveryPetAtDefaults() {
        val s = GameState()
        assertEquals(PetId.entries.toSet(), s.pets.keys)
        assertEquals(PetState(PetId.GHOSTFACE), s.pets.getValue(PetId.GHOSTFACE))
        assertEquals(1, s.version)
        assertEquals(null, s.selected)
        assertEquals(null, s.lastSeenMs)
    }

    @Test
    fun petDefaults() {
        val p = PetState(PetId.JASON)
        assertEquals(20.0, p.hunger, 0.0)
        assertEquals(80.0, p.funLevel, 0.0)
        assertEquals(20.0, p.strength, 0.0)
        assertEquals(1, p.level)
        assertEquals(0, p.xp)
        assertEquals(0.0, p.moodBonus, 0.0)
        assertEquals(false, p.fakeDeathPending)
    }

    @Test
    fun gameStateIsSerializable() {
        val s = GameState(
            pets = PetId.entries.associateWith { PetState(it, hunger = 33.5, level = 3, xp = 42) },
            selected = PetId.PINHEAD,
            lastSeenMs = 1_760_000_000_000L,
        )
        val json = Json { encodeDefaults = true }
        val round = json.decodeFromString<GameState>(json.encodeToString(s))
        assertEquals(s, round)
    }
}
