package team.superfun.horrorpets.ui.pantry

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.ui.CookShow
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.theme.Amber

/**
 * A cook on show, as the player put it: "the two items kind of converge into one spot and then show a little poof
 * animation, and then show the new item, and then put it back in the pantry". The times are in ms from the cook (the
 * view model plays the way to cook's sound as it cooks; the poof's plays with the poof).
 */
internal object CookTimeline {
    /** The two inputs slide from their places on the board into its middle. */
    const val CONVERGE_MS = 280L

    /** The puff of smoke where they met, from the end of the converge... */
    const val POOF_MS = 420L

    /** ...with its sound, which the screen plays as it shows it (the view model plays only the way to cook's). */
    const val POOF_SOUND_MS = CONVERGE_MS

    /** The dish pops up inside the smoke... */
    const val REVEAL_AT = 360L

    /** ...growing past its size and settling over this long, its name under it. */
    const val POP_MS = 320L

    /** Then it flies to its shelf slot... */
    const val FLY_AT = 1_050L

    /** ...and lands there. */
    const val FLY_MS = 400L

    const val TOTAL_MS = FLY_AT + FLY_MS
}

/** What a cook show is doing: the inputs meeting, the poof, the dish shown with its name, its flight, and done. */
internal enum class CookPhase { CONVERGE, POOF, REVEAL, FLY, DONE }

/** The cook show's phase [ms] after the cook. */
internal fun cookPhaseAt(ms: Long): CookPhase = when {
    ms < CookTimeline.CONVERGE_MS -> CookPhase.CONVERGE
    ms < CookTimeline.REVEAL_AT -> CookPhase.POOF
    ms < CookTimeline.FLY_AT -> CookPhase.REVEAL
    ms < CookTimeline.TOTAL_MS -> CookPhase.FLY
    else -> CookPhase.DONE
}

/**
 * How far the inputs have come together [ms] after the cook: 0 on their places, 1 met in the middle, speeding up as
 * they go (eased in); null once they have gone into the poof.
 */
internal fun convergeAt(ms: Long): Float? {
    if (ms !in 0 until CookTimeline.CONVERGE_MS) return null
    val t = ms.toFloat() / CookTimeline.CONVERGE_MS
    return t * t * t
}

/** How far the poof has puffed out [ms] after the cook, 0..1; null before the inputs meet and once it has cleared. */
internal fun poofAt(ms: Long): Float? {
    val t = ms - CookTimeline.CONVERGE_MS
    if (t !in 0 until CookTimeline.POOF_MS) return null
    return t.toFloat() / CookTimeline.POOF_MS
}

/**
 * The dish's size [ms] after the cook, as a share of its full size on show: null before it pops up, then growing from
 * small past its size (an ease-out-back) and settling at exactly 1.
 */
internal fun popAt(ms: Long): Float? {
    if (ms < CookTimeline.REVEAL_AT) return null
    val t = min(1f, (ms - CookTimeline.REVEAL_AT).toFloat() / CookTimeline.POP_MS)
    val back = 2.2f
    val u = t - 1f
    val eased = 1f + (back + 1f) * u * u * u + back * u * u
    return 0.25f + 0.75f * eased
}

/** How far the dish has flown to its slot [ms] after the cook: 0 until it sets off, 1 once landed, eased in and out. */
internal fun flyAt(ms: Long): Float {
    val t = ((ms - CookTimeline.FLY_AT).toFloat() / CookTimeline.FLY_MS).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** A point [t] (0..1) of the way from [from] to [to], arcing [lift] above the straight line at its middle. */
internal fun flightPoint(from: Offset, to: Offset, t: Float, lift: Float): Offset =
    Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t - lift * 4f * t * (1f - t))

/**
 * Which of the board's places each of a cook's [inputs] (in the order they went on the board) slides in from, given
 * what stood in each place ([placed]) as the cook was pressed: the first place holding that item that no input before
 * it took. An input not found there (the board never drawn full, as when the debug puppet cooks) takes its own index.
 */
internal fun convergeFrom(inputs: List<PantryItem>, placed: List<PantryItem?>): List<Int> {
    val taken = mutableSetOf<Int>()
    val found = inputs.map { item -> placed.indices.firstOrNull { it !in taken && placed[it] == item }?.also { taken += it } }
    if (found.any { it == null }) return inputs.indices.toList()
    return found.filterNotNull()
}

/**
 * The sound of the poof that shows [result]: the poof for a dish, and a squelch for a botch (the spec's "a squelch
 * for slop", as for a refused cook).
 */
internal fun poofSound(result: PantryItem): String = if (result.kind == ItemKind.BOTCHED) "pantry_squelch" else "pantry_poof"

/** A refused cook's shake lasts this long. */
internal const val SHAKE_MS = 420L

/** How many times the board swings each way in a shake. */
private const val SHAKE_SWINGS = 4

