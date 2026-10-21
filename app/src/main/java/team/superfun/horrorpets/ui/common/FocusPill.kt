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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Glass
import team.superfun.horrorpets.ui.theme.TextHi

/** Height of every pill: big enough to read and hit from the sofa. */
val PillHeight: Dp = 54.dp

/** How much a focused pill grows. */
const val PILL_FOCUS_SCALE = 1.1f

/** A pill's label style; callers that lay pills out by their label widths measure with this. */
fun pillLabelStyle(color: Color = TextHi): TextStyle = displayStyle(24.sp, tracking = 0.06f, color = color)

private val FocusedFill = Brush.verticalGradient(
    0f to Color(0xFFE01B2B),
    0.55f to Blood,
    1f to Color(0xFF8E0B15),
)

/**
 * The game's button: a rounded pill with an uppercase Display label. Unfocused it is translucent
 * white-on-glass; focused it fills with Blood, gets a BloodGlow rim and halo and grows to 1.1x, so the
 * focus is obvious from across the room. OK (or Enter) calls [onClick].
 */
@Composable
fun FocusPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 0.dp,
    horizontalPadding: Dp = 26.dp,
    interactionSource: MutableInteractionSource? = null,
) {
    FocusPillSurface(
        onClick = onClick,
        modifier = modifier,
        minWidth = minWidth,
        horizontalPadding = horizontalPadding,
        interactionSource = interactionSource,
    ) { focused ->
        Text(
            text = label.uppercase(),
            style = pillLabelStyle(color = if (focused) TextHi else TextHi.copy(alpha = 0.88f)),
            maxLines = 1,
        )
    }
}

/**
 * The pill surface with custom [content] (for icons such as the scene arrows); [content] gets whether
 * the pill is focused. Same look and behaviour as [FocusPill].
 */
@Composable
fun FocusPillSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 0.dp,
    horizontalPadding: Dp = 26.dp,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            focused && pressed -> 1.03f
            focused -> PILL_FOCUS_SCALE
            else -> 1f
        },
        animationSpec = tween(180),
        label = "pillScale",
    )
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "pillGlow")

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .defaultMinSize(minWidth = minWidth, minHeight = PillHeight)
            .drawBehind { drawPill(glow) }
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}

private fun DrawScope.drawPill(glow: Float) {
    val r = size.height / 2f
    val corner = CornerRadius(r)
    val stroke = 2.dp.toPx()
    softGlow(BloodGlow, alpha = 0.7f * glow, cornerRadius = r, spread = 18.dp)
    // Unfocused: dark glass with a whisper of white. Focused: the Blood fill fades in over it.
    drawRoundRect(color = Glass, cornerRadius = corner, alpha = 1f - 0.4f * glow)
    drawRoundRect(color = Color.White, alpha = 0.07f * (1f - glow), cornerRadius = corner)
    if (glow > 0f) drawRoundRect(brush = FocusedFill, alpha = glow, cornerRadius = corner)
    val inset = stroke / 2f
    val rim = lerp(Color.White.copy(alpha = 0.18f), BloodGlow, glow)
    drawRoundRect(
        color = rim,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(r - inset),
        style = Stroke(stroke),
    )
}
