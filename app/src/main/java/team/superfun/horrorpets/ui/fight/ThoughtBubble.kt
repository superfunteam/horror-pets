package team.superfun.horrorpets.ui.fight

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Glass

/** Room under the bubble for its two trailing thought dots, down to the fighter's head. */
private val DotsRoom = 22.dp

/**
 * A thought bubble over a fighter's head: a small Amber [caption] over [text] in [ink], rimmed in [accent], with
 * two dots trailing down to the head. [visible] (0..1) fades it, and it bobs gently so the eye finds it. [x], [y]
 * is the top of the head; like the callouts it keeps below [top] and [inset] pixels clear of the sides.
 */
@Composable
internal fun ThoughtBubble(
    caption: String,
    text: String,
    accent: Color,
    ink: Color,
    visible: () -> Float,
    x: () -> Float,
    y: () -> Float,
    top: () -> Float,
    inset: Float,
) {
    val bob = rememberInfiniteTransition(label = "thoughtBob").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "thoughtBobValue",
    )
    val density = LocalDensity.current
    val dots = with(density) { DotsRoom.toPx() }
    Column(
        Modifier
            .placeInBounds(
                x = x,
                y = { y() - dots },
                top = top,
                inset = inset,
                popScale = 1f,
                rise = 0f,
                rim = with(density) { 4.dp.toPx() },
            )
            .graphicsLayer {
                alpha = visible()
                translationY = bob.value * 3.dp.toPx()
            }
            .drawBehind {
                val r = CornerRadius(14.dp.toPx())
                softGlow(Color.Black, alpha = 0.55f, cornerRadius = r.x, spread = 12.dp, offsetY = 3.dp.toPx())
                drawRoundRect(Glass, cornerRadius = r)
                drawRoundRect(Color.Black.copy(alpha = 0.35f), cornerRadius = r)
                val stroke = 2.dp.toPx()
                drawRoundRect(
                    color = accent.copy(alpha = 0.9f),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(r.x - stroke / 2f),
                    style = Stroke(stroke),
                )
                // The thought trails down to the head in two shrinking dots.
                for ((dy, radius) in listOf(8.dp to 5.dp, 17.dp to 3.dp)) {
                    val c = Offset(size.width / 2f, size.height + dy.toPx())
                    drawCircle(Glass, radius.toPx(), c)
                    drawCircle(accent.copy(alpha = 0.9f), radius.toPx(), c, style = Stroke(1.5.dp.toPx()))
                }
            }
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(caption, style = displayStyle(11.sp, FontWeight.Bold, color = Amber, tracking = 0.22f, lineHeight = 12.sp), maxLines = 1)
        BasicText(
            text,
            style = displayStyle(28.sp, color = ink, tracking = 0.08f, lineHeight = 30.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}
