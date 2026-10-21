package team.superfun.horrorpets.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.FocusPillSurface
import team.superfun.horrorpets.ui.common.GlassPanel
import team.superfun.horrorpets.ui.common.PillHeight
import team.superfun.horrorpets.ui.common.bodyStyle
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** Minimum width of the four action pills, so short labels (PLAY, FEED) match the long ones. */
private val ActionPillWidth = 100.dp

/** Side padding inside the action pills: snug, so the whole row fits a 4:3 screen. */
private val ActionPillPadding = 20.dp

/**
 * The home action row, bottom centre: `◀ | PLAY FEED TRAIN FIGHT | ▶`.
 *
 * The arrows walk to the previous / next scene; while one is focused, a caption above it names the
 * scene it leads to. Up from any pill goes to [statCardFocus]. The row is one focus group with a
 * restorer, so coming back into it (Down from the stat card) lands on the pill that last had focus,
 * or on PLAY ([playFocus], also the screen's initial focus) when none has yet.
 */
@Composable
fun ActionBar(
    prevSceneTitle: String,
    nextSceneTitle: String,
    onWalk: (dir: Int) -> Unit,
    onCare: (CareAction) -> Unit,
    onFight: () -> Unit,
    playFocus: FocusRequester,
    statCardFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val prevInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val prevFocused by prevInteraction.collectIsFocusedAsState()
    val nextFocused by nextInteraction.collectIsFocusedAsState()
    val upToCard = Modifier.focusProperties { up = statCardFocus }

    Box(modifier) {
        Row(
            modifier = Modifier
                .padding(top = CaptionSpace)
                // The restorer only acts on a focus group: without focusGroup() entering the row is a
                // plain geometric search, which from the stat card lands on the left arrow.
                .focusRestorer(playFocus)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArrowPill(left = true, onClick = { onWalk(-1) }, interaction = prevInteraction, modifier = upToCard)
            BarDivider()
            ActionPill("Play", { onCare(CareAction.PLAY) }, upToCard.focusRequester(playFocus))
            ActionPill("Feed", { onCare(CareAction.FEED) }, upToCard)
            ActionPill("Train", { onCare(CareAction.TRAIN) }, upToCard)
            ActionPill("Fight", onFight, upToCard)
            BarDivider()
            ArrowPill(left = false, onClick = { onWalk(1) }, interaction = nextInteraction, modifier = upToCard)
        }

        WalkCaption(prevSceneTitle, visible = prevFocused, modifier = Modifier.align(Alignment.TopStart))
        WalkCaption(nextSceneTitle, visible = nextFocused, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** Room above the row for the walk caption, so showing it never moves the row. */
private val CaptionSpace = 64.dp

@Composable
private fun ActionPill(label: String, onClick: () -> Unit, modifier: Modifier) {
    FocusPill(
        label = label,
        onClick = onClick,
        modifier = modifier,
        minWidth = ActionPillWidth,
        horizontalPadding = ActionPillPadding,
    )
}

@Composable
private fun ArrowPill(left: Boolean, onClick: () -> Unit, interaction: MutableInteractionSource, modifier: Modifier) {
    FocusPillSurface(
        onClick = onClick,
        modifier = modifier,
        minWidth = PillHeight,
        horizontalPadding = 0.dp,
        interactionSource = interaction,
    ) { focused ->
        Chevron(left = left, color = if (focused) TextHi else TextHi.copy(alpha = 0.88f), modifier = Modifier.size(22.dp))
    }
}

/** A bold rounded chevron (Barlow has no arrow glyphs, and a font fallback would look off). */
@Composable
fun Chevron(left: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 4.dp.toPx()
        val tipX = if (left) w * 0.28f else w * 0.72f
        val tailX = if (left) w * 0.68f else w * 0.32f
        val path = Path().apply {
            moveTo(tailX, h * 0.12f)
            lineTo(tipX, h * 0.5f)
            lineTo(tailX, h * 0.88f)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun BarDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .size(width = 3.dp, height = 30.dp)
            .background(Color.White.copy(alpha = 0.28f)),
    )
}

/** "WALK TO / CRYSTAL LAKE DOCK" over an arrow while it is focused. */
@Composable
private fun WalkCaption(title: String, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 3 },
    ) {
        GlassPanel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Column {
                Text("WALK TO", style = bodyStyle(13.sp, color = TextLo).copy(letterSpacing = 1.4.sp))
                Text(title.uppercase(), style = displayStyle(22.sp), maxLines = 1)
            }
        }
    }
}
