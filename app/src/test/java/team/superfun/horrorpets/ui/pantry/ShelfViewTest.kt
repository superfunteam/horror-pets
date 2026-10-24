package team.superfun.horrorpets.ui.pantry

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.model.PantryItem.CAMPFIRE
import team.superfun.horrorpets.model.PantryItem.CHEESE
import team.superfun.horrorpets.model.PantryItem.EGG
import team.superfun.horrorpets.model.PantryItem.FRIED_EGG
import team.superfun.horrorpets.model.PantryItem.GRILL
import team.superfun.horrorpets.model.PantryItem.PILE_OF_SLOP
import team.superfun.horrorpets.model.PantryItem.RAW_STEAK

/** The shelf as shown: grouped by kind, arrival order within a kind. */
class ShelfViewTest {
    private val shelf = listOf(PILE_OF_SLOP, RAW_STEAK, GRILL, FRIED_EGG, EGG, CAMPFIRE, PILE_OF_SLOP, CHEESE)
    private val view = ShelfView(shelf)

    @Test
    fun kindsInOrderArrivalWithinThem() {
        assertEquals(listOf(GRILL, CAMPFIRE, RAW_STEAK, EGG, CHEESE, FRIED_EGG, PILE_OF_SLOP, PILE_OF_SLOP), view.items)
        assertEquals(listOf(2, 5, 1, 4, 7, 3, 0, 6), view.order)
    }

    @Test
    fun placeIsTheWayBack() {
        for (index in shelf.indices) assertEquals(index, view.order[view.place(index)])
        assertEquals("the older slop comes first", 6, view.place(0))
    }

    @Test
    fun aDividerWhereTheKindChanges() {
        assertEquals(setOf(2, 5, 6), view.breaks)
        assertEquals(emptySet<Int>(), ShelfView(listOf(EGG, CHEESE)).breaks)
        assertEquals(emptySet<Int>(), ShelfView(emptyList()).breaks)
    }
}
