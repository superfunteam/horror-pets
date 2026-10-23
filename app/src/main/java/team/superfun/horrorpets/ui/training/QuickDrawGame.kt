package team.superfun.horrorpets.ui.training

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.game.training.DrawCall
import team.superfun.horrorpets.game.training.DrawOutcome
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.game.training.drawCallOf
import team.superfun.horrorpets.game.training.drawPartAt
import team.superfun.horrorpets.game.training.drawWaitMs
import team.superfun.horrorpets.game.training.drawWindowMs
import team.superfun.horrorpets.game.training.judgeDraw
import team.superfun.horrorpets.game.training.key
import team.superfun.horrorpets.game.training.nextCall
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.home.HOME_PET_HEIGHT
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow

// ---- the timing, in game ms ----

/** A call's part pops as it lights: it grows by [CALL_POP] of its size and back, this fast. */
private const val CALL_POP_MS = 180L
private const val CALL_POP = 0.12f

/** A matched part flashes white as the ring goes dark for the next call, fading this fast. */
private const val HIT_FLASH_MS = 280L

/** Every this many draws, the hit lands as a crit. */
private const val CRIT_EVERY = 5

/** The losing call: the part pressed blinks [BLINKS] times ([BLINK_MS] on, as long off), and the game is over after. */
private const val BLINKS = 3
private const val BLINK_MS = 120L
private const val LOSS_HOLD_MS = 1_300L

// ---- the layout, in the play area ----

/** Where the killers stand, in play widths: yours on the left, the mate on the right. */
private const val PET_X = 0.27f
private const val MATE_X = 0.73f

/** The mate's idle starts this far into its cycle, so the two don't bob in step. */
private const val MATE_IDLE_OFFSET_MS = 450L

/**
 * The ring's radius (its wedges' outer edge), in play heights, but no more than [RING_MAX_OF_WIDTH] of the play width,
 * so that it keeps clear of the killers either side.
 */
private const val RING_RADIUS = 0.19f
private const val RING_MAX_OF_WIDTH = 0.125f

/** The ring's centre is this many play heights above the floor: about level with the tops of the killers' heads. */
private const val RING_ABOVE_FLOOR = 0.37f

/** In the ring's radii: the centre button's radius, the gap round it, and the time ring's radius and width. */
private const val CENTER_OF_RING = 0.42f
private const val CENTER_GAP = 0.05f
private const val TIME_RING = 1.16f
private const val TIME_WIDTH = 0.07f

/** The callout sits this many play heights under the time ring. */
private const val CALLOUT_GAP = 0.02f

/** The dark of a part not lit, and how bright its arrow (or OK) is. */
private val PartDark = Color(0xE618141A)
private const val DIM_MARK = 0.4f

private val DrawHelp = listOf(
    HelpItem(listOf(TrainingKey.LEFT, TrainingKey.UP, TrainingKey.DOWN, TrainingKey.RIGHT), "OR"),
    HelpItem(listOf(TrainingKey.OK), "MATCH IT, FAST"),
)

/**
 * Quick Draw: your killer on the left, its stable mate on the right, and between them a ring like a remote's D-pad
 * (four wedges round an OK button) with a thin time ring round it. "READY...": the ring stands dark for a random wait
 * ([drawWaitMs]), then one part lights, "SLASH!", and the time ring drains over its window ([drawWindowMs], shorter
 * every draw). Match it: the remote's direction or OK, or a tap on that part. In time, your killer slashes and the mate
 * flinches, and the next call comes; the wrong part, a press before the call or too slow, and the mate slashes, yours
 * flinches, and the score is the streak (see [TrainingEnv] for what a game gets and owes the shell). The view model
 * opens it only with a mate; without one it ends at once with 0.
 */
