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
import team.superfun.horrorpets.ui.common.SAFE_INSET_FRACTION
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.placeAt
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
    /** A damage number: white. */
    DAMAGE,

    /** A critical hit: bigger, Amber into Blood, with CRITICAL! over it. */
    CRIT,

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
 * recomposition per frame) and reports [onDone] once it has faded, so the caller can drop it.
 */
@Composable
internal fun FloatingNumbers(items: List<FloatText>, onDone: (FloatText) -> Unit) {
    for (item in items) {
        key(item.id) { FloatingText(item, onDone) }
    }
}

@Composable
private fun FloatingText(item: FloatText, onDone: (FloatText) -> Unit) {
    val t = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(FLOAT_MS, easing = LinearEasing))
        done(item)
    }
    val rise = with(LocalDensity.current) { FloatRise.toPx() }
    val popScale = if (item.kind == FloatKind.CRIT) 1.3f else 1.25f
    Column(
        Modifier
            .floatAt(item.x, item.y, popScale)
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

/**
 * Places a floating text with its bottom centre at ([x], [y]), nudged sideways as needed to keep it inside
 * the safe inset even at its [popScale] (a fighter stands at 30% of the width, and on 4:3 a wide
 * "CRITICAL! · COUNTER" centred there would reach into the overscan).
 */
private fun Modifier.floatAt(x: Float, y: Float, popScale: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(Constraints())
    val w = constraints.maxWidth
    val inset = w * SAFE_INSET_FRACTION + p.width * (popScale - 1f) / 2f
    val left = (x - p.width / 2f).coerceIn(inset, max(inset, w - inset - p.width))
    layout(w, constraints.maxHeight) {
        p.place(left.roundToInt(), (y - p.height).roundToInt())
    }
}

private val CritFill = Brush.verticalGradient(listOf(Color(0xFFFFD36B), Amber, Blood))
private val ReadyFill = Brush.verticalGradient(listOf(Color(0xFFFF8088), BloodGlow, Blood))

private fun mainStyle(kind: FloatKind): TextStyle = when (kind) {
    FloatKind.DAMAGE -> displayStyle(64.sp, tracking = 0.02f, lineHeight = 64.sp)
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

/** The signature special of each pet, as its callout names it. */
fun specialName(pet: PetId): String = when (pet) {
    PetId.JASON -> "Machete Frenzy"
    PetId.FREDDY -> "Dream Claws"
    PetId.PINHEAD -> "Chains"
    PetId.GHOSTFACE -> "Knife Flurry"
}

/** The words a move pops up as over the fighter that chose it. */
internal fun calloutText(move: Move, pet: PetId): String = when (move) {
    Move.SPECIAL -> specialName(pet).uppercase() + "!"
    Move.RUN -> "RUN!"
    else -> move.name
}

/**
 * A move callout: the move a fighter just chose, popping up over its head on a glass tag. [progress]
 * runs 0..1 over the callout's life: it springs in, holds, and fades away up. Read in the layer only.
 */
@Composable
internal fun MoveCallout(text: String, move: Move, progress: () -> Float, x: () -> Float, y: Float, maxWidth: Dp) {
    val accent = when (move) {
        Move.SPECIAL -> Amber
        Move.BLOCK -> ShieldBlue
        Move.RUN -> Color(0xFFB4B1B8)
        else -> BloodGlow
    }
    Box(
        Modifier
            .placeAt(x = x, y = { y })
            .graphicsLayer {
                val p = progress()
                val pop = (p / 0.22f).coerceIn(0f, 1f)
                // A springy overshoot on the way in.
                val s = if (pop < 1f) lerp(0.55f, 1.12f, pop) else lerp(1.12f, 1f, ((p - 0.22f) / 0.1f).coerceIn(0f, 1f))
                scaleX = s
                scaleY = s
                alpha = when {
                    p < 0.08f -> p / 0.08f
                    p > 0.8f -> ((1f - p) / 0.2f).coerceIn(0f, 1f)
                    else -> 1f
                }
                translationY = -12.dp.toPx() * ((p - 0.8f) / 0.2f).coerceIn(0f, 1f)
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

/** Callouts are 32sp; a long special name steps down so its tag stays inside the fighter's half. */
private fun calloutSize(text: String, maxWidth: Dp): TextUnit {
    // Barlow Condensed ExtraBold with 0.08em tracking averages about 0.56em per character.
    val fits = (maxWidth.value - 40f) / (text.length * 0.56f)
    return fits.coerceIn(22f, 32f).sp
}
