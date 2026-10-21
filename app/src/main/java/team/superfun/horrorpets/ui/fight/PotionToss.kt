package team.superfun.horrorpets.ui.fight

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** How high the potion's arc rises above the straight line from the hand to the head, in potion heights. */
private const val ARC = 2.4f

/** The poof's puffs and sparkles. */
private const val PUFFS = 9
private const val SPARKLES = 14

private val PuffGreen = Color(0xFFB6FF8A)

/**
 * A potion on its way to a fighter: [flight] runs 0..1 as it arcs [from] the screen's edge [to] the fighter's head,
 * turning [spin] (+1 clockwise, -1 the other way); then [poof] runs 0..1 as it bursts. Read while drawing only.
 */
@Stable
internal class PotionToss(val from: Offset, val to: Offset, val spin: Float) {
    val flight = Animatable(0f)
    val poof = Animatable(0f)

    /** The burst's puffs and sparkles, scattered once per toss. */
    val scatter: List<Pair<Float, Float>> = Random(from.hashCode() xor to.hashCode()).let { r ->
        List(PUFFS + SPARKLES) { (r.nextFloat() * 2f * PI.toFloat()) to (0.6f + r.nextFloat() * 0.6f) }
    }
}

/**
 * Draws [toss]: in flight, [image] [sizePx] tall along a parabola with a turning spin; once it lands, a green poof
 * of soft puffs swelling out from the head, a ring of light and sparkles flung outward, all fading as [PotionToss.poof]
 * finishes. Nothing while [toss] is null.
 */
@Composable
internal fun PotionLayer(toss: PotionToss?, image: ImageBitmap, sizePx: Float) {
    if (toss == null) return
    Canvas(Modifier.fillMaxSize()) {
        val f = toss.flight.value
        val p = toss.poof.value
        if (p == 0f) drawFlying(toss, image, sizePx, f) else drawPoof(toss, sizePx, p)
    }
}

private fun DrawScope.drawFlying(toss: PotionToss, image: ImageBitmap, sizePx: Float, f: Float) {
    val x = toss.from.x + (toss.to.x - toss.from.x) * f
    val y = toss.from.y + (toss.to.y - toss.from.y) * f - ARC * sizePx * 4f * f * (1f - f)
    val k = sizePx / max(image.width, image.height)
    withTransform({
        translate(x, y)
        rotate(toss.spin * 540f * f, pivot = Offset.Zero)
        scale(k, k, pivot = Offset.Zero)
        translate(-image.width / 2f, -image.height / 2f)
    }) {
        drawImage(image)
    }
}

private fun DrawScope.drawPoof(toss: PotionToss, sizePx: Float, p: Float) {
    val c = toss.to
    val fade = 1f - p
    val out = 1f - (1f - p) * (1f - p) // ease out: bursts fast, drifts
    // A soft glow, a ring of light and the puffs, swelling as they fade.
    drawCircle(
        Brush.radialGradient(listOf(PotionGreen.copy(alpha = 0.55f * fade), Color.Transparent), center = c, radius = sizePx * (0.8f + 1.4f * out)),
        radius = sizePx * (0.8f + 1.4f * out),
        center = c,
    )
    drawCircle(PuffGreen.copy(alpha = 0.8f * fade), radius = sizePx * (0.3f + 1.2f * out), center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(sizePx * 0.06f * fade))
    for (i in 0 until PUFFS) {
        val (a, d) = toss.scatter[i]
        val r = sizePx * (0.18f + 0.25f * out) * d
        val pc = Offset(c.x + cos(a) * sizePx * 0.7f * out * d, c.y + sin(a) * sizePx * 0.55f * out * d - sizePx * 0.2f * out)
        drawCircle(PuffGreen.copy(alpha = 0.5f * fade), r, pc)
        drawCircle(Color.White.copy(alpha = 0.35f * fade), r * 0.5f, pc + Offset(-r * 0.25f, -r * 0.25f))
    }
    for (i in PUFFS until PUFFS + SPARKLES) {
        val (a, d) = toss.scatter[i]
        val dist = sizePx * 1.4f * out * d
        val sc = Offset(c.x + cos(a) * dist, c.y + sin(a) * dist * 0.8f - sizePx * 0.3f * out)
        drawSparkle(sc, sizePx * 0.09f * (1f - 0.5f * p), if (i % 2 == 0) Color.White else PotionGreen, fade)
    }
}

/** A four-pointed sparkle centred on [c], [r] from its middle to a tip. */
private fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color, alpha: Float) {
    val w = r * 0.28f
    val star = Path().apply {
        moveTo(c.x, c.y - r)
        lineTo(c.x + w, c.y - w)
        lineTo(c.x + r, c.y)
        lineTo(c.x + w, c.y + w)
        lineTo(c.x, c.y + r)
        lineTo(c.x - w, c.y + w)
        lineTo(c.x - r, c.y)
        lineTo(c.x - w, c.y - w)
        close()
    }
    drawPath(star, color.copy(alpha = alpha))
}
