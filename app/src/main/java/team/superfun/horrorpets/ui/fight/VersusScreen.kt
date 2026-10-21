// Placeholder — replaced in Task 14 (VS screen).
package team.superfun.horrorpets.ui.fight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink

/** Functional stand-in for the VS card: "X VS Y" and a FIGHT! pill. */
@Composable
fun VersusScreen(player: PetId, opponent: PetId, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    BackHandler { /* the bout is about to start */ }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(Ink),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(player.displayName.uppercase(), style = displayStyle(64.sp))
        Text("VS", style = displayStyle(96.sp, color = BloodGlow))
        Text(opponent.displayName.uppercase(), style = displayStyle(64.sp))
        Spacer(Modifier.height(32.dp))
        FocusPill("Fight!", onDone, Modifier.focusRequester(focus))
    }
}
