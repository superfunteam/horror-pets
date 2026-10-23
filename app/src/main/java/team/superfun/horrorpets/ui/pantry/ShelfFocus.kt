package team.superfun.horrorpets.ui.pantry

import kotlin.math.floor
import kotlin.math.max
import team.superfun.horrorpets.game.pantry.tasteOf
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.BOARD_PLACES

/**
 * The pantry's shelf, kept free of Compose so it can be tested: where the remote's focus goes when the shelf changes
 * under it, which stretch of a long shelf the carousel shows, which dish wears the heart and where each item on the
 * board sits. Indices are shelf indices (the view model's `Screen.Pantry.board` holds them too).
 */

/**
 * Where the shelf's focus goes when the shelf changes from [oldSize] items to [newSize], the focus having been on
 * [old]: the items at [removedAt] (indices on the old shelf) left it, and one went in at [insertedAt] (an index on
 * the new shelf), if any.
 *
 * - A cook takes its two inputs off and puts its dish in the earlier one's place: the focus follows the new dish, so
 *   OK puts it straight on the board.
 * - Otherwise the focus stays on the item it was on, wherever the removals moved it; if that item went (fed), the one
 *   that slid into its place takes it, or the new last one when it was the last.
 * - Items stocked onto the end change nothing. An empty shelf focuses 0.
 */
fun focusAfter(old: Int, oldSize: Int, newSize: Int, removedAt: List<Int>, insertedAt: Int?): Int {
    if (newSize <= 0) return 0
    val from = old.coerceIn(0, max(0, oldSize - 1))
    val moved = insertedAt ?: (from - removedAt.distinct().count { it in 0 until from })
    return moved.coerceIn(0, newSize - 1)
}

/**
 * The run of shelf slots the carousel shows: [slots] of them from [first]. A shelf shorter than that shows its items
 * from the left, and the rest of the slots stay empty.
 */
data class ShelfWindow(val first: Int, val slots: Int) {
    val last: Int get() = first + slots - 1

    operator fun contains(index: Int): Boolean = index in first..last

    /**
     * [focus] on a shelf of [size] items, moved as little as it takes for this window to keep it as [shelfWindow]
     * would (with its neighbours, away from an end the shelf goes on past): where a drag of the carousel leaves the
     * remote's focus, so that the window stays where the finger left it.
     */
    fun focusWithin(focus: Int, size: Int): Int {
        if (size <= 0) return 0
        val margin = if (slots >= 3) 1 else 0
        val lo = if (first > 0) first + margin else 0
        val hi = if (last < size - 1) last - margin else size - 1
        return focus.coerceIn(lo, max(lo, hi)).coerceIn(0, size - 1)
    }
}

/** How many slots a shelf [width] wide shows, each at least [minPitch] (any one unit): as many as fit, at least one. */
fun shelfSlots(width: Float, minPitch: Float): Int =
    if (minPitch <= 0f) 1 else max(1, floor(width / minPitch).toInt())

/**
 * The window a shelf of [size] items shows in [slots] slots with the focus on [focus], moved from [previous] (the
 * first slot it showed) only as far as it must: so that the focus shows, and with three slots or more the items
 * either side of it too, so the player always sees there is more. It never runs past either end of a shelf that fills
 * it, so a shelf that shrinks (a cook near its end) pulls it back.
 */
fun shelfWindow(focus: Int, size: Int, slots: Int, previous: Int): ShelfWindow {
    val n = max(1, slots)
    if (size <= n) return ShelfWindow(0, n)
    val f = focus.coerceIn(0, size - 1)
    val margin = if (n >= 3) 1 else 0
    var first = previous.coerceIn(0, size - n)
    if (f - margin < first) first = f - margin
    if (f + margin > first + n - 1) first = f + margin - n + 1
    return ShelfWindow(first.coerceIn(0, size - n), n)
}

/**
 * How many slots in from the left a shelf of [size] items starts in [slots] slots: none for a shelf that fills them,
 * else half the slots it leaves empty, so a short shelf stands in the middle of the plank.
 */
fun shelfLead(size: Int, slots: Int): Float = if (size < slots) (slots - size) / 2f else 0f

/**
 * The dish that wears a heart on [pet]'s visit: its favorite, once it has been fed it, and never before (a favorite
 * is never telegraphed).
 */
internal fun favoriteHeart(pet: PetState): PantryItem? = if (pet.favoriteFound) tasteOf(pet.id).favorite else null

/**
 * Which shelf item sits in each of the board's two places now that it holds [board] (in the order they were put on,
 * as the view model keeps it), after [places] (what each place held before, null for empty). An item already on the
 * board stays in its place; one taken off leaves its place empty; a new one goes into the first empty place. So
 * taking the left one off leaves the right one where it is.
 */
internal fun boardPlaces(places: List<Int?>, board: List<Int>): List<Int?> {
    val kept = places.map { held -> held?.takeIf { it in board } }.toMutableList()
    while (kept.size < BOARD_PLACES) kept += null
    for (index in board) {
        if (index in kept) continue
        val free = kept.indexOf(null)
        if (free >= 0) kept[free] = index
    }
    return kept.take(BOARD_PLACES)
}
