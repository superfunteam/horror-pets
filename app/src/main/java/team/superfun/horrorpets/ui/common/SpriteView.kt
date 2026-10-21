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
import androidx.compose.ui.draw.drawWithCache
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
 * as [playKey] so the same animation requested twice plays twice. [onStarted] is called when its first
 * frame goes up (after the strip is decoded, so it can lag the request), to time things against the
 * animation itself. [onFinished] is called once: when a
 * one-shot ends (its last frame then stays up), or after one full cycle of a looping animation (which
 * keeps looping). The previous animation stays on screen until the new strip has been decoded off the
 * main thread, so switching never flashes an empty frame.
 *
 * **Look:** [tint] is the scene's ambient color, blended in by [tintStrength] (0..1) to seat the
 * studio-lit sprite in a night scene; [dim] darkens (0..1), [saturation] drains the color (1 is full color,
 * 0 is greyscale) and [flashWhite] washes it toward white (0..1, for hit flashes). [shadowOpacity] > 0
 * draws a soft contact shadow under the feet.
 *
 * [loopOffsetMs] starts a looping animation that far into its cycle, so several pets idling side by side
 * don't bob in lockstep. It does not move the "one full cycle" report, and one-shots ignore it.
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
    saturation: Float = 1f,
    flashWhite: Float = 0f,
    shadowOpacity: Float = 0f,
    loopOffsetMs: Long = 0L,
    playKey: Any? = null,
    onStarted: (() -> Unit)? = null,
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
    val started by rememberUpdatedState(onStarted)
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(loader, pet, anim, playKey) {
        val spec = manifest.spec(anim)
        val strip = withContext(Dispatchers.IO) { loader.strip(pet, anim) }
        shown = Shown(strip, spec)
        frame = 0
        started?.invoke()
        val total = spec.durationsMs.sumOf { it.coerceAtLeast(0).toLong() }
        var start = -1L
        var reported = false
        val offset = if (spec.loop) loopOffsetMs.coerceAtLeast(0L) else 0L
        while (true) {
            val now = withFrameMillis { it }
            if (start < 0) start = now
            val elapsed = now - start
            val index = frameAt(spec, elapsed + offset)
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

    val filter = remember(tint, tintStrength, dim, saturation, flashWhite) {
        spriteFilter(tint, tintStrength, dim, saturation, flashWhite)
    }
    val density = LocalDensity.current
    val boxW = cellW * scale
    val boxH = manifest.baselineY * scale
    val sizeModifier = with(density) { Modifier.size(boxW.toDp(), boxH.toDp()) }

    Spacer(
        modifier
            .then(sizeModifier)
            .drawWithCache {
                // Everything here depends only on the size and the parameters, so the shadow's gradient
                // is built once per change instead of on every frame of the animation.
                val feet = Offset(size.width / 2f, size.height)
                val shadowRadius = cellW * scale * SHADOW_WIDTH_OF_CELL / 2f
                val shadowBrush = if (shadowOpacity > 0f) {
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = shadowOpacity),
                        0.45f to Color.Black.copy(alpha = shadowOpacity * 0.7f),
                        1f to Color.Transparent,
                        center = feet,
                        radius = shadowRadius,
                    )
                } else {
                    null
                }
                val left = (feet.x - manifest.anchorX * scale).roundToInt()
                val top = (feet.y - manifest.baselineY * scale).roundToInt()
                val dstSize = IntSize((cellW * scale).roundToInt(), (cellH * scale).roundToInt())

                onDrawBehind {
                    // Nothing, not even the shadow, until the strip is decoded: a lone shadow would
                    // flash on the floor before the pet appears.
                    val s = shown ?: return@onDrawBehind
                    if (shadowBrush != null) {
                        scale(scaleX = 1f, scaleY = SHADOW_ASPECT, pivot = feet) {
                            drawCircle(brush = shadowBrush, radius = shadowRadius, center = feet)
                        }
                    }
                    val index = frame.coerceIn(0, (s.spec.frames - 1).coerceAtLeast(0))
                    drawImage(
                        image = s.strip,
                        srcOffset = IntOffset(index * cellW, 0),
                        srcSize = IntSize(cellW, cellH),
                        dstOffset = IntOffset(left, top),
                        dstSize = dstSize,
                        colorFilter = filter,
                        filterQuality = FilterQuality.High,
                    )
                }
            },
    )
}

@Immutable
private class Shown(val strip: ImageBitmap, val spec: AnimSpec)

/** Rec. 709 luma weights (the ones `android.graphics.ColorMatrix.setSaturation` uses). */
private const val LUMA_R = 0.213f
private const val LUMA_G = 0.715f
private const val LUMA_B = 0.072f

/**
 * One color matrix for every look: `rgb' = ((sat(rgb) * (1-s) + tint * s) * (1-dim)) * (1-flash) + white * flash`,
 * where `sat` blends each pixel toward its own grey by `1 - saturation`. Offsets are in 0..255 like
 * `android.graphics.ColorMatrix`; alpha passes through untouched, so the transparent cell around the pet
 * stays transparent.
 */
private fun spriteFilter(tint: Color?, tintStrength: Float, dim: Float, saturation: Float, flashWhite: Float): ColorFilter? {
    val s = if (tint == null) 0f else tintStrength.coerceIn(0f, 1f)
    val d = dim.coerceIn(0f, 1f)
    val sat = saturation.coerceIn(0f, 1f)
    val f = flashWhite.coerceIn(0f, 1f)
    if (s == 0f && d == 0f && sat == 1f && f == 0f) return null
    val gain = (1f - s) * (1f - d) * (1f - f)
    fun offset(channel: Float) = (channel * 255f * s * (1f - d)) * (1f - f) + 255f * f
    // Row i of the saturation matrix is luma * (1 - sat), plus sat on the diagonal.
    val grey = 1f - sat
    val r = LUMA_R * grey * gain
    val g = LUMA_G * grey * gain
    val b = LUMA_B * grey * gain
    val keep = sat * gain
    val t = tint ?: Color.Black
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                r + keep, g, b, 0f, offset(t.red),
                r, g + keep, b, 0f, offset(t.green),
                r, g, b + keep, 0f, offset(t.blue),
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}
