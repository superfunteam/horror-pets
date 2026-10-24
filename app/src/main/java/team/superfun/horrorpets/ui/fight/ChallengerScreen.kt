package team.superfun.horrorpets.ui.fight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.common.rememberTouchTick

// ---- the sounds ----

/** A sound the entrance plays [at] ms in: an effect ([sfx]) or an announcer line ([voice]). */
internal data class EntranceSound(val at: Long, val sfx: String? = null, val voice: String? = null)

/**
 * What [pet]'s entrance plays, in order: the two-note bass hit as it cuts to black, the announcer's "Here comes a new
 * challenger!" as the banner slams in, then [pet]'s name as its lettering does. The lines queue behind each other.
 */
internal fun entranceSounds(pet: PetId): List<EntranceSound> = listOf(
    EntranceSound(Entrance.HIT_AT, sfx = "challenger_hit"),
    EntranceSound(Entrance.BANNER_AT, voice = "new_challenger"),
    EntranceSound(Entrance.NAME_AT, voice = pet.name.lowercase()),
)

// ---- the timeline ----

/**
 * The challenger's entrance as a timeline, in ms from its first frame, and where everything on it is at a time `t`.
 * Every function here stands still from [END_AT] on, so the last picture holds with nothing moving.
 */
internal object Entrance {
    /** OK, a tap or Back does nothing this early: the FIGHT press that opened it, pressed twice, must not skip it. */
    const val GUARD_MS = 400L

    /** The cut to black, on the bass hit's first note: a line of light, the water's surface, cuts across the dark. */
    const val HIT_AT = 0L

    /** The hit's second note (360 ms into `challenger_hit`): the water bursts, and he comes out of it at the camera. */
    const val BURST_AT = 360L

    /** He comes from far off to past full size in this long, ever faster, as something coming at the camera does... */
    const val LUNGE_MS = 560L

    /** ...then settles back to full size in this long. */
    const val SETTLE_MS = 240L

    /** The banner slams in, as the announcer starts "Here comes a new challenger!". */
    const val BANNER_AT = 940L

    /** A slam (the banner's, then the name's) falls from big to its size in this long, and lands. */
    const val SLAM_MS = 180L

    /**
     * His name slams in, landing as the announcer says it: his line is queued behind the banner's (2.18 s), so it
     * starts as that one ends.
     */
    const val NAME_AT = 3_150L

    /**
     * The end: the last picture holds and the VS card takes over. Not before the announcer has said his name: the card
     * cuts whatever he is still saying as it opens. His line starts about 3.27 s in (the banner's 2.18 s line, then
     * the gap between lines) and lasts 1.03 s, so it is over by about 4.3 s, with a moment to spare for the card.
     */
    const val END_AT = 4_450L

    /** The splash his name lands in: its longest-lived droplet falls away this long after the name lands. */
    const val NAME_SPLASH_MS = 850L

    /** A frame never moves the timeline more than this, so a stall slows it instead of skipping part of it. */
    const val MAX_STEP_MS = 100L

    /** The frame of his attack strip as he comes, and as he snaps them at the camera: jaws shut. */
    const val APPROACH_FRAME = 1

    /** The frame of his attack strip once he is on the camera: jaws wide open. */
    const val BITE_FRAME = 3

    private const val LUNGE_FROM = 0.08f
    private const val LUNGE_PEAK = 1.14f
    private const val SLAM_FROM = 2.8f
    private const val LANDED_AT = BURST_AT + LUNGE_MS + SETTLE_MS
    private const val HOVER_PERIOD_MS = 1_600f

    /** He snaps his jaws at the camera twice while the announcer reads the banner: shut for [CHOMP_MS] at each. */
    private val CHOMPS = longArrayOf(1_820L, 2_060L)
    private const val CHOMP_MS = 110L

    /**
     * How big he is at [t], as a share of his full size: 0 before the burst (not drawn), then from far off (small)
     * growing ever faster to past full size as he reaches the camera, then settling back to exactly full size.
     */
    fun lungeScale(t: Long): Float {
        if (t < BURST_AT) return 0f
        val p = (t - BURST_AT) / LUNGE_MS.toFloat()
        if (p < 1f) return LUNGE_FROM + (LUNGE_PEAK - LUNGE_FROM) * p.pow(2.2f)
        val q = (t - BURST_AT - LUNGE_MS) / SETTLE_MS.toFloat()
        if (q >= 1f) return 1f
        return LUNGE_PEAK + (1f - LUNGE_PEAK) * easeOutCubic(q)
    }

