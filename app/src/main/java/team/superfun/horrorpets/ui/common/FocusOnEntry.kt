package team.superfun.horrorpets.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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

/** How long OK / Enter is ignored after a screen that guards it is entered. */
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
 * Swallows OK / Enter key events (down and up) for everything inside while [ready] says no. A press
 * whose down was swallowed never clicks, even if its up arrives after the guard lifts: a clickable only
 * acts on an up that follows its own down.
 */
fun Modifier.ignoreOkUnless(ready: State<Boolean>): Modifier =
    onPreviewKeyEvent { !ready.value && it.key in OkKeys }

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
