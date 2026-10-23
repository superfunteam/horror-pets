package team.superfun.horrorpets.ui.training

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.game.training.CATCH_HALF
import team.superfun.horrorpets.game.training.DROP_RANGE
import team.superfun.horrorpets.game.training.Drop
import team.superfun.horrorpets.game.training.KeyHold
import team.superfun.horrorpets.game.training.RUN_SPEED
import team.superfun.horrorpets.game.training.START_X
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.game.training.caught
import team.superfun.horrorpets.game.training.fallMs
import team.superfun.horrorpets.game.training.landGapMs
import team.superfun.horrorpets.game.training.nextDrop
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.common.spriteFilter
import team.superfun.horrorpets.ui.fight.PotionGreen
import team.superfun.horrorpets.ui.home.HOME_PET_HEIGHT
import team.superfun.horrorpets.ui.theme.Ink

// ---- the timing, in game ms ----

/** A caught organ pops into the pet this fast: shrinking into its chest and fading. */
private const val POP_MS = 220L

/** A catch's "+1" floats up from the pet and fades out over this long. */
private const val PLUS_MS = 800L

/** The organ that got past squashes flat on the floor this fast. */
private const val SQUASH_MS = 140L

/** The game is over this long after the splat. */
private const val OVER_HOLD_MS = 1_300L

/** Organs are dealt until the last one shows at the top this long after the next landing (so none pops up late). */
private const val DEAL_AHEAD_MS = 250L

// ---- the layout, in the play area ----

/** Each organ is this many play heights tall. */
private const val ORGAN_HEIGHT = 0.11f

/**
 * The catch line is this far up the pet's cell as drawn (display scale and all): its chest, where Slash Says centres
 * its ring.
 */
private const val CHEST_AT = 0.5f

/** An organ shows with its centre this many of its heights above the top edge, just out of sight. */
private const val TOP_START = 0.6f

/** One that got past comes down on the floor with its centre this many of its heights above the floor line. */
private const val REST_ABOVE_FLOOR = 0.3f

/**
 * Before the layout is known (only ever in a test), how much farther than its fall one that got past has to go on to
 * the floor: about a pet's chest under a play area's worth of fall.
 */
private const val DEFAULT_BELOW_SHARE = 0.35f

/** A falling organ's speed streak: this many of its heights wide, as long as [STREAK_SHORT] to [STREAK_LONG]. */
private const val STREAK_WIDTH = 0.3f
private const val STREAK_SHORT = 1.3f
private const val STREAK_LONG = 2.6f

/** The streak's brightness at its foot, slowest to fastest fall: faint. */
private const val STREAK_DIM = 0.1f
private const val STREAK_BRIGHT = 0.24f

/** A caught organ shrinks by this much of its size as it pops in, sinking this many of its heights. */
private const val POP_SHRINK = 0.75f
private const val POP_SINK = 0.2f

/**
 * The "+1" is this many play heights tall, its foot starting just over the pet's head ([OVER_HEAD] up its cell as
 * drawn, where the fight's callouts sit), and rises this many play heights.
 */
private const val PLUS_SIZE = 0.075f
private const val OVER_HEAD = 0.93f
private const val PLUS_RISE = 0.09f

/** Squashed on the floor, the organ is this much wider and this much as tall. */
private const val SPLAT_WIDE = 1.5f
private const val SPLAT_FLAT = 0.42f

/** A finger this near the pet as it stands (in play widths: a few px) doesn't set it running. */
private const val TOUCH_STILL = 0.008f

/** No time yet: a time to come that hasn't been set. */
private const val NEVER = Long.MAX_VALUE

private val DropHelp = listOf(HelpItem(listOf(TrainingKey.LEFT, TrainingKey.RIGHT), "MOVE"))

