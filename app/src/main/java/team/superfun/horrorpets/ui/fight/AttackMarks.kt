package team.superfun.horrorpets.ui.fight

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.ui.common.hash01

/**
 * The marks a learned attack leaves on its target, in its killer's style, scaled by its [slot] and [tier] (see
 * [markPlan]): Jason's machete arcs, Freddy's claw rakes, Pinhead's hooked chains, Ghostface's knife slashes,
 * Chucky's stabs and voodoo pins, Michael's long diagonal cut, Leatherface's jagged saw tears, Jaws's bites. A
 * target's body is centred on ([cx], [cy]) and drawn [bodyH] tall. [seed] varies where the marks fall; a new
 * instance is a new showing.
 */
@Immutable
internal class MarkFx(val pet: PetId, val slot: Slot, val tier: Int, val cx: Float, val cy: Float, val bodyH: Float, val seed: Int = 0)

/**
 * How a slot and tier's marks look: [count] marks, each [length] (a share of the body's height) long and [width]
 * dp thick, an impact [ring], [sparks] flying off, a [flash] of the killer's color, and how long it all lasts.
 */
internal data class MarkPlan(
    val count: Int,
    val length: Float,
    val width: Float,
    val ring: Boolean,
    val sparks: Int,
    val flash: Boolean,
    val ms: Int,
)

/**
 * What [slot]'s tier [tier] draws: nothing at tier 0 for a STRIKE or HEAVY (as the game always was); then one to
 * three light marks, one or two big marks with an impact ring (an X at tier 3) for a HEAVY, and for a special,
 * marks on top of its own effect, more each tier, with sparks from tier 2. Higher tiers linger longer.
 */
internal fun markPlan(slot: Slot, tier: Int): MarkPlan? = when (slot) {
    Slot.LIGHT -> if (tier == 0) null else MarkPlan(count = tier, length = 0.45f, width = 4f, ring = false, sparks = if (tier == 3) 14 else 0, flash = false, ms = 520 + 140 * tier)
    Slot.HEAVY -> if (tier == 0) null else MarkPlan(count = if (tier == 3) 3 else tier, length = 0.72f, width = 7f, ring = true, sparks = if (tier >= 2) 10 + 8 * tier else 0, flash = tier == 3, ms = 600 + 160 * tier)
    Slot.SPECIAL -> if (tier == 0) null else MarkPlan(count = 1 + tier, length = 0.85f, width = 6f, ring = tier >= 2, sparks = if (tier >= 2) 16 * tier else 0, flash = tier >= 2, ms = 800 + 200 * tier)
}

/** A killer's mark colors: its outer [glow], its [body] and a bright [core]. */
private class MarkInk(val glow: Color, val body: Color, val core: Color)

private fun inkOf(pet: PetId): MarkInk = when (pet) {
    PetId.JASON -> MarkInk(Color(0xFFFF2D3A), Color(0xFFC1121F), Color(0xFFFFD6D9))
    PetId.FREDDY -> MarkInk(Color(0xFF63F25A), Color(0xFF2E9E2A), Color(0xFFE6FFD9))
    PetId.PINHEAD -> MarkInk(Color(0xFF7FB2FF), Color(0xFF8A93A6), Color(0xFFE9EEF8))
    PetId.GHOSTFACE -> MarkInk(Color(0xFF9B9BA8), Color(0xFFDADAE2), Color(0xFFFFFFFF))
    PetId.CHUCKY -> MarkInk(Color(0xFFB24DFF), Color(0xFF59FF8C), Color(0xFFF1E6FF))
    PetId.MICHAEL -> MarkInk(Color(0xFFFFB3A0), Color(0xFFF2F2F2), Color(0xFFFFFFFF))
    PetId.LEATHERFACE -> MarkInk(Color(0xFFFF8A1F), Color(0xFFE0262F), Color(0xFFFFE08A))
    PetId.JAWS -> MarkInk(Color(0xFF3FB8FF), Color(0xFFD7263D), Color(0xFFFFFFFF))
}

/** The marks layer: [fx]'s marks at [progress] (0..1 over its plan's length). Draws nothing without one. */
@Composable
internal fun AttackMarks(fx: MarkFx?, progress: () -> Float, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxSize()
            .drawWithCache {
                onDrawWithContent {
                    val f = fx ?: return@onDrawWithContent
                    val p = progress()
                    if (p <= 0f || p >= 1f) return@onDrawWithContent
                    drawMarks(f, p)
                }
            },
    )
}

/** A mark's own timeline inside the whole showing: when it starts cutting, as a share of [p]'s 0..1. */
private fun markStart(i: Int, count: Int): Float = if (count <= 1) 0f else 0.32f * i / (count - 1)

