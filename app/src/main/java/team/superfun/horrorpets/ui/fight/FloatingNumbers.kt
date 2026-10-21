package team.superfun.horrorpets.ui.fight

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.max
import kotlin.math.roundToInt
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Glass
import team.superfun.horrorpets.ui.theme.TextHi

/** A floating number rises this far while it fades (120 px on a 1080p frame). */
private val FloatRise = 60.dp

/** How long a floating number lives: it holds, then fades out over the second half. */
const val FLOAT_MS = 900

/** The dark rim around every floating word, so it reads over any part of a scene. */
private val Outline = Color(0xFF0B0709)

/** Steel blue for a block: the color of the shield ring. */
val ShieldBlue = Color(0xFFA9D2FF)

/** The look of a floating text. */
internal enum class FloatKind {
    /** A damage number, "−23": red, like an RPG's. */
    DAMAGE,

    /** A critical hit: bigger, hot orange into blood red, with CRITICAL! over it. */
    CRIT,

    /** Hit points back from a potion, "+34": green. */
    HEAL,

    /** A whiff: grey. */
    MISS,

    /** A block: steel blue. */
    BLOCK,

    /** A full special meter. */
    READY,
}

/**
 * One floating text over a fighter: [text] large, with [labels] stacked over it in smaller letters
 * (CRITICAL!, COUNTER, GUARD BREAK, INTERRUPTED). [x], [y] is where its bottom centre starts, in pixels of
 * the arena; it pops in, rises and fades over [FLOAT_MS]. Each instance is one showing.
 */
@Immutable
internal class FloatText(
    val id: Long,
    val kind: FloatKind,
    val text: String,
    val labels: List<String>,
    val x: Float,
    val y: Float,
)

/**
 * The fight's floating numbers ("RPG damage numbers"). Each one animates itself in a layer (no
 * recomposition per frame) and reports [onDone] once it has faded, so the caller can drop it. They keep
 * [inset] pixels (the screen's safe inset) clear of the left and right edges.
 */
@Composable
internal fun FloatingNumbers(items: List<FloatText>, top: () -> Float, inset: Float, onDone: (FloatText) -> Unit) {
    for (item in items) {
        key(item.id) { FloatingText(item, top, inset, onDone) }
    }
}

@Composable
private fun FloatingText(item: FloatText, top: () -> Float, inset: Float, onDone: (FloatText) -> Unit) {
    val t = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(FLOAT_MS, easing = LinearEasing))
        done(item)
    }
    val density = LocalDensity.current
    val rise = with(density) { FloatRise.toPx() }
    val rim = with(density) { FloatRim.toPx() }
    val popScale = if (item.kind == FloatKind.CRIT) 1.3f else 1.25f
    Column(
        Modifier
            .placeInBounds(x = { item.x }, y = { item.y }, top = top, inset = inset, popScale = popScale, rise = rise, rim = rim)
            .graphicsLayer {
                val p = t.value
                val up = 1f - (1f - p) * (1f - p) * (1f - p) // ease out: quick off the mark, then drifting
                translationY = -rise * up
                alpha = if (p < 0.5f) 1f else (1f - (p - 0.5f) / 0.5f)
                // Pop: lands big and settles within the first ~110ms.
                val pop = (p / 0.12f).coerceAtMost(1f)
                val s = lerp(popScale, 1f, 1f - (1f - pop) * (1f - pop))
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Every label on one line ("CRITICAL! · COUNTER"), so a busy hit never towers into the HUD.
        if (item.labels.isNotEmpty()) {
            OutlinedText(item.labels.joinToString("  \u00b7  "), labelStyle(item.kind), outline = 4.dp)
        }
        OutlinedText(item.text, mainStyle(item.kind), outline = if (item.kind == FloatKind.CRIT) 7.dp else 5.dp)
    }
}

/** Room kept for a floating word's dark outline and drop shadow, which draw outside its measured box. */
private val FloatRim = 8.dp

