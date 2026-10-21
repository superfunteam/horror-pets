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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import team.superfun.horrorpets.model.SpecialStyle
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow

/** How long a special's effect runs over its target. */
const val SPECIAL_MS = 950

/**
 * The Shape's slash, as fractions of [SPECIAL_MS]: it starts at [SHAPE_SLASH_START] (once the dark has closed
 * in), lasts [SHAPE_SLASH_LIFE], and has cut all the way through after [SHAPE_SLASH_CUT] of that.
 */
internal const val SHAPE_SLASH_START = 0.5f
internal const val SHAPE_SLASH_LIFE = 0.34f
internal const val SHAPE_SLASH_CUT = 0.24f

/**
 * How far into its effect a special's blow lands: its sound, damage number, hit flash and HP drain wait this
 * long after the impact. The Shape's lands as its slash cuts through (about 550 ms in); every other special's
 * effect is the blow itself and lands at the impact (0).
 */
internal fun specialLandsAtMs(style: SpecialStyle): Long = when (style) {
    SpecialStyle.THE_SHAPE -> ((SHAPE_SLASH_START + SHAPE_SLASH_LIFE * SHAPE_SLASH_CUT) * SPECIAL_MS).toLong()
    else -> 0L
}

/**
 * When a special's sound starts, after the impact, for a blow that lands [landMs] in: a crit plays the crit
 * hit (and the announcer) with the blow itself; otherwise the special's own clip, whose hit is [hitMs] into
 * it, starts that much earlier so the hit lands with the blow, but never before the impact. For every
 * special that lands at the impact this is 0, as it always was.
 */
internal fun specialSoundAtMs(landMs: Long, hitMs: Long, crit: Boolean): Long =
    if (crit) landMs else (landMs - hitMs).coerceAtLeast(0L)

/** How long the shield ring of a block takes to flare out. */
const val SHIELD_MS = 480

/**
 * One special's effect: the attacker's signature [style], over a target whose body is centred on ([cx], [cy])
 * pixels and whose sprite cell is drawn [bodyH] pixels tall (its display scale included, so a half-height
 * Chucky gets an effect his size). A new instance is a new showing.
 */
@Immutable
internal class SpecialFx(val style: SpecialStyle, val cx: Float, val cy: Float, val bodyH: Float)

private val DreamGreen = Color(0xFF63F25A)
private val DreamPale = Color(0xFFE6FFD9)
private val Steel = Color(0xFFC4C7D0)
private val SteelDark = Color(0xFF1A1A20)
private val VoodooPurple = Color(0xFFB24DFF)
private val VoodooGreen = Color(0xFF59FF8C)
private val VoodooPale = Color(0xFFF1E6FF)

/**
 * The special effects layer, over the fighters. [progress] runs 0..1 over [SPECIAL_MS] and is only read
 * while drawing; at 0 or 1 the layer draws nothing, so it costs nothing between specials.
 * - Jason, Machete Frenzy: four red slashes cut diagonally across the target, one after another.
 * - Freddy, Dream Claws: green dream-warp ripples spread from the target while two rakes of four claw
 *   streaks tear across it.
 * - Pinhead, Chains: eight chains shoot in from the edges of the screen and hook into the target.
 * - Ghostface, Knife Flurry: a quick dark vignette pulse and a flurry of white knife streaks.
 * - Chucky, Voodoo Stab: a swirling purple-green voodoo glyph ring takes hold of the target, and three
 *   quick white stab streaks drive into it, each one flaring the glyph.
 * - Michael, The Shape: the dark closes in slowly around the target, then one big overhead diagonal
 *   slash cuts down across it.
 * - Leatherface, Saw Dance: three white-hot chainsaw blades whirl around the target on two tilted orbits,
 *   trailing orange arcs (dimmer where they pass behind it) and biting as they sweep across its front,
 *   while a shower of orange-yellow sparks flies off the blades and falls.
 * Everything is lines, arcs, circles and ovals placed by [hash01], with brushes and strokes built once
 * per showing, so a frame allocates nothing.
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
                val voodoo = VoodooKit(unit, fx)
                val shape = ShapeKit(w, h, fx)
                val saw = SawKit(fx)
                onDrawBehind {
                    val p = progress()
                    if (p <= 0f || p >= 1f) return@onDrawBehind
                    val f = fx ?: return@onDrawBehind
                    when (f.style) {
                        SpecialStyle.MACHETE_FRENZY -> machete(f, p, unit)
                        SpecialStyle.DREAM_CLAWS -> dreamClaws(f, p, unit, dreamGlow, rings)
                        SpecialStyle.CHAINS -> chains(f, p, unit, linkStroke, linkShadow)
                        SpecialStyle.KNIFE_FLURRY -> knives(f, p, unit, vignette)
                        SpecialStyle.VOODOO_STAB -> voodooStab(f, p, unit, voodoo)
                        SpecialStyle.THE_SHAPE -> theShape(f, p, unit, shape)
                        SpecialStyle.SAW_DANCE -> sawDance(f, p, unit, saw)
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

// ---- Chucky: Voodoo Stab ----

/** Voodoo Stab's strokes and glow, built once per showing. */
private class VoodooKit(unit: Float, fx: SpecialFx?) {
    val band = Stroke(6f * unit, cap = StrokeCap.Round)
    val bandGlow = Stroke(16f * unit, cap = StrokeCap.Round)
    val inner = Stroke(3.5f * unit)
    val glow: Brush = Brush.radialGradient(
        0f to VoodooPurple.copy(alpha = 0.42f),
        0.5f to VoodooGreen.copy(alpha = 0.12f),
        1f to Color.Transparent,
        center = Offset(fx?.cx ?: 0f, fx?.cy ?: 0f),
        radius = (fx?.bodyH ?: 1f) * 0.8f,
    )
}

