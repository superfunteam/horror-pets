package team.superfun.horrorpets.ui.pantry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.pantry.Reaction
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.model.PantryItem.BAKED_POTATO
import team.superfun.horrorpets.model.PantryItem.EGG
import team.superfun.horrorpets.model.PantryItem.FLOUR
import team.superfun.horrorpets.model.PantryItem.FRIED_EGG
import team.superfun.horrorpets.model.PantryItem.GRILL
import team.superfun.horrorpets.model.PantryItem.RAW_STEAK
import team.superfun.horrorpets.ui.CookShow
import team.superfun.horrorpets.ui.FedShow
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.SlabHeight
import team.superfun.horrorpets.ui.training.HelpItem

/** The kitchen's remote: its rows and where the focus goes, the help strip, the layout and when input is held. */
class KitchenTest {
    private val shelf = listOf(EGG, GRILL, RAW_STEAK, FLOUR, BAKED_POTATO)

    /** A cursor that has seen [shelf] with [board] on the board, on [row]. */
    private fun cursor(board: List<Int> = emptyList(), row: KitchenRow = KitchenRow.SHELF, focus: Int = 0): KitchenCursor =
        KitchenCursor().apply {
            follow(shelf, Screen.Pantry(session = 1, board = board), boardPlaces(emptyList(), board))
            this.row = row
            this.focus = focus
        }

    @Test
    fun upFromTheShelfGoesToTheButtonThatCanAct() {
        val two = cursor(board = listOf(0, 1))
        assertEquals(KitchenAction.Moved, two.move(TrainingKey.UP, listOf(0, 1), shelf.size))
        assertEquals(KitchenRow.BUTTONS, two.row)
        assertEquals(KitchenButton.COOK, two.button)

        val one = cursor(board = listOf(3))
        one.button = KitchenButton.COOK
        one.move(TrainingKey.UP, listOf(3, null), shelf.size)
        assertEquals(KitchenRow.BUTTONS, one.row)
        assertEquals(KitchenButton.FEED, one.button)

        // Down from the shelf goes nowhere: it is the bottom row.
        val bottom = cursor()
        assertEquals(KitchenAction.None, bottom.move(TrainingKey.DOWN, listOf(null, null), shelf.size))
        assertEquals(KitchenRow.SHELF, bottom.row)
    }

    @Test
    fun downFromTheButtonsGoesBackToTheShelf() {
        val c = cursor(board = listOf(2), row = KitchenRow.BUTTONS, focus = 4)
        assertEquals(KitchenAction.Moved, c.move(TrainingKey.DOWN, listOf(2, null), shelf.size))
        assertEquals(KitchenRow.SHELF, c.row)
        assertEquals("the shelf's focus kept while away", 4, c.focus)
    }

    @Test
    fun upFromTheButtonsGoesOntoTheBoardsItems() {
        // Two on the board: COOK's side lands on the left place, FEED's on the right.
        val cook = cursor(board = listOf(0, 1), row = KitchenRow.BUTTONS)
        cook.button = KitchenButton.COOK
        assertEquals(KitchenAction.Moved, cook.move(TrainingKey.UP, listOf(0, 1), shelf.size))
        assertEquals(KitchenRow.BOARD, cook.row)
        assertEquals(0, cook.place)

        val feed = cursor(board = listOf(0, 1), row = KitchenRow.BUTTONS)
        feed.button = KitchenButton.FEED
        feed.move(TrainingKey.UP, listOf(0, 1), shelf.size)
        assertEquals(1, feed.place)

        // One on the board, in its right place: that is where the focus goes, whichever button.
        val right = cursor(board = listOf(2), row = KitchenRow.BUTTONS)
        right.button = KitchenButton.COOK
        right.move(TrainingKey.UP, listOf(null, 2), shelf.size)
        assertEquals(KitchenRow.BOARD, right.row)
        assertEquals(1, right.place)

        // An empty board: Up does nothing.
        val empty = cursor(row = KitchenRow.BUTTONS)
        assertEquals(KitchenAction.None, empty.move(TrainingKey.UP, listOf(null, null), shelf.size))
        assertEquals(KitchenRow.BUTTONS, empty.row)
    }

