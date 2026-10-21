package team.superfun.horrorpets.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import team.superfun.horrorpets.game.StatusTag
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi

/**
 * A pet's headline status as a small solid tag: CONTENT grey, BORED/HUNGRY Amber, STARVING/GRUMPY Blood.
 * Shared by the stat card and the pet switcher.
 */
@Composable
fun StatusChip(tag: StatusTag, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tag) {
        StatusTag.CONTENT -> Color.White.copy(alpha = 0.16f) to TextHi
        StatusTag.BORED, StatusTag.HUNGRY -> Amber to Ink
        StatusTag.STARVING, StatusTag.GRUMPY -> Blood to TextHi
    }
    Box(
        modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Text(text = tag.name, style = displayStyle(17.sp, color = fg, tracking = 0.08f, lineHeight = 22.sp))
    }
}