/** Where the three stabs come from (degrees, 0 = right, counter-clockwise) and when each lands (0..1). */
private val StabAngles = floatArrayOf(28f, 205f, 118f)
private val StabStarts = floatArrayOf(0.22f, 0.4f, 0.58f)
private const val STAB_LIFE = 0.15f

private fun DrawScope.voodooStab(f: SpecialFx, p: Float, unit: Float, kit: VoodooKit) {
    val center = Offset(f.cx, f.cy)
    // The glyph takes hold (pops in), swirls, and lets go at the end.
    val appear = easeOut((p / 0.16f).coerceAtMost(1f))
    val fade = if (p < 0.78f) 1f else 1f - (p - 0.78f) / 0.22f
    val a = appear * fade
    // Each stab flares the glyph for a moment.
    var flare = 0f
    for (i in StabStarts.indices) {
        val life = window(p, StabStarts[i], STAB_LIFE)
        if (life in 0f..1f) flare = max(flare, 1f - life)
    }
    val r = f.bodyH * 0.42f * lerp(0.6f, 1f, appear)
    drawRect(VoodooPurple, alpha = 0.07f * a)
    drawCircle(kit.glow, radius = f.bodyH * 0.8f, center = center, alpha = a * (0.7f + 0.3f * flare))

    // The outer band: three arcs swirling clockwise, purple over a soft glow.
    val spin = p * 260f
    val arcTopLeft = Offset(center.x - r, center.y - r)
    val arcSize = Size(r * 2f, r * 2f)
    for (k in 0 until 3) {
        val start = spin + k * 120f
        drawArc(VoodooPurple, start, 84f, false, arcTopLeft, arcSize, alpha = 0.35f * a, style = kit.bandGlow)
        drawArc(VoodooPurple, start, 84f, false, arcTopLeft, arcSize, alpha = a, style = kit.band)
    }
    // The inner ring, green, breathing.
    val ri = r * (0.7f + 0.04f * sin(p * 30f))
    drawCircle(VoodooGreen, ri, center, alpha = 0.85f * a, style = kit.inner)
    // Runes between the rings, swirling the other way: a short bar and a stitch across it.
    for (k in 0 until 8) {
        val ang = (-spin * 0.6f + k * 45f) * DEG
        val c = cos(ang)
        val s = sin(ang)
        val mid = (r + ri) / 2f
        val half = (r - ri) * 0.42f
        val x = center.x + c * mid
        val y = center.y + s * mid
        val color = if (k % 2 == 0) VoodooGreen else VoodooPale
        drawLine(color, Offset(x - c * half, y - s * half), Offset(x + c * half, y + s * half), strokeWidth = 3f * unit, cap = StrokeCap.Round, alpha = a)
        drawLine(color, Offset(x + s * half * 0.7f, y - c * half * 0.7f), Offset(x - s * half * 0.7f, y + c * half * 0.7f), strokeWidth = 2.5f * unit, cap = StrokeCap.Round, alpha = 0.8f * a)
    }
    // The heart of the glyph: a stitched X with a pin-head in the middle.
    val g = ri * 0.55f
    for (d in 0 until 2) {
        val sx = if (d == 0) 1f else -1f
        drawLine(VoodooPale, Offset(center.x - g * sx, center.y - g), Offset(center.x + g * sx, center.y + g), strokeWidth = 4f * unit, cap = StrokeCap.Round, alpha = a * (0.6f + 0.4f * flare))
        for (t in 1..3) {
            // Stitches across each arm of the X.
            val along = t / 4f * 2f - 1f
            val px = center.x + g * sx * along
            val py = center.y + g * along
            val st = 7f * unit
            drawLine(VoodooGreen, Offset(px - st * 0.7f, py + st * 0.7f * sx), Offset(px + st * 0.7f, py - st * 0.7f * sx), strokeWidth = 2.5f * unit, cap = StrokeCap.Round, alpha = 0.8f * a)
        }
    }
    drawCircle(VoodooGreen, 6f * unit, center, alpha = a)
    drawCircle(Color.White, 3f * unit, center, alpha = a)

    // Three quick stabs driving in from outside the ring to the heart.
    for (i in StabStarts.indices) {
        val life = window(p, StabStarts[i], STAB_LIFE)
        if (life <= 0f || life >= 1f) continue
        val ang = StabAngles[i] * DEG
        val dx = cos(ang)
        val dy = -sin(ang)
        val from = Offset(center.x + dx * f.bodyH * 0.78f, center.y + dy * f.bodyH * 0.78f)
        val to = Offset(center.x + dx * f.bodyH * 0.04f, center.y + dy * f.bodyH * 0.04f)
        val cut = easeOut((life / 0.35f).coerceAtMost(1f))
        val stabFade = if (life < 0.5f) 1f else 1f - (life - 0.5f) / 0.5f
        streak(from, to, cut, stabFade, KnifeGlow, KnifeSteel, Color.White, 11f * unit)
        if (cut >= 1f) drawCircle(Color.White, (10f + 14f * (1f - stabFade)) * unit, to, alpha = 0.7f * stabFade)
    }
}

