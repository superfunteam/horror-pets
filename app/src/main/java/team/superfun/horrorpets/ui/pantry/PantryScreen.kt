package team.superfun.horrorpets.ui.pantry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** How long a refusal ("That will not cook.") stays up. */
private const val NOTE_MS = 1_500L

/**
 * FEED: the pantry, for now a plain stand-in that offers everything the view model does so the visit can be played
 * through: [shelf] listed down the left (OK or a tap puts an item on [Screen.Pantry.board], or takes it off again),
 * the board and the last cook beside it, COOK and FEED under them, and Back to leave. Once [pet] has eaten, the
 * reaction covers the screen until OK, a tap or any key carries on Home. [loader] is for the kitchen's art, which this
 * stand-in does not draw.
 *
 * Everything it reports names [Screen.Pantry.session] (see there). [focusEntry] is as on the other screens.
 */
@Composable
fun PantryScreen(
    screen: Screen.Pantry,
    pet: PetState,
    shelf: List<PantryItem>,
    loader: AssetLoader,
    onPick: (Long, Int) -> Unit,
    onCook: (Long) -> Unit,
    onFeed: (Long) -> Unit,
    onDone: (Long) -> Unit,
    onBack: (Long) -> Unit,
    focusEntry: Any?,
) {
    val session = screen.session
    GameBackHandler(enabled = focusEntry != null) { onBack(session) }
    val cookButton = remember { FocusRequester() }
    RequestFocusOnEntry(cookButton, focusEntry)
    val okReady = rememberOkReady(focusEntry)
    val fed = screen.fed

    // A refusal says so for a moment; the screen's tokens change on each one.
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen.refusedCook) {
        if (screen.refusedCook == 0L) return@LaunchedEffect
        note = "That will not cook."
        delay(NOTE_MS)
        note = null
    }
    LaunchedEffect(screen.tooFull) {
        if (screen.tooFull == 0L) return@LaunchedEffect
        note = "${pet.id.displayName} is too full to eat."
        delay(NOTE_MS)
        note = null
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .ignoreOkUnless(okReady)
            // Once the killer has eaten, any key carries on (Back is the back handler's own).
            .onPreviewKeyEvent { event ->
                val carryOn = fed != null && event.type == KeyEventType.KeyDown && event.key != Key.Back
                if (carryOn) onDone(session)
                carryOn
            },
    ) {
        val safe = safeInset()
        Column(Modifier.fillMaxSize().padding(horizontal = safe.horizontal, vertical = safe.vertical)) {
            BasicText(
                "${pet.id.displayName.uppercase()}'S PANTRY  ·  HUNGER ${pet.hunger.toInt()}",
                style = displayStyle(22.sp, tracking = 0.08f),
                maxLines = 1,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(shelf) { index, item ->
                        val place = screen.board.indexOf(index)
                        ShelfRow(
                            label = item.title + if (place >= 0) "  (on the board)" else "",
                            onBoard = place >= 0,
                            onClick = { onPick(session, index) },
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText("BOARD", style = displayStyle(16.sp, FontWeight.Bold, color = Amber, tracking = 0.14f))
                    val onBoard = screen.board.mapNotNull(shelf::getOrNull)
                    BasicText(
                        if (onBoard.isEmpty()) "Pick something from the shelf." else onBoard.joinToString("  +  ") { it.title },
                        style = displayStyle(18.sp, color = TextHi),
                    )
                    screen.cook?.let { cook ->
                        BasicText(
                            "Made ${cook.result.title} from ${cook.inputs.joinToString(" and ") { it.title }}.",
                            style = displayStyle(16.sp, color = TextLo),
                        )
                    }
                    note?.let { BasicText(it, style = displayStyle(16.sp, color = Blood)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                FocusPill("Cook", onClick = { onCook(session) }, modifier = Modifier.focusRequester(cookButton), minWidth = 140.dp)
                FocusPill("Feed", onClick = { onFeed(session) }, minWidth = 140.dp)
            }
            Spacer(Modifier.height(8.dp))
            BasicText(
                if (isTouchInput()) "TAP THE SHELF TO PICK  ·  BACK TO LEAVE" else "OK PICKS  ·  BACK TO LEAVE",
                style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f),
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        if (fed != null) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { onDone(session) },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(fed.item.title.uppercase(), style = displayStyle(30.sp, tracking = 0.06f))
                Spacer(Modifier.height(8.dp))
                BasicText("\"${fed.line}\"", style = displayStyle(22.sp, color = Amber))
                Spacer(Modifier.height(8.dp))
                BasicText(
                    "+${fed.xp} XP" + if (fed.levelsGained > 0) "  ·  LEVEL UP!" else "",
                    style = displayStyle(18.sp, color = TextHi),
                )
                Spacer(Modifier.height(16.dp))
                BasicText(
                    if (isTouchInput()) "TAP TO CARRY ON" else "PRESS ANY KEY TO CARRY ON",
                    style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f),
                )
            }
        }
    }
}

/** One item on the shelf: lit amber while focused, dim while it sits on the board. */
@Composable
private fun ShelfRow(label: String, onBoard: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicText(
        label,
        style = displayStyle(18.sp, color = if (focused) Amber else if (onBoard) TextLo else TextHi),
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}
