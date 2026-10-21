package team.superfun.horrorpets.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.rememberTouchTick
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextHi

/** The four action labels, left to right. */
private val ActionLabels = listOf("Play", "Feed", "Train", "Fight")

/** Every slab's height, big enough to read and hit from the sofa. */
private val SlabHeight = 50.dp

/** How much a focused slab grows. */
private const val SLAB_FOCUS_SCALE = 1.1f

/** How much a slab shrinks under a finger, so a tap feels pressed. */
private const val SLAB_TOUCH_PRESS_SCALE = 0.95f

/** The slabs' lean, the same as the fight's HP bars and Home's gauges: run over rise. */
private const val SLANT = 0.25f

private val SlabFill = Brush.verticalGradient(listOf(Color(0xFF2A1D21), Color(0xFF0C090B)))
private val LitFill = Brush.verticalGradient(
    0f to Color(0xFFFF7A82),
    0.34f to Color(0xFFE0262F),
    0.62f to Color(0xFFC1121F),
    1f to Color(0xFF7A0912),
)

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
 * The home action row, bottom centre: `◀ PLAY FEED TRAIN FIGHT ▶` as slanted arcade slabs. The focused one grows
 * and fills glossy blood red with a white edge.
 *
 * The arrows walk to the previous / next scene; while one is focused, a caption above it names the
 * scene it leads to (not under a finger, where nothing is focused). Up from any slab goes to [statCardFocus]. The
 * row is one focus group with a restorer, so coming back into it (Down from the stat card) lands on the slab that
 * last had focus, or on PLAY ([playFocus], also the screen's initial focus) when none has yet.
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

/**
 * The bar's button: a slanted slab, dark with a hard drop shadow; focused it grows to [SLAB_FOCUS_SCALE] and fills
 * glossy blood red with a white edge and a red halo, so the focus is obvious from across the room. OK (or Enter)
 * calls [onClick]. Under a finger (see `isTouchInput`) nothing shows focus: a slab lights the same way while it is
 * pressed, shrinking a little instead of growing, and a tap calls [onClick] with the `ui_select` tick. [content]
 * gets whether the slab is lit.
 */
@Composable
private fun SlabSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    minWidth: Dp,
    horizontalPadding: Dp,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (lit: Boolean) -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val touch = isTouchInput()
    val lit = if (touch) pressed else focused
    val scale by animateFloatAsState(
        targetValue = when {
            touch -> if (pressed) SLAB_TOUCH_PRESS_SCALE else 1f
            focused && pressed -> 1.03f
            focused -> SLAB_FOCUS_SCALE
            else -> 1f
        },
        animationSpec = tween(180),
        label = "slabScale",
    )
    val glow = animateFloatAsState(if (lit) 1f else 0f, tween(200), label = "slabGlow")
    val tick = rememberTouchTick("ui_select")

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null) {
                tick()
                onClick()
            }
            .defaultMinSize(minWidth = minWidth, minHeight = SlabHeight)
            .drawBehind { drawSlab(glow.value) }
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        content(lit)
    }
}

/** A slab leaning right like the HP bars: a parallelogram across this whole box. */
private fun slab(size: Size): Path = Path().apply {
    val s = size.height * SLANT
    moveTo(s, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width - s, size.height)
    lineTo(0f, size.height)
    close()
}

private fun DrawScope.drawSlab(glow: Float) {
    val shape = slab(size)
    val drop = 4.dp.toPx()
    translate(drop, drop) { drawPath(shape, Color.Black.copy(alpha = 0.55f)) }
    if (glow > 0f) softGlow(BloodGlow, alpha = 0.55f * glow, cornerRadius = 2.dp.toPx(), spread = 16.dp, layers = 7)
    drawPath(shape, SlabFill)
    if (glow > 0f) drawPath(shape, LitFill, alpha = glow)
    clipPath(shape) {
        // A thin highlight along the top edge, swelling into a gloss across the top half when lit.
        drawRect(Color.White.copy(alpha = 0.18f * (1f - glow)), size = Size(size.width, 1.dp.toPx()))
        if (glow > 0f) {
            drawRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f * glow), Color.Transparent), endY = size.height * 0.45f),
                size = Size(size.width, size.height * 0.45f),
            )
        }
    }
    val edge = lerp(Color.White.copy(alpha = 0.14f), Color.White, glow)
    drawPath(shape, edge, style = Stroke(1.dp.toPx() + glow * 1.dp.toPx()))
}

/** Draws its content leaning right like the slabs, without changing its layout: an italic for a font without one. */
private fun Modifier.leaning(): Modifier = drawWithContent {
    val lean = Matrix().apply {
        values[Matrix.SkewX] = -SLANT
        values[Matrix.TranslateX] = SLANT * size.height / 2f
    }
    withTransform({ transform(lean) }) { this@drawWithContent.drawContent() }
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
