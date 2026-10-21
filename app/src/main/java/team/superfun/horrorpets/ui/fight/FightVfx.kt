package team.superfun.horrorpets.ui.fight

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow

/** How long a special's effect runs over its target. */
const val SPECIAL_MS = 950

/** How long the shield ring of a block takes to flare out. */
const val SHIELD_MS = 480

/**
 * One special's effect: [pet]'s signature, over a target whose body is centred on ([cx], [cy]) pixels and
 * whose sprite cell is [bodyH] pixels tall. A new instance is a new showing.
 */
@Immutable
internal class SpecialFx(val pet: PetId, val cx: Float, val cy: Float, val bodyH: Float)

private val DreamGreen = Color(0xFF63F25A)
private val DreamPale = Color(0xFFE6FFD9)
private val Steel = Color(0xFFC4C7D0)
private val SteelDark = Color(0xFF1A1A20)

/**
 * The special effects layer, over the fighters. [progress] runs 0..1 over [SPECIAL_MS] and is only read
 * while drawing; at 0 or 1 the layer draws nothing, so it costs nothing between specials.
 * - Jason, Machete Frenzy: four red slashes cut diagonally across the target, one after another.
 * - Freddy, Dream Claws: green dream-warp ripples spread from the target while two rakes of four claw
 *   streaks tear across it.
 * - Pinhead, Chains: eight chains shoot in from the edges of the screen and hook into the target.
 * - Ghostface, Knife Flurry: a quick dark vignette pulse and a flurry of white knife streaks.
 * Everything is lines, circles and ovals placed by [hash01], so a frame allocates nothing.
 */
@Composable
internal fun SpecialVfx(fx: SpecialFx?, progress: () -> Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val unit = 1.dp.toPx()
                val w = size.width
                val h = size.height
                val vignette = Brush.radialGradient(
                    0.25f to Color.Transparent,
                    0.7f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Black.copy(alpha = 0.92f),
                    center = Offset(fx?.cx ?: (w / 2f), fx?.cy ?: (h / 2f)),
                    radius = max(w, h) * 0.75f,
                )
                val dreamGlow = Brush.radialGradient(
                    0f to DreamGreen.copy(alpha = 0.5f),
                    0.45f to DreamGreen.copy(alpha = 0.16f),
                    1f to Color.Transparent,
                    center = Offset(fx?.cx ?: 0f, fx?.cy ?: 0f),
                    radius = (fx?.bodyH ?: h) * 0.85f,
                )
                val rings = Array(3) { Stroke((7f - it * 1.5f) * unit) }
                val linkStroke = Stroke(3.5f * unit)
                val linkShadow = Stroke(7.5f * unit)
                onDrawBehind {
                    val p = progress()
                    if (p <= 0f || p >= 1f) return@onDrawBehind
                    val f = fx ?: return@onDrawBehind
                    when (f.pet) {
                        PetId.JASON -> machete(f, p, unit)
                        PetId.FREDDY -> dreamClaws(f, p, unit, dreamGlow, rings)
                        PetId.PINHEAD -> chains(f, p, unit, linkStroke, linkShadow)
                        PetId.GHOSTFACE -> knives(f, p, unit, vignette)
                    }
                }
            },
    )
}

/**
 * The shield ring of a block: a bright ring that flares out around the blocker and fades, with a faint
 * bubble inside and sparks riding its rim. [progress] runs 0..1 over [SHIELD_MS]; [center] is the body's
 * centre and is only read while the ring is up.
 */
