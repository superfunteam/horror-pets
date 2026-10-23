package team.superfun.horrorpets.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/**
 * Gives [requester] focus each time a screen is entered: whenever [entry] changes to a non-null value.
 *
 * `HorrorPetsApp` passes a fresh value every time the router switches to a screen, and null while that
 * screen is on its way out. Keying on [Unit] instead is not enough: when the player flips away and
 * straight back (Menu, then Back) before the cross-fade ends, the router reuses the leaving screen's
 * composition, so a `LaunchedEffect(Unit)` would never run again and focus would be lost.
 */
@Composable
fun RequestFocusOnEntry(requester: FocusRequester, entry: Any?) {
    LaunchedEffect(entry) {
        if (entry != null) requester.requestFocus()
    }
}

/** How long OK / Enter (and a tap) is ignored after a screen that guards it is entered. */
const val OK_GUARD_MS = 400L

/**
 * Whether OK / Enter may act yet on a screen entered with [entry] (see [RequestFocusOnEntry]): false for
 * [OK_GUARD_MS] after each entry, and while the screen is leaving ([entry] is null). Pair it with
 * [ignoreOkUnless] on the screen's root, so a mashed OK that ended the previous screen cannot carry
 * straight through this one (a fight's last hit skipping the results, then firing PLAY on Home).
 */
@Composable
fun rememberOkReady(entry: Any?): State<Boolean> {
    val ready = remember { mutableStateOf(false) }
    LaunchedEffect(entry) {
        ready.value = false
        if (entry != null) {
            delay(OK_GUARD_MS)
            ready.value = true
        }
    }
    return ready
}

/**
 * Swallows OK / Enter key events (down and up; Space and a gamepad's A too) for everything inside while
 * [ready] says no. A press whose down was swallowed never clicks, even if its up arrives after the guard
 * lifts: a clickable only acts on an up that follows its own down.
 *
 * Taps get the same guard: a touch that comes down while [ready] says no is consumed before anything
 * inside sees it, so a second tap mashed through the end of one screen cannot press a button on the next.
 */
fun Modifier.ignoreOkUnless(ready: State<Boolean>): Modifier =
    onPreviewKeyEvent { !ready.value && it.key in OkKeys }
        .pointerInput(ready) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!ready.value) down.consume()
            }
        }

/**
 * Every key that presses a button: a clickable takes the D-pad's centre, Enter, the keypad's Enter and Space, and a
 * gamepad's A falls back to the D-pad's centre when nothing takes it.
 */
private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.ButtonA)