    /** How far he has come at [t], 0..1: from the burst in the water to his place. */
    fun lungeTravel(t: Long): Float = easeOutCubic(((t - BURST_AT) / LUNGE_MS.toFloat()).coerceIn(0f, 1f))

    /**
     * The frame of his attack strip on screen at [t]: [APPROACH_FRAME] as he comes, [BITE_FRAME] once he is on us,
     * but for the two snaps of his jaws.
     */
    fun lungeFrame(t: Long): Int = when {
        t < BURST_AT + LUNGE_MS * 6 / 10 -> APPROACH_FRAME
        CHOMPS.any { t >= it && t < it + CHOMP_MS } -> APPROACH_FRAME
        else -> BITE_FRAME
    }

    /** His slow bob once he has landed, -1..1: 0 until then, and still from [END_AT] on. */
    fun hover(t: Long): Float {
        if (t < LANDED_AT) return 0f
        return sin((min(t, END_AT) - LANDED_AT) / HOVER_PERIOD_MS * 2f * PI.toFloat())
    }

    /** A slam's scale [since] it started: falling from big to exactly its size over [SLAM_MS], then still. */
    fun slamScale(since: Long): Float {
        val p = (since / SLAM_MS.toFloat()).coerceIn(0f, 1f)
        return if (p >= 1f) 1f else SLAM_FROM + (1f - SLAM_FROM) * p * p
    }

    /**
     * 0..1 screen shake at [t]: a thump on the first note, a hard jolt at the burst, one as each slam lands, and a
     * nudge at each snap of his jaws.
     */
    fun shakeAt(t: Long): Float = max(
        max(
            max(pulse(t, HIT_AT, 240L, 0.3f), pulse(t, BURST_AT, 520L, 1f)),
            max(pulse(t, BANNER_AT + SLAM_MS, 340L, 0.8f), pulse(t, NAME_AT + SLAM_MS, 280L, 0.55f)),
        ),
        max(pulse(t, CHOMPS[0], 160L, 0.22f), pulse(t, CHOMPS[1], 160L, 0.22f)),
    )

    /** 0..1 white flash at [t]: the burst, then each slam landing. Each pops and clears fast. */
    fun flashAt(t: Long): Float = max(
        pulse(t, BURST_AT, 260L, 0.9f),
        max(pulse(t, BANNER_AT + SLAM_MS, 220L, 0.5f), pulse(t, NAME_AT + SLAM_MS, 200L, 0.4f)),
    )

    /** A jolt or a flash [at] a time, as strong as [strength] at first and gone [ms] later, at [t]. */
    private fun pulse(t: Long, at: Long, ms: Long, strength: Float): Float {
        val since = t - at
        if (since < 0 || since >= ms) return 0f
        val fade = 1f - since / ms.toFloat()
        return strength * fade * fade
    }
}

/**
 * One run of the entrance, frame by frame: where it is ([t]), what it plays as it gets there, and its end, by itself
 * or skipped. It has no clock of its own; the screen steps it once a frame.
 */
internal class EntranceRun(private val sounds: List<EntranceSound>) {
    /** Where the run is, in ms on the timeline. */
    var t: Long = 0L
        private set

    /** Whether it has ended, by itself at [Entrance.END_AT] or skipped. Nothing moves or plays from then on. */
    var over: Boolean = false
        private set

    private var started = false

    /** Whether OK, a tap or Back would end it now: once [Entrance.GUARD_MS] has passed, until it is over. */
    val canSkip: Boolean get() = !over && t >= Entrance.GUARD_MS

    /**
     * A frame: the first (whatever [elapsedMs] says, it is at 0) or the next, [elapsedMs] after the last (at most
     * [Entrance.MAX_STEP_MS] of it counts). Returns the sounds that fall due on it, each once; nothing once over.
     */
    fun frame(elapsedMs: Long): List<EntranceSound> {
        if (over) return emptyList()
        val from = if (started) t else -1L
        if (started) t = (t + elapsedMs.coerceIn(0L, Entrance.MAX_STEP_MS)).coerceAtMost(Entrance.END_AT)
        started = true
        if (t >= Entrance.END_AT) over = true
        return sounds.filter { it.at > from && it.at <= t }
    }

