package team.superfun.horrorpets.ui.fight

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow

/** How long a super's cut-in plays before its swing. */
internal const val SUPER_MS = 1_150

/** A tier 3 special's cut-in: [pet]'s head and the special's [name], sweeping in from its side ([fromLeft]). */
@Immutable
internal class SuperFx(val pet: PetId, val name: String, val fromLeft: Boolean)

private val NameFill = Brush.verticalGradient(listOf(Color(0xFFFFF0C8), Color(0xFFFFB347), BloodGlow, Blood))

/**
 * The super cut-in, over everything, as [progress] runs 0..1 over [SUPER_MS]: the arena drops to black, a blood-red
 * band slashes across it from the attacker's side, the attacker's head slides in big, its special's name slams down
 * across the band, and it all holds a beat and clears. Everything moves in the layer; nothing recomposes per frame.
 */
@Composable
internal fun SuperCutIn(fx: SuperFx?, progress: () -> Float, loader: AssetLoader) {
    if (fx == null) return
    val head = remember(loader, fx.pet) { loader.head(fx.pet) }
    val dir = if (fx.fromLeft) 1f else -1f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        // How visible it all is: in fast, out over the last 15%.
        fun shown(p: Float) = (p / 0.12f).coerceIn(0f, 1f) * (1f - ((p - 0.85f) / 0.15f).coerceIn(0f, 1f))
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val p = progress()
                    if (p <= 0f || p >= 1f) return@drawBehind
                    val a = shown(p)
                    drawRect(Color.Black, alpha = 0.82f * a)
                    // The band: a slanted strip across the middle, sweeping in from the attacker's side.
                    val sweep = (p / 0.22f).coerceIn(0f, 1f)
                    val reach = w * sweep
                    val top = h * 0.38f
                    val bottom = h * 0.66f
                    val slant = h * 0.12f
                    val band = Path().apply {
                        if (fx.fromLeft) {
                            moveTo(0f, top + slant)
                            lineTo(reach, top)
                            lineTo(reach, bottom - slant)
                            lineTo(0f, bottom)
                        } else {
                            moveTo(w, top + slant)
                            lineTo(w - reach, top)
                            lineTo(w - reach, bottom - slant)
                            lineTo(w, bottom)
                        }
                        close()
                    }
                    drawPath(band, Brush.verticalGradient(listOf(Blood, Color(0xFF5A0610)), startY = top, endY = bottom), alpha = a)
                    drawPath(band, BloodGlow.copy(alpha = 0.6f * a), style = Stroke(3.dp.toPx()))
                    // Speed lines streaking along the band.
                    for (i in 0 until 7) {
                        val y = lerp(top + slant * 0.5f, bottom - slant * 0.5f, i / 6f)
                        val x0 = if (fx.fromLeft) reach * (0.05f + 0.1f * i) else w - reach * (0.05f + 0.1f * i)
                        drawLine(Color.White.copy(alpha = 0.18f * a), Offset(x0, y), Offset(x0 + dir * w * 0.25f, y), strokeWidth = 2.dp.toPx())
                    }
                },
        )
        // The head, big, sliding in from the attacker's side to a third of the way across.
        val headSize = maxHeight * 0.62f
        Image(
            head,
            contentDescription = null,
            modifier = Modifier
                .size(headSize)
                .align(if (fx.fromLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .graphicsLayer {
                    val p = progress()
                    val a = shown(p)
                    val slide = 1f - ((p - 0.06f) / 0.22f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
                    translationX = dir * (w * 0.06f - slide * w * 0.5f) + dir * w * 0.03f * ((p - 0.3f).coerceIn(0f, 0.55f))
                    alpha = a
                    val s = 1f + 0.06f * (p - 0.3f).coerceIn(0f, 0.55f)
                    scaleX = s
                    scaleY = s
                },
        )
        // The name, slamming down across the band on the far side.
        val nameSize = (maxWidth.value * 0.55f / (fx.name.length * 0.52f)).coerceIn(34f, 84f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress()
                    val land = ((p - 0.24f) / 0.12f).coerceIn(0f, 1f)
                    alpha = shown(p) * (if (land > 0f) 1f else 0f)
                    val s = lerp(1.9f, 1f, land * land)
                    scaleX = s
                    scaleY = s
                    translationX = dir * w * 0.14f
                },
            contentAlignment = Alignment.Center,
        ) {
            OutlinedText(
                fx.name.uppercase() + "!",
                displayStyle(nameSize.sp, tracking = 0.04f, lineHeight = nameSize.sp).copy(brush = NameFill),
                outline = 6.dp,
            )
        }
    }
}
