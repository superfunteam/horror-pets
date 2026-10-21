package team.superfun.horrorpets.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester

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
