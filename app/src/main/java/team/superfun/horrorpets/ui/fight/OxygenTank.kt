package team.superfun.horrorpets.ui.fight

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import team.superfun.horrorpets.model.SpecialStyle
import team.superfun.horrorpets.ui.common.hash01

// Jaws's Oxygen Tank. He lunges with his ATTACK and, at its snap, a silver scuba tank flies out of his mouth in a
// spinning lob onto the other fighter, landing as his special's sound explodes ([TANK_FLIGHT_MS]), and blows up:
// a white flash over the whole screen and a hard screen shake (the fight screen's, see [specialJolt]), a fireball
// that swells and rolls up into smoke, a shock ring, debris and sparks. The tank is the only picture; the rest is
// drawn here, from brushes built once per showing ([TankKit]).

/** The tank's picture: a silver scuba tank, 312 x 384 (made with the VS art, see the spec's assets). */
internal const val TANK_ART = "sprites/fight/oxygen_tank.webp"

/**
 * How long the tank is in the air, from the swing's impact (the effect's start) to the blast. It is where
 * `special_jaws` explodes (his `specialHitMs`), so with the clip started at the impact the two land together.
 */
internal const val TANK_FLIGHT_MS = 780

/** How long the blast burns and smokes after the tank lands. */
internal const val TANK_BLAST_MS = 1_150

private const val TANK_TOTAL_MS = TANK_FLIGHT_MS + TANK_BLAST_MS

/**
 * Jaws's mouth, wide open in the frames after his ATTACK's snap, from his feet, as fractions of his cell's height:
 * about 92 px ahead of his anchor (toward the other fighter, once he is turned to face it) and 96 px above his
 * baseline, in his 416 px cell.
 */
private const val MOUTH_AHEAD = 0.22f
private const val MOUTH_UP = 0.23f

/** The tank in flight is this tall, as a fraction of the thrower's cell (the potion is 0.3 of the drinker's). */
internal const val TANK_SIZE = 0.34f

/** How high the lob rises over the straight line at its middle, as a share of how far it flies across... */
internal const val TANK_ARC = 0.4f

/** ...but never less than this share of the thrower's height, so a short throw (4:3) still goes up and drops. */
private const val TANK_MIN_ARC = 0.45f

/** How high the lob rises at its middle for a throw [dx] across, by a thrower [throwerH] tall: see [TANK_ARC]. */
internal fun tankArc(dx: Float, throwerH: Float): Float = max(TANK_ARC * abs(dx), TANK_MIN_ARC * throwerH)

/** How many times it turns over on the way. */
private const val TANK_TURNS = 1.25f

/** Where the effect's clock (0..1 over the special's whole length, see [specialMs]) is, in ms. */
internal fun tankMsAt(p: Float): Float = p * TANK_TOTAL_MS

/** How far along its flight the tank is [ms] after the impact: 0 leaving his mouth, 1 landing (and after). */
internal fun tankFlight(ms: Float): Float = (ms / TANK_FLIGHT_MS).coerceIn(0f, 1f)

/** How far the blast is [ms] after the impact: negative while the tank flies, 0 as it lands, 1 at the effect's end. */
internal fun tankBlast(ms: Float): Float = (ms - TANK_FLIGHT_MS) / TANK_BLAST_MS

/** The tank's x [f] (0..1) of the way from [fromX] to [toX]. */
internal fun tankX(fromX: Float, toX: Float, f: Float): Float = fromX + (toX - fromX) * f

/** The tank's y [f] of the way from [fromY] to [toY]: the straight line, lifted by a parabola [arc] high at its middle. */
internal fun tankY(fromY: Float, toY: Float, arc: Float, f: Float): Float = fromY + (toY - fromY) * f - arc * 4f * f * (1f - f)

/** How far the tank has turned, in degrees, [f] of the way: clockwise flying right ([dir] +1), the other way flying left. */
internal fun tankSpinDeg(f: Float, dir: Float): Float = dir * TANK_TURNS * 360f * f