@Composable
fun QuickDrawGame(env: TrainingEnv, onOver: (score: Int) -> Unit) {
    val latestOnOver by rememberUpdatedState(onOver)
    val mate = env.mate
    val play = remember { QuickDrawPlay(env.clock, env.sounds, env.score, Random.Default) }
    LaunchedEffect(play) {
        if (mate == null) latestOnOver(0) else play.playOut { latestOnOver(it) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(env.focus)
            .noKeyRepeats()
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
        val spot = remember(w, h, floorY) { DrawRingSpot.of(w, h, floorY) }
        SideEffect { play.ring = spot }

        if (mate != null) {
            // Both face the camera, standing apart on the scene's floor.
            DrawKiller(env, env.pet.id, play.pet, petHeight, 0L, Modifier.placeAt(x = { w * PET_X }, y = { env.floorY }))
            DrawKiller(env, mate.id, play.mate, petHeight, MATE_IDLE_OFFSET_MS, Modifier.placeAt(x = { w * MATE_X }, y = { env.floorY }))
        }
        DrawRingView(play, spot, env.clock)
        DrawCallout(play, Modifier.placeAt(x = { w / 2f }, y = { spot.cy + spot.reach + h * CALLOUT_GAP }, pivotY = 0f))
        DrawHud(play, env.best)
    }
}

/** A held key's repeats (more downs) never reach the game: in Quick Draw a press is one press, however long it's held. */
private fun Modifier.noKeyRepeats(): Modifier = onPreviewKeyEvent {
    it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount > 0 && trainingKeyOf(it.key) != null
}

/** One of the two killers, [pet], idling, swinging and flinching as [actor] has it. */
@Composable
private fun DrawKiller(env: TrainingEnv, pet: PetId, actor: DrawActor, heightPx: Float, idleOffsetMs: Long, modifier: Modifier) {
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

/** The HUD, its status line the streak. */
@Composable
private fun DrawHud(play: QuickDrawPlay, best: Int) {
    TrainingHud(TrainingGame.QUICK_DRAW.title, "STREAK ×${play.streak}", best, DrawHelp, "TAP THE PART THAT LIGHTS UP, FAST")
}

/** Under the ring, in gold: "READY..." while it waits, "SLASH!" while a call is lit, and how the game was lost. */
@Composable
private fun DrawCallout(play: QuickDrawPlay, modifier: Modifier) {
    val callout = when (play.phase) {
        DrawPhase.READY -> "READY..."
        DrawPhase.CALL -> "SLASH!"
        DrawPhase.OVER -> when (play.outcome) {
            DrawOutcome.WRONG -> "WRONG!"
            DrawOutcome.EARLY -> "TOO SOON!"
            DrawOutcome.LATE -> "TOO SLOW!"
            DrawOutcome.HIT, null -> return
        }
    }
    GlowText(callout, displayStyle(30.sp, color = TrainingGold, tracking = 0.06f, lineHeight = 32.sp), modifier)
}

/**
 * The ring at [spot], drawn from [play]'s state on the [clock] (so it holds while that is paused): the call lit and
 * popping, the time draining, a matched part's flash and the losing press blinking. All of it is read in the draw, so
 * nothing here recomposes as the game plays.
 */
@Composable
private fun DrawRingView(play: QuickDrawPlay, spot: DrawRingSpot, clock: GameClock) {
    val measurer = rememberTextMeasurer()
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val ring = DrawRingShapes(Offset(spot.cx, spot.cy), spot.outer)
                val ok = measurer.measure(
                    "OK",
                    displayStyle((ring.center * 0.7f).toSp(), color = Color.White, tracking = 0.02f),
                    maxLines = 1,
                    softWrap = false,
                )
                val mark = Stroke(spot.outer * 0.05f, join = StrokeJoin.Round)
                onDrawBehind {
                    val now = clock.nowMs
                    val phase = play.phase
                    val call = play.call
                    val window = play.windowMs.toFloat()
                    val pop = if (phase == DrawPhase.CALL) callPop(now - play.calledAtMs) else 1f
                    // Full as the call lights, draining to nothing at the window's end; held where it was when lost.
                    val timeLeft = when {
                        call == null -> 0f
                        phase == DrawPhase.OVER -> 1f - (play.overAtMs - play.calledAtMs) / window
                        else -> 1f - (now - play.calledAtMs) / window
                    }
                    drawDrawRing(ring, call, pop, timeLeft.coerceIn(0f, 1f), ok)

                    val matched = play.matched
                    val flash = 1f - (now - play.matchedAtMs) / HIT_FLASH_MS.toFloat()
                    if (phase == DrawPhase.READY && matched != null && flash > 0f) {
                        drawPath(ring.part(matched), Color.White, alpha = 0.75f * flash)
                    }
                    val pressed = play.pressed
                    if (phase == DrawPhase.OVER && pressed != null && blinkOn(now - play.overAtMs)) {
                        drawPath(ring.part(pressed), Color.White, style = mark)
                    }
                }
            },
    )
}

