package team.superfun.horrorpets.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Ink

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
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        BasicText(label, style = displayStyle(13.sp, color = Ink, tracking = 0.06f))
    }
}
