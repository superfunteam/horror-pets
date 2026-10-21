package team.superfun.horrorpets.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FoodTest {
    @Test
    fun nextFoodNeverRepeatsTheLastOne() {
        val rng = Random(3)
        var last: Food? = null
        val dropped = List(300) { nextFood(last, rng).also { f -> assertNotEquals(last, f); last = f } }
        assertEquals(Food.entries.toSet(), dropped.toSet())
    }

    @Test
    fun theFirstFoodCanBeAnything() {
        val rng = Random(11)
        assertEquals(Food.entries.toSet(), List(200) { nextFood(null, rng) }.toSet())
    }

    @Test
    fun eachFoodHasItsOwnFile() {
        assertEquals("meat.webp", Food.MEAT.file)
        assertEquals(Food.entries.size, Food.entries.map { it.file }.toSet().size)
    }
}