    /** OK, a tap or Back: ends the run on its last picture when it [canSkip]. Returns whether it did. */
    fun skip(): Boolean {
        if (!canSkip) return false
        t = Entrance.END_AT
        over = true
        return true
    }
}

// ---- the screen ----

/**
 * "HERE COMES A NEW CHALLENGER!", about 4 s, the [Entrance] timeline drawn:
 * - the screen cuts to black on the bass hit's first note, and a line of light, the water's surface, cuts across it;
 * - on the second note the water bursts (a crown of spray, a ring, mist, droplets flying at the camera, some in front
 *   of him) and [pet] comes out of it at the camera, his attack frames growing from far off to past full size, over a
 *   deep-sea glow with turning rays and speed lines;
 * - the banner slams in with the announcer's line; bubbles rise and he snaps his jaws at the camera twice; then his
 *   name lettering slams in with his name. Each slam lands with a flash, a jolt and a shine across it.
 *
 * Then [onDone] with [session], and the timeline stops on its last picture: nothing is drawn per frame from then on.
 * OK (or a tap) and Back skip to that end once [Entrance.GUARD_MS] has passed, and do nothing before. Either way the
 * reveal was saved as the entrance started, so leaving early loses nothing. [focusEntry] gates OK and Back (see
 * `RequestFocusOnEntry`); a held key's repeats are dropped by `MainActivity`, which also plays the key ticks, as on
 * the VS card.
 *
 * He faces left in his art and is drawn so: coming at the camera, that reads as a three-quarter turn, never as
 * backwards. The art decodes off the main thread during the first black beat; what is not ready yet is not drawn.
 * Everything is drawn in one layer that reads the clock as it draws, so nothing recomposes per frame.
 */
@Composable
fun ChallengerScreen(
    pet: PetId,
    session: Long,
    loader: AssetLoader,
    onDone: (Long) -> Unit,
    focusEntry: Any? = Unit,
) {
    val sounds = LocalSoundSink.current
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val latestOnDone by rememberUpdatedState(onDone)
    val skipTick = rememberTouchTick("ui_select")
    val art by produceState<EntranceArt?>(null, loader, pet) {
        value = withContext(Dispatchers.IO) { EntranceArt.load(loader, pet) }
    }
    val run = remember(session) { EntranceRun(entranceSounds(pet)) }
    val clock = remember(session) { mutableLongStateOf(0L) }
    val ended = remember(session) { BooleanArray(1) }

    fun end() {
        if (ended[0]) return
        ended[0] = true
        clock.longValue = run.t
        latestOnDone(session)
    }

    fun skip() {
        if (focusEntry != null && run.skip()) end()
    }

    GameBackHandler(enabled = focusEntry != null) { skip() }
    RequestFocusOnEntry(focus, focusEntry)

    LaunchedEffect(session, focusEntry) {
        if (focusEntry == null) return@LaunchedEffect // leaving: it has already ended
        var elapsed = 0L
        var last = withFrameMillis { it }
        while (!run.over) {
            for (cue in run.frame(elapsed)) {
                cue.sfx?.let(sounds::sfx)
                cue.voice?.let { sounds.voice(listOf(it)) }
            }
            clock.longValue = run.t
            if (run.over) break
            val now = withFrameMillis { it }
            elapsed = now - last
            last = now
        }
        end()
    }

    Spacer(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .clickable(interactionSource = interaction, indication = null) {
                if (focusEntry != null && run.canSkip) {
                    skipTick()
                    skip()
                }
            }
            .drawWithCache {
                val shown = art
                val kit = Kit(size, 1.dp.toPx(), shown)
                onDrawBehind { drawEntrance(clock.longValue, kit, shown) }
            },
    )
}

/** What the entrance draws from the assets, decoded once. */
@Immutable
private class EntranceArt(
    val banner: ImageBitmap,
    val name: ImageBitmap,
    val strip: ImageBitmap,
    val cellW: Int,
    val cellH: Int,
    val frames: Int,
) {
    companion object {
        /** Blocking: call it off the main thread. */
        fun load(loader: AssetLoader, pet: PetId): EntranceArt {
            val manifest = loader.manifest(pet)
            return EntranceArt(
                banner = loader.challengerBanner(),
                name = loader.vsName(pet),
                strip = loader.strip(pet, Anim.ATTACK),
                cellW = manifest.cell[0],
                cellH = manifest.cell[1],
                frames = manifest.spec(Anim.ATTACK).frames,
            )
        }
    }
}

