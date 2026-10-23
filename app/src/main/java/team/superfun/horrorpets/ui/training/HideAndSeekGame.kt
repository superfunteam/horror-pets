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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.game.training.SEEK_STONES
import team.superfun.horrorpets.game.training.Shuffle
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.game.training.afterSwap
import team.superfun.horrorpets.game.training.shuffleFor
import team.superfun.horrorpets.game.training.tombDesign
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.common.spriteFilter
import team.superfun.horrorpets.ui.home.HOME_PET_HEIGHT
import team.superfun.horrorpets.ui.theme.Ink

// ---- the timing, in game ms ----

/** A round's stones pop up in its design this fast as it is dealt, each [POP_STAGGER_MS] after the one before. */
private const val POP_IN_MS = 200L
private const val POP_STAGGER_MS = 60L

/**
 * The hide: this long after the deal the mate rises in front of its stone, taking [RISE_MS], and waves; [SHOW_MS] after
 * it began rising it ducks down behind it, taking [DUCK_MS].
 */
private const val HIDE_LEAD_MS = 350L
private const val RISE_MS = 250L
private const val SHOW_MS = 1_000L
private const val DUCK_MS = 400L

/** The swaps start this long after the mate is gone, with this gap between one and the next. */
private const val SHUFFLE_LEAD_MS = 300L
private const val SWAP_GAP_MS = 60L

/** A stone checked lifts and tips this fast. */
private const val LIFT_MS = 220L

/** A right pick: the next round is dealt this long after it. */
private const val FOUND_HOLD_MS = 800L

/** A wrong pick: the right stone lifts this long after it, and the game is over [MISS_HOLD_MS] after that. */
private const val MISS_REVEAL_MS = 500L
private const val MISS_HOLD_MS = 1_300L

/** The pointer glides to the next stone this fast, and bobs up and down once every [BOB_MS]. */
private const val AIM_GLIDE_MS = 90L
private const val BOB_MS = 900L

// ---- the layout, in the play area ----

/**
 * Your killer watches from the left edge, a small onlooker: its feet this far across the play area, [WATCHER_HEIGHT] of
 * the height tall (its cell, as `SpriteView` takes it; Home's pet is 0.46), but in a narrow play area no more than
 * [WATCHER_MAX_OF_WIDTH] of the width, so that even waving it keeps clear of the left stone (0.30 of the height on a
 * TV, 0.23 on 4:3).
 */
private const val PET_X = 0.065f
private const val WATCHER_HEIGHT = 0.34f
private const val WATCHER_MAX_OF_WIDTH = 0.2f

/** The stones stand this many play widths apart, the middle one in the middle. */
private const val STONE_SPACING = 0.25f

/**
 * Each stone is this many play heights tall, but in a narrow play area no wider than [STONE_MAX_OF_SPACING] of the
 * spacing (the widest design, the slab, being [WIDEST_TOMB] as wide as it is tall), so a gap shows between them.
 */
private const val STONE_HEIGHT = 0.42f
private const val STONE_MAX_OF_SPACING = 0.82f
private const val WIDEST_TOMB = 393f / 512f

/** How far down its art a stone meets the floor: the front of its mound. */
private const val TOMB_BASE = 0.95f

/** A stone's shadow: its radius in the stone's widths, and its height over its width. */
private const val SHADOW_WIDTH = 0.55f
private const val SHADOW_ASPECT = 0.22f

/**
 * Halfway through a swap, the stone passing in front is this much nearer (lower, by [SWAP_DEPTH] of a stone's height,
 * and [SWAP_SCALE] larger), the one passing behind as much farther.
 */
private const val SWAP_DEPTH = 0.12f
private const val SWAP_SCALE = 0.1f

/** A stone checked lifts this much of its height and tips back this many degrees. */
private const val LIFT = 0.16f
private const val TIP = -9f

/** The mate stands this many play heights in front of its stone's foot. */
private const val MATE_IN_FRONT = 0.02f

/** The mate's idle starts this far into its cycle, so it doesn't bob in step with your killer. */
private const val MATE_IDLE_OFFSET_MS = 450L