/**
 * Organ Drop: the pet runs along its scene's floor catching organs (heart, brain, eyeball, bone, hand, kidney) falling
 * from above the top, each turning a little with a faint speed streak above it. They come one at a time at first, then
 * faster and closer together ([fallMs], [landGapMs]), more of them in the air at once, every one within a run of where
 * the last came down ([nextDrop]). The remote's Left/Right run the pet while held ([KeyHold]); a finger held down
 * anywhere runs it toward the finger. An organ reaching the pet's chest within [CATCH_HALF] of it pops into it, a
 * squish and a green "+1" over its head; the first that doesn't falls on to the floor, a splat, "DROPPED IT!" and the
 * pet's FAIL, and the score is the organs caught (see [TrainingEnv] for what a game gets and owes the shell).
 */
@Composable
fun OrganDropGame(env: TrainingEnv, onOver: (score: Int) -> Unit) {
    val latestOnOver by rememberUpdatedState(onOver)
    val play = remember { OrganDropPlay(env.clock, env.sounds, env.score, Random.Default) }
    var organs by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    // The first organ is in the air as the game begins, so the game doesn't wait on the art: it shows once loaded.
    LaunchedEffect(play) { organs = withContext(Dispatchers.IO) { ORGAN_ART.map(env.loader::prop) } }
    LaunchedEffect(play) { play.playOut { latestOnOver(it) } }
    // A key held as the app went away may never come up: whatever is held is let go as the game stops and starts again.
    LaunchedEffect(play) { snapshotFlow { env.clock.paused }.collect { play.letGo() } }
    // The organs take the scene's light, as the killers do.
    val organFilter = remember { spriteFilter(hexColor(env.scene.tint), env.scene.tintStrength, 0f, 1f, 0f) }
    val bodyScale = remember { env.loader.manifest(env.pet.id).bodyScale }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(env.focus)
            .trainingKeys { key, down ->
                play.key(key, down)
                true
            }
            .focusable()
            .pointerInput(play) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    play.touch(down.position.x)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            play.touch(change.position.x)
                        }
                    } finally {
                        play.lift()
                    }
                }
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val petHeight = h * HOME_PET_HEIGHT
        val floorY = env.floorY
        val lane = remember(w, h, floorY) { DropLane.of(w, h, floorY, petHeight * bodyScale) }
        SideEffect { play.lane = lane }

        // Its feet on the scene's floor, where the game has it across.
        DropPet(env, play, petHeight, Modifier.placeAt(x = { play.killerX(env.clock.nowMs) * w }, y = { env.floorY }))
        DropOrgans(play, lane, organs, organFilter, env.clock)
        DropCallout(play, Modifier.align(Alignment.TopCenter).padding(top = safeInset().vertical + 6.dp))
        DropHud(play, env.best)
    }
}

/** The pet, running while it moves (its way), idle while it stands, and its FAIL once the game is lost. */
@Composable
private fun DropPet(env: TrainingEnv, play: OrganDropPlay, heightPx: Float, modifier: Modifier) {
    val tint = remember { hexColor(env.scene.tint) }
    // Follows the clock, but only changes (and recomposes) as the pet starts or stops running or turns.
    val anim by remember(play) { derivedStateOf { play.petAnim(env.clock.nowMs) } }
    SpriteView(
        loader = env.loader,
        pet = env.pet.id,
        anim = anim,
        heightPx = heightPx,
        tint = tint,
        tintStrength = env.scene.tintStrength,
        shadowOpacity = env.scene.shadowOpacity,
        modifier = modifier,
    )
}

/** At the top, in gold, once the organ that got past has hit the floor: "DROPPED IT!". */
@Composable
private fun DropCallout(play: OrganDropPlay, modifier: Modifier) {
    if (play.phase != DropPhase.OVER) return
    GlowText("DROPPED IT!", displayStyle(30.sp, color = TrainingGold, tracking = 0.06f, lineHeight = 32.sp), modifier)
}

/** The HUD, its status line the organs caught. */
@Composable
private fun DropHud(play: OrganDropPlay, best: Int) {
    TrainingHud(TrainingGame.ORGAN_DROP.title, "CAUGHT ${play.caught}", best, DropHelp, "HOLD A FINGER WHERE YOU WANT TO RUN")
}