// ---- staging, as fractions of the screen ----

/** The water's surface, where it bursts. */
private const val SURFACE_Y = 0.60f

/** Where he ends up: a little right of centre, so his open jaws (to the left in his art) come at the middle. */
private const val HOME_X_BY_H = 0.12f
private const val HOME_Y = 0.56f

/** His whole cell's height at full size, of the screen's. */
private const val CELL_OF_H = 0.92f

/** In his cell, his body's middle is this far down, so he is placed by his body and not by his cell. */
private const val BODY_MID = 0.6f

private const val BANNER_Y = 0.25f
private const val NAME_Y = 0.80f

private const val DROPS = 96
private const val CROWN_SHEETS = 14
private const val RAYS = 18
private const val SPEED_LINES = 36
private const val NAME_DROPS = 40
private const val BUBBLES = 26

/** The spray falls this many screen heights per second, per second. */
private const val GRAVITY = 2.2f

private val Abyss = Color(0xFF020A14)
private val SeaDeep = Color(0xFF05233A)
private val SeaMid = Color(0xFF0C5482)
private val SeaLight = Color(0xFF3DB4E6)
private val Spray = Color(0xFF9FE6FF)
private val Foam = Color(0xFFE6FAFF)
private val Heat = Color(0xFFFF7A1A)
private val FlashTint = Color(0xFFF2FCFF)

/** Everything the entrance draws with that depends only on the screen's size and the [art]: made once per both. */
private class Kit(size: Size, val unit: Float, art: EntranceArt?) {
    val w = size.width
    val h = size.height
    val diag = hypot(w, h)
    val origin = Offset(w / 2f, h * SURFACE_Y)
    val home = Offset(w / 2f + h * HOME_X_BY_H, h * HOME_Y)
    val cellH = h * CELL_OF_H
    val shakePx = 18f * unit

    val sea = Brush.radialGradient(
        0f to SeaMid,
        0.35f to SeaDeep,
        1f to Abyss,
        center = Offset(w / 2f, h * 0.55f),
        radius = diag * 0.6f,
    )
    val surfaceGlow = Brush.radialGradient(
        0f to SeaLight.copy(alpha = 0.55f),
        0.4f to SeaMid.copy(alpha = 0.25f),
        1f to Color.Transparent,
        center = origin,
        radius = w * 0.45f,
    )
    val vignette = Brush.radialGradient(
        0.55f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.75f),
        center = Offset(w / 2f, h / 2f),
        radius = diag / 2f,
    )

    /** The rays: wedges around (0, 0), drawn turned about his place. */
    val rays = Path().apply {
        val step = 2f * PI.toFloat() / RAYS
        for (i in 0 until RAYS) {
            val a = i * step
            val b = a + step * 0.42f
            moveTo(0f, 0f)
            lineTo(cos(a) * diag, sin(a) * diag)
            lineTo(cos(b) * diag, sin(b) * diag)
            close()
        }
    }
    val rayFill = Brush.radialGradient(
        0f to Foam.copy(alpha = 0.22f),
        0.5f to SeaLight.copy(alpha = 0.08f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = diag * 0.7f,
    )

    /** A puff of mist around (0, 0), [MIST_R] across, scaled as it spreads. */
    val mist = Brush.radialGradient(
        0f to Foam.copy(alpha = 0.85f),
        0.45f to Spray.copy(alpha = 0.3f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = h * MIST_R,
    )

    val bubble = Stroke(width = 1.5f * unit)

    /** A glow behind him at full size, around (0, 0). */
    val backGlow = Brush.radialGradient(
        0f to SeaLight.copy(alpha = 0.5f),
        0.5f to SeaLight.copy(alpha = 0.14f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = h * 0.5f,
    )

    // The banner across the top and the name across the bottom, each as wide as fits: set by the width on a narrow
    // screen and by the height on a wide one, so neither crowds him out. Each keeps its art's shape (nothing is drawn
    // before the art is there).
    val bannerRect = centred(Offset(w / 2f, h * BANNER_Y), min(w * 0.70f, h * 1.19f), art?.banner.aspect())
    val nameRect = centred(Offset(w / 2f, h * NAME_Y), min(w * 0.40f, h * 0.66f), art?.name.aspect())
    val bannerGlow = Brush.radialGradient(
        0f to Heat.copy(alpha = 0.8f),
        0.5f to Heat.copy(alpha = 0.3f),
        1f to Color.Transparent,
        center = bannerRect.center,
        radius = bannerRect.width * 0.6f,
    )
    val nameGlow = Brush.radialGradient(
        0f to SeaLight.copy(alpha = 0.6f),
        0.5f to SeaLight.copy(alpha = 0.16f),
        1f to Color.Transparent,
        center = nameRect.center,
        radius = nameRect.width * 0.65f,
    )
    val bannerShine = shine(bannerRect)
    val nameShine = shine(nameRect)

    /** This picture's height to its width; 0 for none. */
    private fun ImageBitmap?.aspect(): Float = if (this == null) 0f else height / width.toFloat()

    private fun centred(c: Offset, width: Float, aspect: Float): Rect {
        val height = width * aspect
        return Rect(c.x - width / 2f, c.y - height / 2f, c.x + width / 2f, c.y + height / 2f)
    }

    /** A slanted band of light around x = 0, as tall as [r]: slid across the art for its shine. */
    private fun shine(r: Rect): Brush {
        val band = r.height * 0.45f
        return Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = 0.8f),
            1f to Color.Transparent,
            start = Offset(-band, r.top),
            end = Offset(band, r.bottom),
        )
    }
}

