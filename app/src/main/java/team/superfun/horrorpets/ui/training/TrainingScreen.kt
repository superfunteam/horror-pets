package team.superfun.horrorpets.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Ink

/**
 * TRAIN: [screen]'s game on [pet]'s scene, against its stable [mate] when the game uses one. The game is over when it
 * calls [onOver] with its score, and its results card (shown once [Screen.Training.result] is set) carries on Home
 * with [onDone]. Back ([onQuit]) leaves the reel with nothing gained.
 *
 * A placeholder for now: it shows the game's name, and OK or a tap ends the game with 0 and then goes Home.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun TrainingScreen(
    screen: Screen.Training,
    pet: PetState,
    mate: PetState?,
    loader: AssetLoader,
    onOver: (Int) -> Unit,
    onDone: () -> Unit,
    onQuit: () -> Unit,
    focusEntry: Any?,
) {
    val focus = remember { FocusRequester() }
    GameBackHandler(enabled = focusEntry != null, onBack = onQuit)
    RequestFocusOnEntry(focus, focusEntry)
    val result = screen.result

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .focusRequester(focus)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (result == null) onOver(0) else onDone()
            },
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(screen.game.title.uppercase(), style = displayStyle(40.sp, tracking = 0.06f))
        BasicText(
            if (result == null) "OK ENDS THE GAME" else "SCORED ${result.score}  ·  OK GOES HOME",
            style = displayStyle(14.sp, FontWeight.Bold, color = Amber, tracking = 0.2f),
        )
    }
}