/** The pointer is this long (in play heights), its tip this far above the stones, and it bobs this far. */
private const val POINTER_LENGTH = 0.085f
private const val POINTER_GAP = 0.025f
private const val POINTER_BOB = 0.012f

/**
 * A tap is on a stone out to half way to the next one (and as far beyond the end ones), from this many of its heights
 * above its top (where the pointer is) to this many below its foot.
 */
private const val TAP_ABOVE = 0.3f
private const val TAP_BELOW = 0.15f

/** No time yet: a time to come that hasn't been set. */
private const val NEVER = Long.MAX_VALUE

private val SeekHelp = listOf(
    HelpItem(listOf(TrainingKey.LEFT, TrainingKey.RIGHT), "PICK"),
    HelpItem(listOf(TrainingKey.OK), "CHECK"),
)

/**
 * Hide and Eek!: three tombstones in a row on the scene's floor, all of one design (the next design each round,
 * [tombDesign]), your killer watching from the left edge. Each round the stable mate rises in front of one, waves, and
 * ducks down out of sight at its foot; then the stones shuffle ([shuffleFor]: more swaps each round, faster), pairs
 * swapping places, one passing in front of the other. "WHERE'S <MATE>?": the remote's Left/Right move a pointer over
 * the stones and OK checks the one under it, or a tap checks a stone. The right one lifts, the mate pops up, and the
 * next round is dealt; the wrong one lifts empty, then the right one lifts on the mate, your killer's FAIL, and the
 * score is the rounds won (see [TrainingEnv] for what a game gets and owes the shell). The view model opens it only
 * with a mate; without one it ends at once with 0.
 */
