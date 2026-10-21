package team.superfun.horrorpets.assets

import kotlin.math.max

/** How a scene image is drawn into a view: scaled by [scale], top-left at ([offsetX], [offsetY]) in view pixels. */
data class Crop(val scale: Float, val offsetX: Float, val offsetY: Float)

/** The floor line may sit at most this far down the view; below it is the 5% bottom safe inset and then some. */
const val FLOOR_MAX_VIEW_FRACTION = 0.90f

/** Sub-pixel slack so float rounding can never leave the floor a hair below [FLOOR_MAX_VIEW_FRACTION]. */
private const val FLOOR_EPSILON_PX = 0.01f

/** Relative extra zoom for the same reason, when the floor rule has to be met by zooming. */
private const val ZOOM_EPSILON = 1.0001f

/**
 * "Cover" fit: the image is scaled by `max(viewW/imgW, viewH/imgH)` so it fills the view, then centered
 * on both axes (offsets are negative on the axis where it overflows).
 *
 * When [floorY] (the floor line as a fraction of the full image height) is given, the crop is also
 * adjusted so [floorYOnScreen] is at most [FLOOR_MAX_VIEW_FRACTION] of [viewH], which keeps the pet out of the
 * bottom safe inset. The image always keeps covering the whole view (no gap at any edge), so:
 *  1. if the centered crop already satisfies the rule, nothing changes;
 *  2. otherwise the image is pushed up (offsetY decreases) as far as the vertical overflow allows;
 *  3. if the overflow is not enough, the image is also zoomed in just enough, bottom edge flush with
 *     the view, so that both "covers" and "floor <= 0.90*viewH" hold. [Crop.scale] then exceeds the plain
 *     cover scale, so callers must draw with the returned scale rather than recompute it.
 * A floorY of 1 or more (at or past the bottom edge, or NaN) can never be lifted that way and is ignored.
 */
fun coverCrop(imgW: Int, imgH: Int, viewW: Int, viewH: Int, floorY: Float? = null): Crop {
    var scale = max(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
    var offsetY = (viewH - imgH * scale) / 2f

    if (floorY != null && floorY < 1f) {
        val maxFloor = FLOOR_MAX_VIEW_FRACTION * viewH
        val excess = offsetY + floorY * imgH * scale - maxFloor
        if (excess > 0f) {
            val highestOffsetY = viewH - imgH * scale // pushing further would uncover the bottom edge
            val pushed = offsetY - excess - FLOOR_EPSILON_PX
            if (pushed >= highestOffsetY) {
                offsetY = pushed
            } else {
                // Flush bottom puts the floor at viewH - imgH*scale*(1 - floorY); solve for the scale
                // that makes that exactly maxFloor.
                scale = max(scale, (viewH - maxFloor) / (imgH * (1f - floorY)) * ZOOM_EPSILON)
                offsetY = viewH - imgH * scale
            }
        }
    }
    return Crop(scale = scale, offsetX = (viewW - imgW * scale) / 2f, offsetY = offsetY)
}

/** Where the floor line lands in view pixels. [floorY] is a fraction of the full image height [imgH]. */
fun floorYOnScreen(c: Crop, imgH: Int, floorY: Float): Float = c.offsetY + floorY * imgH * c.scale
