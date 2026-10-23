package team.superfun.horrorpets.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Ink

/** A key cap's label: dark display letters, 13 sp. */
private val KeyCapLabel = displayStyle(13.sp, color = Ink, tracking = 0.06f)

/** A key cap's padding: either side of its label, and above and below it. */
private val KeyCapPadX = 7.dp
private val KeyCapPadY = 2.dp

/** A key's name in dark letters on a small gold cap with clipped corners, like the game's frames. */
@Composable
fun KeyCap(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .drawBehind {
                val cut = 5.dp.toPx()
                drawPath(
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width - cut, 0f)
                        lineTo(size.width, cut)
                        lineTo(size.width, size.height)
                        lineTo(cut, size.height)
                        lineTo(0f, size.height - cut)
                        close()
                    },
                    Amber,
                )
            }
            .padding(horizontal = KeyCapPadX, vertical = KeyCapPadY),
    ) {
        BasicText(label, style = KeyCapLabel)
    }
}

/**
 * How tall a [KeyCap] stands: its label's line and the padding above and below it, for a cap drawn to match one (the
 * training HUD's arrow keys).
 */
@Composable
fun keyCapHeight(): Dp {
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val density = LocalDensity.current
    return remember(measurer, density) {
        with(density) { measurer.measure("OK", KeyCapLabel).size.height.toDp() } + KeyCapPadY * 2
    }
}
