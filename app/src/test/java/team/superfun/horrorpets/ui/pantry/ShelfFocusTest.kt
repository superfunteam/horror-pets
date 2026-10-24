package team.superfun.horrorpets.ui.pantry

import kotlin.math.max
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.pantry.tasteOf
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class ShelfFocusTest {
    @Test
    fun focusStaysOnTheShelfWhenItShrinks() {
        // A cook takes two items off the shelf and puts one back: from every focus, at every pair of positions, in
        // either order on the board, the focus lands on an item of the shorter shelf.
        for (size in 2..12) {
            for (a in 0 until size) {
                for (b in a + 1 until size) {
                    for (focus in 0 until size) {
                        for (board in listOf(listOf(a, b), listOf(b, a))) {
                            val cooked = focusAfter(focus, size, size - 1, removedAt = board, insertedAt = a)
                            assertTrue("cook $board of $size from $focus: $cooked", cooked in 0 until size - 1)
                            // Two gone and nothing back (never a cook, but the rule holds for any shrink).
                            val gone = focusAfter(focus, size, size - 2, removedAt = board, insertedAt = null)
                            assertTrue("lose $board of $size from $focus: $gone", gone in 0 until max(1, size - 2))
                        }
                    }
                }
            }
        }
        // Feeding the last item, focused: the focus steps back onto the one before it.
        for (size in 2..12) assertEquals(size - 2, focusAfter(size - 1, size, size - 1, listOf(size - 1), null))
        // Feeding one before the focus keeps the focus on the same item; feeding the focused one, on the next.
        assertEquals(3, focusAfter(4, 8, 7, listOf(1), null))
        assertEquals(4, focusAfter(4, 8, 7, listOf(4), null))
        assertEquals(4, focusAfter(4, 8, 7, listOf(6), null))
        // An empty shelf: 0, whatever came before.
        assertEquals(0, focusAfter(0, 1, 0, listOf(0), null))
        assertEquals(0, focusAfter(5, 6, 0, (0..5).toList(), null))
        assertEquals(0, focusAfter(0, 0, 0, emptyList(), null))
        // A focus already past the end (stale) is pulled back onto the shelf.
        assertEquals(2, focusAfter(9, 3, 3, emptyList(), null))
    }

    @Test
    fun focusFollowsTheNewDish() {
        // The dish takes the earlier input's place, and the focus goes with it, wherever it was.
        for (size in 2..12) {
            for (a in 0 until size) {
                for (b in a + 1 until size) {
                    for (focus in 0 until size) {
                        assertEquals("cook $a+$b of $size from $focus", a, focusAfter(focus, size, size - 1, listOf(b, a), insertedAt = a))
                    }
                }
            }
        }
        // Items stocked onto the end leave the focus on the item it was on.
        assertEquals(2, focusAfter(2, 5, 8, emptyList(), null))
    }

    @Test
    fun theWindowKeepsFocusVisible() {
        // The slots a shelf shows: as many whole slots as fit, never none.
        assertEquals(6, shelfSlots(width = 540f, minPitch = 84f))
        assertEquals(5, shelfSlots(width = 486f, minPitch = 84f))
        assertEquals(1, shelfSlots(width = 40f, minPitch = 84f))
        assertEquals(1, shelfSlots(width = 0f, minPitch = 84f))

        // Random walks along every shelf size up to the cap, through windows of every width, with jumps (a cook's
        // new dish) as well as steps.
        val rng = Random(7)
        for (size in 0..40) {
            for (slots in 1..9) {
                var focus = 0
                var window = shelfWindow(focus, size, slots, previous = 0)
                repeat(300) {
                    focus = when (rng.nextInt(10)) {
                        0 -> if (size == 0) 0 else rng.nextInt(size)
                        in 1..4 -> focus - 1
                        else -> focus + 1
                    }.coerceIn(0, max(0, size - 1))
                    val next = shelfWindow(focus, size, slots, previous = window.first)
                    val label = "size $size, $slots slots, focus $focus after ${window.first}"
                    assertEquals(label, slots, next.slots)
                    assertTrue("$label: first ${next.first}", next.first in 0..max(0, size - slots))
                    if (size > 0) assertTrue("$label: shows ${next.first}..${next.last}", focus in next)
                    // A long shelf never shows an empty slot at its end.
                    if (size >= slots) assertTrue("$label: full", next.last <= size - 1)
                    // With room, the items either side of the focus show too, so the player sees what's next.
                    if (slots >= 3 && focus > 0) assertTrue("$label: left neighbour", focus - 1 in next)
                    if (slots >= 3 && focus < size - 1) assertTrue("$label: right neighbour", focus + 1 in next)
                    // It only moves when it must: a focus already well inside it leaves it where it was.
                    val inside = focus - 1 >= window.first && focus + 1 <= window.last
                    if (slots >= 3 && inside && window.last <= size - 1) assertEquals(label, window.first, next.first)
                    window = next
                }
            }
        }
        // A drag leaves the window wherever the finger let go of it: the focus moves into it, and the window stays.
        for (size in 0..40) {
            for (slots in 1..9) {
                for (first in 0..max(0, size - slots)) {
                    val dragged = ShelfWindow(first, slots)
                    for (focus in 0 until max(1, size)) {
                        val moved = dragged.focusWithin(focus, size)
                        val label = "size $size, $slots slots from $first, focus $focus to $moved"
                        if (size > 0) assertTrue(label, moved in dragged)
                        assertEquals(label, first, shelfWindow(moved, size, slots, previous = first).first)
                        if (focus in dragged && shelfWindow(focus, size, slots, first).first == first) assertEquals(label, focus, moved)
                    }
                }
            }
        }
        // A short shelf stands in the middle of the plank; a long one starts at its left end.
        assertEquals(1.5f, shelfLead(size = 6, slots = 9), 0f)
        assertEquals(0.5f, shelfLead(size = 5, slots = 6), 0f)
        assertEquals(0f, shelfLead(size = 6, slots = 6), 0f)
        assertEquals(0f, shelfLead(size = 40, slots = 6), 0f)
        // A shelf that shrinks under the window (a cook near its end) pulls the window back.
        assertEquals(0, shelfWindow(focus = 5, size = 6, slots = 6, previous = 3).first)
        assertEquals(2, shelfWindow(focus = 7, size = 8, slots = 6, previous = 4).first)
    }

    @Test
    fun theHeartIsNeverShownBeforeTheFavoriteIsFound() {
        for (id in PetId.entries) {
            assertNull("$id", favoriteHeart(PetState(id = id)))
            assertEquals("$id", tasteOf(id).favorite, favoriteHeart(PetState(id = id, favoriteFound = true)))
        }
    }

    @Test
    fun boardItemsKeepTheirPlace() {
        // Placed in turn, they fill the left place, then the right.
        assertEquals(listOf(4, null), boardPlaces(listOf(null, null), listOf(4)))
        assertEquals(listOf(4, 1), boardPlaces(listOf(4, null), listOf(4, 1)))
        // Taking the left one off leaves the right one where it is, and the next goes into the gap.
        assertEquals(listOf(null, 1), boardPlaces(listOf(4, 1), listOf(1)))
        assertEquals(listOf(7, 1), boardPlaces(listOf(null, 1), listOf(1, 7)))
        // A cleared board (a cook) clears both places; a board seen for the first time fills them in order.
        assertEquals(listOf(null, null), boardPlaces(listOf(7, 1), emptyList()))
        assertEquals(listOf(3, 0), boardPlaces(listOf(null, null), listOf(3, 0)))
    }

    // The shelf's labels.

    @Test
    fun aLabelBreaksOnlyBetweenWordsAndAfterHyphens() {
        assertEquals(listOf("EGG"), labelWords("EGG"))
        assertEquals(listOf("CAMP", "CRYSTAL", "FISH", "FRY"), labelWords("CAMP CRYSTAL FISH FRY"))
        assertEquals(listOf("NOT-", "STRAWBERRY", "MILK"), labelWords("NOT-STRAWBERRY MILK"))
        assertEquals(listOf("SUN-", "DRIED", "TOMATOES"), labelWords("SUN-DRIED TOMATOES"))
        assertEquals(listOf("COCOA", "&", "MARSHMALLOWS"), labelWords("COCOA &  MARSHMALLOWS"))
        // Every name's words, put back together, are the name.
        for (item in PantryItem.entries) {
            val label = item.title.uppercase()
            assertEquals(label.replace(" ", ""), labelWords(label).joinToString(""))
        }
    }

    /** A pretend typeface: every letter (and space) half its size wide, in a label [width] wide. */
    private fun fit(label: String, width: Float): Float {
        fun widthOf(text: String, sp: Float) = text.length * sp * 0.5f
        // Greedy wrapping into [width], counting lines (a word too wide for a line takes one of its own).
        fun linesOf(sp: Float): Int {
            var lines = 1
            var line = 0f
            for (word in labelWords(label)) {
                val w = widthOf(word, sp)
                val gap = if (line == 0f || word.endsWith("-")) 0f else widthOf(" ", sp)
                if (line > 0f && line + gap + w > width) {
                    lines++
                    line = w
                } else {
                    line += gap + w
                }
            }
            return lines
        }
        return labelFontSize(label, width, maxSp = 13f, minSp = 10f, stepSp = 0.5f, widthOf = ::widthOf, linesOf = ::linesOf)
    }

    @Test
    fun aLabelShrinksOnlyAsFarAsItMust() {
        // A short name, and one that wraps onto two lines whole: the biggest size.
        assertEquals(13f, fit("EGG", 70f), 0f)
        assertEquals(13f, fit("PILE OF SLOP", 70f), 0f)
        // A word too wide for the label shrinks it until the word fits (12 letters x 6 = 72 > 70; x 5.75 = 69).
        assertEquals(11.5f, fit("COCOA & MARSHMALLOWS", 70f), 0f)
        // Three lines at the biggest size shrink it to two.
        assertEquals(13f, fit("A B C", 6.5f * 3f), 0f) // "A B" fits a line: two lines
        val crowded = fit("AAAA BBBB CCCC", 26f * 2f) // 4 letters = 26 at 13: one word a line, three lines
        assertTrue("$crowded", crowded < 13f)
        // Nothing fits at all: the smallest, and the label ends in an ellipsis.
        assertEquals(10f, fit("SUPERCALIFRAGILISTIC", 40f), 0f)
        // Never bigger than it was asked, never smaller.
        for (item in PantryItem.entries) for (width in listOf(40f, 70f, 90f)) {
            val sp = fit(item.title.uppercase(), width)
            assertTrue("${item.title} $width: $sp", sp in 10f..13f)
        }
    }
}