/** The mist's full radius, of the screen's height. */
private const val MIST_R = 0.35f

private fun easeOutCubic(p: Float): Float = 1f - (1f - p) * (1f - p) * (1f - p)

private fun DrawScope.drawEntrance(t: Long, k: Kit, art: EntranceArt?) {
    drawRect(Color.Black)
    val shake = Entrance.shakeAt(t)
    val seed = (t / 28L).toInt()
    val dx = if (shake > 0f) (hash01(seed, 1) - 0.5f) * 2f * k.shakePx * shake else 0f
    val dy = if (shake > 0f) (hash01(seed, 2) - 0.5f) * 2f * k.shakePx * shake else 0f
    val zoom = 1f + 0.04f * shake
    withTransform({
        translate(dx, dy)
        scale(zoom, zoom, center)
    }) {
        surface(t, k)
        if (t >= Entrance.BURST_AT) {
            sea(t, k)
            spray(t, k, front = false)
            if (art != null) jaws(t, k, art)
            spray(t, k, front = true)
            drawRect(k.vignette)
        }
        if (art != null) {
            slammed(t - Entrance.BANNER_AT, art.banner, k.bannerRect, k.bannerGlow, k.bannerShine)
            val nameSince = t - Entrance.NAME_AT
            if (nameSince >= Entrance.SLAM_MS) nameSplash(nameSince - Entrance.SLAM_MS, k)
            slammed(nameSince, art.name, k.nameRect, k.nameGlow, k.nameShine)
        }
    }
    val flash = Entrance.flashAt(t)
    if (flash > 0f) drawRect(FlashTint, alpha = flash)
}

/**
 * The first note: a line of light, the water's surface, cuts across the black from the middle, over a deep glow.
 * On the burst it parts from the middle as the water comes through, and fades.
 */
private fun DrawScope.surface(t: Long, k: Kit) {
    val fade = if (t < Entrance.BURST_AT) 1f else 1f - ((t - Entrance.BURST_AT) / 500f).coerceIn(0f, 1f)
    if (fade <= 0f) return
    val swell = 1f - ((t - Entrance.HIT_AT) / 700f).coerceIn(0f, 1f) * 0.5f
    drawRect(k.surfaceGlow, alpha = fade * swell)
    val half = k.w * 0.5f * easeOutCubic(((t - Entrance.HIT_AT) / 220f).coerceIn(0f, 1f))
    val gap = if (t < Entrance.BURST_AT) 0f else k.w * 0.5f * easeOutCubic(((t - Entrance.BURST_AT) / 450f).coerceIn(0f, 1f))
    if (gap >= half) return
    val y = k.origin.y
    for (side in intArrayOf(-1, 1)) {
        val a = Offset(k.origin.x + side * gap, y)
        val b = Offset(k.origin.x + side * half, y)
        drawLine(SeaLight, a, b, strokeWidth = 16f * k.unit, alpha = 0.22f * fade, cap = StrokeCap.Round)
        drawLine(Spray, a, b, strokeWidth = 5f * k.unit, alpha = 0.6f * fade, cap = StrokeCap.Round)
        drawLine(Color.White, a, b, strokeWidth = 2f * k.unit, alpha = 0.95f * fade, cap = StrokeCap.Round)
    }
}