/**
 * The organs on [lane] ([organs] their art, none drawn until it is loaded, lit by [filter]): falling, popping into the
 * pet, the one that got past falling on to the floor and squashed there; and the catches' "+1"s. Drawn from [play] on
 * the [clock], read in the draw, so nothing here recomposes as the game plays.
 */
@Composable
private fun DropOrgans(
    play: OrganDropPlay,
    lane: DropLane,
    organs: List<ImageBitmap>?,
    filter: ColorFilter?,
    clock: GameClock,
) {
    val measurer = rememberTextMeasurer()
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val streak = organStreak()
                val plus = measurer.measure(
                    "+1",
                    displayStyle((size.height * PLUS_SIZE).toSp(), color = PotionGreen, tracking = 0.02f),
                    maxLines = 1,
                    softWrap = false,
                )
                // Centred on the glyphs' edges, the fill covering its inner half: twice the outline wanted.
                val rim = Stroke(size.height * PLUS_SIZE * 0.14f, join = StrokeJoin.Round)
                onDrawBehind {
                    val images = organs ?: return@onDrawBehind
                    val now = clock.nowMs
                    val falling = play.falling
                    for (i in falling.indices) {
                        val f = falling[i]
                        drawDrop(play, f, images[f.drop.organ], lane, streak, filter, now)
                    }
                    val pluses = play.pluses
                    for (i in pluses.indices) drawPlus(pluses[i], plus, rim, lane, now)
                }
            },
    )
}

/** [f] at [now], drawn as [image] on [lane]: falling with its [streak], popping into the pet, or splatting. */
private fun DrawScope.drawDrop(
    play: OrganDropPlay,
    f: Falling,
    image: ImageBitmap,
    lane: DropLane,
    streak: Brush,
    filter: ColorFilter?,
    now: Long,
) {
    val x = f.drop.x * size.width
    val height = lane.organH
    if (f.caughtAtMs != NEVER) {
        // Into the pet's chest, wherever it has run to since.
        val u = ((now - f.caughtAtMs) / POP_MS.toFloat()).coerceIn(0f, 1f)
        if (u >= 1f) return
        val e = easeOut(u)
        val to = play.killerX(now) * size.width
        val at = Offset(x + (to - x) * e, lane.catchY + POP_SINK * height * e)
        val shrink = 1f - POP_SHRINK * e
        drawOrgan(image, at, height, f.angleAt(f.landAtMs), shrink, shrink, alpha = 1f - u, filter = filter)
        return
    }
    if (f === play.missed && now >= play.splatAtMs) {
        // Squashed flat on the floor, coming straight as it goes.
        val e = easeOut((now - play.splatAtMs) / SQUASH_MS.toFloat())
        val wide = 1f + (SPLAT_WIDE - 1f) * e
        val flat = 1f + (SPLAT_FLAT - 1f) * e
        // A soft dark patch under it, as wide.
        val patch = height * image.width / image.height * wide * 1.1f
        drawOval(Ink, Offset(x - patch / 2f, lane.floorY - height * 0.1f), Size(patch, height * 0.2f), alpha = 0.35f)
        val center = Offset(x, lane.floorY + height * 0.08f - height * flat / 2f)
        drawOrgan(image, center, height, f.angleAt(play.splatAtMs) * (1f - e), wide, flat, filter = filter)
        return
    }
    val fall = play.fallOf(f, now)
    if (fall <= 0f) return
    val y = min(lane.y(fall), lane.restY)
    val fast = (SLOWEST_FALL_MS - f.drop.fallMs) / (SLOWEST_FALL_MS - FASTEST_FALL_MS).toFloat()
    drawFallingOrgan(image, streak, Offset(x, y), height, f.angleAt(play.fallTime(f, now)), fast, filter)
}

