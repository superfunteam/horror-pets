package team.superfun.horrorpets.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

private val HorrorColors = darkColorScheme(
    primary = Blood,
    onPrimary = TextHi,
    secondary = Amber,
    onSecondary = Ink,
    background = Ink,
    onBackground = TextHi,
    surface = Ink,
    onSurface = TextHi,
    surfaceVariant = Glass,
    onSurfaceVariant = TextLo,
    error = BloodGlow,
    onError = Ink,
)

private fun TextStyle.display(weight: FontWeight) = copy(fontFamily = Display, fontWeight = weight)

private fun TextStyle.body(weight: FontWeight = FontWeight.Normal) =
    copy(fontFamily = Body, fontWeight = weight)

private val Base = Typography()

private val HorrorTypography = Typography(
    displayLarge = Base.displayLarge.display(FontWeight.ExtraBold),
    displayMedium = Base.displayMedium.display(FontWeight.ExtraBold),
    displaySmall = Base.displaySmall.display(FontWeight.ExtraBold),
    headlineLarge = Base.headlineLarge.display(FontWeight.ExtraBold),
    headlineMedium = Base.headlineMedium.display(FontWeight.ExtraBold),
    headlineSmall = Base.headlineSmall.display(FontWeight.Bold),
    titleLarge = Base.titleLarge.display(FontWeight.Bold),
    titleMedium = Base.titleMedium.display(FontWeight.Bold),
    titleSmall = Base.titleSmall.display(FontWeight.Bold),
    labelLarge = Base.labelLarge.body(FontWeight.SemiBold),
    labelMedium = Base.labelMedium.body(FontWeight.SemiBold),
    labelSmall = Base.labelSmall.body(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.body(),
    bodyMedium = Base.bodyMedium.body(),
    bodySmall = Base.bodySmall.body(),
)

/**
 * Minimal app theme: dark color scheme and fonts built from the design tokens.
 * Screens reach for the tokens (Blood, Amber, Display, Body, ...) directly; this wrapper
 * just makes untouched composables (a bare Text) readable on the Ink background.
 */
@Composable
fun HorrorPetsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HorrorColors, typography = HorrorTypography) {
        CompositionLocalProvider(LocalContentColor provides TextHi, content = content)
    }
}
