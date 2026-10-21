package team.superfun.horrorpets.ui.common

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/** The 5% overscan-safe padding for the screen this scope fills. */
fun BoxWithConstraintsScope.safeInsetPadding(): PaddingValues =
    PaddingValues(horizontal = maxWidth * SAFE_INSET_FRACTION, vertical = maxHeight * SAFE_INSET_FRACTION)

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
