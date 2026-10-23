package team.superfun.horrorpets.ui.training

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.training.TrainingKey

class TrainingInputTest {
    @Test
    fun dpadAndArrowsMapToDirections() {
        // A remote's D-pad, a gamepad's and a keyboard's arrow keys all send the D-pad's key codes on Android.
        assertEquals(TrainingKey.UP, trainingKeyOf(Key.DirectionUp))
        assertEquals(TrainingKey.DOWN, trainingKeyOf(Key.DirectionDown))
        assertEquals(TrainingKey.LEFT, trainingKeyOf(Key.DirectionLeft))
        assertEquals(TrainingKey.RIGHT, trainingKeyOf(Key.DirectionRight))
    }

    @Test
    fun okEnterAndSpaceAreOk() {
        for (key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.ButtonA)) {
            assertEquals("$key", TrainingKey.OK, trainingKeyOf(key))
        }
    }

    @Test
    fun otherKeysAreIgnored() {
        val others = listOf(
            Key.Back, Key.Escape, Key.Menu, Key.ButtonB, Key.ButtonX, Key.Tab, Key.A, Key.W, Key.Zero,
            Key.MediaPlayPause, Key.MediaRewind, Key.MediaFastForward, Key.DirectionUpLeft, Key.Home,
        )
        for (key in others) assertNull("$key", trainingKeyOf(key))
    }

    @Test
    fun okGoesDownOncePerPressWhateverTheKey() {
        // Space and a gamepad's A repeat while held (MainActivity drops only the remote's OK repeats): one OK a press.
        assertTrue(reachesGame(TrainingKey.OK, down = true, repeat = 0))
        assertFalse(reachesGame(TrainingKey.OK, down = true, repeat = 1))
        assertFalse(reachesGame(TrainingKey.OK, down = true, repeat = 12))
        assertTrue(reachesGame(TrainingKey.OK, down = false, repeat = 0))
    }

    @Test
    fun heldDirectionsKeepRepeating() {
        for (key in listOf(TrainingKey.UP, TrainingKey.DOWN, TrainingKey.LEFT, TrainingKey.RIGHT)) {
            assertTrue("$key", reachesGame(key, down = true, repeat = 0))
            assertTrue("$key", reachesGame(key, down = true, repeat = 5))
            assertTrue("$key", reachesGame(key, down = false, repeat = 0))
        }
    }

    @Test
    fun aGameTakingNoRepeatsSeesOneDownAPress() {
        // Quick Draw: a press is one press, however long it's held.
        for (key in TrainingKey.entries) {
            assertTrue("$key", reachesGame(key, down = true, repeat = 0, repeats = false))
            assertFalse("$key", reachesGame(key, down = true, repeat = 1, repeats = false))
            assertFalse("$key", reachesGame(key, down = true, repeat = 9, repeats = false))
            assertTrue("$key", reachesGame(key, down = false, repeat = 0, repeats = false))
        }
    }

    @Test
    fun whileHeldNoTrainingKeyGoesDown() {
        for (key in listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionCenter, Key.Enter, Key.Spacebar, Key.ButtonA)) {
            assertTrue("$key", swallowedWhileHeld(key, KeyEventType.KeyDown, held = true))
            assertFalse("$key", swallowedWhileHeld(key, KeyEventType.KeyDown, held = false))
            // A key let go during the hold still gets through, so a game tracking held keys sees it go up.
            assertFalse("$key", swallowedWhileHeld(key, KeyEventType.KeyUp, held = true))
        }
        // Back is never held back: it still leaves.
        assertFalse(swallowedWhileHeld(Key.Back, KeyEventType.KeyDown, held = true))
    }

    @Test
    fun noGameIsPressedOrTouchedUntilTheReelHasFadedOffIt() {
        // The reel hands over at 4800 ms of training time: the game is composed and has focus under the fading reel,
        // and a player still pressing OK from "GET READY..." must not lose Quick Draw at 0 with TOO SOON before READY
        // is seen.
        val start = 4_800L
        fun held(now: Long, paused: Boolean = false, over: Boolean = false) =
            inputHeld(paused, reelFading(gameOn = true, nowMs = now, startMs = start), over)
        val pressedKeys =
            listOf(Key.DirectionCenter, Key.Enter, Key.Spacebar, Key.ButtonA, Key.DirectionLeft, Key.DirectionRight)

        for (now in start until start + REEL_FADE_MS) {
            assertTrue("held $now ms in", held(now))
            for (key in pressedKeys) {
                assertTrue("$key at $now", swallowedWhileHeld(key, KeyEventType.KeyDown, held(now)))
            }
            // Back is never held back, and a key let go still gets through.
            assertFalse(swallowedWhileHeld(Key.Back, KeyEventType.KeyDown, held(now)))
            assertFalse(swallowedWhileHeld(Key.DirectionCenter, KeyEventType.KeyUp, held(now)))
        }
        // The fade over, the game has its keys and touches.
        assertFalse(held(start + REEL_FADE_MS))
        assertFalse(held(start + REEL_FADE_MS + 2_000L))
        assertFalse(swallowedWhileHeld(Key.DirectionCenter, KeyEventType.KeyDown, held(start + REEL_FADE_MS)))
    }

    @Test
    fun theReelFadesOnlyOnceAGameHasBegunUnderIt() {
        // Before the hand-over there is no game under the reel to hold anything from.
        assertFalse(reelFading(gameOn = false, nowMs = 0L, startMs = 0L))
        assertFalse(reelFading(gameOn = false, nowMs = 3_000L, startMs = 0L))
        assertTrue(reelFading(gameOn = true, nowMs = 4_800L, startMs = 4_800L))
        assertTrue(reelFading(gameOn = true, nowMs = 4_800L + REEL_FADE_MS - 1L, startMs = 4_800L))
        assertFalse(reelFading(gameOn = true, nowMs = 4_800L + REEL_FADE_MS, startMs = 4_800L))
    }

    @Test
    fun theHoldIsTheAppAwayOrTheReelFadingUntilTheGameIsOver() {
        assertFalse(inputHeld(paused = false, reelFading = false, over = false))
        assertTrue("away, or the GET READY beat", inputHeld(paused = true, reelFading = false, over = false))
        assertTrue("the reel fading", inputHeld(paused = false, reelFading = true, over = false))
        assertTrue(inputHeld(paused = true, reelFading = true, over = false))
        // Once its card is up the card has the keys (and swallows the touches itself), whatever else is going on.
        for (paused in listOf(false, true)) {
            for (fading in listOf(false, true)) assertFalse(inputHeld(paused, fading, over = true))
        }
    }
}