/** How much a call's part has grown [sinceMs] after it lit: out and back over [CALL_POP_MS]. */
private fun callPop(sinceMs: Long): Float {
    val u = sinceMs / CALL_POP_MS.toFloat()
    return if (u >= 1f) 1f else 1f + CALL_POP * sin(PI.toFloat() * u.coerceAtLeast(0f))
}

/** Whether the losing press's mark shows [sinceMs] after it: on, off, [BLINKS] times, then on. */
private fun blinkOn(sinceMs: Long): Boolean = sinceMs >= 2 * BLINKS * BLINK_MS || (sinceMs / BLINK_MS) % 2 == 0L

/**
 * Where Quick Draw's ring is in the play area, in px: centred on ([cx], [cy]), its wedges [outer] in radius, the time
 * ring round them reaching [reach].
 */
@Immutable
internal class DrawRingSpot(val cx: Float, val cy: Float, val outer: Float) {
    /** How far out from the centre the time ring reaches, its width and all. */
    val reach: Float get() = outer * (TIME_RING + TIME_WIDTH / 2f)

    /**
     * The part a tap at [at] lands on: the centre button out to half way across the gap round it, past that a wedge
     * (see [drawPartAt]) out to the time ring, so a finger a little wide of a wedge still has it; nothing farther out.
     */
    fun partAt(at: Offset): DrawCall? =
        drawPartAt(at.x - cx, at.y - cy, outer * (CENTER_OF_RING + CENTER_GAP / 2f), outer * TIME_RING)

    companion object {
        /**
         * The ring for a [w] x [h] play area with its floor at [floorY]: [RING_RADIUS] of the height (less in a narrow
         * one), in the middle, [RING_ABOVE_FLOOR] of the height above the floor, or lower should that take it off the
         * top.
         */
        fun of(w: Float, h: Float, floorY: Float): DrawRingSpot {
            val outer = min(h * RING_RADIUS, w * RING_MAX_OF_WIDTH)
            val cy = max(floorY - h * RING_ABOVE_FLOOR, outer * (TIME_RING + TIME_WIDTH) + h * CALLOUT_GAP)
            return DrawRingSpot(w / 2f, cy, outer)
        }
    }
}

/**
 * Quick Draw's ring centred on [c], its wedges [outer] in radius, as shapes to draw (build it once for a size): each
 * part's outline ([part]), the wedges' arrows, the centre button's radius ([center]) and the time ring's.
 */
internal class DrawRingShapes(val c: Offset, val outer: Float) {
    val center = outer * CENTER_OF_RING
    private val parts = Array(DrawCall.entries.size) { Path() }
    private val arrows = arrayOfNulls<Path>(DrawCall.entries.size)

    /** The lit part's glow, the centre's faint rim, and the time ring and its track. */
    val glow = Stroke(outer * 0.12f, join = StrokeJoin.Round)
    val rim = Stroke(outer * 0.03f)
    val time = Stroke(outer * TIME_WIDTH, cap = StrokeCap.Round)
    val track = Stroke(outer * TIME_WIDTH)
    val timeR = outer * TIME_RING
    val timeTopLeft = Offset(c.x - timeR, c.y - timeR)
    val timeSize = Size(timeR * 2f, timeR * 2f)

    init {
        val inner = outer * (CENTER_OF_RING + CENTER_GAP)
        for (call in DrawCall.entries) {
            if (call == DrawCall.CENTER) {
                parts[call.ordinal].addOval(Rect(c, center))
                continue
            }
            // A quarter of the ring centred on the wedge's way, less a sliver either side.
            val way = directionDegrees(call.key())
            val start = way - 45f
            parts[call.ordinal].apply {
                arcTo(Rect(c, outer), start + 3f, 84f, forceMoveTo = true)
                arcTo(Rect(c, inner), start + 87f, -84f, forceMoveTo = false)
                close()
            }
            val a = Math.toRadians(way.toDouble())
            val mid = (outer + center) / 2f
            arrows[call.ordinal] = arrowPath(c + Offset(cos(a).toFloat(), sin(a).toFloat()) * mid, outer * 0.26f, way)
        }
    }

