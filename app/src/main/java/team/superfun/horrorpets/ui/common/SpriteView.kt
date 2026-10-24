package team.superfun.horrorpets.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AnimSpec
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.assets.frameAt
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.model.PetId

/** Contact-shadow ellipse: width as a fraction of the drawn cell width, height as a fraction of its width. */
private const val SHADOW_WIDTH_OF_CELL = 0.45f
private const val SHADOW_ASPECT = 0.2f

/** How much a full [SpritePose.squash] widens and flattens the pet on its feet. */
private const val SQUASH_X = 0.14f
private const val SQUASH_Y = 0.12f

/**
 * Extra motion for a [SpriteView], read while drawing, so it never recomposes: [lift] raises the pet off the floor
 * (px, up), [shiftX] slides it (px), [swing] turns it (degrees) about the top of its cell, as if held up by the
 * head, and [squash] (0..1) flattens it onto its feet. Its shadow stays on the floor, following only the slide and
 * shrinking as the pet rises.
 */
@Stable
class SpritePose {
    val lift = Animatable(0f)
    val shiftX = Animatable(0f)
    val swing = Animatable(0f)
    val squash = Animatable(0f)

    /** Back to standing still on the floor. */
    suspend fun reset() {
        lift.snapTo(0f)
        shiftX.snapTo(0f)
        swing.snapTo(0f)
        squash.snapTo(0f)
    }
}

/**
 * Draws one pet animation, one cell of its strip at a time, with bilinear filtering.
 *
 * **Placement:** the composable's bottom-centre is the pet's feet (the manifest's `baselineY` and
 * `anchorX`), so a caller plants a pet by putting that point on a floor line. The composable is
 * `cellWidth x baselineY` scaled to [heightPx] (the height of a full-size cell); the strip's few rows
 * below the feet and the contact shadow draw just outside its bounds.
 *
 * **Display scale:** a pet whose manifest has a `displayScale` below 1 (Chucky, 0.5) is drawn that much
 * smaller inside the same bounds, its feet on the same spot, and its contact shadow shrinks with it. The
 * bounds stay full-size, so every caller's placement is unchanged; a caller that anchors something to
 * the body itself (a number over the head, an effect on the chest) scales by
 * [bodyScale][team.superfun.horrorpets.assets.bodyScale] too.
 *
 * **Playback** restarts whenever [pet], [anim] or [playKey] changes; pass the view model's request token
 * as [playKey] so the same animation requested twice plays twice. [onStarted] is called when its first
 * frame goes up (after the strip is decoded, so it can lag the request), to time things against the
 * animation itself. [onFinished] is called once: when a
 * one-shot ends (its last frame then stays up), or after one full cycle of a looping animation (which
 * keeps looping). The previous animation stays on screen until the new strip has been decoded off the
 * main thread, so switching never flashes an empty frame; a sprite whose strip is already decoded shows
 * its first frame as soon as it is composed.
 *
 * **Look:** [tint] is the scene's ambient color, blended in by [tintStrength] (0..1) to seat the
 * studio-lit sprite in a night scene; [dim] darkens (0..1), [saturation] drains the color (1 is full color,
 * 0 is greyscale) and [flashWhite] washes it toward white (0..1, for hit flashes). [shadowOpacity] > 0
 * draws a soft contact shadow under the feet.
 *
 * [pose], when given, moves the drawn pet (and not its layout) as a [SpritePose] describes.
 *
 * [loopOffsetMs] starts a looping animation that far into its cycle, so several pets idling side by side
 * don't bob in lockstep. It does not move the "one full cycle" report, and one-shots ignore it.
 *
 * [timeScale] is how fast the animation's clock runs, read every frame (never in composition): at 1 it plays as
 * timed, at 0 its frame holds, at 0.3 it crawls (a fight's hit-stop and knockout). A one-shot's end and a loop's full
 * cycle come in that time too.
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
    pose: SpritePose? = null,
    loopOffsetMs: Long = 0L,
    timeScale: () -> Float = { 1f },
    playKey: Any? = null,
    onStarted: (() -> Unit)? = null,
    onFinished: (() -> Unit)? = null,
) {
    val manifest = remember(loader, pet) { loader.manifest(pet) }
    val cellW = manifest.cell[0]
    val cellH = manifest.cell[1]
    val scale = heightPx / cellH
    // The pet's own size inside the full-size bounds.
    val drawScale = scale * manifest.bodyScale

    // What is on screen: the strip being drawn and its current frame. Both change together on the main
    // thread, so a frame index never meets the wrong strip. A strip already decoded is up from the very
    // first frame (a pet sliding into the switcher whose strip was prefetched draws at once).
    var shown by remember(pet) { mutableStateOf(loader.readyStrip(pet, anim)?.let { Shown(it, manifest.spec(anim)) }) }
    var frame by remember(pet) { mutableIntStateOf(0) }
    val started by rememberUpdatedState(onStarted)
    val finished by rememberUpdatedState(onFinished)
    val speed by rememberUpdatedState(timeScale)

    LaunchedEffect(loader, pet, anim, playKey) {
        val spec = manifest.spec(anim)
        val strip = withContext(Dispatchers.IO) { loader.strip(pet, anim) }
        shown = Shown(strip, spec)
        frame = 0
        started?.invoke()
        val total = spec.durationsMs.sumOf { it.coerceAtLeast(0).toLong() }
        val clock = ScaledClock()
        var last = -1L
        var reported = false
        val offset = if (spec.loop) loopOffsetMs.coerceAtLeast(0L) else 0L
        while (true) {
            val now = withFrameMillis { it }
            val elapsed = clock.advance(if (last < 0) 0L else now - last, speed())
            last = now
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
                val shadowRadius = cellW * drawScale * SHADOW_WIDTH_OF_CELL / 2f
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
                val left = (feet.x - manifest.anchorX * drawScale).roundToInt()
                val top = (feet.y - manifest.baselineY * drawScale).roundToInt()
                val dstSize = IntSize((cellW * drawScale).roundToInt(), (cellH * drawScale).roundToInt())

                onDrawBehind {
                    // Nothing, not even the shadow, until the strip is decoded: a lone shadow would
                    // flash on the floor before the pet appears.
                    val s = shown ?: return@onDrawBehind
                    val lift = pose?.lift?.value ?: 0f
                    val shift = pose?.shiftX?.value ?: 0f
                    val swing = pose?.swing?.value ?: 0f
                    val squash = pose?.squash?.value ?: 0f
                    if (shadowBrush != null) {
                        val fade = (1f - lift / size.height).coerceIn(0.25f, 1f)
                        translate(left = shift) {
                            scale(scaleX = fade, scaleY = SHADOW_ASPECT * fade, pivot = feet) {
                                drawCircle(brush = shadowBrush, radius = shadowRadius, center = feet, alpha = fade)
                            }
                        }
                    }
                    val index = frame.coerceIn(0, (s.spec.frames - 1).coerceAtLeast(0))
                    withTransform({
                        translate(shift, -lift)
                        if (swing != 0f) rotate(swing, pivot = Offset(feet.x, top.toFloat()))
                        if (squash != 0f) scale(1f + SQUASH_X * squash, 1f - SQUASH_Y * squash, pivot = feet)
                    }) {
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
internal fun spriteFilter(tint: Color?, tintStrength: Float, dim: Float, saturation: Float, flashWhite: Float): ColorFilter? {
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
