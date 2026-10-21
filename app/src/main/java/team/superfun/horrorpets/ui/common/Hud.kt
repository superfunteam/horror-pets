package team.superfun.horrorpets.ui.common

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import team.superfun.horrorpets.ui.theme.Body
import team.superfun.horrorpets.ui.theme.Display
import team.superfun.horrorpets.ui.theme.TextHi

/**
 * Shared look-and-feel helpers for every screen: the CRT-safe inset, the two text styles and the soft
 * glow / shadow painter. Everything here is drawn with plain fills, so it looks the same on API 22
 * (no colored shadows, no blur) as on a current Google TV.
 */

/** Fraction of each screen edge kept clear for CRT overscan. Everything (UI and pets) stays inside it. */
const val SAFE_INSET_FRACTION = 0.05f

/**
 * How far in from both edges of a [size]-long side the UI keeps (any one unit): [SAFE_INSET_FRACTION] of it,
 * or more where the system takes more of an edge ([start] and [end], such as a phone's camera cutout or its
 * gesture bar). Both edges get the larger, so a centred layout stays centred. A TV reports no such insets,
 * so on a TV this is always exactly the 5% overscan inset.
 */
fun safeInset(size: Float, start: Float, end: Float): Float = maxOf(size * SAFE_INSET_FRACTION, start, end)

/** The safe inset (see [safeInset]) from the left and right edges, and from the top and bottom, of one screen. */
@Immutable
class SafeInset(val horizontal: Dp, val vertical: Dp)

/**
 * The safe inset for the screen this scope fills: 5% of each side, widened to clear a phone's display
 * cutout and its mandatory gesture area (the swipe-up bar). Exactly 5% on a TV.
 */
@Composable
fun BoxWithConstraintsScope.safeInset(): SafeInset {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val system = WindowInsets.displayCutout.union(WindowInsets.mandatorySystemGestures)
    return with(density) {
        SafeInset(
            horizontal = safeInset(
                maxWidth.value,
                system.getLeft(density, direction).toDp().value,
                system.getRight(density, direction).toDp().value,
            ).dp,
            vertical = safeInset(
                maxHeight.value,
                system.getTop(density).toDp().value,
                system.getBottom(density).toDp().value,
            ).dp,
        )
    }
}

/** The safe-inset padding (see [safeInset]) for the screen this scope fills. */
@Composable
fun BoxWithConstraintsScope.safeInsetPadding(): PaddingValues {
    val inset = safeInset()
    return PaddingValues(horizontal = inset.horizontal, vertical = inset.vertical)
}

/** A dark halo under bright text so it stays readable over any part of a scene. */
val TextHalo = Shadow(color = Color.Black.copy(alpha = 0.75f), offset = Offset(0f, 2f), blurRadius = 10f)

/** Barlow Condensed for names, numbers, buttons and titles. Callers uppercase the text themselves. */
fun displayStyle(
    size: TextUnit,
    weight: FontWeight = FontWeight.ExtraBold,
    color: Color = TextHi,
    tracking: Float = 0.04f,
    halo: Boolean = false,
    lineHeight: TextUnit = TextUnit.Unspecified,
): TextStyle = TextStyle(
    fontFamily = Display,
    fontWeight = weight,
    fontSize = size,
    letterSpacing = tracking.em,
    color = color,
    shadow = if (halo) TextHalo else null,
    lineHeight = lineHeight,
    // With an explicit line height, drop the font's tall ascent/descent padding so rows pack tightly.
    lineHeightStyle = if (lineHeight == TextUnit.Unspecified) {
        null
    } else {
        LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
    },
)

/** Inter for small text. Keep it at 13sp or more: 26 px at 1080p, still legible on a CRT. */
fun bodyStyle(
    size: TextUnit,
    weight: FontWeight = FontWeight.SemiBold,
    color: Color = TextHi,
    halo: Boolean = false,
    tracking: TextUnit = TextUnit.Unspecified,
): TextStyle = TextStyle(
    fontFamily = Body,
    fontWeight = weight,
    fontSize = size,
    color = color,
    letterSpacing = tracking,
    shadow = if (halo) TextHalo else null,
)

/**
 * Paints a soft halo around a rounded rect of [size] at the origin: [layers] filled rects, each [step]
 * larger than the last, whose alphas add up to [alpha] near the edge and fade to nothing outward.
 * A blur without `BlurMaskFilter`, which hardware rendering ignores before API 28.
 */
fun DrawScope.softGlow(
    color: Color,
    alpha: Float,
    cornerRadius: Float,
    spread: Dp = 18.dp,
    layers: Int = 9,
    offsetY: Float = 0f,
    size: Size = this.size,
) {
    if (alpha <= 0f) return
    val step = spread.toPx() / layers
    // Each layer adds its own alpha; the falloff is quadratic so the edge of the halo is invisible.
    for (i in layers downTo 1) {
        val grow = step * i
        val t = 1f - (i - 1).toFloat() / layers
        drawRoundRect(
            color = color,
            alpha = (alpha * t * t / layers * 2.2f).coerceIn(0f, 1f),
            topLeft = Offset(-grow, -grow + offsetY),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(cornerRadius + grow),
        )
    }
}

/** `#RRGGBB` (or `#AARRGGBB`) from the scene catalog as a Color; [fallback] when it does not parse. */
fun hexColor(hex: String, fallback: Color = Color.Black): Color {
    val digits = hex.removePrefix("#")
    val value = digits.toLongOrNull(16) ?: return fallback
    return when (digits.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> fallback
    }
}
