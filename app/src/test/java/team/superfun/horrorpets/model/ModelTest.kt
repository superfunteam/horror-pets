package team.superfun.horrorpets.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelTest {
    @Test
    fun sevenPetsWithDisplayNames() {
        assertEquals(
            listOf("Jason", "Freddy", "Pinhead", "Ghostface", "Chucky", "Michael", "Leatherface"),
            PetId.entries.map { it.displayName },
        )
    }

    @Test
    fun aDefaultGameIsAnEmptyStable() {
        val s = GameState()
        assertTrue(s.pets.isEmpty())
        assertEquals(null, s.selected)
        assertEquals(2, s.version)
        assertFalse(s.isUnlocked(PetId.JASON))
        val one = s.copy(pets = mapOf(PetId.MICHAEL to PetState(PetId.MICHAEL)), selected = PetId.MICHAEL)
        assertTrue(one.isUnlocked(PetId.MICHAEL))
        assertFalse(one.isUnlocked(PetId.JASON))
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