/**
 * The board's sideways shake [ms] after a refused cook, as a share of its reach (-1..1): a few quick swings that die
 * away, and 0 before and after.
 */
internal fun shakeAt(ms: Long): Float {
    if (ms !in 0 until SHAKE_MS) return 0f
    val t = ms.toFloat() / SHAKE_MS
    return (sin(2.0 * PI * SHAKE_SWINGS * t).toFloat()) * (1f - t)
}

/** One puff of the poof's smoke: out along [angle] (radians) to [reach] (item sizes), [radius] (item sizes) round. */
internal data class Puff(val angle: Float, val reach: Float, val radius: Float)

/**
 * The poof's puffs, the same every time for one [seed]: a lumpy ring of them, uneven as cartoon smoke is, and a few
 * smaller ones further out.
 */
internal fun poofPuffs(seed: Int): List<Puff> {
    val rng = Random(seed)
    val ring = List(8) { i ->
        val angle = (2.0 * PI * i / 8 + rng.nextDouble(-0.2, 0.2)).toFloat()
        Puff(angle, reach = 0.42f + rng.nextFloat() * 0.14f, radius = 0.27f + rng.nextFloat() * 0.08f)
    }
    val wisps = List(5) { i ->
        val angle = (2.0 * PI * (i + 0.5) / 5 + rng.nextDouble(-0.3, 0.3)).toFloat()
        Puff(angle, reach = 0.72f + rng.nextFloat() * 0.16f, radius = 0.12f + rng.nextFloat() * 0.05f)
    }
    return ring + wisps
}

/** The poof's smoke: bone white, warm in the kitchen's light, with a grey-brown underside. */
private val SmokeLight = Color(0xFFF6EFE2)
private val SmokeShade = Color(0xFF9C8F7E)

/**
 * The poof [p] (0..1) of the way through, round [center] for items [item] px across: a white flash, then a cartoon
 * cloud, each puff a bone-white ball over its darker underside with a glint, billowing out from one clump into a
 * lumpy ring and thinning away from the middle (so the dish shows through it) before it fades. Drawn in code, once
 * per cook.
 */
private fun DrawScope.drawPoof(center: Offset, item: Float, p: Float, puffs: List<Puff>) {
    val out = 1f - (1f - p) * (1f - p) * (1f - p)
    // Solid at first, then it fades over the second half.
    val alpha = if (p < 0.45f) 1f else (1f - (p - 0.45f) / 0.55f).coerceIn(0f, 1f)
    if (p < 0.3f) {
        val flash = 1f - p / 0.3f
        drawCircle(Color.White.copy(alpha = 0.9f * flash), radius = item * (0.3f + 0.5f * out), center = center)
    }
    // The clump in the middle, which thins out first so the dish shows through.
    val core = (1f - p / 0.55f).coerceIn(0f, 1f)
    if (core > 0f) {
        drawCircle(SmokeShade.copy(alpha = alpha * core), radius = item * 0.42f, center = center + Offset(0f, item * 0.06f))
        drawCircle(SmokeLight.copy(alpha = alpha * core), radius = item * 0.38f, center = center)
    }
    for (puff in puffs) {
        val reach = puff.reach * item * (0.3f + 0.7f * out)
        val c = Offset(center.x + cos(puff.angle) * reach, center.y + sin(puff.angle) * reach * 0.78f)
        // Grows as it billows, then shrinks a little as it dissipates.
        val r = item * puff.radius * (0.5f + 0.7f * out) * (1f - 0.3f * p * p)
        drawCircle(SmokeShade.copy(alpha = alpha), radius = r, center = c + Offset(0f, r * 0.2f))
        drawCircle(SmokeLight.copy(alpha = alpha), radius = r * 0.92f, center = c)
        drawCircle(Color.White.copy(alpha = 0.55f * alpha), radius = r * 0.32f, center = c + Offset(-r * 0.3f, -r * 0.32f))
    }
}

/**
 * Where the pantry's cook show draws, in px in the kitchen: each of the board's places ([place], by index), the middle
 * of the board ([middle]) where the inputs meet, an item's size on the board ([item]), how big the dish shows
 * ([shown]), and where the dish lands: its slot's bottom centre and its size there ([slot], read as it flies, as the
 * carousel slides to it).
 */
internal class CookStage(
    val place: (Int) -> Offset,
    val middle: Offset,
    val item: Float,
    val shown: Float,
    val slot: () -> Pair<Offset, Float>,
)

/**
 * The cook show for [cook]: its inputs slide in from the board's places ([from], see [convergeFrom]) to its middle,
 * meet in a poof, the dish pops up there with its name, then flies off to its slot on the shelf, where the shelf takes
 * over (its slot is hidden till then). Its own clock runs once, for [CookTimeline.TOTAL_MS], then it calls [onDone]
 * and nothing more draws; the poof's sound ([poofSound]) plays as the poof shows. The dish's picture is asked for from the start, so
 * it is decoded by the time it shows.
 */