/** Where the tank leaves Jaws's mouth: x, for his feet at [feetX], his cell [cellH] tall, facing [dir] (+1 right). */
internal fun tankMouthX(feetX: Float, cellH: Float, dir: Float): Float = feetX + dir * MOUTH_AHEAD * cellH

/** Where the tank leaves Jaws's mouth: y, for his feet on [floorY] and his cell [cellH] tall. */
internal fun tankMouthY(floorY: Float, cellH: Float): Float = floorY - MOUTH_UP * cellH

private fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

/**
 * How far the fireball has spread at blast [b] (0..1), as a share of its full size: it swells almost all the way in
 * the first fifth, then keeps rolling out slowly (a little past 1).
 */
internal fun fireballReach(b: Float): Float {
    if (b <= 0f) return 0f
    return easeOut((b / 0.22f).coerceAtMost(1f)) + 0.15f * ((b - 0.22f) / 0.78f).coerceIn(0f, 1f)
}

/** How hot the fireball still burns at blast [b]: white-hot (1) as it bursts, burnt out (0) from 0.7 on. */
internal fun fireballHeat(b: Float): Float = 1f - smoothstep(0.1f, 0.7f, b)

/** How thick the smoke is at blast [b]: none at the burst, thickest as the fire dies, gone by the end. */
internal fun smokeAlpha(b: Float): Float = 0.9f * smoothstep(0.04f, 0.3f, b) * (1f - smoothstep(0.62f, 1f, b))

/**
 * A special that jolts the whole screen as it lands: a full-screen white flash of [flashAlpha], fading over
 * [flashMs], and the arena's hardest shake, settling over [shakeMs] (a crit's lasts 250 ms).
 */
@Immutable
internal data class SpecialJolt(val flashAlpha: Float, val flashMs: Int, val shakeMs: Int)

private val TankJolt = SpecialJolt(flashAlpha = 0.85f, flashMs = 480, shakeMs = 800)

/** The jolt [style] lands with: the Oxygen Tank's blast; every other special has none. */
internal fun specialJolt(style: SpecialStyle): SpecialJolt? = if (style == SpecialStyle.OXYGEN_TANK) TankJolt else null

// ---- drawing ----

/** The radius the kit's brushes are made at, around (0, 0): a blob is drawn by moving and scaling one. */
private const val KIT_R = 100f

private const val FIRE_BLOBS = 12
private const val SMOKE_PUFFS = 10
private const val DEBRIS = 16
private const val SPARKS = 40
private const val HISS_PUFFS = 22
private const val HISS_EVERY_MS = 35f
private const val HISS_LIFE_MS = 380f
private const val SPRAY = 7
private const val SPRAY_MS = 260f

private val TankSilver = Color(0xFFCDD2DA)
private val TankDark = Color(0xFF1C1C21)
private val Vapor = Color(0xFFEAF4FF)

