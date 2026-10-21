package team.superfun.horrorpets.ui.common

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate

/**
 * The nested trapezoids of a soft-edged light beam from a lamp at [top] down to [bottom] (pixels),
 * centred on x = 0 so one set serves every beam of that shape (see [drawBeam]). Each layer is narrower
 * than the last, down to [core] of the full width, so where they stack the beam is bright and its edges
 * fade out instead of ending in a hard line. Build them once (in `drawWithCache`), not per frame.
 */
fun beamLayers(top: Float, bottom: Float, topHalf: Float, bottomHalf: Float, layers: Int = 12, core: Float = 0.25f): List<Path> =
    List(layers) { layer ->
        val k = 1f - layer * (1f - core) / (layers - 1).coerceAtLeast(1)
        Path().apply {
            moveTo(-topHalf * k, top)
            lineTo(topHalf * k, top)
            lineTo(bottomHalf * k, bottom)
            lineTo(-bottomHalf * k, bottom)
            close()
        }
    }

/**
 * Draws a beam built by [beamLayers] centred on [cx], filled with [brush] (usually a vertical gradient
 * that fades toward the floor). [strength] is roughly the alpha of the beam's core.
 */
fun DrawScope.drawBeam(layers: List<Path>, cx: Float, brush: Brush, strength: Float) {
    if (strength <= 0.005f || layers.isEmpty()) return
    val alpha = strength / layers.size * 2f
    translate(left = cx) {
        for (layer in layers) drawPath(layer, brush, alpha = alpha)
    }
}
