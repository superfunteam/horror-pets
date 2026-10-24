package team.superfun.horrorpets.ui.title

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.rememberTouchTick
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** Who made the chibi figure a killer is loosely based on, by their [username]. */
data class ArtCredit(val pet: PetId, val username: String)

/**
 * Every killer's credit but a secret one's, in the roster's order: the credits are on the title screen, before anything
 * is revealed, so they never name him. The landing page's footer (site/index.html) lists the same.
 */
val ArtCredits = listOf(
    ArtCredit(PetId.JASON, "Ninomalbinho"),
    ArtCredit(PetId.FREDDY, "novYTerra"),
    ArtCredit(PetId.PINHEAD, "Marcinnn"),
    ArtCredit(PetId.GHOSTFACE, "MartinCrow"),
    ArtCredit(PetId.CHUCKY, "Ninomalbinho"),
    ArtCredit(PetId.MICHAEL, "marjers"),
    ArtCredit(PetId.LEATHERFACE, "ChainsawGraphic"),
)

/**
 * The title's little CREDITS link, in a corner, out of PRESS START's way. Lit (focused, or pressed under a finger)
 * it fills with Blood like a pill; a tap still gets a finger-sized target.
 */
@Composable
internal fun CreditsLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lit = if (isTouchInput()) pressed else focused
    val glow by animateFloatAsState(if (lit) 1f else 0f, tween(160), label = "creditsGlow")
    val tick = rememberTouchTick("ui_select")
    Box(
        modifier
            .clickable(interactionSource = interaction, indication = null) {
                tick()
                onClick()
            }
            .defaultMinSize(minHeight = 44.dp)
            .drawBehind {
                if (glow > 0f) drawRoundRect(Blood, alpha = glow, cornerRadius = CornerRadius(size.height / 2f))
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "CREDITS",
            style = displayStyle(15.sp, FontWeight.Bold, color = TextHi.copy(alpha = 0.55f + 0.45f * glow), tracking = 0.2f),
            maxLines = 1,
        )
    }
}

/**
 * The credits, in a game window over a scrim: whose art each killer is loosely based on. CLOSE has the focus;
 * Back, or a tap anywhere off the window, closes it too. The scrim takes every tap, so none reaches the title.
 */
@Composable
internal fun CreditsDialog(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val closeFocus = remember { FocusRequester() }
    GameBackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
    val back = rememberTouchTick("ui_back")
    // Taps, not clicks, on the scrim and the window: a clickable would take the remote's focus off CLOSE.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .pointerInput(Unit) {
                detectTapGestures {
                    back()
                    onClose()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier
                .gameFrame(topEnd = 14.dp, bottomStart = 14.dp)
                .pointerInput(Unit) { detectTapGestures {} } // a tap on the window itself doesn't close it
                .padding(horizontal = 44.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("CREDITS", style = displayStyle(44.sp, tracking = 0.08f, lineHeight = 44.sp), maxLines = 1)
            Spacer(Modifier.height(6.dp))
            BasicText(
                "CHARACTERS LOOSELY BASED ON ART BY",
                style = displayStyle(15.sp, FontWeight.Bold, color = TextLo, tracking = 0.12f),
                maxLines = 1,
            )
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ArtCredits.forEach { CreditRow(it) }
            }
            Spacer(Modifier.height(22.dp))
            FocusPill("Close", onClick = onClose, modifier = Modifier.focusRequester(closeFocus), minWidth = 160.dp)
        }
    }
}

/** One killer's line: their name, then the artist's @username. */
@Composable
private fun CreditRow(credit: ArtCredit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            credit.pet.displayName.uppercase(),
            style = displayStyle(20.sp, tracking = 0.06f),
            maxLines = 1,
            modifier = Modifier.width(144.dp),
        )
        BasicText("@${credit.username}", style = displayStyle(20.sp, FontWeight.Bold, color = Amber, tracking = 0.02f), maxLines = 1)
    }
}
