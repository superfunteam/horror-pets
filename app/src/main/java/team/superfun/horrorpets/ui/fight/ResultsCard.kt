// Placeholder — replaced in Task 14 (results card).
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
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextLo

/** Functional stand-in for the results card: the outcome, XP, level-up and a CONTINUE pill. */
@Composable
fun ResultsCard(screen: Screen.Results, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onDone)
    LaunchedEffect(Unit) { focus.requestFocus() }
    val title = when {
        screen.ran -> "YOU RAN"
        screen.won -> "YOU WIN"
        else -> "YOU LOSE"
    }
    Column(
        Modifier.fillMaxSize().background(Ink),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = displayStyle(88.sp, color = if (screen.won) BloodGlow else TextLo))
        Text("+${screen.reward.xpGained} XP", style = displayStyle(36.sp))
        if (screen.reward.levelsGained > 0) Text("LEVEL UP!", style = displayStyle(36.sp, color = Amber))
        Spacer(Modifier.height(32.dp))
        FocusPill("Continue", onDone, Modifier.focusRequester(focus))
    }
}
