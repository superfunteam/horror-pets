// Placeholder — replaced in Task 13 (fake death gotcha).
package team.superfun.horrorpets.ui.fakedeath

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextLo

/** Functional stand-in for the fake-death reveal: the copy and a CONTINUE pill. */
@Composable
fun FakeDeathScreen(pet: PetId, onDone: () -> Unit, focusEntry: Any? = Unit) {
    val focus = remember { FocusRequester() }
    BackHandler { /* the reveal has to be seen */ }
    RequestFocusOnEntry(focus, focusEntry)
    val name = pet.displayName.uppercase()
    Column(
        Modifier.fillMaxSize().background(Ink),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("R.I.P. $name", style = displayStyle(40.sp, color = TextLo, tracking = 0.12f))
        Spacer(Modifier.height(16.dp))
        Text("GOTCHA.", style = displayStyle(96.sp, color = BloodGlow))
        Text("$name NEVER DIES.", style = displayStyle(44.sp, tracking = 0.08f))
        Spacer(Modifier.height(40.dp))
        FocusPill("Continue", onDone, Modifier.focusRequester(focus))
    }
}