/** The slowest and the fastest any organ falls, in ms ([fallMs]'s first and its floor). */
private val SLOWEST_FALL_MS = fallMs(0)
private val FASTEST_FALL_MS = fallMs(1_000)

/** A catch's "+1" ([plus], outlined by [rim]) at [now]: popping up over the pet's head, rising and fading. */
private fun DrawScope.drawPlus(c: PlusOne, plus: TextLayoutResult, rim: Stroke, lane: DropLane, now: Long) {
    val u = (now - c.atMs) / PLUS_MS.toFloat()
    if (u < 0f || u >= 1f) return
    val pop = 0.7f + 0.3f * easeOut(u / 0.15f)
    val alpha = 1f - ((u - 0.6f) / 0.4f).coerceIn(0f, 1f)
    val foot = lane.headY - PLUS_RISE * size.height * easeOut(u)
    val center = Offset(c.x * size.width, foot - plus.size.height / 2f)
    val topLeft = Offset(center.x - plus.size.width / 2f, center.y - plus.size.height / 2f)
    scale(pop, pivot = center) {
        drawText(plus, color = Ink, topLeft = topLeft, alpha = alpha, drawStyle = rim)
        drawText(plus, topLeft = topLeft, alpha = alpha)
    }
}

/** An organ's speed streak for [drawFallingOrgan]: white at its foot, fading out up its length (−1 to 0, scaled). */
internal fun organStreak(): Brush =
    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.White, startY = -1f, endY = 0f)

/**
 * A falling organ: [image] [height] tall centred on [center], turned [degrees], in the scene's light by [filter],
 * with a faint [streak] (an [organStreak], one per thread that draws) rising behind it, longer and brighter the
 * [fast]er it falls (0 the slowest, 1 the fastest). The game's organs, and the reel's picture of the game.
 */
internal fun DrawScope.drawFallingOrgan(
    image: ImageBitmap,
    streak: Brush,
    center: Offset,
    height: Float,
    degrees: Float,
    fast: Float,
    filter: ColorFilter? = null,
) {
    val f = fast.coerceIn(0f, 1f)
    val width = height * STREAK_WIDTH
    val length = height * (STREAK_SHORT + (STREAK_LONG - STREAK_SHORT) * f)
    withTransform({
        translate(center.x, center.y)
        scale(1f, length, Offset.Zero)
    }) {
        val alpha = STREAK_DIM + (STREAK_BRIGHT - STREAK_DIM) * f
        drawRect(streak, Offset(-width / 2f, -1f), Size(width, 1f), alpha = alpha)
    }
    drawOrgan(image, center, height, degrees, filter = filter)
}