@Composable
internal fun CookShowView(loader: AssetLoader, cook: CookShow, from: List<Int>, heart: PantryItem?, stage: CookStage, onDone: () -> Unit) {
    val clock = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val sounds = LocalSoundSink.current
    LaunchedEffect(Unit) {
        launch {
            snapshotFlow { clock.value >= CookTimeline.POOF_SOUND_MS }.first { it }
            sounds.sfx(poofSound(cook.result))
        }
        clock.animateTo(CookTimeline.TOTAL_MS.toFloat(), tween(CookTimeline.TOTAL_MS.toInt(), easing = LinearEasing))
        done()
    }
    val ms = { clock.value.toLong() }
    // Only the phase recomposes (the name comes and goes); everything else is read while placing and drawing.
    val phase by remember { derivedStateOf { cookPhaseAt(ms()) } }
    val puffs = remember(cook.token) { poofPuffs(cook.token.toInt()) }
    val density = LocalDensity.current
    val itemDp = with(density) { stage.item.toDp() }
    val shownDp = with(density) { stage.shown.toDp() }

    Box(Modifier.fillMaxSize()) {
        if (phase == CookPhase.CONVERGE) {
            for ((k, input) in cook.inputs.withIndex()) {
                val start = stage.place(from.getOrElse(k) { k })
                val side = if (k == 0) -1f else 1f
                ItemArt(
                    loader,
                    input,
                    Modifier
                        .offset { centred(lerp(start, stage.middle, convergeAt(ms()) ?: 1f), stage.item) }
                        .size(itemDp)
                        .graphicsLayer {
                            val c = convergeAt(ms()) ?: 1f
                            val s = 1f - 0.45f * c
                            scaleX = s
                            scaleY = s
                            rotationZ = side * 28f * c
                        },
                    heart = input == heart,
                )
            }
        }
        if (phase == CookPhase.REVEAL) {
            // A warm glow behind the dish as it shows.
            Canvas(Modifier.fillMaxSize()) {
                val p = popAt(ms()) ?: return@Canvas
                val r = (stage.shown * 0.62f * p.coerceAtMost(1f)).coerceAtLeast(1f)
                drawCircle(
                    Brush.radialGradient(0f to Amber.copy(alpha = 0.32f), 1f to Amber.copy(alpha = 0f), center = stage.middle, radius = r),
                    radius = r,
                    center = stage.middle,
                )
            }
        }
        // The dish: composed from the start (so its picture decodes), shown from its pop.
        ItemArt(
            loader,
            cook.result,
            Modifier
                .offset {
                    val t = ms()
                    val popped = Offset(stage.middle.x, stage.middle.y + stage.shown / 2f)
                    val bottom = if (t < CookTimeline.FLY_AT) {
                        popped
                    } else {
                        val (to, _) = stage.slot()
                        flightPoint(popped, to, flyAt(t), lift = stage.shown * 0.9f)
                    }
                    IntOffset((bottom.x - stage.shown / 2f).roundToInt(), (bottom.y - stage.shown).roundToInt())
                }
                .size(shownDp)
                .graphicsLayer {
                    val t = ms()
                    val pop = popAt(t)
                    if (pop == null) {
                        alpha = 0f
                    } else {
                        val (_, landing) = stage.slot()
                        val s = if (t < CookTimeline.FLY_AT) pop else 1f + (landing / stage.shown - 1f) * flyAt(t)
                        alpha = 1f
                        scaleX = s
                        scaleY = s
                    }
                    // It pops out of the middle of the smoke, and shrinks onto its feet as it lands.
                    transformOrigin = if (t < CookTimeline.FLY_AT) TransformOrigin.Center else TransformOrigin(0.5f, 1f)
                },
            heart = cook.result == heart,
        )
        if (phase == CookPhase.POOF || phase == CookPhase.REVEAL) {
            Canvas(Modifier.fillMaxSize()) {
                val t = ms()
                val p = poofAt(t) ?: return@Canvas
                drawPoof(stage.middle, stage.item, p, puffs)
            }
        }
        if (phase == CookPhase.REVEAL) {
            // The dish's name under it.
            with(density) {
                ItemName(
                    cook.result.title,
                    maxWidth = shownDp * 2.2f,
                    centerX = stage.middle.x.toDp(),
                    top = (stage.middle.y + stage.shown / 2f).toDp() + 2.dp,
                    modifier = Modifier.graphicsLayer {
                        val since = ms() - CookTimeline.REVEAL_AT
                        alpha = (since / 160f).coerceIn(0f, 1f)
                    },
                )
            }
        }
    }
}

/** The top-left corner that centres a [size]-px square on [center]. */
private fun centred(center: Offset, size: Float): IntOffset =
    IntOffset((center.x - size / 2f).roundToInt(), (center.y - size / 2f).roundToInt())

private fun lerp(a: Offset, b: Offset, t: Float): Offset = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