/**
 * Places a floating word with its bottom centre at ([x], [y]), moved as little as needed to stay in
 * bounds for its whole life:
 * - sideways inside the safe inset ([inset] pixels from each side), even at its [popScale] (a fighter
 *   stands at 30% of the width, and on 4:3 or a CRT a wide "CRITICAL! · COUNTER" centred there would
 *   reach into the overscan);
 * - down, so that neither its popped-in size nor its top after rising [rise] pixels comes above [top]
 *   (the bottom of the HUD), which on a small screen a labelled crit would otherwise reach.
 * [rim] is extra room for the outline. The word must not be drawn inside a layer that moves (the arena's
 * shake), or the clamp no longer holds; all positions are read during layout.
 */
internal fun Modifier.placeInBounds(
    x: () -> Float,
    y: () -> Float,
    top: () -> Float,
    inset: Float,
    popScale: Float,
    rise: Float,
    rim: Float,
): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(Constraints())
    val w = constraints.maxWidth
    val side = inset + rim + p.width * (popScale - 1f) / 2f
    val left = (x() - p.width / 2f).coerceIn(side, max(side, w - side - p.width))
    val above = max(p.height * popScale, p.height + rise) + rim
    val bottom = max(y(), top() + above)
    layout(w, constraints.maxHeight) {
        p.place(left.roundToInt(), (bottom - p.height).roundToInt())
    }
}

private val DamageFill = Brush.verticalGradient(listOf(Color(0xFFFF8A8F), Color(0xFFFF2D3A), Color(0xFFB30F1C)))
private val CritFill = Brush.verticalGradient(listOf(Color(0xFFFFC46B), Color(0xFFFF3B2F), Blood))
private val HealFill = Brush.verticalGradient(listOf(Color(0xFFE0FFC2), PotionGreen, Color(0xFF2E9E2A)))
private val ReadyFill = Brush.verticalGradient(listOf(Color(0xFFFF8088), BloodGlow, Blood))

private fun mainStyle(kind: FloatKind): TextStyle = when (kind) {
    FloatKind.DAMAGE -> displayStyle(64.sp, tracking = 0.02f, lineHeight = 64.sp).copy(brush = DamageFill)
    FloatKind.HEAL -> displayStyle(72.sp, tracking = 0.02f, lineHeight = 72.sp).copy(brush = HealFill)
    FloatKind.CRIT -> displayStyle(96.sp, tracking = 0.02f, lineHeight = 96.sp).copy(brush = CritFill)
    FloatKind.MISS -> displayStyle(56.sp, color = Color(0xFFB4B1B8), tracking = 0.08f, lineHeight = 56.sp)
    FloatKind.BLOCK -> displayStyle(56.sp, color = ShieldBlue, tracking = 0.08f, lineHeight = 56.sp)
    FloatKind.READY -> displayStyle(40.sp, tracking = 0.08f, lineHeight = 40.sp).copy(brush = ReadyFill)
}

private fun labelStyle(kind: FloatKind): TextStyle = when (kind) {
    FloatKind.CRIT -> displayStyle(34.sp, color = Amber, tracking = 0.08f, lineHeight = 36.sp)
    else -> displayStyle(28.sp, color = Amber, tracking = 0.08f, lineHeight = 30.sp)
}

/**
 * [text] on one line with a dark [outline] all round and a soft drop shadow: a stroked copy underneath,
 * the fill (a color or a brush from [style]) on top.
 */
@Composable
internal fun OutlinedText(text: String, style: TextStyle, outline: Dp, modifier: Modifier = Modifier, outlineColor: Color = Outline) {
    val px = with(LocalDensity.current) { outline.toPx() }
    val rim = remember(style, px, outlineColor) {
        // The stroke is centred on the glyph edge and the fill covers its inner half, so draw it twice as wide.
        style.copy(
            color = outlineColor,
            drawStyle = Stroke(width = px * 2f, join = StrokeJoin.Round),
            shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, px * 0.8f), blurRadius = px * 2.5f),
        )
    }
    Box(modifier) {
        BasicText(text, style = rim, maxLines = 1, softWrap = false)
        BasicText(text, style = style, maxLines = 1, softWrap = false)
    }
}