/** An organ [image] [height] tall centred on [center], turned [degrees], stretched [scaleX] by [scaleY], at [alpha]. */
private fun DrawScope.drawOrgan(
    image: ImageBitmap,
    center: Offset,
    height: Float,
    degrees: Float,
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    alpha: Float = 1f,
    filter: ColorFilter? = null,
) {
    if (scaleX <= 0f || scaleY <= 0f || alpha <= 0f) return
    val width = height * image.width / image.height
    withTransform({
        translate(center.x, center.y)
        rotate(degrees, Offset.Zero)
        scale(scaleX, scaleY, Offset.Zero)
    }) {
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((-width / 2f).roundToInt(), (-height / 2f).roundToInt()),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            alpha = alpha,
            colorFilter = filter,
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * Where Organ Drop happens in the play area, in px: [width] across (the drops' and the pet's places are shares of it);
 * an organ shows centred at [topY] (out of sight above the top), reaches the catch line at [catchY] (the pet's chest),
 * and one that got past comes down centred at [restY], just over the floor at [floorY]. Each is [organH] tall. A
 * catch's "+1" rises from [headY], just over the pet's head.
 */
@Immutable
internal class DropLane(
    val width: Float,
    val topY: Float,
    val catchY: Float,
    val restY: Float,
    val floorY: Float,
    val organH: Float,
    val headY: Float,
) {
    /** Where a finger at [px] across means, in play widths, kept to [DROP_RANGE]. */
    fun xOf(px: Float): Float = (px / width).coerceIn(DROP_RANGE)

    /** Where an organ [fall] of the way down is (see [OrganDropPlay.fallOf]): its centre's y. */
    fun y(fall: Float): Float = topY + (catchY - topY) * fall

    /** How much farther than its fall (top to catch line) one that got past goes on to the floor. */
    val belowShare: Float get() = ((restY - catchY) / (catchY - topY).coerceAtLeast(1f)).coerceAtLeast(0f)

    companion object {
        /**
         * The lane for a [w] x [h] play area with its floor at [floorY], the pet [bodyHeight] tall as drawn: organs
         * [ORGAN_HEIGHT] of the height, caught at the pet's chest, the "+1"s over its head.
         */
        fun of(w: Float, h: Float, floorY: Float, bodyHeight: Float): DropLane {
            val organH = h * ORGAN_HEIGHT
            return DropLane(
                width = w,
                topY = -organH * TOP_START,
                catchY = floorY - bodyHeight * CHEST_AT,
                restY = floorY - organH * REST_ABOVE_FLOOR,
                floorY = floorY,
                organH = organH,
                headY = floorY - bodyHeight * OVER_HEAD,
            )
        }
    }
}

/**
 * A [drop] dealt, the [index]th of the game (from 0), on the game's clock (it began at `startMs`): it shows at the top
 * at [appearAtMs] and reaches the catch line at [landAtMs], caught then if [caughtAtMs] says so. It turns as it falls,
 * from its own tilt at its own slight spin ([angleAt]).
 */
internal class Falling(val drop: Drop, val index: Int, startMs: Long) {
    val landAtMs: Long = startMs + drop.landMs
    val appearAtMs: Long = landAtMs - drop.fallMs

    /** When it was caught (its landing), or [NEVER]. */
    var caughtAtMs: Long = NEVER
        internal set

    /** Tilted −22° to 22°, spinning 12 to 30 degrees a second, every other one the other way. */
    private val tilt = ((index * 61) % 45 - 22).toFloat()
    private val spin = (12 + (index * 7) % 19) * if (index % 2 == 0) 1f else -1f

    /** How far it has turned at [ms] on the clock, in degrees. */
    fun angleAt(ms: Long): Float = tilt + spin * (ms - appearAtMs) / 1_000f
}

/** A catch's "+1": when ([atMs]), and where across the pet was then ([x], in play widths). */
internal class PlusOne(val atMs: Long, val x: Float)

/**
 * Where a game of Organ Drop is: in [PLAY]; one [MISSED] (it got past the pet and is falling on to the floor); or
 * [OVER] (it splatted).
 */
internal enum class DropPhase { PLAY, MISSED, OVER }

/**
 * A game of Organ Drop as it goes: [playOut] plays it on the [clock], the remote ([key]) and the finger ([touch],
 * [lift]) steer the pet, and the composables draw what it holds. The pet's place is worked out from when it was last
 * steered ([killerX]), so it moves exactly [RUN_SPEED] in game time however the frames fall, and each organ is judged
 * where the pet was the moment it landed. A catch plays `squish` and keeps [score] at the organs caught; the miss
 * `splat`. Main thread only.
 */
@Stable
internal class OrganDropPlay(
    private val clock: GameClock,
    private val sounds: SoundSink,
    private val score: MutableIntState,
    private val rng: Random,
) {
    /**
     * When the game began on the clock: the drops' times count from it. Made in a composition, which must not follow
     * the clock.
     */
    val startMs: Long = Snapshot.withoutReadObservation { clock.nowMs }

    var phase by mutableStateOf(DropPhase.PLAY)
        private set

    /** The organs caught: the score. */
    var caught by mutableIntStateOf(0)
        private set

    /** The organs to draw, in the order they land: falling (some not shown yet), popping in, or the one missed. */
    val falling: List<Falling> get() = dealt
    private val dealt = ArrayList<Falling>()
    private var lastDrop: Drop? = null
    private var dealtCount = 0

    /** The "+1"s floating up, the oldest first. */
    val pluses: List<PlusOne> get() = plusOnes
    private val plusOnes = ArrayList<PlusOne>()

    /** The organ that got past, if one has: it crossed the catch line at [endAtMs], hits the floor at [splatAtMs]. */
    var missed: Falling? = null
        private set
    var endAtMs = NEVER
        private set
    var splatAtMs = NEVER
        private set

    /** The arrows held. */
    private val keys = KeyHold()

    /**
     * How the pet is steered, since it was last: from [fromX] at [fromMs], running [way] (−1, 0, +1: the arrows held)
     * or, while a finger is down, to [target] (not a number when none is).
     */
    private var fromX by mutableFloatStateOf(START_X)
    private var fromMs by mutableLongStateOf(startMs)
    private var way by mutableIntStateOf(0)
    private var target by mutableFloatStateOf(Float.NaN)

    /** Where the organs fall, once measured, for the touches and the miss's fall to the floor. */
    var lane: DropLane? = null

    init {
        deal()
    }

    /** The next organ to land, while the game is on. */
    val landingNext: Falling?
        get() = if (phase != DropPhase.PLAY) null else dealt.firstOrNull { it.caughtAtMs == NEVER }

    /**
     * Plays the game on the training clock, organ after organ, until one gets past: then once it has hit the floor,
     * [OVER_HOLD_MS] later, [onOver] hears the organs caught.
     */
    suspend fun playOut(onOver: (caught: Int) -> Unit) {
        while (phase == DropPhase.PLAY) {
            val next = landingNext ?: break
            clock.waitUntil(next.landAtMs)
            settle(clock.nowMs)
        }
        clock.waitUntil(splatAtMs)
        phase = DropPhase.OVER
        sounds.sfx("splat")
        clock.waitUntil(splatAtMs + OVER_HOLD_MS)
        onOver(caught)
    }

    /** A remote's key going [down] or up: the arrows steer while the game is on. */
    fun key(key: TrainingKey, down: Boolean) {
        val now = clock.nowMs
        settle(now)
        if (phase != DropPhase.PLAY) return
        keys.key(key, down)
        steer(now, target)
    }

    /**
     * A finger down (or moved) at [px] across the play area: while the game is on, the pet runs to it and stops there;
     * one resting a few px from the pet as it stands doesn't set it off.
     */
    fun touch(px: Float) {
        val lane = lane ?: return
        val now = clock.nowMs
        settle(now)
        if (phase != DropPhase.PLAY) return
        val to = lane.xOf(px)
        val x = killerX(now)
        steer(now, if (runningWay(now) == 0 && abs(to - x) <= TOUCH_STILL) x else to)
    }

    /** The finger lifted: the pet stops, or runs on as the arrows held have it. */
    fun lift() {
        val now = clock.nowMs
        settle(now)
        if (phase == DropPhase.PLAY) steer(now, Float.NaN)
    }

    /**
     * Lets go of every arrow and finger held, as the game stops and starts again (the app going away and coming
     * back): a key whose coming up went elsewhere would run the pet on forever. A key still held picks up with its
     * next repeat.
     */
    fun letGo() {
        keys.clear()
        lift()
    }

    /** Where across the play area the pet is at [now], in play widths. */
    fun killerX(now: Long): Float {
        val run = RUN_SPEED * (now - fromMs).coerceAtLeast(0L) / 1_000f
        val to = target
        return if (!to.isNaN()) toward(fromX, to, run) else (fromX + way * run).coerceIn(DROP_RANGE)
    }

    /** Which way the pet runs at [now]: −1 left, +1 right, 0 standing (not steered, there already, or at an edge). */
    fun runningWay(now: Long): Int {
        if (phase != DropPhase.PLAY) return 0
        val x = killerX(now)
        val to = target
        return when {
            !to.isNaN() -> sign(to - x).toInt()
            way > 0 && x < DROP_RANGE.endInclusive -> 1
            way < 0 && x > DROP_RANGE.start -> -1
            else -> 0
        }
    }

    /** The pet's animation at [now]: running its way, idle standing, its FAIL once the one that got past splatted. */
    fun petAnim(now: Long): Anim = when {
        phase == DropPhase.OVER -> Anim.FAIL
        else -> when (runningWay(now)) {
            1 -> Anim.RUN_RIGHT
            -1 -> Anim.RUN_LEFT
            else -> Anim.IDLE
        }
    }

    /**
     * How far [f] has fallen at [now]: 0 at the top, 1 at the catch line. Once one has got past, the rest stay where
     * they were then; it falls on beyond 1, to the floor.
     */
    fun fallOf(f: Falling, now: Long): Float {
        val fall = (fallTime(f, now) - f.appearAtMs) / f.drop.fallMs.toFloat()
        return if (f === missed) fall else min(fall, 1f)
    }

    /** The time [f] has got to at [now]: the clock's, but the game's end for all but the one that got past. */
    fun fallTime(f: Falling, now: Long): Long = if (f === missed) now else min(now, endAtMs)

    /** Steers the pet from where it is at [now]: to [to], or (not a number) as the arrows held have it. */
    private fun steer(now: Long, to: Float) {
        fromX = killerX(now)
        fromMs = now
        way = keys.direction
        target = to
    }

    /**
     * Judges every organ that has landed by [now], in turn, where the pet was as it landed: caught, or the one that got
     * past (and the game is over). Then forgets the pops and "+1"s done with.
     */
    private fun settle(now: Long) {
        while (phase == DropPhase.PLAY) {
            val next = landingNext ?: break
            val at = next.landAtMs
            if (at > now) break
            val x = killerX(at)
            if (!caught(next.drop.x, x)) {
                miss(next)
                break
            }
            next.caughtAtMs = at
            caught++
            score.intValue = caught
            sounds.sfx("squish")
            plusOnes += PlusOne(at, x)
            deal()
        }
        while (dealt.isNotEmpty() && dealt[0].caughtAtMs != NEVER && dealt[0].caughtAtMs + POP_MS <= now) {
            dealt.removeAt(0)
        }
        while (plusOnes.isNotEmpty() && plusOnes[0].atMs + PLUS_MS <= now) plusOnes.removeAt(0)
    }

    /** [f] got past the pet: the pet stops where it was, and nothing more lands; it falls on to the floor. */
    private fun miss(f: Falling) {
        val at = f.landAtMs
        keys.clear()
        steer(at, Float.NaN)
        missed = f
        endAtMs = at
        splatAtMs = at + (f.drop.fallMs * (lane?.belowShare ?: DEFAULT_BELOW_SHARE)).roundToLong()
        phase = DropPhase.MISSED
    }

    /** Deals organs on until the last shows at the top a little after the next one lands. */
    private fun deal() {
        while (true) {
            val next = landingNext
            val last = dealt.lastOrNull()
            if (next != null && last != null && last.appearAtMs > next.landAtMs + DEAL_AHEAD_MS) return
            val drop = nextDrop(lastDrop, dealtCount, rng)
            dealt += Falling(drop, dealtCount, startMs)
            lastDrop = drop
            dealtCount++
        }
    }
}

/** [from] moved [by] toward [to], stopping there. */
private fun toward(from: Float, to: Float, by: Float): Float =
    if (abs(to - from) <= by) to else from + sign(to - from) * by

private suspend fun GameClock.waitUntil(ms: Long) = waitMs(ms - nowMs)

/** Fast, then easing in to 1 at [u] = 1 (clamped). */
private fun easeOut(u: Float): Float {
    val rest = 1f - u.coerceIn(0f, 1f)
    return 1f - rest * rest
}
