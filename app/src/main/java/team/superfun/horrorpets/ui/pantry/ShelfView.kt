package team.superfun.horrorpets.ui.pantry

import team.superfun.horrorpets.model.PantryItem

/**
 * The shelf as the pantry shows it: in kind order (ways to cook, raw foods, snacks, meals, then botches), each kind in
 * the order its items arrived, with a divider on the plank where one kind ends and the next begins ([breaks]). The game
 * keeps the shelf as items arrived (the oldest botch is the first to go when room is short, a cooked dish takes an
 * input's place), so this is a view of it: [items] is what is shown, [order] gives each shown slot's index on the real
 * shelf, and [place] a real index's slot.
 */
internal class ShelfView(shelf: List<PantryItem>) {
    val order: List<Int> = shelf.indices.sortedBy { shelf[it].kind }
    val items: List<PantryItem> = order.map { shelf[it] }
    private val slotOf = IntArray(shelf.size).also { slots -> order.forEachIndexed { slot, index -> slots[index] = slot } }

    fun place(index: Int): Int = slotOf[index]

    val breaks: Set<Int> = (1 until items.size).filter { items[it].kind != items[it - 1].kind }.toSet()
}
