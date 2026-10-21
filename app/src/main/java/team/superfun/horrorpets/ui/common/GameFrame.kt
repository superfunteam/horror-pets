package team.superfun.horrorpets.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import team.superfun.horrorpets.ui.theme.Amber

private val FrameFill = Brush.verticalGradient(listOf(Color(0xF01B1215), Color(0xF0070507)))

/** A lit frame's edge: Amber brightened toward pale gold. */
private val LitEdge = Color(0xFFFFE08A)

/**
 * The game's window frame (Home's status window and its little tags; the fight's command windows draw the same):
 * a dark panel with its [topEnd] and [bottomStart] corners cut off at 45°, a gold edge and, with [inner], a faint
 * second gold line inside it. [lit] (0..1, read while drawing, so a focus fade never recomposes) brightens and
 * thickens the edge and adds a gold halo.
 */
fun Modifier.gameFrame(topEnd: Dp, bottomStart: Dp, inner: Boolean = true, lit: () -> Float = { 0f }): Modifier =
    drawWithCache {
        val stroke = 1.5.dp.toPx()
        val te = topEnd.toPx()
        val bs = bottomStart.toPx()
        val outer = chamfered(size, stroke / 2f, te, bs)
        val innerInset = 4.5.dp.toPx()
        // Inset along both edges of a 45° cut, the chamfer comes in by about (2 - √2) of the inset.
        val innerPath = if (inner) {
            chamfered(size, innerInset, (te - innerInset * 0.6f).coerceAtLeast(0f), (bs - innerInset * 0.6f).coerceAtLeast(0f))
        } else {
            null
        }
        val innerStroke = Stroke(1.dp.toPx())
        onDrawBehind {
            val l = lit().coerceIn(0f, 1f)
            if (l > 0f) softGlow(Amber, alpha = 0.45f * l, cornerRadius = 4.dp.toPx(), spread = 14.dp, layers = 6)
            drawPath(outer, FrameFill)
            drawPath(outer, lerp(Amber, LitEdge, l), style = Stroke(stroke + l * 1.dp.toPx()))
            if (innerPath != null) drawPath(innerPath, Amber.copy(alpha = 0.28f + 0.2f * l), style = innerStroke)
        }
    }

/** A [size] rectangle inset by [inset], its top-right and bottom-left corners cut off [topEnd] and [bottomStart]. */
private fun chamfered(size: Size, inset: Float, topEnd: Float, bottomStart: Float): Path = Path().apply {
    val l = inset
    val t = inset
    val r = size.width - inset
    val b = size.height - inset
    moveTo(l, t)
    lineTo(r - topEnd, t)
    lineTo(r, t + topEnd)
    lineTo(r, b)
    lineTo(l + bottomStart, b)
    lineTo(l, b - bottomStart)
    close()
}