@Composable
fun HideAndSeekGame(env: TrainingEnv, onOver: (score: Int) -> Unit) {
    val latestOnOver by rememberUpdatedState(onOver)
    val mate = env.mate
    val play = remember { HideAndSeekPlay(env.clock, env.sounds, env.score, Random.Default) }
    var tombs by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    LaunchedEffect(play) {
        if (mate == null) {
            latestOnOver(0)
            return@LaunchedEffect
        }
        tombs = withContext(Dispatchers.IO) { TOMBSTONE_ART.map(env.loader::prop) }
        play.playOut { latestOnOver(it) }
    }
    // The stones take the scene's light, as the killers do.
    val stoneFilter = remember { spriteFilter(hexColor(env.scene.tint), env.scene.tintStrength, 0f, 1f, 0f) }
    val mateName = remember { mate?.id?.displayName?.uppercase().orEmpty() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(env.focus)
            .trainingKeys { key, down ->
                if (down) play.press(key)
                true
            }
            .focusable()
            .pointerInput(play) {
                awaitEachGesture { play.tap(awaitFirstDown(requireUnconsumed = false).position) }
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val petHeight = h * HOME_PET_HEIGHT
        val floorY = env.floorY
        val row = remember(w, h, floorY) { TombRow.of(w, h, floorY) }
        val watcher = remember(w, h) { SeekWatcher.of(w, h) }
        SideEffect { play.row = row }

        SeekStones(play, row, tombs, stoneFilter, env.clock)
        if (mate != null) SeekMate(env, mate.id, play, row, petHeight, h * MATE_IN_FRONT)
        // Facing the camera at the left edge, its feet on the scene's floor.
        SeekKiller(env, env.pet.id, play.pet, watcher.height, 0L, Modifier.placeAt(x = { watcher.x }, y = { env.floorY }))
        SeekPointer(play, row, env.clock)
        SeekCallout(play, mateName, Modifier.align(Alignment.TopCenter).padding(top = safeInset().vertical + 6.dp))
        SeekHud(play, env, mateName)
    }
}

/** One of the killers, [pet], acting as [actor] has it. */
@Composable
private fun SeekKiller(env: TrainingEnv, pet: PetId, actor: DrawActor, heightPx: Float, idleOffsetMs: Long, modifier: Modifier) {
    val tint = remember { hexColor(env.scene.tint) }
    SpriteView(
        loader = env.loader,
        pet = pet,
        anim = actor.anim,
        heightPx = heightPx,
        tint = tint,
        tintStrength = env.scene.tintStrength,
        shadowOpacity = env.scene.shadowOpacity,
        loopOffsetMs = idleOffsetMs,
        playKey = actor.playKey,
        onFinished = actor.onFinished,
        modifier = modifier,
    )
}

/**
 * The mate, while it is out ([HideAndSeekPlay.mateAt]): standing [inFront] px in front of its stone's foot, rising out
 * of the floor and ducking back down into it, fading as it goes (cut off at the floor, so it sinks out of sight). Its
 * motion is read in the layout and the draw, so it doesn't recompose as it moves.
 */
@Composable
private fun SeekMate(env: TrainingEnv, mate: PetId, play: HideAndSeekPlay, row: TombRow, heightPx: Float, inFront: Float) {
    val at = play.mateAt ?: return
    val clock = env.clock
    SeekKiller(
        env,
        mate,
        play.mate,
        heightPx,
        MATE_IDLE_OFFSET_MS,
        Modifier
            .placeAt(x = { row.x(at) }, y = { row.footY + inFront + play.mateDown(clock.nowMs) * heightPx })
            .graphicsLayer { alpha = play.mateAlpha(clock.nowMs) }
            .drawWithContent {
                val down = play.mateDown(clock.nowMs) * heightPx
                if (down <= 0f) {
                    drawContent()
                } else {
                    clipRect(-size.width, -size.height, size.width * 2f, size.height - down) {
                        this@drawWithContent.drawContent()
                    }
                }
            },
    )
}

/** The HUD, its status line the round, `· WATCH` while the mate hides and the stones shuffle. */
@Composable
private fun SeekHud(play: HideAndSeekPlay, env: TrainingEnv, mateName: String) {
    val status = when (play.phase) {
        SeekPhase.HIDE, SeekPhase.SHUFFLE -> "ROUND ${play.round} · WATCH"
        else -> "ROUND ${play.round}"
    }
    TrainingHud(TrainingGame.HIDE_AND_SEEK.title, status, env.hudBest, SeekHelp, "TAP THE STONE $mateName IS BEHIND")
}

/** At the top, in gold: "WHERE'S <MATE>?" while the player picks, then how the pick went. */
@Composable
private fun SeekCallout(play: HideAndSeekPlay, mateName: String, modifier: Modifier) {
    val callout = when (play.phase) {
        SeekPhase.PICK -> "WHERE'S $mateName?"
        SeekPhase.FOUND -> "FOUND $mateName!"
        SeekPhase.MISSED -> "WRONG STONE!"
        else -> return
    }
    GlowText(callout, displayStyle(30.sp, color = TrainingGold, tracking = 0.06f, lineHeight = 32.sp), modifier)
}

/**
 * The three stones on [row], in the round's design (none until [tombs], the designs, are loaded), lit by [filter]:
 * popping up as a round is dealt, swapping, lifting when checked. They stand still while the player picks, and only
 * follow the [clock] (read in the draw, so nothing recomposes) the rest of the time.
 */
@Composable
private fun SeekStones(play: HideAndSeekPlay, row: TombRow, tombs: List<ImageBitmap>?, filter: ColorFilter?, clock: GameClock) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val shadow = tombShadow()
                onDrawBehind {
                    val image = tombs?.get(tombDesign(play.round)) ?: return@onDrawBehind
                    val height = row.stoneH
                    if (play.phase == SeekPhase.PICK) {
                        for (pos in 0 until SEEK_STONES) {
                            drawTombstone(image, shadow, Offset(row.x(pos), row.footY), height, filter = filter)
                        }
                        return@onDrawBehind
                    }
                    val now = clock.nowMs
                    val swap = play.swapping?.let { play.shuffle.swaps[it] }
                    val u = if (swap == null) 0f else smooth((now - play.swapAtMs) / play.shuffle.swapMs.toFloat())
                    // The one passing behind under the one standing by, the one passing in front over it.
                    if (swap != null) drawSwapping(image, shadow, row, swap.second, swap.first, u, nearer = false, filter)
                    for (pos in 0 until SEEK_STONES) {
                        if (swap != null && (pos == swap.first || pos == swap.second)) continue
                        val lift = play.liftOf(pos, now)
                        drawTombstone(
                            image,
                            shadow,
                            Offset(row.x(pos), row.footY),
                            height,
                            scale = popIn(now - play.dealtAtMs - pos * POP_STAGGER_MS),
                            lift = lift * LIFT * height,
                            tip = lift * TIP,
                            filter = filter,
                        )
                    }
                    if (swap != null) drawSwapping(image, shadow, row, swap.first, swap.second, u, nearer = true, filter)
                }
            },
    )
}