    @Test
    fun onTheBoardLeftAndRightMoveBetweenItsItemsAndOkTakesOneOff() {
        val places = listOf<Int?>(3, 1)
        val c = cursor(board = listOf(3, 1), row = KitchenRow.BUTTONS)
        c.button = KitchenButton.COOK
        c.move(TrainingKey.UP, places, shelf.size)
        assertEquals(KitchenAction.None, c.move(TrainingKey.LEFT, places, shelf.size))
        assertEquals(KitchenAction.Moved, c.move(TrainingKey.RIGHT, places, shelf.size))
        assertEquals(1, c.place)
        assertEquals(KitchenAction.None, c.move(TrainingKey.RIGHT, places, shelf.size))
        assertEquals("the shelf index of the item in that place", KitchenAction.Pick(1), c.move(TrainingKey.OK, places, shelf.size))
        assertEquals(KitchenAction.Moved, c.move(TrainingKey.LEFT, places, shelf.size))
        assertEquals(KitchenAction.Pick(3), c.move(TrainingKey.OK, places, shelf.size))
        assertEquals("Up past the board goes nowhere", KitchenAction.None, c.move(TrainingKey.UP, places, shelf.size))

        // With one item, Left and Right have nowhere to go.
        val one = listOf<Int?>(null, 4)
        val single = cursor(board = listOf(4), row = KitchenRow.BUTTONS)
        single.move(TrainingKey.UP, one, shelf.size)
        assertEquals(KitchenAction.None, single.move(TrainingKey.LEFT, one, shelf.size))
        assertEquals(KitchenAction.Pick(4), single.move(TrainingKey.OK, one, shelf.size))

        // Down goes back to the buttons.
        assertEquals(KitchenAction.Moved, c.move(TrainingKey.DOWN, places, shelf.size))
        assertEquals(KitchenRow.BUTTONS, c.row)
    }

    @Test
    fun theBoardRowFollowsItsItemsOff() {
        val c = cursor(board = listOf(0, 2), row = KitchenRow.BUTTONS)
        c.button = KitchenButton.FEED
        c.move(TrainingKey.UP, listOf(0, 2), shelf.size)
        assertEquals(1, c.place)

        // The right one comes off: the focus goes to the one left, still on the board.
        c.follow(shelf, Screen.Pantry(session = 1, board = listOf(0)), listOf(0, null))
        assertEquals(KitchenRow.BOARD, c.row)
        assertEquals(0, c.place)

        // And that one too: nothing is left to focus on the board, so the remote goes back to the buttons.
        c.follow(shelf, Screen.Pantry(session = 1, board = emptyList()), listOf(null, null))
        assertEquals(KitchenRow.BUTTONS, c.row)
    }

    @Test
    fun aCookTakesTheRemoteToTheNewDish() {
        val c = cursor(board = listOf(0, 1), row = KitchenRow.BOARD, focus = 4)
        val cooked = listOf(FRIED_EGG, RAW_STEAK, FLOUR, BAKED_POTATO)
        val show = CookShow(FRIED_EGG, listOf(EGG, GRILL), at = 0, token = 7)
        c.follow(cooked, Screen.Pantry(session = 1, cook = show), listOf(null, null))
        assertEquals(KitchenRow.SHELF, c.row)
        assertEquals(0, c.focus)

        // Seen once: the same show again moves nothing.
        c.focus = 3
        c.follow(cooked, Screen.Pantry(session = 1, cook = show), listOf(null, null))
        assertEquals(3, c.focus)
    }

    @Test
    fun aMealEatenOffTheShelfKeepsTheFocusOnItsItem() {
        val c = cursor(board = listOf(1), focus = 3)
        val fed = FedShow(GRILL, Reaction.YUCK, "That's a grill.", 0, 0)
        c.follow(shelf - GRILL, Screen.Pantry(session = 1, fed = fed), listOf(null, null))
        assertEquals("FLOUR slid down a place", 2, c.focus)
    }

