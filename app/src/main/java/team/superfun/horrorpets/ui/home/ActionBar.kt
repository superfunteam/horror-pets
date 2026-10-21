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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.FocusPillSurface
import team.superfun.horrorpets.ui.common.GlassPanel
import team.superfun.horrorpets.ui.common.PILL_FOCUS_SCALE
import team.superfun.horrorpets.ui.common.PillHeight
import team.superfun.horrorpets.ui.common.bodyStyle
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.pillLabelStyle
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** The four action labels, left to right. */
private val ActionLabels = listOf("Play", "Feed", "Train", "Fight")

/**
 * The row's spacing. The bar uses the roomiest of [BarTiers] whose width fits the space it is given
 * (the width inside the safe inset), so it stays whole on narrow screens: a 640 dp-wide screen has
 * only 576 dp inside the inset, and the roomy row needs about 610 dp.
 *
 * @param pillMinWidth the action pills' minimum width, so short labels (PLAY, FEED) match the long ones.
 * @param pillPadding side padding inside an action pill.
 * @param gap space between neighbouring items.
 * @param dividerPadding space on each side of a divider, on top of [gap].
 * @param arrowWidth width of the arrow pills (their height is always [PillHeight]).
 * @param chevron the arrow glyph's size.
 */
@Immutable
private class BarMetrics(
    val pillMinWidth: Dp,
    val pillPadding: Dp,
    val gap: Dp,
    val dividerPadding: Dp,
    val arrowWidth: Dp,
    val chevron: Dp,
) {
    /**
     * The row's width for labels [labelWidths] wide, plus room for an end arrow to grow when focused
     * ([PILL_FOCUS_SCALE] scales a pill about its centre, past the row's edge).
     */
    fun rowWidth(labelWidths: List<Dp>): Dp {
        val pills = labelWidths.fold(0.dp) { sum, w -> sum + maxOf(pillMinWidth, w + pillPadding * 2) }
        val dividers = (DividerWidth + dividerPadding * 2) * 2
        val gaps = gap * (labelWidths.size + 4 - 1) // pills, two arrows, two dividers
        val focusGrowth = arrowWidth * (PILL_FOCUS_SCALE - 1f)
        return arrowWidth * 2 + dividers + pills + gaps + focusGrowth
    }
}

/** Roomiest first. The last fits any screen from 560 dp wide up, and is used even when nothing fits. */
private val BarTiers = listOf(
    BarMetrics(pillMinWidth = 100.dp, pillPadding = 20.dp, gap = 10.dp, dividerPadding = 4.dp, arrowWidth = PillHeight, chevron = 22.dp),
    BarMetrics(pillMinWidth = 88.dp, pillPadding = 14.dp, gap = 6.dp, dividerPadding = 2.dp, arrowWidth = 48.dp, chevron = 20.dp),
    BarMetrics(pillMinWidth = 0.dp, pillPadding = 10.dp, gap = 4.dp, dividerPadding = 0.dp, arrowWidth = 46.dp, chevron = 20.dp),
)

private val DividerWidth = 3.dp

/**
 * The home action row, bottom centre: `◀ | PLAY FEED TRAIN FIGHT | ▶`.
 *
 * The arrows walk to the previous / next scene; while one is focused, a caption above it names the
 * scene it leads to (not under a finger, where nothing is focused). Up from any pill goes to [statCardFocus]. The row is one focus group with a
 * restorer, so coming back into it (Down from the stat card) lands on the pill that last had focus,
 * or on PLAY ([playFocus], also the screen's initial focus) when none has yet.
 *
 * The row tightens its spacing (pill widths and padding, gaps, arrow size; never the text) to fit the
 * width it is given, see [BarMetrics].
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
    val touch = isTouchInput()
    val upToCard = Modifier.focusProperties { up = statCardFocus }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidths = remember(measurer, density) {
        ActionLabels.map { with(density) { measurer.measure(it.uppercase(), pillLabelStyle()).size.width.toDp() } }
    }

    BoxWithConstraints(modifier) {
        val m = BarTiers.firstOrNull { it.rowWidth(labelWidths) <= maxWidth } ?: BarTiers.last()
        Row(
            modifier = Modifier
                .padding(top = CaptionSpace)
                // The restorer only acts on a focus group: without focusGroup() entering the row is a
                // plain geometric search, which from the stat card lands on the left arrow.
                .focusRestorer(playFocus)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(m.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArrowPill(left = true, onClick = { onWalk(-1) }, interaction = prevInteraction, metrics = m, modifier = upToCard)
            BarDivider(m)
            ActionPill(ActionLabels[0], { onCare(CareAction.PLAY) }, m, upToCard.focusRequester(playFocus))
            ActionPill(ActionLabels[1], { onCare(CareAction.FEED) }, m, upToCard)
            ActionPill(ActionLabels[2], { onCare(CareAction.TRAIN) }, m, upToCard)
            ActionPill(ActionLabels[3], onFight, m, upToCard)
            BarDivider(m)
            ArrowPill(left = false, onClick = { onWalk(1) }, interaction = nextInteraction, metrics = m, modifier = upToCard)
        }

        WalkCaption(prevSceneTitle, visible = prevFocused && !touch, modifier = Modifier.align(Alignment.TopStart))
        WalkCaption(nextSceneTitle, visible = nextFocused && !touch, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** Room above the row for the walk caption, so showing it never moves the row. */
private val CaptionSpace = 64.dp

@Composable
private fun ActionPill(label: String, onClick: () -> Unit, metrics: BarMetrics, modifier: Modifier) {
    FocusPill(
        label = label,
        onClick = onClick,
        modifier = modifier,
        minWidth = metrics.pillMinWidth,
        horizontalPadding = metrics.pillPadding,
    )
}

@Composable
private fun ArrowPill(
    left: Boolean,
    onClick: () -> Unit,
    interaction: MutableInteractionSource,
    metrics: BarMetrics,
    modifier: Modifier,
) {
    FocusPillSurface(
        onClick = onClick,
        modifier = modifier,
        minWidth = metrics.arrowWidth,
        horizontalPadding = 0.dp,
        interactionSource = interaction,
    ) { lit ->
        Chevron(left = left, color = if (lit) TextHi else TextHi.copy(alpha = 0.88f), modifier = Modifier.size(metrics.chevron))
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
private fun BarDivider(metrics: BarMetrics) {
    Box(
        Modifier
            .padding(horizontal = metrics.dividerPadding)
            .size(width = DividerWidth, height = 30.dp)
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
