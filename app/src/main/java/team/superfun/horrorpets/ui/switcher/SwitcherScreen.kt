// Placeholder — replaced in Task 12 (spotlight switcher).
package team.superfun.horrorpets.ui.switcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.SwitcherMode
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.safeInsetPadding
import team.superfun.horrorpets.ui.theme.Ink

/**
 * Functional stand-in for the spotlight switcher: the focused pet idles centre stage over a row of
 * name pills. OK picks; Back cancels (and leaves the app while onboarding, where there is nothing to
 * cancel back to). In OPPONENT mode the player's own pet is left out.
 */
@Composable
fun SwitcherScreen(
    mode: SwitcherMode,
    game: GameState,
    loader: AssetLoader,
    onPick: (PetId) -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit,
) {
    val pets = PetId.entries.filter { mode != SwitcherMode.OPPONENT || it != game.selected }
    val initial = game.selected?.takeIf { it in pets } ?: pets.first()
    var focusedPet by remember { mutableStateOf(initial) }
    val initialFocus = remember { FocusRequester() }

    BackHandler { if (mode == SwitcherMode.ONBOARD) onExit() else onBack() }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() } * 0.42f
        Column(Modifier.fillMaxSize().padding(safeInsetPadding()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (mode == SwitcherMode.OPPONENT) "CHOOSE YOUR OPPONENT" else "CHOOSE YOUR KILLER",
                style = displayStyle(48.sp, tracking = 0.12f),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                SpriteView(
                    loader = loader,
                    pet = focusedPet,
                    anim = Anim.IDLE,
                    heightPx = heightPx,
                    tint = null,
                    tintStrength = 0f,
                    shadowOpacity = 0.6f,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (pet in pets) {
                    FocusPill(
                        label = pet.displayName,
                        onClick = { onPick(pet) },
                        minWidth = 150.dp,
                        modifier = Modifier
                            .then(if (pet == initial) Modifier.focusRequester(initialFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedPet = pet },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
