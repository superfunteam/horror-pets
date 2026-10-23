package team.superfun.horrorpets.ui.training

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
