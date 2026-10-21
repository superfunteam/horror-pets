package team.superfun.horrorpets.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PlayGag
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.SpritePose

/** The glove's longer side, as a fraction of a full-size pet's height: a big cartoon hand, even for Chucky. */
private const val HAND_SIZE = 0.5f

/** The key ring's longer side, as a fraction of a full-size pet's height. */
private const val KEYS_SIZE = 0.38f

/** How high the glove lifts a pet to shake it, as a fraction of a full-size pet's height. */
private const val LIFT = 0.3f

/** The glove's pictures (`sprites/hand/<file>.webp`). The side poses point left, the pinch points down. */
private enum class HandArt(file: String, val tip: Edge) {
    POINT("point", Edge.LEFT),
    TICKLE_A("tickle_a", Edge.LEFT),
    TICKLE_B("tickle_b", Edge.LEFT),
    PINCH("pinch", Edge.BOTTOM),
    KEYS("keys", Edge.TOP),
    ;

    val path = "sprites/hand/$file.webp"
    val sideways: Boolean get() = tip == Edge.LEFT
}

/** Which edge of a picture its contact point is on: the fingertips, the pinch, or the key ring's top. */
private enum class Edge { LEFT, BOTTOM, TOP }

/** Every picture the glove can show, to decode ahead of a press. */
internal val PLAY_HAND_ART: List<String> = HandArt.entries.map { it.path }

/** What the player says during each gag; one line is picked at random each time. */
private val LINES = mapOf(
    PlayGag.TICKLE to listOf("play_tickle_1", "play_tickle_2"),
    PlayGag.POKE to listOf("play_poke_1", "play_poke_2"),
    PlayGag.KEYS to listOf("play_keys_1", "play_keys_2"),
    PlayGag.SHAKE to listOf("play_shake_1", "play_shake_2"),
)

/** Every line the player can say, for the asset check. */
internal val PLAY_VOICE_LINES: List<String> = LINES.values.flatten()

/** The side of the pet standing at [petX] the glove comes in from: its roomier one, +1 from the right. */
internal fun handSide(petX: Float, screenW: Float): Int = if (petX <= screenW / 2f) 1 else -1

/** Where the glove is and what it shows; read while drawing. [x], [y] place the picture's contact point. */
@Stable
private class Hand {
    var art by mutableStateOf(HandArt.POINT)
    var shown by mutableStateOf(false)
    var keys by mutableStateOf(false)
    val x = Animatable(0f)
    val y = Animatable(0f)
    val tilt = Animatable(0f)
    val swing = Animatable(0f)
}

/**
 * How far above its feet [pet]'s head tops out (hat, hair or pins included), as a fraction of its cell's height:
 * the top solid row of its first idle frame. The heads sit at different heights in their cells, from Ghostface's
 * hood at 0.8 to Jason's mask at 0.94.
 */
private fun headHeight(loader: AssetLoader, pet: PetId): Float {
    val manifest = loader.manifest(pet)
    val cw = manifest.cell[0]
    val ch = manifest.cell[1]
    val px = IntArray(cw * ch)
    loader.strip(pet, Anim.IDLE).readPixels(px, startX = 0, startY = 0, width = cw, height = ch)
    val top = (0 until ch).firstOrNull { y -> (0 until cw).any { x -> (px[y * cw + x] ushr 24) > 100 } } ?: 0
    return (manifest.baselineY - top).toFloat() / ch
}

/**
 * The player's cartoon glove doing [gag] to [pet], then leaving. The pet stands at [petX] on [floorY], its cell
 * drawn [petH] tall, with a body [bodyHalf] wide each side of its middle; [fullH] is a full-size pet's height,
 * which sizes the glove. The glove comes in from the pet's roomier side ([handSide]) or from above, moves the pet
 * through [pose] (a poke squashes it, a tickle jiggles it, a shake lifts and swings it) and says one of the
 * gag's lines. [onReact] is when the pet's own happy reaction should start; [onDone], once the glove is gone.
 */
