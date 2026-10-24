package team.superfun.horrorpets.game.pantry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem.EGG
import team.superfun.horrorpets.model.PantryItem.FRIED_EGG
import team.superfun.horrorpets.model.PantryItem.GRILL
import team.superfun.horrorpets.model.PantryItem.RAW_STEAK

/** Recipes found: the dishes the cookbook makes, and whether a pair has made one before. */
class KnownDishTest {
    @Test
    fun theDishesAreEverySnackAndMealTheCookbookMakes() {
        assertTrue(DISHES.isNotEmpty())
        assertTrue(DISHES.all { it.kind == ItemKind.SNACK || it.kind == ItemKind.MEAL })
        assertTrue(FRIED_EGG in DISHES)
        assertTrue("the botches some pairs make on purpose are no dishes", RECIPES.values.any { it.kind == ItemKind.BOTCHED })
        assertEquals(DISHES.size, RECIPES.values.filterNot { it.kind == ItemKind.BOTCHED }.toSet().size)
    }

    @Test
    fun aPairIsKnownOnceItsDishHasBeenMade() {
        assertNull(knownDish(GRILL, EGG, emptySet()))
        assertEquals(FRIED_EGG, knownDish(GRILL, EGG, setOf(FRIED_EGG.id)))
        assertEquals("either way round", FRIED_EGG, knownDish(EGG, GRILL, setOf(FRIED_EGG.id)))
        assertNull("no recipe at all", knownDish(EGG, RAW_STEAK, setOf(FRIED_EGG.id)))
    }

    @Test
    fun onlyDishesCount() {
        assertEquals(1, dishesFound(setOf(FRIED_EGG.id, "nonsense", GRILL.id)))
        assertEquals(0, dishesFound(emptySet()))
    }
}
