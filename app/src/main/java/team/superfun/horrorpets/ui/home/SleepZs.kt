package team.superfun.horrorpets.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.ui.common.displayStyle

/** A new Z rises from a sleeping killer's head this often. */
private const val Z_EVERY_MS = 1_600L

/** Each Z takes this long to rise and fade away. */
private const val Z_LIFE_MS = 2_000L

/** Never more than this many Zs up at once. */
private const val Z_MOST = 3

/** A Z fades in over this share of its rise, so it never pops on, and fades out over the rest. */
private const val Z_FADE_IN = 0.15f

/** How far a Z rises, and drifts right, from the head over its life. */
private val ZRise = 40.dp
private val ZDrift = 8.dp

/** The Zs are the HUD's display font, with its halo so they read over a dark scene and a bright one. */
private val ZStyle = displayStyle(26.sp, halo = true)

/**
 * How far along its rise (0 just out, toward 1 gone) each Z is [elapsedMs] after the killer dropped off, oldest first:
 * one every [everyMs] from the start, each up for [Z_LIFE_MS], and only the newest [Z_MOST] of them. Empty before the
 * start.
 */
internal fun sleepZsAt(elapsedMs: Long, everyMs: Long = Z_EVERY_MS): List<Float> {
    if (elapsedMs < 0L) return emptyList()
    val newest = elapsedMs / everyMs
    val oldest = maxOf(0L, newest - Z_MOST + 1)
    return (oldest..newest).map { (elapsedMs - it * everyMs).toFloat() / Z_LIFE_MS }.filter { it < 1f }
}

/**
 * The Zzz over a sleeping killer, from the moment this is composed: a "Z" in the HUD's display font every 1.6 s that
 * rises [ZRise] from just over the head ([headX], [headY], in this box's pixels) over 2 s, drifting [ZDrift] to the
 * right and fading away, never more than three at once (see [sleepZsAt]). The head and the time are read while
 * drawing, so the Zs follow a killer slumping into sleep without recomposing anything.
 */
@Composable
fun SleepZs(headX: () -> Float, headY: () -> Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val z = remember(measurer) { measurer.measure("Z", ZStyle) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) elapsedMs = withFrameMillis { it } - start
    }
    Canvas(modifier.fillMaxSize()) {
        val rise = ZRise.toPx()
        val drift = ZDrift.toPx()
        for (t in sleepZsAt(elapsedMs)) {
            val alpha = if (t < Z_FADE_IN) t / Z_FADE_IN else (1f - t) / (1f - Z_FADE_IN)
            // The Z's bottom centre is where it has got to.
            val at = Offset(headX() + drift * t - z.size.width / 2f, headY() - rise * t - z.size.height)
            drawText(z, topLeft = at, alpha = alpha)
        }
    }
}