@Composable
internal fun PlayHand(
    gag: PlayGag,
    pet: PetId,
    loader: AssetLoader,
    petX: Float,
    floorY: Float,
    petH: Float,
    fullH: Float,
    bodyHalf: Float,
    screenW: Float,
    pose: SpritePose,
    filter: ColorFilter?,
    onReact: () -> Unit,
    onDone: () -> Unit,
) {
    val sounds = LocalSoundSink.current
    val images = remember(loader) { HandArt.entries.associateWith { loader.prop(it.path) } }
    val tips = remember(images) { images.mapValues { (art, image) -> contactPoint(image, art.tip) } }
    val hand = remember { Hand() }
    val side = remember(petX, screenW) { handSide(petX, screenW) }
    val handPx = fullH * HAND_SIZE
    val keysPx = fullH * KEYS_SIZE
    val keysLength = keysPx * images.getValue(HandArt.KEYS).let { it.height.toFloat() / max(it.width, it.height) }
    val dp = LocalDensity.current.density
    val react by rememberUpdatedState(onReact)
    val done by rememberUpdatedState(onDone)

    LaunchedEffect(gag) {
        val rng = Random.Default
        fun say() = sounds.voice(listOf(LINES.getValue(gag).random(rng)))
        fun offside(to: Offset) = Offset(if (side > 0) screenW + handPx else -handPx, to.y)
        fun above(to: Offset) = Offset(to.x, -handPx - keysLength)
        suspend fun moveTo(to: Offset, ms: Int) = coroutineScope {
            launch { hand.x.animateTo(to.x, tween(ms, easing = FastOutSlowInEasing)) }
            hand.y.animateTo(to.y, tween(ms, easing = FastOutSlowInEasing))
        }
        suspend fun enter(from: Offset, to: Offset, ms: Int) {
            hand.x.snapTo(from.x)
            hand.y.snapTo(from.y)
            hand.shown = true
            moveTo(to, ms)
        }
        // A little inside the body's edge, so the fingertips land on it.
        val belly = Offset(petX + side * bodyHalf * 0.55f, floorY - petH * 0.45f)
        val headTop = floorY - petH * withContext(Dispatchers.IO) { headHeight(loader, pet) }
        try {
            pose.reset()
            when (gag) {
                PlayGag.TICKLE -> {
                    hand.art = HandArt.TICKLE_A
                    enter(offside(belly), belly, 350)
                    say()
                    sounds.sfx("tickle")
                    // The two tickle frames in turn wiggle the fingers; the pet jiggles and squirms with them.
                    repeat(14) { i ->
                        val beat = i % 2 == 0
                        hand.art = if (beat) HandArt.TICKLE_B else HandArt.TICKLE_A
                        hand.x.snapTo(belly.x + side * rng.nextFloat() * 6f * dp)
                        hand.y.snapTo(belly.y + (rng.nextFloat() - 0.5f) * 8f * dp)
                        pose.shiftX.snapTo(-side * (if (beat) 3f else -1.5f) * dp)
                        pose.squash.snapTo(if (beat) 0.25f else 0f)
                        pose.swing.snapTo(if (beat) 2f * side else -1f * side)
                        if (i == 7) sounds.sfx("tickle")
                        delay(90)
                    }
                    pose.reset()
                    react()
                    moveTo(offside(belly), 300)
                }
                PlayGag.POKE -> {
                    hand.art = HandArt.POINT
                    val touch = Offset(petX + side * bodyHalf * 0.55f, floorY - petH * 0.5f)
                    val rest = touch + Offset(side * 30f * dp, 0f)
                    enter(offside(rest), rest, 300)
                    say()
                    delay(120)
                    repeat(3) {
                        hand.x.animateTo(touch.x, tween(90, easing = FastOutLinearInEasing))
                        sounds.sfx("boop")
                        launch {
                            pose.squash.snapTo(1f)
                            pose.squash.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 600f))
                        }
                        launch {
                            pose.shiftX.snapTo(-side * 8f * dp)
                            pose.shiftX.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f))
                        }
                        hand.x.animateTo(rest.x, tween(160, easing = FastOutSlowInEasing))
                        delay(110)
                    }
                    react()
                    moveTo(offside(rest), 300)
                }
                PlayGag.KEYS -> {
                    hand.art = HandArt.PINCH
                    hand.keys = true
                    // The keys swing like a pendulum from the glove for as long as they are up.
                    val swinging = launch {
                        val start = withFrameMillis { it }
                        while (true) {
                            val t = (withFrameMillis { it } - start) / 1000f
                            hand.swing.snapTo(20f * sin(2f * PI.toFloat() * t / 0.85f))
                        }
                    }
                    // Low enough that the keys' tips hang just over the head, so the pet's jump reaches them.
                    val pinch = Offset(petX + side * bodyHalf * 0.15f, headTop - keysLength * 0.85f)
                    sounds.sfx("jingle")
                    enter(above(pinch), pinch, 450)
                    say()
                    launch {
                        hand.y.animateTo(pinch.y - 6f * dp, tween(350))
                        hand.y.animateTo(pinch.y + 6f * dp, tween(350))
                        hand.y.animateTo(pinch.y, tween(300))
                    }
                    delay(650)
                    react() // the pet jumps for the keys
                    delay(450)
                    sounds.sfx("jingle")
                    delay(550)
                    moveTo(above(pinch), 400)
                    swinging.cancel()
                }
                PlayGag.SHAKE -> {
                    hand.art = HandArt.PINCH
                    // The pinch closes on the top of the head (the hat brim, the hair, the pins).
                    val grab = Offset(petX, headTop + petH * 0.03f)
                    enter(above(grab), grab, 400)
                    sounds.sfx("boing")
                    say()
                    val lift = fullH * LIFT
                    coroutineScope {
                        launch { pose.lift.animateTo(lift, tween(450, easing = FastOutSlowInEasing)) }
                        hand.y.animateTo(grab.y - lift, tween(450, easing = FastOutSlowInEasing))
                    }
                    // Shaken side to side, swinging from the glove by the head.
                    val start = withFrameMillis { it }
                    while (true) {
                        val t = (withFrameMillis { it } - start) / 1000f
                        if (t >= 1f) break
                        val s = sin(2f * PI.toFloat() * 4.5f * t)
                        pose.shiftX.snapTo(18f * dp * s)
                        pose.swing.snapTo(-12f * s)
                        hand.x.snapTo(grab.x + 18f * dp * s)
                        hand.tilt.snapTo(-8f * s)
                    }
                    pose.shiftX.snapTo(0f)
                    pose.swing.snapTo(0f)
                    hand.x.snapTo(grab.x)
                    hand.tilt.snapTo(0f)
                    sounds.sfx("boing")
                    coroutineScope {
                        launch { pose.lift.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 400f)) }
                        hand.y.animateTo(grab.y, tween(380, easing = FastOutSlowInEasing))
                    }
                    react()
                    moveTo(above(grab), 350)
                }
            }
        } finally {
            withContext(NonCancellable) { pose.reset() }
        }
        hand.shown = false
        done()
    }

    Canvas(Modifier.fillMaxSize()) {
        if (!hand.shown) return@Canvas
        if (hand.keys) {
            // Behind the glove, so its fingers hold the ring.
            drawPicture(images.getValue(HandArt.KEYS), tips.getValue(HandArt.KEYS), Offset(hand.x.value, hand.y.value),
                keysPx, hand.swing.value, mirror = false, filter)
        }
        val art = hand.art
        drawPicture(images.getValue(art), tips.getValue(art), Offset(hand.x.value, hand.y.value), handPx,
            hand.tilt.value, mirror = art.sideways && side < 0, filter)
    }
}

