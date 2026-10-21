package team.superfun.horrorpets.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density

/** The height, in dp, of the canvas every screen is laid out on, whatever the TV's resolution or dpi. */
const val DESIGN_HEIGHT_DP = 540f

/**
 * The Compose density that makes a window [heightPx] tall exactly [DESIGN_HEIGHT_DP] dp tall, or null when
 * the height is not usable (zero or negative, before the window has been measured).
 *
 * This is a no-op for the verified configurations: 1920x1080 at 320 dpi and 1440x1080 at 320 dpi (2.0),
 * 1280x720 at 213 dpi (1.333), and 720x540 at 160 dpi (1.0).
 */
fun designDensityFor(heightPx: Int): Float? =
    if (heightPx > 0) heightPx / DESIGN_HEIGHT_DP else null

/**
 * Lays everything inside [content] out on a [DESIGN_HEIGHT_DP]-dp-tall canvas, whatever the screen's real
 * density, by replacing [LocalDensity] with `windowHeightPx / 540` (the system font scale is kept).
 *
 * Why: the UI is designed in dp and sp against a 1080p, 320 dpi screen (540 dp tall), but a TV stick's
 * reported dpi has little to do with how big its picture looks. A 4:3 CRT fed 1280x960 at 213 dpi is 720 dp
 * tall, so every dp is a smaller slice of the screen than on the design screen and the small text becomes
 * unreadable at CRT distances. This is the usual TV-game approach: size to the screen's height, not its dpi.
 * It sits once at the root, so every screen, and every `dp.toPx()` in the code, agrees with it. When the
 * window height is not known yet it leaves the device density alone.
 */
@Composable
fun ProvideScreenDensity(content: @Composable () -> Unit) {
    val device = LocalDensity.current
    val density = designDensityFor(LocalWindowInfo.current.containerSize.height)
        ?.let { Density(density = it, fontScale = device.fontScale) }
        ?: device
    CompositionLocalProvider(LocalDensity provides density, content = content)
}