// ---- Michael: The Shape ----

/** The Shape's closing dark, built once per showing: a vignette around the target. */
private class ShapeKit(w: Float, h: Float, fx: SpecialFx?) {
    val reach = max(w, h)
    val dark: Brush = Brush.radialGradient(
        0f to Color.Transparent,
        0.32f to Color.Transparent,
        0.7f to Color.Black.copy(alpha = 0.7f),
        1f to Color.Black.copy(alpha = 0.95f),
        center = Offset(fx?.cx ?: (w / 2f), fx?.cy ?: (h / 2f)),
        radius = reach * 0.62f,
    )
}

private val ShapeSteel = Color(0xFFF2F4F8)
private val ShapeGlow = Color(0xFFFF3B45)

private fun DrawScope.theShape(f: SpecialFx, p: Float, unit: Float, kit: ShapeKit) {
    // The dark creeps in slowly (the vignette shrinks toward the target), holds, then lets go.
    val creep = easeOut((p / 0.62f).coerceAtMost(1f))
    val pull = lerp(1.7f, 0.62f, creep)
    val darkAlpha = (p / 0.12f).coerceAtMost(1f) * (if (p < 0.82f) 1f else 1f - (p - 0.82f) / 0.18f)
    if (darkAlpha > 0f) {
        // Drawn scaled toward the target; the rect is made large enough to still cover the screen.
        val half = kit.reach * 1.6f / pull
        scale(pull, pivot = Offset(f.cx, f.cy)) {
            drawRect(kit.dark, topLeft = Offset(f.cx - half, f.cy - half), size = Size(half * 2f, half * 2f), alpha = darkAlpha)
        }
    }
    // One big overhead slash, from high above the head down across the body.
    val life = window(p, SHAPE_SLASH_START, SHAPE_SLASH_LIFE)
    if (life <= 0f || life >= 1f) return
    val cut = easeOut((life / SHAPE_SLASH_CUT).coerceAtMost(1f))
    val fade = if (life < 0.45f) 1f else 1f - (life - 0.45f) / 0.55f
    val from = Offset(f.cx + f.bodyH * 0.72f, f.cy - f.bodyH * 1.3f)
    val to = Offset(f.cx - f.bodyH * 0.62f, f.cy + f.bodyH * 0.8f)
    if (life < 0.18f) drawRect(Color.White, alpha = 0.14f * (1f - life / 0.18f))
    // A faint after-image trailing the blade, then the blade itself: long and keen rather than wide.
    val lag = Offset(-f.bodyH * 0.06f, -f.bodyH * 0.03f)
    streak(from + lag, to + lag, cut * 0.92f, fade * 0.35f, ShapeGlow, Blood, ShapeSteel, 9f * unit)
    streak(from, to, cut, fade, ShapeGlow, ShapeSteel, Color.White, 15f * unit)
}

// ---- Leatherface: Saw Dance ----