@Composable
internal fun ShieldRing(center: () -> Offset, radius: Float, progress: () -> Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val unit = 1.dp.toPx()
                val glow = Stroke(18f * unit)
                val core = Stroke(4.5f * unit)
                val thin = Stroke(2.5f * unit)
                onDrawBehind {
                    val p = progress()
                    if (p <= 0f || p >= 1f) return@onDrawBehind
                    val c = center()
                    val grow = 1f - (1f - p) * (1f - p)
                    val r = radius * (0.8f + 0.34f * grow)
                    val fade = if (p < 0.12f) p / 0.12f else 1f - (p - 0.12f) / 0.88f
                    drawCircle(ShieldBlue, r, c, alpha = 0.13f * fade)
                    drawCircle(ShieldBlue, r, c, alpha = 0.32f * fade, style = glow)
                    drawCircle(Color.White, r, c, alpha = 0.95f * fade, style = core)
                    drawCircle(ShieldBlue, r * 0.8f, c, alpha = 0.55f * fade * (1f - grow), style = thin)
                    // Sparks: short ticks flying off the rim.
                    for (i in 0 until 10) {
                        val a = (i * 36f + 18f * hash01(i, 5)) * DEG + p * 0.6f
                        val dx = cos(a)
                        val dy = sin(a)
                        val r0 = r * (1.02f + 0.2f * grow * hash01(i, 6))
                        val len = (10f + 14f * hash01(i, 7)) * unit * (1f - grow * 0.6f)
                        drawLine(
                            Color.White,
                            Offset(c.x + dx * r0, c.y + dy * r0),
                            Offset(c.x + dx * (r0 + len), c.y + dy * (r0 + len)),
                            strokeWidth = 3f * unit,
                            cap = StrokeCap.Round,
                            alpha = 0.8f * fade,
                        )
                    }
                }
            },
    )
}

private const val DEG = (PI / 180.0).toFloat()

/** 0..1 within a window that opens at [start] and lasts [length]; negative before, above 1 after. */
private fun window(p: Float, start: Float, length: Float): Float = (p - start) / length

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

/** A blade streak from [a] toward [b], cut to [cut] (0..1) of its length: a wide glow, a body and a hot core, tapered. */
private fun DrawScope.streak(a: Offset, b: Offset, cut: Float, alpha: Float, glow: Color, body: Color, core: Color, width: Float) {
    // A cut that has barely started would only be a dot (the line's round cap).
    if (alpha <= 0f || cut < 0.04f) return
    val tip = Offset(lerp(a.x, b.x, cut), lerp(a.y, b.y, cut))
    // Taper: the wide layers cover the middle of the cut, only the core reaches the ends.
    val m0 = Offset(lerp(a.x, tip.x, 0.12f), lerp(a.y, tip.y, 0.12f))
    val m1 = Offset(lerp(a.x, tip.x, 0.9f), lerp(a.y, tip.y, 0.9f))
    drawLine(glow, m0, m1, strokeWidth = width * 2.6f, cap = StrokeCap.Round, alpha = 0.3f * alpha)
    drawLine(body, m0, m1, strokeWidth = width, cap = StrokeCap.Round, alpha = 0.92f * alpha)
    drawLine(core, a, tip, strokeWidth = width * 0.32f, cap = StrokeCap.Round, alpha = alpha)
}

private fun DrawScope.machete(f: SpecialFx, p: Float, unit: Float) {
    val half = f.bodyH * 0.6f
    for (i in 0 until 4) {
        val life = window(p, i * 0.13f, 0.42f)
        if (life <= 0f || life >= 1f) continue
        val cut = easeOut((life / 0.16f).coerceAtMost(1f))
        val fade = if (life < 0.35f) 1f else 1f - (life - 0.35f) / 0.65f
        // Alternate the diagonal, so the cuts cross into an X.
        val angle = (if (i % 2 == 0) -42f else 222f) * DEG + (hash01(i, 1) - 0.5f) * 0.35f
        val dx = cos(angle) * half
        val dy = -sin(angle) * half
        val cx = f.cx + (hash01(i, 2) - 0.5f) * f.bodyH * 0.22f
        val cy = f.cy + (hash01(i, 3) - 0.5f) * f.bodyH * 0.3f
        // A red wash on the screen with each cut.
        if (life < 0.2f) drawRect(Blood, alpha = 0.08f * (1f - life / 0.2f))
        streak(Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), cut, fade, BloodGlow, Blood, Color.White, 15f * unit)
        // Spatter flung off the cut.
        if (cut >= 1f) {
            for (j in 0 until 7) {
                val t = (life - 0.16f).coerceAtLeast(0f)
                val along = hash01(i * 7 + j, 8)
                val ox = cx - dx + 2f * dx * along
                val oy = cy - dy + 2f * dy * along
                val vx = (hash01(i * 7 + j, 9) - 0.5f) * f.bodyH * 0.9f
                val vy = -(0.2f + 0.5f * hash01(i * 7 + j, 10)) * f.bodyH
                val x = ox + vx * t
                val y = oy + vy * t + f.bodyH * 2.2f * t * t
                drawCircle(Blood, radius = (2.5f + 3.5f * hash01(i * 7 + j, 11)) * unit, center = Offset(x, y), alpha = fade)
            }
        }
    }
}

