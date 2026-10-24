package team.superfun.horrorpets.ui.attacks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.model.Attack
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.home.Chevron
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextLo

/**
 * "JASON CAN LEARN A NEW HEAVY!": the attack [pet] has in that slot now ([current], NOW) beside the one it just
 * unlocked ([attack], NEW), each landed by the killer on a loop (see `AttackPreview`), the new one's stats marked
 * better ▲ or worse ▼.
 * LEARN IT ([onLearn], focused first) swaps it in; KEEP ([onKeep]) leaves the old one. Either way the pet knows it
 * now and can equip it later from MOVES. Back ([onLater]) puts the choice off until the next launch. OK and taps are
 * ignored for a moment after it comes up (see `rememberOkReady`), so a press mashed through the results cannot
 * choose for the player. The cards' previews take the killer's sprites, and any picture they throw, from [loader].
 */
@Composable
fun LearnScreen(
    pet: PetState,
    attack: Attack,
    current: Attack,
    loader: AssetLoader,
    onLearn: () -> Unit,
    onKeep: () -> Unit,
    onLater: () -> Unit,
    focusEntry: Any? = Unit,
) {
    val learn = remember { FocusRequester() }
    GameBackHandler(enabled = focusEntry != null, onBack = onLater)
    RequestFocusOnEntry(learn, focusEntry)
    val okReady = rememberOkReady(focusEntry)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2A0C12), Ink), radius = 1400f))
            .ignoreOkUnless(okReady),
    ) {
        val safe = safeInset()
        Column(
            Modifier.fillMaxSize().padding(horizontal = safe.horizontal, vertical = safe.vertical),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScreenTitle("${pet.id.displayName} can learn a new ${attack.slot.label} attack!", "Level ${pet.level}")
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AttackCard(current, vs = null, title = "NOW", loader = loader, modifier = Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    Chevron(left = false, color = Amber, modifier = Modifier.size(26.dp))
                }
                AttackCard(attack, vs = current, title = "NEW", loader = loader, lit = true, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                FocusPill("Learn it", onClick = { if (focusEntry != null) onLearn() }, modifier = Modifier.focusRequester(learn), minWidth = 170.dp)
                FocusPill("Keep ${current.name}", onClick = { if (focusEntry != null) onKeep() }, minWidth = 170.dp)
            }
            Spacer(Modifier.height(8.dp))
            BasicText(
                "EITHER WAY YOU CAN SWAP IT IN LATER FROM MOVES ON HOME",
                style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f),
                maxLines = 1,
            )
        }
    }
}