private val SawHot = Color(0xFFFFF6DC)
private val SawYellow = Color(0xFFFFD23F)
private val SawOrange = Color(0xFFFF8A1F)
private val SawEmber = Color(0xFFD9381E)

/** Points along each blade's trail, from the white-hot leading edge back to the ember tail. */
private const val SAW_TRAIL_STEPS = 14

/** How far round its orbit a blade's trail reaches behind it, in radians (about 150 degrees). */
private const val SAW_TRAIL_RAD = 2.6f

/** How many times the blades go round over the effect. */
private const val SAW_TURNS = 2.3f

private const val SAW_SPARKS = 90

/**
 * The blades' orbits, flattened ellipses round the target's body: centre height and radii as fractions of the
 * body height, tilt in degrees, spin direction (1 runs left to right across the front), and blades on it.
 */
private val SawOrbitY = floatArrayOf(-0.06f, 0.17f)
private val SawOrbitRx = floatArrayOf(0.44f, 0.36f)
private val SawOrbitRy = floatArrayOf(0.13f, 0.1f)
private val SawOrbitTilt = floatArrayOf(-14f, 9f)
private val SawOrbitDir = floatArrayOf(1f, -1f)
private val SawOrbitBlades = intArrayOf(2, 1)

/** Each blade as (orbit, starting angle in radians). Three blades: two opposite on the upper orbit, one below. */
private val SawBladeOrbit = intArrayOf(0, 0, 1)
private val SawBladeStart = floatArrayOf(0.4f, 0.4f + PI.toFloat(), 2.1f)

/** Saw Dance's warm glow and its trail colours, built once per showing. */
private class SawKit(fx: SpecialFx?) {
    val glow: Brush = Brush.radialGradient(
        0f to SawOrange.copy(alpha = 0.36f),
        0.5f to SawYellow.copy(alpha = 0.1f),
        1f to Color.Transparent,
        center = Offset(fx?.cx ?: 0f, fx?.cy ?: 0f),
        radius = (fx?.bodyH ?: 1f) * 0.8f,
    )

    /** The trail's colour at each step: white-hot at the blade, through yellow and orange to an ember. */
    val trail: List<Color> = List(SAW_TRAIL_STEPS) { j ->
        val t = j / (SAW_TRAIL_STEPS - 1f)
        when {
            t < 0.2f -> lerpColor(SawHot, SawYellow, t / 0.2f)
            t < 0.6f -> lerpColor(SawYellow, SawOrange, (t - 0.2f) / 0.4f)
            else -> lerpColor(SawOrange, SawEmber, (t - 0.6f) / 0.4f)
        }
    }

    /** A spark's colour through its life, in four steps: it cools from white-yellow to a red-orange. */
    val spark: List<Color> = listOf(SawHot, SawYellow, SawOrange, SawEmber)
}

/** The angle (radians) of [blade]'s leading edge at [p]. */
private fun sawAngle(blade: Int, p: Float): Float {
    val orbit = SawBladeOrbit[blade]
    return SawBladeStart[blade] + SawOrbitDir[orbit] * 2f * PI.toFloat() * SAW_TURNS * p
}

/** The x of the point at angle [theta] on [orbit] round a body centred on ([cx], [cy]) and [bodyH] tall. */
private fun sawX(orbit: Int, theta: Float, cx: Float, bodyH: Float): Float {
    val tilt = SawOrbitTilt[orbit] * DEG
    return cx + SawOrbitRx[orbit] * bodyH * cos(theta) * cos(tilt) - SawOrbitRy[orbit] * bodyH * sin(theta) * sin(tilt)
}

/** The y of the point at angle [theta] on [orbit]; see [sawX]. */
private fun sawY(orbit: Int, theta: Float, cy: Float, bodyH: Float): Float {
    val tilt = SawOrbitTilt[orbit] * DEG
    return cy + SawOrbitY[orbit] * bodyH + SawOrbitRx[orbit] * bodyH * cos(theta) * sin(tilt) +
        SawOrbitRy[orbit] * bodyH * sin(theta) * cos(tilt)
}