    @Test
    fun heldKeysMoveAlongButNeverSkipRows() {
        val c = cursor(board = listOf(0))
        assertEquals(KitchenAction.Moved, c.move(TrainingKey.RIGHT, listOf(0, null), shelf.size, repeat = 3))
        assertEquals(1, c.focus)
        assertEquals("a held Up's repeats stay on the row", KitchenAction.None, c.move(TrainingKey.UP, listOf(0, null), shelf.size, repeat = 1))
        assertEquals(KitchenRow.SHELF, c.row)
        assertEquals(KitchenAction.Moved, c.move(TrainingKey.UP, listOf(0, null), shelf.size, repeat = 0))
        assertEquals(KitchenRow.BUTTONS, c.row)
        assertEquals(KitchenAction.None, c.move(TrainingKey.UP, listOf(0, null), shelf.size, repeat = 1))
        assertEquals(KitchenRow.BUTTONS, c.row)

        // Only the press itself ticks.
        assertTrue(ticks(KitchenAction.Moved, repeat = 0))
        assertFalse(ticks(KitchenAction.Moved, repeat = 1))
        assertFalse(ticks(KitchenAction.None, repeat = 0))
        assertFalse(ticks(KitchenAction.Pick(0), repeat = 0))
    }

    @Test
    fun theShelfStopsAtItsEndsAndOkPicksTheFocus() {
        val c = cursor(focus = 0)
        assertEquals(KitchenAction.None, c.move(TrainingKey.LEFT, listOf(null, null), shelf.size))
        repeat(10) { c.move(TrainingKey.RIGHT, listOf(null, null), shelf.size) }
        assertEquals(shelf.size - 1, c.focus)
        assertEquals(KitchenAction.Pick(shelf.size - 1), c.move(TrainingKey.OK, listOf(null, null), shelf.size))
        val empty = KitchenCursor()
        assertEquals("nothing to pick on an empty shelf", KitchenAction.None, empty.move(TrainingKey.OK, listOf(null, null), 0))
    }

    @Test
    fun theButtonsTakeOkWhetherOrNotTheyCanAct() {
        val c = cursor(row = KitchenRow.BUTTONS)
        c.button = KitchenButton.COOK
        assertEquals(KitchenAction.Moved, c.move(TrainingKey.RIGHT, listOf(null, null), shelf.size))
        assertEquals(KitchenAction.None, c.move(TrainingKey.RIGHT, listOf(null, null), shelf.size))
        assertEquals(KitchenAction.Press(KitchenButton.FEED), c.move(TrainingKey.OK, listOf(null, null), shelf.size))
        assertEquals(KitchenAction.Moved, c.move(TrainingKey.LEFT, listOf(null, null), shelf.size))
        assertEquals(KitchenAction.Press(KitchenButton.COOK), c.move(TrainingKey.OK, listOf(null, null), shelf.size))
    }

    @Test
    fun theHelpStripSaysWhatTheKeysDoOnEachRow() {
        fun labels(help: List<HelpItem>) = help.map { item -> item.keys.joinToString("") { it.name.take(1) } + " " + item.label }

        assertEquals(listOf("LR Shelf", "O On the board"), labels(kitchenHelp(KitchenRow.SHELF, KitchenButton.COOK, emptyList(), 0, 5)))
        assertEquals(
            listOf("LR Shelf", "O Off the board", "U Feed"),
            labels(kitchenHelp(KitchenRow.SHELF, KitchenButton.COOK, listOf(0), 0, 5)),
        )
        assertEquals(
            "a full board: OK only takes the focused one off",
            listOf("LR Shelf", "U Cook"),
            labels(kitchenHelp(KitchenRow.SHELF, KitchenButton.COOK, listOf(1, 2), 0, 5)),
        )
        assertEquals(listOf("O On the board"), labels(kitchenHelp(KitchenRow.SHELF, KitchenButton.COOK, emptyList(), 0, 1)))
        assertEquals(emptyList<String>(), labels(kitchenHelp(KitchenRow.SHELF, KitchenButton.COOK, emptyList(), 0, 0)))

        assertEquals(listOf("D Shelf"), labels(kitchenHelp(KitchenRow.BUTTONS, KitchenButton.COOK, emptyList(), 0, 5)))
        assertEquals(
            listOf("D Shelf", "U Board", "O Cook"),
            labels(kitchenHelp(KitchenRow.BUTTONS, KitchenButton.COOK, listOf(0, 1), 0, 5)),
        )
        assertEquals(
            listOf("D Shelf", "U Board", "O Feed"),
            labels(kitchenHelp(KitchenRow.BUTTONS, KitchenButton.FEED, listOf(3), 0, 5)),
        )
        assertEquals(
            "a dim button gets no OK",
            listOf("D Shelf", "U Board"),
            labels(kitchenHelp(KitchenRow.BUTTONS, KitchenButton.COOK, listOf(3), 0, 5)),
        )

        assertEquals(
            listOf("LR Board", "O Off the board", "D Cook"),
            labels(kitchenHelp(KitchenRow.BOARD, KitchenButton.COOK, listOf(0, 1), 0, 5)),
        )
        assertEquals(listOf("O Off the board", "D Feed"), labels(kitchenHelp(KitchenRow.BOARD, KitchenButton.FEED, listOf(1), 0, 5)))

        assertEquals("Tap something on the shelf", kitchenTouchHelp(0))
        assertEquals("Feed it, or add one more to cook", kitchenTouchHelp(1))
        assertEquals("Cook them, or tap one to take it off", kitchenTouchHelp(2))
    }