/**
 * A stone [u] (0 to 1) of the way through swapping from [from] to [to] on [row], arcing [nearer] (lower and larger) or
 * farther (higher and smaller), most so half way.
 */
private fun DrawScope.drawSwapping(
    image: ImageBitmap,
    shadow: Brush,
    row: TombRow,
    from: Int,
    to: Int,
    u: Float,
    nearer: Boolean,
    filter: ColorFilter?,
) {
    val arc = sin(PI.toFloat() * u) * if (nearer) 1f else -1f
    val x = row.x(from) + (row.x(to) - row.x(from)) * u
    val foot = Offset(x, row.footY + arc * SWAP_DEPTH * row.stoneH)
    drawTombstone(image, shadow, foot, row.stoneH, scale = 1f + arc * SWAP_SCALE, filter = filter)
}

/**
 * The white pointer over the stone the remote is on (gliding to the next, bobbing), while the player picks. Read in
 * the draw.
 */
@Composable
private fun SeekPointer(play: HideAndSeekPlay, row: TombRow, clock: GameClock) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val length = size.height * POINTER_LENGTH
                val arrow = arrowPath(Offset.Zero, length, 90f)
                val rim = Stroke(length * 0.14f, join = StrokeJoin.Round)
                val y = row.top - size.height * POINTER_GAP - length / 2f
                val bob = size.height * POINTER_BOB
                onDrawBehind {
                    if (play.phase != SeekPhase.PICK) return@onDrawBehind
                    val now = clock.nowMs
                    val wave = sin(2f * PI.toFloat() * (now % BOB_MS) / BOB_MS)
                    translate(row.x(play.pointerAt(now)), y + bob * wave) {
                        drawPath(arrow, Ink, alpha = 0.6f, style = rim)
                        drawPath(arrow, Color.White)
                    }
                }
            },
    )
}

/** A [tombShadow] is [SHADOW_R] px in radius, to be scaled to its stone. */
private const val SHADOW_R = 100f

/**
 * A tombstone's soft shadow for [drawTombstone]: black, fading out to its edge, round (0, 0). One for each place that
 * draws stones, kept and drawn on one thread at a time (a brush keeps its shader).
 */
internal fun tombShadow(): Brush = Brush.radialGradient(
    0f to Color.Black.copy(alpha = 0.5f),
    0.5f to Color.Black.copy(alpha = 0.32f),
    1f to Color.Transparent,
    center = Offset.Zero,
    radius = SHADOW_R,
)

/**
 * A tombstone [image] meeting the floor at [foot] (the front of its mound there), [height] tall at [scale], lifted
 * [lift] px and tipped [tip] degrees about its foot, with its [shadow] (a [tombShadow]) on the floor under it, paler as
 * it lifts, in the scene's light by [filter]. The game's stones, and the reel's picture of the game.
 */
