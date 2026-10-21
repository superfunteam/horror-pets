package team.superfun.horrorpets.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AnimSpec
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.frameAt
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.model.PetId

/** Contact-shadow ellipse: width as a fraction of the drawn cell width, height as a fraction of its width. */
private const val SHADOW_WIDTH_OF_CELL = 0.45f
private const val SHADOW_ASPECT = 0.2f

/**
 * Draws one pet animation, one cell of its strip at a time, with bilinear filtering.
 *
 * **Placement:** the composable's bottom-centre is the pet's feet (the manifest's `baselineY` and
 * `anchorX`), so a caller plants a pet by putting that point on a floor line. The composable is
 * `cellWidth x baselineY` scaled to [heightPx] (the drawn height of the whole cell); the strip's few rows
 * below the feet and the contact shadow draw just outside its bounds.
 *
 * **Playback** restarts whenever [pet], [anim] or [playKey] changes; pass the view model's request token
 * as [playKey] so the same animation requested twice plays twice. [onFinished] is called once: when a
 * one-shot ends (its last frame then stays up), or after one full cycle of a looping animation (which
 * keeps looping). The previous animation stays on screen until the new strip has been decoded off the
 * main thread, so switching never flashes an empty frame.
 *
 * **Look:** [tint] is the scene's ambient color, blended in by [tintStrength] (0..1) to seat the
 * studio-lit sprite in a night scene; [dim] darkens (0..1) and [flashWhite] washes it toward white (0..1,
 * for hit flashes). [shadowOpacity] > 0 draws a soft contact shadow under the feet.
 */
@Composable
fun SpriteView(
    loader: AssetLoader,
    pet: PetId,
    anim: Anim,
    heightPx: Float,
    tint: Color?,
    tintStrength: Float,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    flashWhite: Float = 0f,
    shadowOpacity: Float = 0f,
    playKey: Any? = null,
    onFinished: (() -> Unit)? = null,
) {
    val manifest = remember(loader, pet) { loader.manifest(pet) }
    val cellW = manifest.cell[0]
    val cellH = manifest.cell[1]
    val scale = heightPx / cellH

    // What is on screen: the strip being drawn and its current frame. Both change together on the main
    // thread, so a frame index never meets the wrong strip.
    var shown by remember(pet) { mutableStateOf<Shown?>(null) }
    var frame by remember(pet) { mutableIntStateOf(0) }
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(loader, pet, anim, playKey) {
        val spec = manifest.spec(anim)
        val strip = withContext(Dispatchers.IO) { loader.strip(pet, anim) }
        shown = Shown(strip, spec)
        frame = 0
        val total = spec.durationsMs.sumOf { it.coerceAtLeast(0).toLong() }
        var start = -1L
        var reported = false
        while (true) {
            val now = withFrameMillis { it }
            if (start < 0) start = now
            val elapsed = now - start
            val index = frameAt(spec, elapsed)
            if (index == null) {
                frame = frameAt(spec, total - 1) ?: 0 // hold the last frame of a finished one-shot
                if (!reported) finished?.invoke()
                break
            }
            frame = index
            if (spec.loop && !reported && elapsed >= total) {
                reported = true
                finished?.invoke()
            }
        }
    }

    val filter = remember(tint, tintStrength, dim, flashWhite) { spriteFilter(tint, tintStrength, dim, flashWhite) }
    val density = LocalDensity.current
    val boxW = cellW * scale
    val boxH = manifest.baselineY * scale
    val sizeModifier = with(density) { Modifier.size(boxW.toDp(), boxH.toDp()) }

    Spacer(
        modifier
            .then(sizeModifier)
            .drawBehind {
                val feet = Offset(size.width / 2f, size.height)
                if (shadowOpacity > 0f) {
                    val w = cellW * scale * SHADOW_WIDTH_OF_CELL
                    val r = w / 2f
                    scale(scaleX = 1f, scaleY = SHADOW_ASPECT, pivot = feet) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to Color.Black.copy(alpha = shadowOpacity),
                                0.45f to Color.Black.copy(alpha = shadowOpacity * 0.7f),
                                1f to Color.Transparent,
                                center = feet,
                                radius = r,
                            ),
                            radius = r,
                            center = feet,
                        )
                    }
                }
                val s = shown ?: return@drawBehind
                val index = frame.coerceIn(0, (s.spec.frames - 1).coerceAtLeast(0))
                val left = feet.x - manifest.anchorX * scale
                val top = feet.y - manifest.baselineY * scale
                drawImage(
                    image = s.strip,
                    srcOffset = IntOffset(index * cellW, 0),
                    srcSize = IntSize(cellW, cellH),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((cellW * scale).roundToInt(), (cellH * scale).roundToInt()),
                    colorFilter = filter,
                    filterQuality = FilterQuality.High,
                )
            },
    )
}

@Immutable
private class Shown(val strip: ImageBitmap, val spec: AnimSpec)

/**
 * One color matrix for all three looks: `rgb' = ((rgb * (1-s) + tint * s) * (1-dim)) * (1-flash) + white * flash`.
 * Offsets are in 0..255 like `android.graphics.ColorMatrix`; alpha passes through untouched, so the
 * transparent cell around the pet stays transparent.
 */
private fun spriteFilter(tint: Color?, tintStrength: Float, dim: Float, flashWhite: Float): ColorFilter? {
    val s = if (tint == null) 0f else tintStrength.coerceIn(0f, 1f)
    val d = dim.coerceIn(0f, 1f)
    val f = flashWhite.coerceIn(0f, 1f)
    if (s == 0f && d == 0f && f == 0f) return null
    val gain = (1f - s) * (1f - d) * (1f - f)
    fun offset(channel: Float) = (channel * 255f * s * (1f - d)) * (1f - f) + 255f * f
    val t = tint ?: Color.Black
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                gain, 0f, 0f, 0f, offset(t.red),
                0f, gain, 0f, 0f, offset(t.green),
                0f, 0f, gain, 0f, offset(t.blue),
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}