    /** [call]'s part: its wedge, or the centre button. */
    fun part(call: DrawCall): Path = parts[call.ordinal]

    /** [call]'s arrow; none for the centre. */
    fun arrow(call: DrawCall): Path? = arrows[call.ordinal]
}

/**
 * Quick Draw's [ring]: the four wedges with their arrows and the centre button (labelled [ok], when given), all dark but
 * the [lit] one, blood red in a glow and grown by [pop] about the middle; round them the time ring, a faint track with
 * [timeLeft] (0 to 1) of it bright, from the top clockwise. The game's ring, and its picture on the reel.
 */
internal fun DrawScope.drawDrawRing(ring: DrawRingShapes, lit: DrawCall?, pop: Float, timeLeft: Float, ok: TextLayoutResult?) {
    for (i in DrawCall.entries.indices) {
        val call = DrawCall.entries[i]
        if (call != lit) drawRingPart(ring, call, lit = false, ok)
    }
    // The lit part last, its glow over its neighbours.
    if (lit != null) scale(pop, pivot = ring.c) { drawRingPart(ring, lit, lit = true, ok) }
    drawCircle(BloodGlow, ring.timeR, ring.c, alpha = 0.16f, style = ring.track)
    if (timeLeft > 0f) {
        drawArc(BloodGlow, -90f, 360f * timeLeft, useCenter = false, topLeft = ring.timeTopLeft, size = ring.timeSize, style = ring.time)
    }
}

/** One part of [ring], dark or [lit], with its arrow, or for the centre its rim and [ok]. */
private fun DrawScope.drawRingPart(ring: DrawRingShapes, call: DrawCall, lit: Boolean, ok: TextLayoutResult?) {
    val shape = ring.part(call)
    if (lit) drawPath(shape, BloodGlow, alpha = 0.35f, style = ring.glow)
    drawPath(shape, if (lit) Blood else PartDark)
    val mark = if (lit) 1f else DIM_MARK
    ring.arrow(call)?.let { drawPath(it, Color.White, alpha = mark) }
    if (call != DrawCall.CENTER) return
    drawCircle(Color.White, ring.center * 0.92f, ring.c, alpha = 0.08f, style = ring.rim)
    if (ok != null) {
        drawText(ok, topLeft = Offset(ring.c.x - ok.size.width / 2f, ring.c.y - ok.size.height / 2f), alpha = mark)
    }
}

/** Where a game of Quick Draw is: the ring dark, [READY] for the call; a [CALL] lit; or [OVER], lost. */
internal enum class DrawPhase { READY, CALL, OVER }

/** One of Quick Draw's killers: its animation, and its play count so that the same one plays again. Main thread only. */
@Stable
internal class DrawActor {
    var anim by mutableStateOf(Anim.IDLE)
        private set
    var playKey by mutableIntStateOf(0)
        private set
    private var holds = false

    /** A swing or a flinch ends back at idle, unless it [holds] its last frame (the game is over). */
    val onFinished: () -> Unit = { if (!holds) anim = Anim.IDLE }

    /** Plays [next], from the start even if it is playing now; [hold] keeps its last frame up when it ends. */
    fun act(next: Anim, hold: Boolean = false) {
        anim = next
        holds = hold
        playKey++
    }
}

/**
 * A game of Quick Draw as it goes: [playOut] plays it on the [clock], the remote's presses ([press]) and the finger's
 * taps ([tap]) answer the calls, and the composables draw what it holds. Each draw plays `hit` (`crit` every
 * [CRIT_EVERY]th) and keeps [score] at the streak; a loss plays `refuse`. Main thread only.
 */
