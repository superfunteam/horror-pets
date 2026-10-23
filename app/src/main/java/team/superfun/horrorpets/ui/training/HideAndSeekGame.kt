package team.superfun.horrorpets.ui.training

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.model.TrainingGame

/**
 * Hide and Seek, standing in until the game is built: its HUD, and OK or a tap ends it with 0 (see [TrainingEnv] for
 * what a game gets and owes the shell).
 */
@Composable
fun HideAndSeekGame(env: TrainingEnv, onOver: (score: Int) -> Unit) {
    val latestOnOver by rememberUpdatedState(onOver)
    var over by remember { mutableStateOf(false) }
    val end = {
        if (!over) {
            over = true
            latestOnOver(0)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(env.focus)
            .trainingKeys { key, down ->
                if (key == TrainingKey.OK && down) end()
                true
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { end() } },
    ) {
        TrainingHud(
            title = TrainingGame.HIDE_AND_SEEK.title,
            status = "",
            best = env.best,
            help = listOf(HelpItem(listOf(TrainingKey.OK), "End")),
            touchHelp = "Tap to end",
        )
    }
}