private fun DrawScope.sawDance(f: SpecialFx, p: Float, unit: Float, kit: SawKit) {
    // The saw revs up (the blades sweep in) and lets go at the end.
    val appear = easeOut((p / 0.12f).coerceAtMost(1f))
    val fade = if (p < 0.8f) 1f else 1f - (p - 0.8f) / 0.2f
    val a = appear * fade
    // The engine's flicker in the glow.
    val flicker = 0.75f + 0.25f * sin(p * 90f)
    drawRect(SawOrange, alpha = 0.06f * a)
    drawCircle(kit.glow, radius = f.bodyH * 0.8f, center = Offset(f.cx, f.cy), alpha = a * flicker)

    // The blades: each a tapered arc trailing round its orbit, dimmer and thinner where it passes behind
    // the body (the back half of the orbit), with a glow under its hot leading half. Butt caps, so the
    // short segments meet without overlapping (a round cap would double the alpha at every joint).
    for (b in SawBladeOrbit.indices) {
        val orbit = SawBladeOrbit[b]
        val dir = SawOrbitDir[orbit]
        val lead = sawAngle(b, p)
        // A sweep that has barely started is a short trail: it grows to full length as the saw revs up.
        val reach = SAW_TRAIL_RAD * appear
        var px = sawX(orbit, lead, f.cx, f.bodyH)
        var py = sawY(orbit, lead, f.cy, f.bodyH)
        for (j in 1 until SAW_TRAIL_STEPS) {
            val t = j / (SAW_TRAIL_STEPS - 1f)
            val theta = lead - dir * reach * t
            val x = sawX(orbit, theta, f.cx, f.bodyH)
            val y = sawY(orbit, theta, f.cy, f.bodyH)
            val mid = theta + dir * reach * 0.5f / (SAW_TRAIL_STEPS - 1f)
            val front = sin(mid) > 0f
            val depth = if (front) 1f else 0.4f
            val taper = (1f - t)
            val width = (2f + 10f * taper * taper * if (front) 1f else 0.6f) * unit
            val alpha = a * depth * taper.coerceAtLeast(0.15f)
            if (t <= 0.45f) {
                drawLine(SawOrange, Offset(px, py), Offset(x, y), strokeWidth = width * 2.4f, cap = StrokeCap.Butt, alpha = 0.22f * alpha)
            }
            drawLine(kit.trail[j], Offset(px, py), Offset(x, y), strokeWidth = width, cap = StrokeCap.Butt, alpha = alpha)
            px = x
            py = y
        }
        // The blade's edge: a hot glint, and a bite where it sweeps across the front of the body.
        val tipX = sawX(orbit, lead, f.cx, f.bodyH)
        val tipY = sawY(orbit, lead, f.cy, f.bodyH)
        val front = sin(lead)
        val tipAlpha = a * if (front > 0f) 1f else 0.4f
        drawCircle(SawYellow, 9f * unit, Offset(tipX, tipY), alpha = 0.35f * tipAlpha)
        drawCircle(SawHot, 4.5f * unit, Offset(tipX, tipY), alpha = tipAlpha)
        val bite = front.coerceAtLeast(0f).let { it * it * it * it * it * it }
        if (bite > 0.05f) {
            drawCircle(SawYellow, (12f + 18f * bite) * unit, Offset(tipX, tipY), alpha = 0.3f * bite * a)
            drawCircle(SawHot, (5f + 7f * bite) * unit, Offset(tipX, tipY), alpha = 0.85f * bite * a)
        }
    }

    // The shower: sparks thrown off the blades' edges, flying out and up, then falling.
    for (i in 0 until SAW_SPARKS) {
        val born = 0.04f + 0.76f * hash01(i, 51)
        val life = 0.12f + 0.12f * hash01(i, 52)
        val age = (p - born) / life
        if (age <= 0f || age >= 1f) continue
        val b = i % SawBladeOrbit.size
        val orbit = SawBladeOrbit[b]
        val theta = sawAngle(b, born)
        val ox = sawX(orbit, theta, f.cx, f.bodyH)
        val oy = sawY(orbit, theta, f.cy, f.bodyH)
        // Flung along the blade's way round (its tangent) and outward, with a kick upward.
        val tangent = -sin(theta) * SawOrbitDir[orbit]
        val vx = ((hash01(i, 53) - 0.5f) * 0.9f + tangent * 0.7f) * f.bodyH
        val vy = -(0.3f + 0.6f * hash01(i, 54)) * f.bodyH
        val fall = f.bodyH * (0.7f + 0.5f * hash01(i, 55))
        val tail = (age - 0.1f).coerceAtLeast(0f)
        val hx = ox + vx * age
        val hy = oy + vy * age + fall * age * age
        val tx = ox + vx * tail
        val ty = oy + vy * tail + fall * tail * tail
        val color = kit.spark[(age * kit.spark.size).toInt().coerceAtMost(kit.spark.size - 1)]
        val alpha = (1f - age) * fade
        drawLine(color, Offset(tx, ty), Offset(hx, hy), strokeWidth = (2.5f + 2f * (1f - age)) * unit, cap = StrokeCap.Round, alpha = alpha)
        if (age < 0.4f) drawCircle(SawHot, 3f * unit, Offset(hx, hy), alpha = alpha)
    }
}