@Stable
internal class QuickDrawPlay(
    private val clock: GameClock,
    private val sounds: SoundSink,
    private val score: MutableIntState,
    private val rng: Random,
) {
    var phase by mutableStateOf(DrawPhase.READY)
        private set

    /** The part called (lit) and when; null while the ring waits for the next call. */
    var call: DrawCall? by mutableStateOf(null)
        private set
    var calledAtMs by mutableLongStateOf(0L)
        private set

    /** How long the call has, in ms ([drawWindowMs] for the [streak]). */
    var windowMs by mutableLongStateOf(drawWindowMs(0))
        private set

    /** The draws made so far: the score. */
    var streak by mutableIntStateOf(0)
        private set

    /** The part last matched, and when (it flashes as the ring goes dark). */
    var matched: DrawCall? by mutableStateOf(null)
        private set
    var matchedAtMs by mutableLongStateOf(0L)
        private set

    /** How the game was lost, the part pressed then (null when too slow), and when. */
    var outcome: DrawOutcome? by mutableStateOf(null)
        private set
    var pressed: DrawCall? by mutableStateOf(null)
        private set
    var overAtMs by mutableLongStateOf(0L)
        private set

    /** Your killer and the mate. */
    val pet = DrawActor()
    val mate = DrawActor()

    /** Where the ring is, once measured, for the taps. */
    var ring: DrawRingSpot? = null

    /** The call's outcome, once judged, for [playOut]; null while nothing is open to a press (the game over, or not begun). */
    private var judge: CompletableDeferred<DrawOutcome>? = null
    private var judgedAtMs = 0L

    /**
     * Plays the game on the training clock, call after call, until one is lost: then, [LOSS_HOLD_MS] later, [onOver]
     * hears the streak.
     */
    suspend fun playOut(onOver: (streak: Int) -> Unit) {
        val recent = ArrayList<DrawCall>(2)
        while (true) {
            val judged = CompletableDeferred<DrawOutcome>()
            judge = judged
            val outcome = coroutineScope {
                val timer = launch {
                    clock.waitMs(drawWaitMs(rng))
                    if (judged.isCompleted) return@launch // pressed too soon on the very frame the wait ran out
                    val next = nextCall(recent, rng)
                    recent += next
                    if (recent.size > 2) recent.removeAt(0)
                    call = next
                    calledAtMs = clock.nowMs
                    phase = DrawPhase.CALL
                    // A press at the window's very last ms still counts; only past it is too slow.
                    clock.waitMs(windowMs + 1)
                    settle(judgeDraw(next, null, clock.nowMs - calledAtMs, windowMs), null)
                }
                judged.await().also { timer.cancel() }
            }
            if (outcome != DrawOutcome.HIT) {
                this.outcome = outcome
                phase = DrawPhase.OVER
                sounds.sfx("refuse")
                mate.act(Anim.ATTACK)
                pet.act(Anim.FAIL, hold = true)
                clock.waitMs(LOSS_HOLD_MS - (clock.nowMs - overAtMs))
                onOver(streak)
                return
            }
            streak++
            score.intValue = streak
            sounds.sfx(if (streak % CRIT_EVERY == 0) "crit" else "hit")
            pet.act(Anim.ATTACK)
            mate.act(Anim.FAIL)
            matched = call
            matchedAtMs = judgedAtMs
            call = null
            windowMs = drawWindowMs(streak)
            phase = DrawPhase.READY
        }
    }

    /** A remote's key going down: its part of the ring (OK the centre). */
    fun press(key: TrainingKey) = draw(drawCallOf(key))

    /** A finger going down at [at]: the part of the ring under it, if any; anywhere else does nothing. */
    fun tap(at: Offset) {
        val part = ring?.partAt(at) ?: return
        draw(part)
    }

    /** [part] pressed: judged against the call open now, or as too soon while the ring still waits. */
    private fun draw(part: DrawCall) {
        if (judge == null) return
        settle(judgeDraw(call, part, clock.nowMs - calledAtMs, windowMs), part)
    }

    /** Ends the call open now with [outcome], [part] the press that did (none for too slow). Only the first counts. */
    private fun settle(outcome: DrawOutcome, part: DrawCall?) {
        val judged = judge ?: return
        judge = null
        judgedAtMs = clock.nowMs
        if (outcome != DrawOutcome.HIT) {
            pressed = part
            overAtMs = judgedAtMs
        }
        judged.complete(outcome)
    }
}
