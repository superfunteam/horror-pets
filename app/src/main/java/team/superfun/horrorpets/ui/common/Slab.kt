package team.superfun.horrorpets.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import team.superfun.horrorpets.ui.theme.BloodGlow

/** Every slab's height, big enough to read and hit from the sofa. */
val SlabHeight = 50.dp

/** How much a focused slab grows. */
const val SLAB_FOCUS_SCALE = 1.1f

/** How much a slab shrinks under a finger, so a tap feels pressed. */
private const val SLAB_TOUCH_PRESS_SCALE = 0.95f

/** The slabs' lean, the same as the fight's HP bars and Home's gauges: run over rise. */
const val SLAB_SLANT = 0.25f

private val SlabFill = Brush.verticalGradient(listOf(Color(0xFF2A1D21), Color(0xFF0C090B)))
private val LitFill = Brush.verticalGradient(
    0f to Color(0xFFFF7A82),
    0.34f to Color(0xFFE0262F),
    0.62f to Color(0xFFC1121F),
    1f to Color(0xFF7A0912),
)

/**
 * The game's arcade button (Home's action bar, the title and unlock screens): a slanted slab, dark with a hard drop shadow; focused it grows to [SLAB_FOCUS_SCALE] and fills
 * glossy blood red with a white edge and a red halo, so the focus is obvious from across the room. OK (or Enter)
 * calls [onClick]. Under a finger (see `isTouchInput`) nothing shows focus: a slab lights the same way while it is
 * pressed, shrinking a little instead of growing, and a tap calls [onClick] with the `ui_select` tick. [content]
 * gets whether the slab is lit.
 */
@Composable
fun SlabSurface(
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
    val s = size.height * SLAB_SLANT
    moveTo(s, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width - s, size.height)
    lineTo(0f, size.height)
    close()
}

/** A slab's look across this whole box, [glow] (0..1) of the way to lit; the pantry's COOK and FEED draw it too. */
internal fun DrawScope.drawSlab(glow: Float) {
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
fun Modifier.leaning(): Modifier = drawWithContent {
    val lean = Matrix().apply {
        values[Matrix.SkewX] = -SLAB_SLANT
        values[Matrix.TranslateX] = SLAB_SLANT * size.height / 2f
    }
    withTransform({ transform(lean) }) { this@drawWithContent.drawContent() }
}