/** The deep sea behind him once the water has burst: its glow, turning rays, and speed lines rushing at the camera. */
private fun DrawScope.sea(t: Long, k: Kit) {
    val since = t - Entrance.BURST_AT
    val a = (since / 220f).coerceIn(0f, 1f)
    drawRect(k.sea, alpha = a)
    withTransform({
        translate(k.home.x, k.home.y)
        rotate(since * 0.012f, Offset.Zero)
    }) {
        drawPath(k.rays, k.rayFill, alpha = a)
    }
    // Strongest while he comes, then a steady rush under the words.
    val rush = 1f - 0.7f * ((since - Entrance.LUNGE_MS) / 700f).coerceIn(0f, 1f)
    val seconds = since / 1000f
    for (i in 0 until SPEED_LINES) {
        val angle = hash01(i, 11) * 2f * PI.toFloat()
        val speed = 0.9f + 0.8f * hash01(i, 12)
        val phase = (hash01(i, 13) + seconds * speed) % 1f
        val dirX = cos(angle)
        val dirY = sin(angle)
        val r0 = k.diag * (0.06f + phase * 0.55f)
        val len = k.diag * (0.04f + 0.16f * phase)
        drawLine(
            if (i % 3 == 0) Color.White else Spray,
            Offset(k.home.x + dirX * r0, k.home.y + dirY * r0),
            Offset(k.home.x + dirX * (r0 + len), k.home.y + dirY * (r0 + len)),
            strokeWidth = (1f + 2.5f * phase) * k.unit,
            alpha = a * rush * 0.3f * phase,
            cap = StrokeCap.Round,
        )
    }
    // Bubbles rising through the deep, wobbling as they go.
    for (i in 0 until BUBBLES) {
        val rise = k.h * (0.12f + 0.16f * hash01(i, 61))
        val y = k.h * 1.05f - ((hash01(i, 62) * 1.1f + seconds * rise / k.h) % 1.1f) * k.h
        val x = k.w * hash01(i, 63) + sin(seconds * (2f + 2f * hash01(i, 64)) + i) * 9f * k.unit
        val r = k.unit * (3f + 7f * hash01(i, 65))
        drawCircle(Spray, r, Offset(x, y), alpha = a * 0.35f, style = k.bubble)
        drawCircle(Foam, r * 0.28f, Offset(x - r * 0.35f, y - r * 0.35f), alpha = a * 0.5f)
    }
}

/**
 * The burst: behind him ([front] false) a crown of spray sheets, a ring spreading on the surface, a puff of mist and
 * two thirds of the droplets, which slow in the air; in front of him the rest, which come at the camera, spreading
 * ever faster and growing as they come. All of it falls.
 */
