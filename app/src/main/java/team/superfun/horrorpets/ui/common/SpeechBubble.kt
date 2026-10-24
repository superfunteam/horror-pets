package team.superfun.horrorpets.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import team.superfun.horrorpets.ui.theme.Ink

/** How long a killer's short line (a refusal on Home, "Too full!" in the pantry) stays over its head. */
internal const val BUBBLE_MS = 2_000L

/** A speech bubble's tail sits this far above the feet, as a fraction of the cell height (just over the head). */
internal const val BUBBLE_ABOVE_FEET = 0.91f

/** The bubble's tail: where it sits from the bubble's left edge when there is room, and its width. */
private val BubbleTailInset = 40.dp
private val BubbleTailWidth = 26.dp

/** Longer lines wrap onto two lines rather than reach across the screen. */
private val BubbleMaxWidth = 330.dp

private const val BUBBLE_POP_FROM = 0.6f
private const val BUBBLE_SHRINK_TO = 0.85f

/**
 * Bone-white speech bubble whose tail points down at a killer's head ([headX]), popping in when [text] comes and
 * shrinking away when it goes (null): Home's refusals, and the pantry's reactions. Its bottom (the tail's tip) is at
 * [bottomY]. The box hangs right of the head where it can and slides left near the right edge, always between
 * [minLeft] (on Home, just right of the stat card) and [maxRight] (the right safe inset); the tail is drawn under the
 * head wherever the box ends up (see [bubbleLeft], [bubbleTailX]). The position is read in layout and draw, and the pop
 * grows from the tail, so nothing jumps as it fades out.
 */
@Composable
internal fun SpeechBubble(text: String?, headX: () -> Float, bottomY: () -> Float, minLeft: () -> Float, maxRight: Float) {
    var lastText by remember { mutableStateOf("") }
    if (text != null) lastText = text
    val shown = text != null
    val fade = remember { Animatable(0f) }
    val pop = remember { Animatable(BUBBLE_POP_FROM) }
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown) {
            present = true
            pop.snapTo(BUBBLE_POP_FROM)
            launch { fade.animateTo(1f, tween(120)) }
            pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
        } else if (present) {
            launch { pop.animateTo(BUBBLE_SHRINK_TO, tween(220)) }
            fade.animateTo(0f, tween(220))
            present = false
        }
    }
    if (!present) return

    val density = LocalDensity.current
    val preferredTail = with(density) { (BubbleTailInset + BubbleTailWidth / 2).toPx() }
    val tailEdge = with(density) { (16.dp + BubbleTailWidth / 2).toPx() }
    val maxWidthPx = with(density) { BubbleMaxWidth.toPx() }
    val tailPath = remember { Path() }
    Column(
        Modifier
            .layout { measurable, constraints ->
                // Never wider than the gap it has to fit in (it wraps to a second line instead).
                val room = (maxRight - minLeft()).coerceAtLeast(0f)
                val p = measurable.measure(Constraints(maxWidth = min(maxWidthPx, room).roundToInt()))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val left = bubbleLeft(headX(), p.width.toFloat(), preferredTail, minLeft(), maxRight)
                    p.place(left.roundToInt(), (bottomY() - p.height).roundToInt())
                }
            }
            .graphicsLayer {
                alpha = fade.value
                scaleX = pop.value
                scaleY = pop.value
                // Pop from the tail, so the bubble grows out of the pet's head.
                val w = size.width.coerceAtLeast(1f)
                val left = bubbleLeft(headX(), w, preferredTail, minLeft(), maxRight)
                transformOrigin = TransformOrigin(bubbleTailX(headX(), left, w, tailEdge) / w, 1f)
            }
            .width(IntrinsicSize.Max),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    softGlow(Color.Black, alpha = 0.6f, cornerRadius = 16.dp.toPx(), spread = 14.dp, offsetY = 4.dp.toPx())
                }
                .background(BubbleWhite, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = lastText.uppercase(),
                style = displayStyle(28.sp, color = Ink, tracking = 0.05f, lineHeight = 30.sp),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val left = bubbleLeft(headX(), size.width, preferredTail, minLeft(), maxRight)
            val cx = bubbleTailX(headX(), left, size.width, tailEdge)
            val half = BubbleTailWidth.toPx() / 2f
            tailPath.reset()
            tailPath.moveTo(cx - half, 0f)
            tailPath.lineTo(cx + half, 0f)
            tailPath.lineTo(cx, size.height)
            tailPath.close()
            drawPath(tailPath, BubbleWhite)
        }
        // Room under the tail so it floats just clear of the head.
        Box(Modifier.height(6.dp))
    }
}

private val BubbleWhite = Color(0xFFF4F0EA)