/** Draws [f]'s marks at [p] (0..1): each cuts in fast, holds, then fades over the last third. */
internal fun DrawScope.drawMarks(f: MarkFx, p: Float) {
    val plan = markPlan(f.slot, f.tier) ?: return
    val ink = inkOf(f.pet)
    val unit = 1.dp.toPx()
    val fade = if (p > 0.66f) ((1f - p) / 0.34f).coerceIn(0f, 1f) else 1f
    // A flash of the killer's color behind the cuts, as the first one lands.
    if (plan.flash && p < 0.35f) {
        val a = (1f - p / 0.35f) * 0.45f
        drawCircle(
            Brush.radialGradient(listOf(ink.glow.copy(alpha = a), Color.Transparent), center = Offset(f.cx, f.cy), radius = f.bodyH * 0.9f),
            radius = f.bodyH * 0.9f,
            center = Offset(f.cx, f.cy),
        )
    }
    if (plan.ring) {
        val r = (p / 0.45f).coerceIn(0f, 1f)
        if (r < 1f) {
            drawCircle(
                ink.core.copy(alpha = (1f - r) * 0.8f),
                radius = f.bodyH * (0.15f + 0.45f * r),
                center = Offset(f.cx, f.cy),
                style = Stroke(width = unit * (6f - 4f * r)),
            )
        }
    }
    val len = f.bodyH * plan.length
    for (i in 0 until plan.count) {
        val start = markStart(i, plan.count)
        val cut = ((p - start) / 0.14f).coerceIn(0f, 1f)
        if (cut <= 0f) continue
        // Marks fall across the body at varied angles; an X's two big cuts cross, a third cuts between.
        val baseAngle = when {
            f.slot == Slot.HEAVY && plan.count == 3 -> listOf(-40f, 40f, 0f)[i]
            else -> -55f + 110f * hash01(f.seed + i * 17, 3) + if (i % 2 == 1) 90f else 0f
        }
        val angle = baseAngle * DEG
        val off = Offset((hash01(f.seed + i * 29, 5) - 0.5f) * f.bodyH * 0.25f, (hash01(f.seed + i * 31, 7) - 0.5f) * f.bodyH * 0.3f)
        val dir = Offset(cos(angle), sin(angle))
        val a = Offset(f.cx, f.cy) + off - dir * (len / 2f)
        val b = Offset(f.cx, f.cy) + off + dir * (len / 2f)
        drawMark(f.pet, a, b, cut, fade, plan.width * unit, ink, f.seed + i)
    }
    if (plan.sparks > 0) sparks(f, p, plan.sparks, ink, unit)
}

private const val DEG = (PI / 180.0).toFloat()