private fun DrawScope.spray(t: Long, k: Kit, front: Boolean) {
    val since = (t - Entrance.BURST_AT) / 1000f
    if (since < 0f) return
    if (!front) {
        val mist = (since / 0.9f).coerceIn(0f, 1f)
        if (mist < 1f) {
            withTransform({
                translate(k.origin.x, k.origin.y)
                scale(0.3f + 1.7f * easeOutCubic(mist), 0.3f + 1.2f * easeOutCubic(mist), Offset.Zero)
            }) {
                drawCircle(k.mist, radius = k.h * MIST_R, center = Offset.Zero, alpha = 0.6f * (1f - mist))
            }
        }
        val ring = (since / 0.7f).coerceIn(0f, 1f)
        if (ring < 1f) {
            val rx = k.w * (0.05f + 0.45f * easeOutCubic(ring))
            val ry = rx * 0.16f
            drawOval(
                Foam,
                topLeft = Offset(k.origin.x - rx, k.origin.y - ry),
                size = Size(rx * 2f, ry * 2f),
                alpha = 0.8f * (1f - ring),
                style = Stroke(width = k.unit * (1f + 8f * (1f - ring))),
            )
        }
        val crown = easeOutCubic((since / 0.45f).coerceIn(0f, 1f))
        val crownFade = 1f - ((since - 0.15f) / 0.6f).coerceIn(0f, 1f)
        if (crownFade > 0f) {
            for (j in 0 until CROWN_SHEETS) {
                val angle = -PI.toFloat() * (j + 0.5f) / CROWN_SHEETS + (hash01(j, 31) - 0.5f) * 0.15f
                val len = k.h * (0.18f + 0.32f * hash01(j, 32)) * crown
                val droop = 0.3f * GRAVITY * k.h * since * since
                val base = Offset(k.origin.x + cos(angle) * k.w * 0.02f, k.origin.y)
                val tip = Offset(k.origin.x + cos(angle) * len, k.origin.y + sin(angle) * len + droop)
                drawLine(Spray, base, tip, strokeWidth = (10f + 10f * hash01(j, 33)) * k.unit, alpha = 0.32f * crownFade, cap = StrokeCap.Round)
                drawLine(Foam, base, tip, strokeWidth = (3f + 3f * hash01(j, 34)) * k.unit, alpha = 0.75f * crownFade, cap = StrokeCap.Round)
            }
        }
    }
    for (i in 0 until DROPS) {
        if ((i % 3 == 0) != front) continue
        val tau = since - hash01(i, 21) * 0.12f
        val life = 0.55f + 0.9f * hash01(i, 22)
        if (tau <= 0f || tau >= life) continue
        val angle = -PI.toFloat() / 2f + (hash01(i, 23) - 0.5f) * PI.toFloat() * 1.7f
        val speed = k.h * (0.5f + 1.3f * hash01(i, 24))
        val radius = k.unit * (2f + 6f * hash01(i, 25)) * (if (front) 1f + 3f * tau else 1f)
        val alpha = (1f - tau / life) * (0.6f + 0.4f * hash01(i, 26))
        val color = when (i % 4) {
            0 -> Color.White
            1 -> Foam
            2 -> Spray
            else -> SeaLight
        }
        val at = drop(k, angle, speed, tau, front)
        val trail = drop(k, angle, speed, (tau - 0.04f).coerceAtLeast(0f), front)
        drawLine(color, trail, at, strokeWidth = radius, alpha = alpha, cap = StrokeCap.Round)
        if (radius > 5f * k.unit) drawCircle(Color.White, radius * 0.3f, at, alpha = alpha * 0.8f)
    }
}

/** Where a droplet thrown at [angle] and [speed] (px/s) from the burst is [tau] s on; one [front] comes at the camera. */
private fun drop(k: Kit, angle: Float, speed: Float, tau: Float, front: Boolean): Offset {
    val reach = if (front) speed * tau * (1f + 1.4f * tau) else speed * tau * (1f - 0.35f * tau)
    val fall = 0.5f * GRAVITY * k.h * tau * tau
    return Offset(k.origin.x + cos(angle) * reach, k.origin.y + sin(angle) * reach + fall)
}

/** Him: out of the water and at the camera, nose up as he breaks the surface, with afterimages as he comes. */
private fun DrawScope.jaws(t: Long, k: Kit, art: EntranceArt) {
    val s = Entrance.lungeScale(t)
    if (s <= 0f) return
    val travel = Entrance.lungeTravel(t)
    val x = lerp(k.origin.x, k.home.x, travel)
    val y = lerp(k.origin.y + k.h * 0.04f, k.home.y, travel) + Entrance.hover(t) * k.h * 0.012f
    val tilt = lerp(-14f, 0f, travel)
    val frame = Entrance.lungeFrame(t).coerceAtMost(art.frames - 1)
    val full = k.cellH / art.cellH
    withTransform({
        translate(x, y)
        scale(s, s, Offset.Zero)
    }) {
        drawCircle(k.backGlow, radius = k.h * 0.5f, center = Offset.Zero, alpha = travel)
    }
    if (t < Entrance.BURST_AT + Entrance.LUNGE_MS) {
        for (ghost in 2 downTo 1) jawsFrame(art, frame, x, y, tilt, full * s * (1f - 0.13f * ghost), 0.24f / ghost)
    }
    jawsFrame(art, frame, x, y, tilt, full * s, 1f)
}