/** The blast's brushes and the sparks' colours, built once per showing, all around (0, 0) at [KIT_R]. */
internal class TankKit {
    /** A fire blob's white-hot heart, fading out through yellow. */
    val core: Brush = Brush.radialGradient(
        0f to Color(0xFFFFFFF4),
        0.4f to Color(0xFFFFF3A0),
        0.75f to Color(0xFFFFD040).copy(alpha = 0.6f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = KIT_R,
    )

    /** A fire blob's body: gold through orange. */
    val body: Brush = Brush.radialGradient(
        0f to Color(0xFFFFB42E),
        0.6f to Color(0xFFFF7A1A),
        0.85f to Color(0xFFE2471A).copy(alpha = 0.8f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = KIT_R,
    )

    /** A fire blob's dark red rim, which outlines the fireball and is the last of it to burn down. */
    val rim: Brush = Brush.radialGradient(
        0f to Color(0xFFC03A1A),
        0.7f to Color(0xFF7A1A0E).copy(alpha = 0.85f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = KIT_R,
    )

    /** A puff of smoke: grey enough to show over the darkened arena. */
    val smoke: Brush = Brush.radialGradient(
        0f to Color(0xFF6E6965).copy(alpha = 0.92f),
        0.55f to Color(0xFF4E4A4B).copy(alpha = 0.65f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = KIT_R,
    )

    /** A puff of the valve's hiss. */
    val vapor: Brush = Brush.radialGradient(
        0f to Vapor,
        0.5f to Vapor.copy(alpha = 0.5f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = KIT_R,
    )

    /** The fire's light thrown on everything round it. */
    val glow: Brush = Brush.radialGradient(
        0f to Color(0xFFFFE7A3).copy(alpha = 0.75f),
        0.45f to Color(0xFFFF8A1F).copy(alpha = 0.28f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = KIT_R,
    )

    /** The first instant's white burst. */
    val flash: Brush = Brush.radialGradient(
        0f to Color.White,
        0.5f to Color(0xFFFFF6D6).copy(alpha = 0.85f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = KIT_R,
    )

    /** The shock ring: a bright band at the rim of an empty disc (it thickens as it spreads, as a wave does). */
    val ring: Brush = Brush.radialGradient(
        0f to Color.Transparent,
        0.87f to Color.Transparent,
        0.95f to Color(0xFFFFF4D0),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = KIT_R,
    )

    /** A spark through its life, in four steps: white-hot to yellow, orange and a red ember. */
    val spark: List<Color> = listOf(Color(0xFFFFFBE8), Color(0xFFFFD23F), Color(0xFFFF8A1F), Color(0xFFD9381E))
}

/** [brush] (one of [TankKit]'s) as a disc of radius [r] centred on ([x], [y]). */
private fun DrawScope.blob(brush: Brush, x: Float, y: Float, r: Float, alpha: Float) {
    if (alpha <= 0f || r <= 0f) return
    val k = r / KIT_R
    withTransform({
        translate(x, y)
        scale(k, k, pivot = Offset.Zero)
    }) {
        drawCircle(brush, radius = KIT_R, center = Offset.Zero, alpha = alpha.coerceAtMost(1f))
    }
}

/**
 * Draws the Oxygen Tank [ms] after the impact: the tank in flight from [SpecialFx.fromX], [SpecialFx.fromY] (his
 * mouth) to the target's body, hissing, and from [TANK_FLIGHT_MS] on, the blast on the target. [tank] null (its
 * picture not decoded yet) leaves the flight to the hiss alone.
 */
internal fun DrawScope.oxygenTank(f: SpecialFx, ms: Float, unit: Float, kit: TankKit, tank: ImageBitmap?) {
    val dir = if (f.cx >= f.fromX) 1f else -1f
    val arc = tankArc(f.cx - f.fromX, f.throwerH)
    hiss(f, ms, unit, arc, kit)
    if (ms < TANK_FLIGHT_MS) {
        spray(f, ms, unit, dir, kit)
        if (tank != null) flyingTank(f, ms, arc, dir, tank)
    }
    val b = tankBlast(ms)
    if (b >= 0f) blast(f, b, b * TANK_BLAST_MS / 1000f, unit, kit)
}

/** The tank in the air: it comes out of his mouth small and grows to full size, turning over as it lobs, with two faint after-images. */
private fun DrawScope.flyingTank(f: SpecialFx, ms: Float, arc: Float, dir: Float, tank: ImageBitmap) {
    val sizePx = f.throwerH * TANK_SIZE
    val fit = sizePx / max(tank.width, tank.height)
    for (ghost in 2 downTo 0) {
        val at = ms - ghost * 40f
        if (at < 0f) continue
        val t = tankFlight(at)
        val x = tankX(f.fromX, f.cx, t)
        val y = tankY(f.fromY, f.cy, arc, t)
        // Out of his mouth: it starts half size, as if still in his jaws, and is full size a fifth of the way.
        val grow = 0.5f + 0.5f * easeOut((t / 0.2f).coerceAtMost(1f))
        val k = fit * grow
        // It comes out lying along his jaws (base first) and turns over on the way.
        val turn = -dir * 80f + tankSpinDeg(t, dir)
        withTransform({
            translate(x, y)
            rotate(turn, pivot = Offset.Zero)
            scale(k, k, pivot = Offset.Zero)
            translate(-tank.width / 2f, -tank.height / 2f)
        }) {
            drawImage(tank, alpha = if (ghost == 0) 1f else 0.32f / ghost)
        }
    }
}

/** A burst of spray and spit off his teeth as the tank leaves his mouth. */
private fun DrawScope.spray(f: SpecialFx, ms: Float, unit: Float, dir: Float, kit: TankKit) {
    val age = ms / SPRAY_MS
    if (age >= 1f) return
    val reach = f.bodyH * 0.32f * easeOut(age)
    blob(kit.vapor, f.fromX, f.fromY, f.throwerH * 0.1f * (1f + 1.2f * age), 0.6f * (1f - age))
    for (i in 0 until SPRAY) {
        // Flung forward, in a fan toward the target.
        val a = (hash01(i, 81) - 0.5f) * 1.6f
        val dx = cos(a) * dir
        val dy = sin(a) - 0.25f
        val r = (2.5f + 2.5f * hash01(i, 82)) * unit
        val d = reach * (0.6f + 0.6f * hash01(i, 83))
        drawCircle(Color.White, r, Offset(f.fromX + dx * d, f.fromY + dy * d), alpha = 1f - age)
    }
}

/** The valve's hiss: soft puffs of vapor left behind along the tank's path, swelling and fading into a trail. */
private fun DrawScope.hiss(f: SpecialFx, ms: Float, unit: Float, arc: Float, kit: TankKit) {
    for (k in 0 until HISS_PUFFS) {
        val born = k * HISS_EVERY_MS
        if (born > ms || born >= TANK_FLIGHT_MS) break
        val age = (ms - born) / HISS_LIFE_MS
        if (age >= 1f) continue
        val t = tankFlight(born)
        val x = tankX(f.fromX, f.cx, t)
        val y = tankY(f.fromY, f.cy, arc, t) - f.bodyH * 0.08f * age
        blob(kit.vapor, x, y, (7f + 18f * age) * unit, 0.4f * (1f - age))
    }
}

/**
 * The blast at [b] (0..1 over [TANK_BLAST_MS]), [sec] seconds after the tank lands, sized by the target's body: the
 * fire's light, smoke behind, the fireball's blobs churning round as they swell and rise (and burn down from white-hot
 * to a dark red rim), the first instant's white burst, the shock ring, the tank's debris and the sparks.
 */
private fun DrawScope.blast(f: SpecialFx, b: Float, sec: Float, unit: Float, kit: TankKit) {
    if (b >= 1f) return
    val r = f.bodyH
    val cx = f.cx
    val cy = f.cy
    val reach = fireballReach(b)
    val heat = fireballHeat(b)
    // The dark red rim outlasts the heat, and has burnt down by 0.72, leaving the smoke.
    val rim = max(heat, 1f - smoothstep(0.35f, 0.72f, b))
    val rise = r * 0.5f * b * b

    blob(kit.glow, cx, cy - rise * 0.5f, r * (1.25f + 0.6f * reach), max(heat, 0.5f * rim))

    // Smoke, behind the fire: puffs born one after another, billowing up and out.
    val smoke = smokeAlpha(b)
    if (smoke > 0f) {
        for (i in 0 until SMOKE_PUFFS) {
            val born = 0.06f + 0.05f * i
            if (b <= born) continue
            val age = (b - born) / (1f - born)
            val a = hash01(i, 91) * 2f * PI.toFloat()
            val d0 = r * 0.28f * hash01(i, 92)
            val x = cx + cos(a) * d0 + r * (hash01(i, 93) - 0.5f) * 0.6f * age
            val y = cy + sin(a) * d0 * 0.6f - r * 0.1f - r * (0.3f + 0.55f * hash01(i, 94)) * age
            val size = r * (0.26f + 0.16f * hash01(i, 95)) * (0.8f + 1.2f * age)
            blob(kit.smoke, x, y, size, smoke * min(1f, age / 0.12f))
        }
    }

    // The fireball: blobs round the centre, churning (alternate ones roll each way) as they swell and rise, in three
    // layers: a dark red rim round them all, their gold-orange bodies, and white-hot hearts that go first as it cools.
    if (rim > 0f) {
        for (layer in 0..2) {
            val alpha = when (layer) {
                0 -> rim
                1 -> heat
                else -> heat * heat
            }
            if (alpha <= 0f) continue
            val brush = when (layer) {
                0 -> kit.rim
                1 -> kit.body
                else -> kit.core
            }
            val grow = when (layer) {
                0 -> 1.2f
                1 -> 1f
                else -> 0.55f
            }
            for (i in 0 until FIRE_BLOBS) {
                val roll = (if (i % 2 == 0) 1f else -1f) * 3.2f * b
                val a = i * 2f * PI.toFloat() / FIRE_BLOBS + (hash01(i, 61) - 0.5f) * 0.5f + roll
                val d = r * (0.1f + 0.32f * hash01(i, 62)) * reach
                val x = cx + cos(a) * d * 1.15f
                val y = cy + sin(a) * d * 0.85f - rise - r * 0.08f * reach
                val pulse = 1f + 0.08f * sin(b * 16f + i * 1.7f)
                val size = r * (0.26f + 0.16f * hash01(i, 63)) * (0.35f + 0.75f * reach) * pulse * grow
                blob(brush, x, y, size, alpha * (0.85f + 0.15f * hash01(i, 64)))
            }
        }
    }

    // The first instant: a white burst as the tank goes.
    val burst = sec / 0.25f
    if (burst < 1f) blob(kit.flash, cx, cy, r * (0.5f + 0.8f * easeOut(burst)), 1f - burst)

    // The shock ring, racing out.
    val wave = sec / 0.45f
    if (wave < 1f) blob(kit.ring, cx, cy, r * (0.35f + 1.2f * easeOut(wave)), 0.85f * (1f - wave))

    // Debris: bits of the tank, silver and black, thrown up and out, tumbling as they fall.
    for (i in 0 until DEBRIS) {
        val life = 0.7f + 0.35f * hash01(i, 71)
        if (sec >= life) continue
        val a = if (i % 5 == 0) hash01(i, 72) * 2f * PI.toFloat() else -PI.toFloat() / 2f + (hash01(i, 72) - 0.5f) * 2.6f
        val speed = r * (1.4f + 1.6f * hash01(i, 73))
        val x = cx + cos(a) * speed * sec
        val y = cy + sin(a) * speed * sec + r * 2.25f * sec * sec
        val spin = hash01(i, 74) * 2f * PI.toFloat() + (hash01(i, 75) - 0.5f) * 22f * sec
        val half = (3.5f + 6f * hash01(i, 76)) * unit
        val hx = cos(spin) * half
        val hy = sin(spin) * half
        val w = (4f + 4f * hash01(i, 77)) * unit
        val fade = 1f - smoothstep(0.7f * life, life, sec)
        val silver = i % 3 != 0
        drawLine(TankDark, Offset(x - hx, y - hy), Offset(x + hx, y + hy), strokeWidth = w + 2.5f * unit, cap = StrokeCap.Round, alpha = 0.9f * fade)
        if (silver) drawLine(TankSilver, Offset(x - hx, y - hy), Offset(x + hx, y + hy), strokeWidth = w, cap = StrokeCap.Round, alpha = fade)
    }

    // Sparks: fast white-hot streaks flying out every way, cooling as they slow and drop.
    for (i in 0 until SPARKS) {
        val life = 0.25f + 0.3f * hash01(i, 101)
        val age = sec / life
        if (age >= 1f) continue
        val a = hash01(i, 102) * 2f * PI.toFloat()
        val dist = r * (0.55f + 0.75f * hash01(i, 103))
        val head = dist * easeOut(age)
        val tail = dist * easeOut((age - 0.14f).coerceAtLeast(0f))
        val drop = r * 0.35f * age * age
        val color = kit.spark[(age * kit.spark.size).toInt().coerceAtMost(kit.spark.size - 1)]
        drawLine(
            color,
            Offset(cx + cos(a) * tail, cy + sin(a) * tail + drop * 0.7f),
            Offset(cx + cos(a) * head, cy + sin(a) * head + drop),
            strokeWidth = (3.2f - 1.8f * age) * unit,
            cap = StrokeCap.Round,
            alpha = 1f - age,
        )
    }
}
