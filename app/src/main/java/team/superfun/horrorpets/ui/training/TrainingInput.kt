package team.superfun.horrorpets.ui.training

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
 * Whether a game sees [key] going [down] (or up) on its [repeat]th repeat (0 for the press itself): all but a held OK's
 * repeats, so OK goes down once a press from any key. `MainActivity` drops the remote's OK repeats, but Space and a
 * gamepad's A keep repeating while held. A held direction keeps repeating, for the games that run while it is held,
 * unless the game takes no [repeats]: then no held key repeats, and a press is one press however long it's held.
 */
internal fun reachesGame(key: TrainingKey, down: Boolean, repeat: Int, repeats: Boolean = true): Boolean =
    !down || repeat == 0 || (repeats && key != TrainingKey.OK)

/**
 * Hands a game its keys while it (or something inside it) has focus: [onKey] gets each training key going down and
 * coming up, and says whether it used it. OK goes down once a press, whichever key it is (see [reachesGame]: a held
 * OK's repeats are swallowed); a held direction's repeats arrive as more downs, unless the game takes no [repeats].
 * Other keys pass on untouched.
 */
fun Modifier.trainingKeys(
    repeats: Boolean = true,
    onKey: (key: TrainingKey, down: Boolean) -> Boolean,
): Modifier = onKeyEvent { event ->
    val key = trainingKeyOf(event.key) ?: return@onKeyEvent false
    val down = when (event.type) {
        KeyEventType.KeyDown -> true
        KeyEventType.KeyUp -> false
        else -> return@onKeyEvent false
    }
    if (!reachesGame(key, down, event.nativeKeyEvent.repeatCount, repeats)) return@onKeyEvent true
    onKey(key, down)
}

/**
 * Whether the shell holds the game's input (keys by [swallowedWhileHeld], touches by [swallowTouches]): while the app
 * is [paused] (away, or the "GET READY" beat after) and while the reel is still fading off the game ([reelFading]: the
 * game is composed and has focus under it, but its player hasn't seen it yet). Not once the game is [over] and its card
 * is up, which has the keys and takes the touches itself.
 */
internal fun inputHeld(paused: Boolean, reelFading: Boolean, over: Boolean): Boolean = (paused || reelFading) && !over

/**
 * Whether the shell keeps a key event of [type] for [key] from the game while the game is [held] (see [inputHeld]: the
 * app away, then the "GET READY" beat, and the reel fading off the game): every training key going down. Keys let go still get through, so a game tracking held keys
 * sees one released during the hold; Back is never kept.
 */
internal fun swallowedWhileHeld(key: Key, type: KeyEventType, held: Boolean): Boolean =
    held && type == KeyEventType.KeyDown && trainingKeyOf(key) != null

/**
 * Takes every touch that lands here from whatever is drawn under it (a frozen or held game): a touch goes to the
 * topmost element with touch input under the finger and no further down. It consumes nothing itself, so a button
 * inside this element still clicks: a consumed move would cancel its tap.
 */
internal fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
