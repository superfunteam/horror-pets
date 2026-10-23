package team.superfun.horrorpets.ui.training

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import team.superfun.horrorpets.game.training.TrainingKey

/**
 * The [TrainingKey] a real key is, if any: the D-pad (a remote's, a gamepad's, and a keyboard's arrow keys, which
 * Android sends as the D-pad) for the directions; the D-pad's centre, Enter, the keypad's Enter, Space and a gamepad's
 * A for OK. Anything else (Back above all) is not the games' to take.
 */
fun trainingKeyOf(key: Key): TrainingKey? = when (key) {
    Key.DirectionUp -> TrainingKey.UP
    Key.DirectionDown -> TrainingKey.DOWN
    Key.DirectionLeft -> TrainingKey.LEFT
    Key.DirectionRight -> TrainingKey.RIGHT
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.ButtonA -> TrainingKey.OK
    else -> null
}

/**
 * Hands a game its keys while it (or something inside it) has focus: [onKey] gets each training key going down (also
 * each repeat of a held direction, which arrives as another down: `MainActivity` drops only OK's and Back's repeats)
 * and coming up, and says whether it used it. Other keys pass on untouched.
 */
fun Modifier.trainingKeys(onKey: (key: TrainingKey, down: Boolean) -> Boolean): Modifier = onKeyEvent { event ->
    val key = trainingKeyOf(event.key) ?: return@onKeyEvent false
    when (event.type) {
        KeyEventType.KeyDown -> onKey(key, true)
        KeyEventType.KeyUp -> onKey(key, false)
        else -> false
    }
}