private fun DrawScope.jawsFrame(art: EntranceArt, frame: Int, x: Float, y: Float, tilt: Float, size: Float, alpha: Float) {
    withTransform({
        translate(x, y)
        rotate(tilt, Offset.Zero)
        scale(size, size, Offset.Zero)
    }) {
        drawImage(
            art.strip,
            srcOffset = IntOffset(frame * art.cellW, 0),
            srcSize = IntSize(art.cellW, art.cellH),
            dstOffset = IntOffset(-art.cellW / 2, -(art.cellH * BODY_MID).roundToInt()),
            dstSize = IntSize(art.cellW, art.cellH),
            alpha = alpha,
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * A slammed piece of art, [since] its slam started (nothing before): it falls from big into [rect], lands with a
 * glow behind it that flares and settles, and a band of light slides across it once.
 */
private fun DrawScope.slammed(since: Long, image: ImageBitmap, rect: Rect, glow: Brush, shine: Brush) {
    if (since < 0) return
    val s = Entrance.slamScale(since)
    val alpha = (since / (Entrance.SLAM_MS * 0.35f)).coerceIn(0f, 1f)
    val landed = since - Entrance.SLAM_MS
    if (landed >= 0) {
        val flare = 0.55f + 0.45f * (1f - (landed / 500f).coerceIn(0f, 1f))
        withTransform({ scale(1f, 0.6f, rect.center) }) {
            drawCircle(glow, radius = rect.width * 0.6f, center = rect.center, alpha = flare)
        }
    }
    val dst = IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
    val dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())
    scale(s, s, rect.center) {
        drawImage(image, dstOffset = dst, dstSize = dstSize, alpha = alpha, filterQuality = FilterQuality.High)
    }
    val sweep = (landed - SHINE_AFTER_MS) / SHINE_MS.toFloat()
    if (sweep <= 0f || sweep >= 1f) return
    // The band only lights the art: drawn over it in a layer of its own, kept where the art is.
    drawContext.canvas.saveLayer(rect, Paint())
    drawImage(image, dstOffset = dst, dstSize = dstSize, filterQuality = FilterQuality.High)
    val x = lerp(rect.left - rect.height, rect.right + rect.height, sweep)
    translate(left = x) {
        drawRect(shine, topLeft = Offset(rect.left - x, rect.top), size = rect.size, blendMode = BlendMode.SrcAtop)
    }
    drawContext.canvas.restore()
}

/** The band of light crosses a slammed piece this long after it lands... */
private const val SHINE_AFTER_MS = 220L

/** ...taking this long. */
private const val SHINE_MS = 520L

/** The shortest-lived droplet off his name, in s; the longest lasts [Entrance.NAME_SPLASH_MS]. */
private const val NAME_DROP_MIN_S = 0.4f

/** His name lands in a splash of its own: droplets thrown up off it, [since] ms after it landed. */
private fun DrawScope.nameSplash(since: Long, k: Kit) {
    val r = k.nameRect
    val seconds = since / 1000f
    for (i in 0 until NAME_DROPS) {
        val life = NAME_DROP_MIN_S + (Entrance.NAME_SPLASH_MS / 1000f - NAME_DROP_MIN_S) * hash01(i, 51)
        if (seconds >= life) continue
        val startX = lerp(r.left + r.width * 0.1f, r.right - r.width * 0.1f, hash01(i, 52))
        val startY = r.center.y - r.height * 0.15f
        val angle = -PI.toFloat() / 2f + (hash01(i, 53) - 0.5f) * 2.4f
        val speed = k.h * (0.35f + 0.6f * hash01(i, 54))
        fun at(s: Float) = Offset(startX + cos(angle) * speed * s, startY + sin(angle) * speed * s + 0.5f * GRAVITY * k.h * s * s)
        val alpha = (1f - seconds / life) * 0.9f
        val radius = k.unit * (3f + 5f * hash01(i, 55))
        val now = at(seconds)
        drawLine(if (i % 2 == 0) Foam else Spray, at((seconds - 0.02f).coerceAtLeast(0f)), now, strokeWidth = radius, alpha = alpha, cap = StrokeCap.Round)
        drawCircle(Color.White, radius * 0.3f, now, alpha = alpha)
    }
}