private fun DrawScope.dreamClaws(f: SpecialFx, p: Float, unit: Float, glow: Brush, rings: Array<Stroke>) {
    val center = Offset(f.cx, f.cy)
    // The dream takes hold: a green breath behind everything, and ripples spreading out of the target.
    val breath = sin(PI.toFloat() * p)
    drawRect(DreamGreen, alpha = 0.07f * breath)
    drawCircle(glow, radius = f.bodyH * 0.85f, center = center, alpha = breath)
    for (k in 0 until 3) {
        val life = window(p, k * 0.14f, 0.62f)
        if (life <= 0f || life >= 1f) continue
        // A wobbling radius makes the ring read as warped dream-space rather than a clean circle.
        val wobble = 1f + 0.04f * sin(life * 18f + k)
        val r = f.bodyH * (0.2f + 0.95f * easeOut(life)) * wobble
        drawCircle(DreamGreen, r, center, alpha = 0.75f * (1f - life), style = rings[k])
    }
    // Two rakes of four blades: down-left, then back across the other way.
    val half = f.bodyH * 0.5f
    val gap = 15f * unit
    for (rake in 0 until 2) {
        val life = window(p, 0.12f + rake * 0.3f, 0.45f)
        if (life <= 0f || life >= 1f) continue
        val cut = easeOut((life / 0.22f).coerceAtMost(1f))
        val fade = if (life < 0.4f) 1f else 1f - (life - 0.4f) / 0.6f
        val angle = (if (rake == 0) -55f else 235f) * DEG
        val dx = cos(angle)
        val dy = -sin(angle)
        // Perpendicular, for spacing the four blades.
        val px = -dy
        val py = dx
        for (b in 0 until 4) {
            val o = (b - 1.5f) * gap
            val a = Offset(f.cx - dx * half + px * o, f.cy - dy * half + py * o)
            val e = Offset(f.cx + dx * half + px * o, f.cy + dy * half + py * o)
            streak(a, e, cut, fade, DreamGreen, Color(0xFF2FBF4A), DreamPale, 7f * unit)
        }
    }
}

/** Where Pinhead's chains come from, as fractions of the screen: around the edges, none from below. */
private val ChainAnchors = floatArrayOf(
    0f, 0.12f,
    0f, 0.5f,
    0f, 0.86f,
    0.2f, 0f,
    0.52f, 0f,
    0.82f, 0f,
    1f, 0.2f,
    1f, 0.62f,
)