/** One mark from [a] to [b], [cut] of it drawn so far, at [alpha], [width] thick, in [pet]'s style. */
private fun DrawScope.drawMark(pet: PetId, a: Offset, b: Offset, cut: Float, alpha: Float, width: Float, ink: MarkInk, seed: Int) {
    val end = a + (b - a) * cut
    val d = b - a
    val normal = Offset(-d.y, d.x) / d.getDistance().coerceAtLeast(1f)
    when (pet) {
        // A thick machete arc: a bowed cut.
        PetId.JASON -> {
            val path = Path().apply {
                moveTo(a.x, a.y)
                val mid = a + (end - a) / 2f + normal * (d.getDistance() * 0.12f * cut)
                quadraticTo(mid.x, mid.y, end.x, end.y)
            }
            layered(path, alpha, width * 1.3f, ink)
        }
        // Four claws raking side by side.
        PetId.FREDDY -> for (k in -1..2) {
            val shift = normal * (width * 2.4f * (k - 0.5f))
            val path = Path().apply {
                moveTo(a.x + shift.x, a.y + shift.y)
                val mid = a + (end - a) / 2f + shift + normal * (d.getDistance() * 0.06f)
                quadraticTo(mid.x, mid.y, end.x + shift.x, end.y + shift.y)
            }
            layered(path, alpha, width * 0.6f, ink)
        }
        // A chain of links ending in a hook.
        PetId.PINHEAD -> {
            val links = 9
            for (k in 0 until (links * cut).toInt().coerceAtLeast(1)) {
                val t = k / links.toFloat()
                val c = a + d * t
                drawOval(
                    ink.body.copy(alpha = alpha),
                    topLeft = c - Offset(width * 1.1f, width * 0.7f),
                    size = Size(width * 2.2f, width * 1.4f),
                    style = Stroke(width * 0.45f),
                )
            }
            if (cut >= 1f) {
                val hook = Path().apply {
                    moveTo(b.x, b.y)
                    quadraticTo(b.x + normal.x * width * 4f, b.y + normal.y * width * 4f, b.x - d.x * 0.08f, b.y - d.y * 0.08f)
                }
                drawPath(hook, ink.core.copy(alpha = alpha), style = Stroke(width * 0.6f, cap = StrokeCap.Round))
            }
        }
        // A thin, clean knife slash and a couple of stab flecks.
        PetId.GHOSTFACE -> {
            val path = Path().apply {
                moveTo(a.x, a.y)
                lineTo(end.x, end.y)
            }
            layered(path, alpha, width * 0.6f, ink)
            if (cut >= 1f) for (k in 0 until 2) {
                val c = a + d * (0.3f + 0.4f * hash01(seed + k, 11)) + normal * (width * 3f * (if (k == 0) 1f else -1f))
                drawLine(ink.core.copy(alpha = alpha), c, c + d * 0.05f, strokeWidth = width * 0.5f, cap = StrokeCap.Round)
            }
        }
        // Short stabs, each with a voodoo pin's head.
        PetId.CHUCKY -> for (k in 0 until 3) {
            val t0 = k / 3f
            if (cut < t0) break
            val c = a + d * (t0 + 0.12f)
            val tip = c + Offset(d.y, -d.x) * 0.12f
            drawLine(ink.glow.copy(alpha = alpha * 0.6f), c, tip, strokeWidth = width * 1.4f, cap = StrokeCap.Round)
            drawLine(ink.core.copy(alpha = alpha), c, tip, strokeWidth = width * 0.5f, cap = StrokeCap.Round)
            drawCircle(ink.body.copy(alpha = alpha), radius = width * 0.9f, center = c)
        }
        // One long, white-hot diagonal.
        PetId.MICHAEL -> {
            val far = a - d * 0.2f
            val path = Path().apply {
                moveTo(far.x, far.y)
                lineTo(far.x + (d.x * 1.4f) * cut, far.y + (d.y * 1.4f) * cut)
            }
            layered(path, alpha, width * 1.1f, ink)
        }
        // A jagged saw tear.
        PetId.LEATHERFACE -> {
            val teeth = 10
            val path = Path().apply {
                moveTo(a.x, a.y)
                for (k in 1..(teeth * cut).toInt().coerceAtLeast(1)) {
                    val t = k / teeth.toFloat()
                    val c = a + d * t + normal * (width * 1.6f * (if (k % 2 == 0) 1f else -1f))
                    lineTo(c.x, c.y)
                }
            }
            layered(path, alpha, width * 0.8f, ink)
        }
        // A bite: two bowed rows of tooth punctures, an upper jaw and a lower one, closing on the line of the cut.
        PetId.JAWS -> {
            val teeth = 7
            for (k in 0 until (teeth * cut).toInt().coerceAtLeast(1)) {
                val t = (k + 0.5f) / teeth
                val bow = sin(PI.toFloat() * t) * width * 2.2f
                for (jaw in 0..1) {
                    val side = if (jaw == 0) -1f else 1f
                    val root = a + d * t + normal * (side * (width * 1.4f + bow))
                    val tip = root - normal * (side * width * 1.3f)
                    drawLine(ink.glow.copy(alpha = alpha * 0.5f), root, tip, strokeWidth = width * 1.3f, cap = StrokeCap.Round)
                    drawLine(ink.body.copy(alpha = alpha), root, tip, strokeWidth = width * 0.8f, cap = StrokeCap.Round)
                    drawLine(ink.core.copy(alpha = alpha), root, tip, strokeWidth = width * 0.3f, cap = StrokeCap.Round)
                }
            }
        }
    }
}

/** [path] stroked three times: a wide soft glow, the body, a thin bright core. */
private fun DrawScope.layered(path: Path, alpha: Float, width: Float, ink: MarkInk) {
    drawPath(path, ink.glow.copy(alpha = alpha * 0.35f), style = Stroke(width * 2.6f, cap = StrokeCap.Round), blendMode = BlendMode.Screen)
    drawPath(path, ink.body.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round))
    drawPath(path, ink.core.copy(alpha = alpha), style = Stroke(width * 0.35f, cap = StrokeCap.Round))
}

/** [n] sparks (blood drops, embers, chips) bursting out from the body and falling. */
private fun DrawScope.sparks(f: MarkFx, p: Float, n: Int, ink: MarkInk, unit: Float) {
    val t = ((p - 0.08f) / 0.8f).coerceIn(0f, 1f)
    if (t <= 0f || t >= 1f) return
    for (k in 0 until n) {
        val ang = hash01(f.seed + k * 7, 13) * 2f * PI.toFloat()
        val speed = f.bodyH * (0.35f + 0.5f * hash01(f.seed + k * 7, 17))
        val x = f.cx + cos(ang) * speed * t
        val y = f.cy + sin(ang) * speed * t + f.bodyH * 0.5f * t * t // they fall as they fly
        val r = unit * (1.5f + 2f * hash01(f.seed + k, 19)) * (1f - t * 0.6f)
        drawCircle(if (k % 3 == 0) ink.core else ink.glow, radius = r, center = Offset(x, y), alpha = (1f - t))
    }
}