// ---- move callouts ----

/** The signature special of each pet, as its callout names it (from the roster). */
fun specialName(pet: PetId): String = pet.spec.special.displayName

/** The words a move pops up as over the fighter that chose it. */
internal fun calloutText(move: Move, pet: PetId): String = when (move) {
    Move.SPECIAL -> specialName(pet).uppercase() + "!"
    Move.RUN -> "RUN!"
    Move.POTION -> "POTION!"
    else -> move.name
}

/**
 * A move callout: the move a fighter just chose, popping up over its head on a glass tag. [progress]
 * runs 0..1 over the callout's life: it springs in, holds, and fades away up. Read in the layer only.
 * Like the floating numbers, it keeps [inset] pixels clear of the sides.
 */
@Composable
internal fun MoveCallout(
    text: String,
    move: Move,
    progress: () -> Float,
    x: () -> Float,
    y: () -> Float,
    top: () -> Float,
    inset: Float,
    maxWidth: Dp,
) {
    val accent = when (move) {
        Move.SPECIAL -> Amber
        Move.BLOCK -> ShieldBlue
        Move.RUN -> Color(0xFFB4B1B8)
        Move.POTION -> PotionGreen
        else -> BloodGlow
    }
    Box(
        Modifier
            .placeInBounds(
                x = x,
                y = y,
                top = top,
                inset = inset,
                popScale = CALLOUT_POP,
                rise = with(LocalDensity.current) { CalloutRise.toPx() },
                rim = with(LocalDensity.current) { 4.dp.toPx() },
            )
            .graphicsLayer {
                val p = progress()
                val pop = (p / 0.22f).coerceIn(0f, 1f)
                // A springy overshoot on the way in.
                val s = if (pop < 1f) lerp(0.55f, CALLOUT_POP, pop) else lerp(CALLOUT_POP, 1f, ((p - 0.22f) / 0.1f).coerceIn(0f, 1f))
                scaleX = s
                scaleY = s
                alpha = when {
                    p < 0.08f -> p / 0.08f
                    p > 0.8f -> ((1f - p) / 0.2f).coerceIn(0f, 1f)
                    else -> 1f
                }
                translationY = -CalloutRise.toPx() * ((p - 0.8f) / 0.2f).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                softGlow(Color.Black, alpha = 0.55f, cornerRadius = r.x, spread = 12.dp, offsetY = 3.dp.toPx())
                drawRoundRect(Glass, cornerRadius = r)
                drawRoundRect(Color.Black.copy(alpha = 0.35f), cornerRadius = r)
                val stroke = 2.dp.toPx()
                drawRoundRect(
                    color = accent.copy(alpha = 0.85f),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(r.x - stroke / 2f),
                    style = Stroke(stroke),
                )
            }
            .padding(horizontal = 20.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = displayStyle(calloutSize(text, maxWidth), color = if (move == Move.SPECIAL) Amber else TextHi, tracking = 0.08f, lineHeight = 34.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A callout overshoots to this size as it pops in, and drifts up this far as it fades. */
private const val CALLOUT_POP = 1.12f
private val CalloutRise = 12.dp

/** Callouts are 32sp; a long special name steps down so its tag stays inside the fighter's half. */
private fun calloutSize(text: String, maxWidth: Dp): TextUnit {
    // Barlow Condensed ExtraBold with 0.08em tracking averages about 0.56em per character.
    val fits = (maxWidth.value - 40f) / (text.length * 0.56f)
    return fits.coerceIn(22f, 32f).sp
}