private fun DrawScope.chains(f: SpecialFx, p: Float, unit: Float, link: Stroke, shadow: Stroke) {
    val spacing = 22f * unit
    val fade = if (p < 0.78f) 1f else 1f - (p - 0.78f) / 0.22f
    // A dim red light on the target once the hooks are in.
    val bite = window(p, 0.3f, 0.5f)
    if (bite in 0f..1f) drawCircle(Blood, f.bodyH * 0.55f, Offset(f.cx, f.cy), alpha = 0.22f * sin(PI.toFloat() * bite))
    for (i in 0 until ChainAnchors.size / 2) {
        val ax = ChainAnchors[i * 2] * size.width
        val ay = ChainAnchors[i * 2 + 1] * size.height
        val tx = f.cx + (hash01(i, 21) - 0.5f) * f.bodyH * 0.35f
        val ty = f.cy + (hash01(i, 22) - 0.5f) * f.bodyH * 0.5f
        val reach = easeOut(window(p, i * 0.025f, 0.24f).coerceIn(0f, 1f))
        if (reach <= 0f) continue
        val dist = hypot(tx - ax, ty - ay)
        val ux = (tx - ax) / dist
        val uy = (ty - ay) / dist
        val head = dist * reach
        val angle = atan2(uy, ux) / DEG
        // Taut chains rattle a little once they have hooked in.
        val rattle = if (reach >= 1f) sin(p * 90f + i * 1.7f) * 2.2f * unit else 0f
        var d = spacing * 0.5f
        var k = 0
        while (d < head) {
            val x = ax + ux * d - uy * rattle
            val y = ay + uy * d + ux * rattle
            if (k % 2 == 0) {
                // A link seen face-on: an oval along the chain.
                val lw = spacing * 1.35f
                val lh = spacing * 0.66f
                rotate(angle, pivot = Offset(x, y)) {
                    drawOval(SteelDark, Offset(x - lw / 2f, y - lh / 2f), Size(lw, lh), alpha = 0.85f * fade, style = shadow)
                    drawOval(Steel, Offset(x - lw / 2f, y - lh / 2f), Size(lw, lh), alpha = fade, style = link)
                }
            } else {
                // A link seen edge-on: a short bar.
                val hx = ux * spacing * 0.55f
                val hy = uy * spacing * 0.55f
                drawLine(SteelDark, Offset(x - hx, y - hy), Offset(x + hx, y + hy), strokeWidth = 8f * unit, cap = StrokeCap.Round, alpha = 0.85f * fade)
                drawLine(Steel, Offset(x - hx, y - hy), Offset(x + hx, y + hy), strokeWidth = 4f * unit, cap = StrokeCap.Round, alpha = fade)
            }
            d += spacing
            k++
        }
        // The hook: a bright barb where the chain bites, flashing as it lands.
        val hx = ax + ux * head
        val hy = ay + uy * head
        drawCircle(Steel, 5f * unit, Offset(hx, hy), alpha = fade)
        if (reach >= 1f) {
            val flash = (1f - window(p, 0.24f + i * 0.025f, 0.2f)).coerceIn(0f, 1f)
            if (flash > 0f) drawCircle(BloodGlow, 16f * unit, Offset(hx, hy), alpha = 0.7f * flash)
        }
    }
}

private val KnifeGlow = Color(0xFFCFE3FF)
private val KnifeSteel = Color(0xFFE9EEF5)

private fun DrawScope.knives(f: SpecialFx, p: Float, unit: Float, vignette: Brush) {
    // A quick dark pulse closes in around the target, then lets go.
    val pulse = window(p, 0f, 0.6f)
    if (pulse in 0f..1f) drawRect(vignette, alpha = sin(PI.toFloat() * pulse))
    for (i in 0 until 18) {
        val life = window(p, i * 0.04f, 0.16f)
        if (life <= 0f || life >= 1f) continue
        val angle = hash01(i, 31) * PI.toFloat()
        val half = f.bodyH * (0.3f + 0.25f * hash01(i, 32))
        val cx = f.cx + (hash01(i, 33) - 0.5f) * f.bodyH * 0.4f
        val cy = f.cy + (hash01(i, 34) - 0.5f) * f.bodyH * 0.55f
        val dx = cos(angle) * half
        val dy = sin(angle) * half
        val cut = easeOut((life / 0.3f).coerceAtMost(1f))
        val fade = 1f - life
        streak(Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), cut, fade, KnifeGlow, KnifeSteel, Color.White, 10f * unit)
        // A glint where the blade is, and a fleck of blood once it is through.
        val tip = Offset(cx - dx + 2f * dx * cut, cy - dy + 2f * dy * cut)
        if (cut < 1f) {
            val g = 9f * unit
            drawLine(Color.White, Offset(tip.x - g, tip.y), Offset(tip.x + g, tip.y), strokeWidth = 2.5f * unit, cap = StrokeCap.Round, alpha = fade)
            drawLine(Color.White, Offset(tip.x, tip.y - g), Offset(tip.x, tip.y + g), strokeWidth = 2.5f * unit, cap = StrokeCap.Round, alpha = fade)
        } else {
            drawCircle(Blood, radius = (2.5f + 2.5f * hash01(i, 35)) * unit, center = Offset(cx + dx * 0.2f, cy + dy * 0.2f + life * f.bodyH * 0.1f), alpha = fade)
        }
    }
}
