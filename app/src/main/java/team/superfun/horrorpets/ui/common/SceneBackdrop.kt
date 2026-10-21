package team.superfun.horrorpets.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.Crop
import team.superfun.horrorpets.assets.SceneSpec
import team.superfun.horrorpets.assets.coverCrop
import team.superfun.horrorpets.assets.floorYOnScreen
import team.superfun.horrorpets.ui.theme.Ink

private const val SCENE_FADE_MS = 450

/**
 * A full-bleed scene: the backdrop scaled to cover the screen (cropping whichever axis overflows, with
 * the floor kept above the bottom safe inset by [coverCrop]) under a soft cinematic vignette.
 *
 * The image is decoded off the main thread. Until the first one is ready the screen is plain [Ink];
 * when [scene] changes, the old image stays up until the new one is decoded and then cross-fades into it.
 *
 * [content] is drawn over the scene and receives the floor line in pixels from the top of this box
 * (where a pet's feet go). It is only composed once a scene is on screen, because the floor depends on
 * the image's size; keep HUD that must exist from the first frame outside this composable.
 */
@Composable
fun SceneBackdrop(
    loader: AssetLoader,
    scene: SceneSpec,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(floorY: Float) -> Unit,
) {
    var current by remember { mutableStateOf<LoadedScene?>(null) }
    var previous by remember { mutableStateOf<LoadedScene?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(loader, scene) {
        val bitmap = withContext(Dispatchers.IO) { loader.sceneBitmap(scene) }
        previous = current
        current = LoadedScene(scene, bitmap)
        fade.snapTo(0f)
        fade.animateTo(1f, tween(SCENE_FADE_MS, easing = LinearOutSlowInEasing))
        previous = null
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Ink)) {
        val viewW = constraints.maxWidth
        val viewH = constraints.maxHeight
        val shownNow = current
        val crop = remember(shownNow, viewW, viewH) {
            shownNow?.let { coverCrop(it.bitmap.width, it.bitmap.height, viewW, viewH, it.spec.floorY) }
        }
        val prevNow = previous
        val prevCrop = remember(prevNow, viewW, viewH) {
            prevNow?.let { coverCrop(it.bitmap.width, it.bitmap.height, viewW, viewH, it.spec.floorY) }
        }

        Canvas(Modifier.fillMaxSize()) {
            if (prevNow != null && prevCrop != null) drawScene(prevNow.bitmap, prevCrop, 1f)
            if (shownNow != null && crop != null) drawScene(shownNow.bitmap, crop, fade.value)
            drawVignette()
        }

        if (shownNow != null && crop != null) {
            content(floorYOnScreen(crop, shownNow.bitmap.height, shownNow.spec.floorY))
        }
    }
}

@Immutable
private class LoadedScene(val spec: SceneSpec, val bitmap: ImageBitmap)

private fun DrawScope.drawScene(image: ImageBitmap, crop: Crop, alpha: Float) {
    if (alpha <= 0f) return
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(crop.offsetX.roundToInt(), crop.offsetY.roundToInt()),
        // Round up so float rounding can never leave a 1 px gap at the right or bottom edge.
        dstSize = IntSize(
            kotlin.math.ceil(image.width * crop.scale).toInt() + 1,
            kotlin.math.ceil(image.height * crop.scale).toInt() + 1,
        ),
        alpha = alpha,
        filterQuality = FilterQuality.High,
    )
}

/**
 * Darkens the edges like a lens, plus a little more at the top and bottom where the HUD sits, so white
 * UI stays readable over bright skies and floors. The centre (where the pet stands) is left alone.
 */
private fun DrawScope.drawVignette() {
    val w = size.width
    val h = size.height
    drawRect(
        brush = Brush.radialGradient(
            0.55f to Color.Transparent,
            0.85f to Color.Black.copy(alpha = 0.28f),
            1f to Color.Black.copy(alpha = 0.55f),
            center = Offset(w / 2f, h * 0.48f),
            radius = max(w, h) * 0.72f,
        ),
    )
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.50f),
            0.28f to Color.Transparent,
            0.72f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.62f),
        ),
    )
}
