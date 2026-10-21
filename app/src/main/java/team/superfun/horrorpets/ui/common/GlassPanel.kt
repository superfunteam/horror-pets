package team.superfun.horrorpets.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Glass

/** Corner radius shared by every glass surface. */
val GlassCorner: Dp = 16.dp

/** The 2dp rim of a glass surface: brighter along the top edge, like light catching a pane. */
private val GlassRim = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.16f),
    0.5f to Color.White.copy(alpha = 0.08f),
    1f to Color.White.copy(alpha = 0.06f),
)

/** Body fill: the `Glass` token, a touch denser at the bottom so text over a bright sky still reads. */
private val GlassFill = Brush.verticalGradient(
    0f to Glass.copy(alpha = 0.62f),
    1f to Glass.copy(alpha = 0.74f),
)

/**
 * A frosted-glass surface over the scene: translucent dark fill, a faint 2dp light rim and a soft drop
 * shadow (Compose has no backdrop blur before API 31, so the depth comes from the shadow and rim).
 *
 * [glow] (0..1) adds a BloodGlow rim and halo, for a panel that takes focus (the stat card).
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    glow: Float = 0f,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(GlassCorner)
    Box(
        modifier
            .drawBehind {
                val r = GlassCorner.toPx()
                // The drop shadow also darkens the glass a little, which keeps text readable over bright skies.
                softGlow(Color.Black, alpha = 0.55f, cornerRadius = r, spread = 16.dp, offsetY = 6.dp.toPx())
                if (glow > 0f) {
                    // The focus halo only shows outside the panel; under the glass it would tint it pink.
                    val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
                    clipPath(outline, ClipOp.Difference) {
                        softGlow(BloodGlow, alpha = 0.7f * glow, cornerRadius = r, spread = 20.dp)
                    }
                }
                drawRoundRect(brush = GlassFill, cornerRadius = CornerRadius(r))
            }
            .border(2.dp, GlassRim, shape)
            .then(if (glow > 0f) Modifier.border(2.dp, BloodGlow.copy(alpha = glow), shape) else Modifier)
            .padding(contentPadding),
    ) {
        content()
    }
}