internal fun DrawScope.drawTombstone(
    image: ImageBitmap,
    shadow: Brush,
    foot: Offset,
    height: Float,
    scale: Float = 1f,
    lift: Float = 0f,
    tip: Float = 0f,
    filter: ColorFilter? = null,
) {
    if (scale <= 0f) return
    val h = height * scale
    val w = h * image.width / image.height
    val shadowScale = w * SHADOW_WIDTH / SHADOW_R
    translate(foot.x, foot.y) {
        scale(shadowScale, shadowScale * SHADOW_ASPECT, pivot = Offset.Zero) {
            drawCircle(shadow, SHADOW_R, Offset.Zero, alpha = (1f - 2f * lift / height).coerceIn(0.4f, 1f))
        }
    }
    withTransform({
        translate(foot.x, foot.y - lift)
        if (tip != 0f) rotate(tip, Offset.Zero)
    }) {
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((-w / 2f).roundToInt(), (-h * TOMB_BASE).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            colorFilter = filter,
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * Where Hide and Eek!'s stones stand in the play area, in px: [spacing] apart about [cx], their feet on [footY], each
 * [stoneH] tall.
 */
@Immutable
internal class TombRow(val cx: Float, val spacing: Float, val footY: Float, val stoneH: Float) {
    /** The top of the stones' art. */
    val top: Float get() = footY - stoneH * TOMB_BASE

    /** How far a standing stone's art reaches either side of its middle, in the widest design. */
    val halfWidth: Float get() = stoneH * WIDEST_TOMB / 2f

    /** Where the stone at [pos] stands, across. */
    fun x(pos: Int): Float = x(pos.toFloat())

    /** Where a stone [pos] of the way along the row stands (1.5: half way from the middle one to the right one). */
    fun x(pos: Float): Float = cx + (pos - (SEEK_STONES - 1) / 2f) * spacing

    /**
     * The stone a tap at [at] is on: the one whose column it is in (out to half way to the next stone, and as far past
     * the end ones), from a little above the stones' tops ([TAP_ABOVE]) to a little under their feet ([TAP_BELOW]).
     */
    fun stoneAt(at: Offset): Int? {
        if (at.y < top - stoneH * TAP_ABOVE || at.y > footY + stoneH * TAP_BELOW) return null
        val pos = ((at.x - cx) / spacing + (SEEK_STONES - 1) / 2f).roundToInt()
        return pos.takeIf { it in 0 until SEEK_STONES }
    }

    companion object {
        /**
         * The row for a [w] x [h] play area with its floor at [floorY]: [STONE_SPACING] of the width apart about the
         * middle, [STONE_HEIGHT] of the height tall, or less should the stones touch.
         */
        fun of(w: Float, h: Float, floorY: Float): TombRow {
            val spacing = w * STONE_SPACING
            return TombRow(w / 2f, spacing, floorY, min(h * STONE_HEIGHT, spacing * STONE_MAX_OF_SPACING / WIDEST_TOMB))
        }
    }
}

/**
 * Where your killer watches Hide and Eek! from, in px: its feet [x] across the play area (on the floor), its cell
 * [height] tall. A small onlooker at the left edge, clear of the stones.
 */
@Immutable
internal class SeekWatcher(val x: Float, val height: Float) {
    companion object {
        /**
         * The watcher for a [w] x [h] play area: [PET_X] of the width across, [WATCHER_HEIGHT] of the height tall, or
         * less in a narrow one.
         */
        fun of(w: Float, h: Float): SeekWatcher =
            SeekWatcher(w * PET_X, min(h * WATCHER_HEIGHT, w * WATCHER_MAX_OF_WIDTH))
    }
}

/**
 * Where a game of Hide and Eek! is: the mate going into [HIDE], the stones in the [SHUFFLE], the player's [PICK], the
 * mate [FOUND] (the next round to come), or [MISSED] (over).
 */
internal enum class SeekPhase { HIDE, SHUFFLE, PICK, FOUND, MISSED }

/**
 * A game of Hide and Eek! as it goes: [playOut] plays it on the [clock], the remote's presses ([press]) and the
 * finger's taps ([tap]) pick a stone, and the composables draw what it holds. Each swap plays `stone_slide`, the
 * pointer moving `ui_move`; a right pick `boing` (and [score] goes to the rounds won), a wrong one `refuse`. Main
 * thread only.
 */
@Stable
internal class HideAndSeekPlay(
    private val clock: GameClock,
    private val sounds: SoundSink,
    private val score: MutableIntState,
    private val rng: Random,
) {
    /** The round being played (from 1), its shuffle, and when it was dealt (its stones pop up). */
    var round by mutableIntStateOf(1)
        private set
    var shuffle: Shuffle by mutableStateOf(shuffleFor(1, rng))
        private set
    // Made in a composition, which must not follow the clock (it would recompose every frame).
    var dealtAtMs by mutableLongStateOf(Snapshot.withoutReadObservation { clock.nowMs })
        private set

    var phase by mutableStateOf(SeekPhase.HIDE)
        private set

    /** Where the mate's stone is: where it hid, then wherever the swaps take it. */
    var hiddenAt by mutableIntStateOf(shuffle.hideAt)
        private set

    /** How many of the shuffle's swaps are done; the one swapping now (its index), if any, and since when. */
    var swapsDone by mutableIntStateOf(0)
        private set
    var swapping: Int? by mutableStateOf(null)
        private set
    var swapAtMs by mutableLongStateOf(0L)
        private set

    /** The stone the mate is out in front of (none while it is out of sight), rising since then, ducking since then. */
    var mateAt: Int? by mutableStateOf(null)
        private set
    private var mateRiseAtMs by mutableLongStateOf(0L)
    private var mateDuckAtMs by mutableLongStateOf(NEVER)

    /** The stone the pointer is on, gliding there from [aimedFrom] (a place along the row) since [aimedAtMs]. */
    var aimed by mutableIntStateOf(1)
        private set
    private var aimedFrom by mutableFloatStateOf(1f)
    private var aimedAtMs by mutableLongStateOf(0L)

    /** The stone checked and when (it lifts then); and when the mate's stone lifted, if it wasn't the one checked. */
    var picked: Int? by mutableStateOf(null)
        private set
    var pickedAtMs by mutableLongStateOf(0L)
        private set
    private var revealedAtMs by mutableLongStateOf(NEVER)

    /** Your killer and the mate. */
    val pet = DrawActor()
    val mate = DrawActor()

    /** Where the stones are, once measured, for the taps. */
    var row: TombRow? = null

    /** The player's pick, while it is open: null the rest of the time, when nothing takes a press. */
    private var pick: CompletableDeferred<Int>? = null

    /**
     * Plays the game on the training clock, round after round, until a wrong pick: then, once the mate has shown where
     * it was, [onOver] hears the rounds won.
     */
    suspend fun playOut(onOver: (rounds: Int) -> Unit) {
        dealtAtMs = clock.nowMs // round 1, dealt as the game was made, comes up now
        while (true) {
            // The mate rises in front of its stone, waves, and ducks down behind it.
            var at = dealtAtMs + HIDE_LEAD_MS
            clock.waitUntil(at)
            rise(shuffle.hideAt, at, Anim.WAVE)
            at += SHOW_MS
            clock.waitUntil(at)
            mateDuckAtMs = at
            at += DUCK_MS
            clock.waitUntil(at)
            mateAt = null
            phase = SeekPhase.SHUFFLE

            // The shuffle: the mate's stone follows each swap it is in.
            at += SHUFFLE_LEAD_MS
            val swaps = shuffle.swaps
            for (i in swaps.indices) {
                if (i > 0) at += SWAP_GAP_MS
                clock.waitUntil(at)
                swapping = i
                swapAtMs = at
                sounds.sfx("stone_slide")
                at += shuffle.swapMs
                clock.waitUntil(at)
                hiddenAt = afterSwap(hiddenAt, swaps[i])
                swapping = null
                swapsDone = i + 1
            }

            // The pick, the pointer on the middle stone.
            val chosen = CompletableDeferred<Int>()
            aimed = 1
            aimedFrom = 1f
            aimedAtMs = clock.nowMs
            pick = chosen
            phase = SeekPhase.PICK
            val checked = chosen.await()
            if (checked == hiddenAt) {
                score.intValue = round
                phase = SeekPhase.FOUND
                sounds.sfx("boing")
                rise(checked, pickedAtMs, Anim.JUMP)
                pet.act(Anim.WAVE)
                val next = pickedAtMs + FOUND_HOLD_MS
                clock.waitUntil(next)
                deal(round + 1, next)
            } else {
                phase = SeekPhase.MISSED
                sounds.sfx("refuse")
                val revealAt = pickedAtMs + MISS_REVEAL_MS
                clock.waitUntil(revealAt)
                revealedAtMs = revealAt
                rise(hiddenAt, revealAt, Anim.WAVE)
                pet.act(Anim.FAIL, hold = true)
                clock.waitUntil(revealAt + MISS_HOLD_MS)
                onOver(round - 1)
                return
            }
        }
    }

    /** A remote's key going down: while the player picks, Left and Right move the pointer, OK checks its stone. */
    fun press(key: TrainingKey) {
        if (pick == null) return
        when (key) {
            TrainingKey.LEFT -> aim(aimed - 1)
            TrainingKey.RIGHT -> aim(aimed + 1)
            TrainingKey.OK -> check(aimed)
            TrainingKey.UP, TrainingKey.DOWN -> Unit
        }
    }

    /** A finger going down at [at]: while the player picks, checks the stone under it, if any. */
    fun tap(at: Offset) {
        if (pick == null) return
        check(row?.stoneAt(at) ?: return)
    }

    /** Where along the row the pointer is drawn at [now] (gliding to the stone aimed at). */
    fun pointerAt(now: Long): Float {
        val u = easeOut((now - aimedAtMs) / AIM_GLIDE_MS.toFloat())
        return aimedFrom + (aimed - aimedFrom) * u
    }

    /** How far the stone at [pos] has lifted at [now], 0 (down) to 1: the one checked, and the mate's at the end. */
    fun liftOf(pos: Int, now: Long): Float {
        val since = when {
            pos == picked -> now - pickedAtMs
            pos == hiddenAt && revealedAtMs != NEVER -> now - revealedAtMs
            else -> return 0f
        }
        return easeOut(since / LIFT_MS.toFloat())
    }

    /** How far down the mate is at [now], in its heights: 1 out of sight, 0 standing up. */
    fun mateDown(now: Long): Float =
        if (now >= mateDuckAtMs) easeIn((now - mateDuckAtMs) / DUCK_MS.toFloat())
        else 1f - easeOut((now - mateRiseAtMs) / RISE_MS.toFloat())

    /** How opaque the mate is at [now]: fading in as it rises (twice as fast), and out as it ducks. */
    fun mateAlpha(now: Long): Float =
        if (now >= mateDuckAtMs) 1f - ((now - mateDuckAtMs) / DUCK_MS.toFloat()).coerceIn(0f, 1f)
        else ((now - mateRiseAtMs) / (RISE_MS / 2f)).coerceIn(0f, 1f)

    /** The pointer onto stone [to], if there is one there. */
    private fun aim(to: Int) {
        if (to !in 0 until SEEK_STONES) return
        val now = clock.nowMs
        aimedFrom = pointerAt(now)
        aimed = to
        aimedAtMs = now
        sounds.sfx("ui_move")
    }

    /** Stone [stone] checked: it lifts, and the pick is over. Only the first counts. */
    private fun check(stone: Int) {
        val chosen = pick ?: return
        pick = null
        picked = stone
        pickedAtMs = clock.nowMs
        chosen.complete(stone)
    }

    /** The mate out in front of the stone at [stone], rising from [atMs], playing [anim]. */
    private fun rise(stone: Int, atMs: Long, anim: Anim) {
        mateAt = stone
        mateRiseAtMs = atMs
        mateDuckAtMs = NEVER
        mate.act(anim)
    }

    /** Deals round [next] at [atMs]: a new shuffle, its stones popping up in its design, all of them down. */
    private fun deal(next: Int, atMs: Long) {
        round = next
        shuffle = shuffleFor(next, rng)
        hiddenAt = shuffle.hideAt
        swapsDone = 0
        swapping = null
        mateAt = null
        picked = null
        revealedAtMs = NEVER
        dealtAtMs = atMs
        phase = SeekPhase.HIDE
    }
}

private suspend fun GameClock.waitUntil(ms: Long) = waitMs(ms - nowMs)

/** A stone's size [sinceMs] after it was dealt: up from nothing over [POP_IN_MS], overshooting a little. */
private fun popIn(sinceMs: Long): Float {
    val u = (sinceMs / POP_IN_MS.toFloat()).coerceIn(0f, 1f) - 1f
    return 1f + 2.70158f * u * u * u + 1.70158f * u * u
}

/** Fast, then easing in to 1 at [u] = 1 (clamped). */
private fun easeOut(u: Float): Float {
    val rest = 1f - u.coerceIn(0f, 1f)
    return 1f - rest * rest
}

/** Slow, then fast, to 1 at [u] = 1 (clamped). */
private fun easeIn(u: Float): Float {
    val v = u.coerceIn(0f, 1f)
    return v * v
}

/** Easing in and out of [u] (clamped): slow at both ends. */
private fun smooth(u: Float): Float {
    val v = u.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}
