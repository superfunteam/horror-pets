package team.superfun.horrorpets.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.mood
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.theme.Amber

/**
 * The stable's four rings, outermost first, like activity rings: Fed (100 minus Hunger, so a full ring is a fed
 * pet, as every full ring is good), Fun, Strength and Mood.
 */
enum class StableRing(val color: Color) {
    FED(Color(0xFFFF9F1C)),
    FUN(Color(0xFFB4FF39)),
    STRENGTH(Color(0xFFFF2D55)),
    MOOD(Color(0xFF2FD8FF)),
}

/** [pet]'s ring fills, 0..1, in [StableRing] order. */
fun ringValues(pet: PetState): List<Float> =
    listOf(Tuning.STAT_MAX - pet.hunger, pet.funLevel, pet.strength, mood(pet).toDouble())
        .map { (it / Tuning.STAT_MAX).toFloat().coerceIn(0f, 1f) }

private val Backing = Color(0xE00A0709)
private val HeadDisc = Color(0xFF2A1D21)
private val LockedDisc = Color(0xFF120C10)
private val Silhouette = Color(0xFF3B3140)

/**
 * A killer's [head] in a round medallion ringed by its [values] (see [StableRing] and [ringValues]), each ring
 * filling clockwise from the top and easing to new values. With no [values] the killer is locked: its head is a
 * dark silhouette with a "?" over it and the rings are empty.
 */
@Composable
fun RingMedallion(head: ImageBitmap?, values: List<Float>?, modifier: Modifier = Modifier) {
    val fills = StableRing.entries.indices.map { i ->
        val v by animateFloatAsState(values?.getOrNull(i) ?: 0f, tween(600), label = "ring$i")
        v
    }
    val measurer = rememberTextMeasurer()
    val locked = values == null
    Canvas(modifier) {
        val size = this.size.minDimension
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val ring = size * 0.034f
        val gap = size * 0.012f
        val r0 = size / 2f - size * 0.05f
        drawCircle(Backing, size / 2f - 1.dp.toPx(), c)
        drawCircle(Amber, size / 2f - 1.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        StableRing.entries.forEachIndexed { i, kind ->
            val r = r0 - i * (ring + gap)
            val topLeft = Offset(c.x - r, c.y - r)
            val arc = Size(r * 2f, r * 2f)
            drawCircle(kind.color.copy(alpha = 0.18f), r, c, style = Stroke(ring))
            val sweep = 360f * fills[i]
            if (sweep > 0f) {
                drawArc(kind.color, -90f, sweep, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(ring, cap = StrokeCap.Round))
            }
        }
        val hr = r0 - StableRing.entries.size * (ring + gap) - 1.dp.toPx()
        drawCircle(if (locked) LockedDisc else HeadDisc, hr, c)
        if (head != null) {
            val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, hr)) }
            clipPath(disc) {
                val w = (hr * 2.3f).roundToInt()
                drawImage(
                    head,
                    dstOffset = IntOffset((c.x - hr * 1.15f).roundToInt(), (c.y - hr * 1.1f).roundToInt()),
                    dstSize = IntSize(w, w),
                    colorFilter = if (locked) ColorFilter.tint(Silhouette, BlendMode.SrcIn) else null,
                )
            }
        }
        if (locked) {
            val q = measurer.measure("?", displayStyle((hr * 0.9f / density).sp, color = Color.White.copy(alpha = 0.5f)))
            drawText(q, topLeft = Offset(c.x - q.size.width / 2f, c.y - q.size.height / 2f))
        }
    }
}
