package team.superfun.horrorpets.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.ui.common.SLAB_FOCUS_SCALE
import team.superfun.horrorpets.ui.common.SlabSurface
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.leaning
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.TextHi

/** The five action labels, left to right. */
private val ActionLabels = listOf("Play", "Feed", "Train", "Fight", "Moves")

/** An action slab's label style; the bar lays the slabs out by their label widths, measured with it. */
private fun slabLabelStyle(color: Color = TextHi): TextStyle = displayStyle(24.sp, tracking = 0.03f, color = color)

/**
 * The row's spacing. The bar uses the roomiest of [BarTiers] whose width fits the space it is given
 * (the width inside the safe inset), so it stays whole on narrow screens: a 640 dp-wide screen has
 * only 576 dp inside the inset.
 *
 * @param slabMinWidth the action slabs' minimum width, so short labels (PLAY, FEED) match the long ones.
 * @param slabPadding side padding inside an action slab.
 * @param gap space between neighbouring slabs.
 * @param arrowWidth width of the arrow slabs (their height is always [SlabHeight]).
 * @param chevron the arrow glyph's size.
 */
@Immutable
private class BarMetrics(
    val slabMinWidth: Dp,
    val slabPadding: Dp,
    val gap: Dp,
    val arrowWidth: Dp,
    val chevron: Dp,
) {
    /**
     * The row's width for labels [labelWidths] wide, plus room for a slab to grow when focused
     * ([SLAB_FOCUS_SCALE] scales a slab about its centre, past the row's edge).
     */
    fun rowWidth(labelWidths: List<Dp>): Dp {
        val slabs = labelWidths.fold(0.dp) { sum, w -> sum + maxOf(slabMinWidth, w + slabPadding * 2) }
        val gaps = gap * (labelWidths.size + 2 - 1) // slabs and two arrows
        val focusGrowth = maxOf(arrowWidth, slabMinWidth) * (SLAB_FOCUS_SCALE - 1f)
        return arrowWidth * 2 + slabs + gaps + focusGrowth
    }
}

/** Roomiest first. The last fits any screen from 560 dp wide up, and is used even when nothing fits. */
private val BarTiers = listOf(
    BarMetrics(slabMinWidth = 104.dp, slabPadding = 22.dp, gap = 10.dp, arrowWidth = 56.dp, chevron = 20.dp),
    BarMetrics(slabMinWidth = 92.dp, slabPadding = 16.dp, gap = 7.dp, arrowWidth = 50.dp, chevron = 18.dp),
    BarMetrics(slabMinWidth = 0.dp, slabPadding = 12.dp, gap = 5.dp, arrowWidth = 46.dp, chevron = 18.dp),
)

/**
 * The home action row, bottom centre: `◀ PLAY FEED TRAIN FIGHT MOVES ▶` as slanted arcade slabs. The focused one
 * grows and fills glossy blood red with a white edge. MOVES ([onMoves]) opens the pet's attacks.
 *
 * The arrows walk to the previous / next scene; while one is focused, a caption above it names the
 * scene it leads to (not under a finger, where nothing is focused). Up from any slab goes to [upFocus] (the stable
 * badge), or nowhere when there is none. The row is one focus group with a restorer, so coming back into it (Down
 * from the badge) lands on the slab that last had focus, or on PLAY ([playFocus], also the screen's initial focus)
 * when none has yet. With [canFocus] off (the stable is open over Home) none of the slabs takes focus.
 *
 * The row tightens its spacing (slab widths and padding, gaps, arrow size; never the text) to fit the
 * width it is given, see [BarMetrics].
 */
@Composable
fun ActionBar(
    prevSceneTitle: String,
    nextSceneTitle: String,
    onWalk: (dir: Int) -> Unit,
    onCare: (CareAction) -> Unit,
    onFight: () -> Unit,
    onMoves: () -> Unit,
    playFocus: FocusRequester,
    upFocus: FocusRequester?,
    modifier: Modifier = Modifier,
    canFocus: Boolean = true,
) {
    val prevInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val prevFocused by prevInteraction.collectIsFocusedAsState()
    val nextFocused by nextInteraction.collectIsFocusedAsState()
    val touch = isTouchInput()
    val upToCard = Modifier.focusProperties {
        up = upFocus ?: FocusRequester.Cancel
        this.canFocus = canFocus
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidths = remember(measurer, density) {
        ActionLabels.map { with(density) { measurer.measure(it.uppercase(), slabLabelStyle()).size.width.toDp() } }
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
            ArrowSlab(left = true, onClick = { onWalk(-1) }, interaction = prevInteraction, metrics = m, modifier = upToCard)
            ActionSlab(ActionLabels[0], { onCare(CareAction.PLAY) }, m, upToCard.focusRequester(playFocus))
            ActionSlab(ActionLabels[1], { onCare(CareAction.FEED) }, m, upToCard)
            ActionSlab(ActionLabels[2], { onCare(CareAction.TRAIN) }, m, upToCard)
            ActionSlab(ActionLabels[3], onFight, m, upToCard)
            ActionSlab(ActionLabels[4], onMoves, m, upToCard)
            ArrowSlab(left = false, onClick = { onWalk(1) }, interaction = nextInteraction, metrics = m, modifier = upToCard)
        }

        WalkCaption(prevSceneTitle, visible = prevFocused && !touch, modifier = Modifier.align(Alignment.TopStart))
        WalkCaption(nextSceneTitle, visible = nextFocused && !touch, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** Room above the row for the walk caption, so showing it never moves the row. */
private val CaptionSpace = 64.dp

@Composable
private fun ActionSlab(label: String, onClick: () -> Unit, metrics: BarMetrics, modifier: Modifier) {
    SlabSurface(onClick, modifier, minWidth = metrics.slabMinWidth, horizontalPadding = metrics.slabPadding) { lit ->
        BasicText(
            label.uppercase(),
            style = slabLabelStyle(if (lit) TextHi else TextHi.copy(alpha = 0.8f)),
            maxLines = 1,
            modifier = Modifier.leaning(),
        )
    }
}

@Composable
private fun ArrowSlab(
    left: Boolean,
    onClick: () -> Unit,
    interaction: MutableInteractionSource,
    metrics: BarMetrics,
    modifier: Modifier,
) {
    SlabSurface(onClick, modifier, minWidth = metrics.arrowWidth, horizontalPadding = 0.dp, interactionSource = interaction) { lit ->
        Chevron(left = left, color = if (lit) TextHi else Amber, modifier = Modifier.size(metrics.chevron))
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

/** "WALK TO / CRYSTAL LAKE DOCK" in a small gold frame over an arrow while it is focused. */
@Composable
private fun WalkCaption(title: String, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 3 },
    ) {
        Column(
            Modifier
                .gameFrame(topEnd = 8.dp, bottomStart = 8.dp, inner = false)
                .padding(start = 14.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
        ) {
            BasicText("WALK TO", style = displayStyle(12.sp, FontWeight.Bold, color = Amber, tracking = 0.2f))
            BasicText(title.uppercase(), style = displayStyle(22.sp), maxLines = 1)
        }
    }
}