/** [image] drawn [sizePx] on its longer side with its contact point [tip] at [at], turned [degrees] about it. */
private fun DrawScope.drawPicture(
    image: ImageBitmap,
    tip: Offset,
    at: Offset,
    sizePx: Float,
    degrees: Float,
    mirror: Boolean,
    filter: ColorFilter?,
) {
    val k = sizePx / max(image.width, image.height)
    withTransform({
        translate(at.x, at.y)
        if (degrees != 0f) rotate(degrees, pivot = Offset.Zero)
        scale(if (mirror) -k else k, k, pivot = Offset.Zero)
        translate(-tip.x, -tip.y)
    }) {
        drawImage(image, colorFilter = filter)
    }
}

/**
 * Where [image] touches what it holds, in its own pixels: on [edge], the first solid pixels in from that side,
 * averaged across them (the fingertips, the pinch, or the top of the key ring).
 */
private fun contactPoint(image: ImageBitmap, edge: Edge): Offset {
    val w = image.width
    val h = image.height
    val px = IntArray(w * h)
    image.readPixels(px)
    fun solid(x: Int, y: Int) = (px[y * w + x] ushr 24) > 160
    return when (edge) {
        Edge.LEFT -> {
            val x = (0 until w).first { c -> (0 until h).any { solid(c, it) } }
            val ys = (0 until h).filter { y -> (x until minOf(w, x + 6)).any { solid(it, y) } }
            Offset(x.toFloat(), ys.average().toFloat())
        }
        Edge.BOTTOM -> {
            val y = (h - 1 downTo 0).first { r -> (0 until w).any { solid(it, r) } }
            val xs = (0 until w).filter { x -> (maxOf(0, y - 5)..y).any { solid(x, it) } }
            Offset(xs.average().toFloat(), y.toFloat())
        }
        Edge.TOP -> {
            val y = (0 until h).first { r -> (0 until w).any { solid(it, r) } }
            val xs = (0 until w).filter { x -> (y until minOf(h, y + 6)).any { solid(x, it) } }
            Offset(xs.average().toFloat(), y.toFloat())
        }
    }
}