    @Test
    fun inputIsHeldWhileTheKitchenIsBusyOrAway() {
        assertFalse(kitchenInputHeld(entered = true, okReady = true, away = false, cooking = false, fed = false))
        assertTrue("leaving", kitchenInputHeld(entered = false, okReady = true, away = false, cooking = false, fed = false))
        assertTrue("the entry guard", kitchenInputHeld(entered = true, okReady = false, away = false, cooking = false, fed = false))
        assertTrue("away, or just back", kitchenInputHeld(entered = true, okReady = true, away = true, cooking = false, fed = false))
        assertTrue("a cook on show", kitchenInputHeld(entered = true, okReady = true, away = false, cooking = true, fed = false))
        assertTrue("fed: the reaction has the keys", kitchenInputHeld(entered = true, okReady = true, away = false, cooking = false, fed = true))
    }

    @Test
    fun theLayoutStacksTheColumnClearOfTheKiller() {
        val killerHalf = 45f
        for ((width, insetX) in listOf(960f to 48f, 720f to 36f, 1200f to 60f, 853f to 42.6f)) {
            val height = 540f
            val insetY = 27f
            val l = KitchenLayout.of(width, height, insetX, insetY, killerHalf)
            val killerRight = l.killerX + killerHalf
            assertTrue("$width: the killer inside the inset", l.killerX - killerHalf >= insetX)
            assertTrue("$width: the column clear of the killer", l.columnCenter - l.columnHalf > killerRight)
            assertTrue("$width: the column inside the inset", l.columnCenter + l.columnHalf <= width - insetX + 0.01f)
            // Bottom up: the help strip on the inset, then the shelf, the buttons and the board.
            assertEquals(height - insetY, l.helpBottom, 0.01f)
            assertTrue(l.shelfTop + ShelfHeight.value < l.helpBottom)
            assertEquals(l.shelfTop - SlabHeight.value, l.buttonsTop, 0.01f)
            assertTrue(l.boardTop + l.boardWidth / BOARD_ASPECT < l.buttonsTop)
            assertTrue("$width: the board under the top inset", l.boardTop >= insetY)
            assertTrue(l.boardWidth <= 290f)
            assertTrue("$width: slots at least 84 dp", l.columnHalf * 2f / l.slots >= 84f)
            assertTrue(l.slots >= 4)
            // The shelf's labels (two lines under every item) still leave the board all its width at 540 dp.
            assertEquals("$width: the board at full size", 290f, l.boardWidth, 0.01f)
            assertTrue("$width: the shelf holds its items and their labels", ShelfHeight.value >= ShelfStandY.value + 50f)
        }
        // 16:9 centres the column; 4:3 has no room either side of the centre, so it moves right past the killer.
        assertEquals(480f, KitchenLayout.of(960f, 540f, 48f, 27f, killerHalf).columnCenter, 0.01f)
        assertTrue(KitchenLayout.of(720f, 540f, 36f, 27f, killerHalf).columnCenter > 360f)
    }
}
