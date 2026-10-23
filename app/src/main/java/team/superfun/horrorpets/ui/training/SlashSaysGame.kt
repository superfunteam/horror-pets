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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.game.training.SLASH_RING_ASPECT
import team.superfun.horrorpets.game.training.SLASH_SLOTS
import team.superfun.horrorpets.game.training.SlashRound
import team.superfun.horrorpets.game.training.SlashStep
import team.superfun.horrorpets.game.training.SlashTurn
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.game.training.aimFrom
import team.superfun.horrorpets.game.training.nextSlashRound
import team.superfun.horrorpets.game.training.slashShowMs
import team.superfun.horrorpets.game.training.slotAngle
import team.superfun.horrorpets.game.training.slotPosition
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.home.HOME_PET_HEIGHT
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink

// ---- the timing, in game ms ----

/** A round's dots pop up this fast as it is dealt, each [POP_STAGGER_MS] after the one before. */
private const val POP_IN_MS = 180L
private const val POP_STAGGER_MS = 25L

/** The dealt round stands this long under "WATCH..." before its first dot lights. */
private const val SHOW_LEAD_MS = 700L

/** A round done: its last dot rings this long, then the pet swings with a slash, and the next round comes after this. */
private const val DONE_BEAT_MS = 150L
private const val ROUND_DONE_MS = 800L

/**
 * A wrong dot: the right one flashes [FLASHES] times ([FLASH_ON_MS] lit, [FLASH_OFF_MS] dark) and then stays lit, and
 * the game is over [MISS_HOLD_MS] after the mistake.
 */
private const val FLASHES = 3
private const val FLASH_ON_MS = 160L
private const val FLASH_OFF_MS = 110L
private const val MISS_HOLD_MS = 1_300L

/** A dot lighting up (shown, or slashed) sends a ring of its colour out to [BURST_REACH] of its radii, this fast. */
private const val BURST_MS = 260L
private const val BURST_REACH = 2.4f

/** The aim ring glides to the next dot this fast. */
private const val AIM_GLIDE_MS = 90L

// ---- the ring, in the play area ----

/** The ring's y radius (its x radius is [SLASH_RING_ASPECT] times it) and each dot's radius, in play-area heights. */
private const val RING_Y_RADIUS = 0.26f
private const val DOT_RADIUS = 0.042f

/**
 * The ring is centred this far up the pet's cell as drawn (display scale and all): its chest, a little above the body's
 * middle so that the top dots clear its head.
 */
private const val CHEST_AT = 0.5f

/** The lowest dots keep above this share of the play area's height, clear of the help strip. */
private const val RING_LOWEST = 0.84f

/**
 * A finger slashes the due dot on reaching this many of its radii (a generous target), but a wrong one only a little
 * inside the dot itself ([WRONG_HIT]), so a straight swipe that passes close by another dot isn't a loss: from one dot
 * to the one two slots round (255° to 320°, or 220° to 285°) the line grazes the dot between at 0.99 of its radius.
 */
private const val HIT = 1.4f
private const val WRONG_HIT = 0.95f

/** How far down the ring its lowest slots sit, in y radii below its centre. */
private val LowestSlot = (0 until SLASH_SLOTS).maxOf { sin(Math.toRadians(slotAngle(it).toDouble())) }.toFloat()

/** No dot. */
private const val NONE = -1

/**
 * The dots' colours, by slot round the ring (low left, over the top, to low right): red, amber, lime, cyan, magenta,
 * violet, orange and bone white. The reel's picture of the game uses the first six.
 */
internal val SlashDotColors: List<Color> = listOf(
    Color(0xFFFF4D57),
    Color(0xFFFFC43D),
    Color(0xFFB4FF39),
    Color(0xFF2FD8FF),
    Color(0xFFFF5CE6),
    Color(0xFFA078FF),
    Color(0xFFFF8A2A),
    Color(0xFFF2ECE0),
)

private val SlashHelp = listOf(
    HelpItem(listOf(TrainingKey.LEFT, TrainingKey.UP, TrainingKey.DOWN, TrainingKey.RIGHT), "AIM"),
    HelpItem(listOf(TrainingKey.OK), "SLASH"),
)

/**
 * Slasher Says: the pet stands in the middle of its scene, ringed by dots. Each round the dots are dealt anew
 * ([nextSlashRound]: one more each round, up to eight) and shown, "WATCH...", each lighting in turn with its tone
 * ([slashShowMs], faster each round). Then "YOUR TURN": the player slashes them in the same order, with a finger (a swipe
 * through them, or taps) or the remote (the arrows move an aim ring from dot to dot, [aimFrom], and OK slashes). Each
 * right dot lights with its tone and the slash trail joins it to the last; at the round's last dot the pet swings, and
 * the next round is dealt. A wrong dot ends it: the right one flashes, a buzz, the pet's FAIL, and the score is the rounds
 * done (see [TrainingEnv] for what a game gets and owes the shell).
 */
@Composable
fun SlashSaysGame(env: TrainingEnv, onOver: (score: Int) -> Unit) {
    val latestOnOver by rememberUpdatedState(onOver)
    val play = remember { SlashSaysPlay(env, Random.Default) }
    LaunchedEffect(play) { play.run { latestOnOver(it) } }

    val manifest = remember { env.loader.manifest(env.pet.id) }
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
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    play.touch(down.position, down = true)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            play.touch(change.position, down = false)
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
        val ring = remember(w, h, floorY) { SlashRing.of(w, h, floorY, petHeight * manifest.bodyScale) }
        SideEffect { play.ring = ring }

        // Standing in the middle of the frame, its feet on the scene's floor.
        SlashPet(env, play, petHeight, Modifier.placeAt(x = { w / 2f }, y = { env.floorY }))
        SlashDots(play, ring.dotR, env.clock)
        SlashCallout(play, Modifier.align(Alignment.TopCenter).padding(top = safeInset().vertical + 6.dp))
        SlashHud(play, env)
    }
}

/** The pet, idling, swinging as a round is done and failing at a wrong dot, as [play] has it. */
@Composable
private fun SlashPet(env: TrainingEnv, play: SlashSaysPlay, heightPx: Float, modifier: Modifier) {
    val tint = remember { hexColor(env.scene.tint) }
    SpriteView(
        loader = env.loader,
        pet = env.pet.id,
        anim = play.petAnim,
        heightPx = heightPx,
        tint = tint,
        tintStrength = env.scene.tintStrength,
        shadowOpacity = env.scene.shadowOpacity,
        playKey = play.petPlay,
        onFinished = play.onPetFinished,
        modifier = modifier,
    )
}

/** "WATCH..." while the round is shown, "YOUR TURN" while the player repeats it, small in gold. */
@Composable
private fun SlashCallout(play: SlashSaysPlay, modifier: Modifier) {
    val callout = when (play.phase) {
        SlashPhase.WATCH -> "WATCH..."
        SlashPhase.TURN -> "YOUR TURN"
        else -> return
    }
    GlowText(callout, displayStyle(24.sp, color = TrainingGold, tracking = 0.06f, lineHeight = 26.sp), modifier)
}

/** The HUD, its status line following the round: `ROUND n · WATCH` while it's shown, then `ROUND n · k OF m`. */
@Composable
private fun SlashHud(play: SlashSaysPlay, env: TrainingEnv) {
    val round = play.round
    val status = when (play.phase) {
        SlashPhase.WATCH -> "ROUND ${round.round} · WATCH"
        else -> "ROUND ${round.round} · ${play.slashed} OF ${round.slots.size}"
    }
    TrainingHud(TrainingGame.SLASH_SAYS.title, status, env.hudBest, SlashHelp, "SWIPE THROUGH THE DOTS IN ORDER")
}

/**
 * The ring, the trail and the aim, drawn from [play]'s state on the [clock] (so they hold while it is paused), [dotR]
 * the dots' radius. All of it is read in the draw, so nothing here recomposes as the game plays.
 */
@Composable
private fun SlashDots(play: SlashSaysPlay, dotR: Float, clock: GameClock) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val r = dotR
                val glows = SlashDotColors.map { slashDotGlow(it, r) }
                val trail = Path()
                val burst = Stroke(r * 0.12f)
                onDrawBehind {
                    val round = play.round
                    val dots = play.dots
                    if (dots.size != round.slots.size) return@onDrawBehind // laid out from the next frame
                    val now = clock.nowMs
                    val phase = play.phase
                    val slashed = play.slashed

                    // The slash trail through the dots slashed, in order, and on to the finger while it's down.
                    trail.rewind()
                    for (k in 0 until slashed) {
                        val p = dots[round.order[k]]
                        if (k == 0) trail.moveTo(p.x, p.y) else trail.lineTo(p.x, p.y)
                    }
                    val finger = play.finger
                    if (slashed > 0 && phase == SlashPhase.TURN && finger.isSpecified) trail.lineTo(finger.x, finger.y)
                    if (slashed > 0) drawSlashTrail(trail, r)

                    for (i in dots.indices) {
                        val slot = round.slots[i]
                        val light = when {
                            phase == SlashPhase.WATCH -> if (i == play.shown) 1f else 0f
                            round.order.indexOf(i) < slashed -> 1f
                            phase == SlashPhase.MISSED && i == play.missed -> flashLight(now - play.missedAtMs)
                            else -> 0f
                        }
                        val pop = popIn(now - play.dealtAtMs - i * POP_STAGGER_MS, POP_IN_MS)
                        drawSlashDot(dots[i], r * pop, SlashDotColors[slot], light, glows[slot])
                    }

                    // The latest dot to light sends out a ring.
                    val watching = phase == SlashPhase.WATCH
                    val lit = if (watching) play.shown else if (slashed > 0) round.order[slashed - 1] else NONE
                    val u = (now - if (watching) play.shownAtMs else play.slashedAtMs) / BURST_MS.toFloat()
                    if (lit != NONE && u < 1f) {
                        val color = SlashDotColors[round.slots[lit]]
                        drawCircle(color, r * (1f + (BURST_REACH - 1f) * easeOut(u)), dots[lit], alpha = 1f - u, style = burst)
                    }

                    if (phase == SlashPhase.TURN) play.aimAt(now)?.let { drawAimRing(it, r) }
                }
            },
    )
}

/** Where a game of Slasher Says is: the round being [WATCH]ed, the player's [TURN], the round [DONE], or [MISSED]. */
private enum class SlashPhase { WATCH, TURN, DONE, MISSED }

/**
 * Where Slasher Says' ring is in the play area, in px: centred on ([cx], [cy]), the pet's chest, its radii [rx] by
 * [ry], the dots [dotR] in radius.
 */
@Immutable
internal class SlashRing(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val dotR: Float) {
    val chest: Offset get() = Offset(cx, cy)

    /** Where [slot]'s dot is. */
    fun dot(slot: Int): Offset = slotPosition(slot, cx, cy, rx, ry).let { (x, y) -> Offset(x, y) }

    companion object {
        /**
         * The ring for a [w] x [h] play area with its floor at [floorY], round a pet [petHeight] tall as drawn, standing
         * in the middle: [RING_Y_RADIUS] of the height tall (less on a play area too narrow for it), [SLASH_RING_ASPECT]
         * times as wide, centred on the pet's chest, or higher should its lowest dots reach the help strip.
         */
        fun of(w: Float, h: Float, floorY: Float, petHeight: Float): SlashRing {
            val dotR = h * DOT_RADIUS
            val ry = min(h * RING_Y_RADIUS, (w / 2f - dotR * HIT) / SLASH_RING_ASPECT)
            val cy = min(floorY - petHeight * CHEST_AT, h * RING_LOWEST - ry * LowestSlot - dotR)
            return SlashRing(w / 2f, cy, SLASH_RING_ASPECT * ry, ry, dotR)
        }
    }
}

/**
 * A game of Slasher Says as it goes: [run] plays it, the remote's presses ([press]) and the finger ([touch], [lift])
 * slash, and the composables draw what it holds. Main thread only.
 */
@Stable
private class SlashSaysPlay(private val env: TrainingEnv, private val rng: Random) {
    /** The round dealt, and when (its dots pop up). */
    var round: SlashRound by mutableStateOf(nextSlashRound(1, rng))
        private set
    // Made in a composition, which must not follow the clock (it would recompose every frame).
    var dealtAtMs by mutableLongStateOf(Snapshot.withoutReadObservation { env.clock.nowMs })
        private set

    var phase by mutableStateOf(SlashPhase.WATCH)
        private set

    /** The ring the game is laid out on, once it has been measured. */
    var ring: SlashRing? by mutableStateOf(null)

    /** The round's dots on the [ring] (none until it is measured). */
    val dots: List<Offset> by derivedStateOf { ring?.let { r -> round.slots.map(r::dot) }.orEmpty() }

    /** The dot the show has lit, and since when. */
    var shown by mutableIntStateOf(NONE)
        private set
    var shownAtMs by mutableLongStateOf(0L)
        private set

    /** How many dots the player has slashed this round, and when the latest. */
    var slashed by mutableIntStateOf(0)
        private set
    var slashedAtMs by mutableLongStateOf(0L)
        private set

    /** The dot that was due when the player slashed a wrong one, and when. */
    var missed by mutableIntStateOf(NONE)
        private set
    var missedAtMs by mutableLongStateOf(0L)
        private set

    /** The dot the remote's aim ring is on, gliding there since [aimedAtMs] from [aimedFrom]. */
    private var aimed by mutableIntStateOf(NONE)
    private var aimedFrom by mutableStateOf(Offset.Zero)
    private var aimedAtMs by mutableLongStateOf(0L)

    /** Where the finger is while it is down. */
    var finger by mutableStateOf(Offset.Unspecified)
        private set

    /** The dots the finger is in now (see [swipeThrough]). */
    private val inside = BooleanArray(SLASH_SLOTS)

    /** The pet's animation, and its play count so that the same one plays again. */
    var petAnim by mutableStateOf(Anim.IDLE)
        private set
    var petPlay by mutableIntStateOf(0)
        private set

    /** The pet's swing ends back at idle; its FAIL holds its last frame, the game being over. */
    val onPetFinished: () -> Unit = { if (petAnim == Anim.ATTACK) petAnim = Anim.IDLE }

    private var turn: SlashTurn? = null
    private var turnOver: CompletableDeferred<SlashStep>? = null

    /**
     * Plays the game on the training clock, round after round, until a wrong dot ends it: then, once the right dot has
     * flashed, [onOver] hears how many rounds were done.
     */
    suspend fun run(onOver: (rounds: Int) -> Unit) {
        val clock = env.clock
        while (true) {
            val round = round
            clock.waitMs(SHOW_LEAD_MS)
            val (lit, gap) = slashShowMs(round.round)
            for (dot in round.order) {
                shown = dot
                shownAtMs = clock.nowMs
                env.sounds.sfx(tone(round.slots[dot]))
                clock.waitMs(lit)
                shown = NONE
                clock.waitMs(gap)
            }
            val over = CompletableDeferred<SlashStep>()
            turnOver = over
            turn = SlashTurn(round)
            phase = SlashPhase.TURN
            if (over.await() == SlashStep.WRONG) {
                env.sounds.sfx("refuse")
                act(Anim.FAIL)
                clock.waitMs(MISS_HOLD_MS - (clock.nowMs - missedAtMs))
                onOver(round.round - 1)
                return
            }
            clock.waitMs(DONE_BEAT_MS)
            act(Anim.ATTACK)
            env.sounds.sfx("slash")
            clock.waitMs(ROUND_DONE_MS)
            deal(nextSlashRound(round.round + 1, rng))
        }
    }

    /** A remote's key going down: during the player's turn an arrow moves the aim ring, OK slashes the dot it's on. */
    fun press(key: TrainingKey) {
        if (phase != SlashPhase.TURN) return
        if (key == TrainingKey.OK) {
            if (aimed != NONE) slash(aimed)
            return
        }
        val ring = ring ?: return
        val dots = dots
        val from = dots.getOrNull(aimed) ?: ring.chest
        val next = aimFrom(from.toPair(), dots.map { it.toPair() }, key, aimed.takeIf { it != NONE }) ?: return
        val now = env.clock.nowMs
        aimedFrom = aimAt(now) ?: ring.chest
        aimed = next
        aimedAtMs = now
        env.sounds.sfx("ui_move")
    }

    /** Where the aim ring is drawn at [now] (gliding to the dot aimed at); null before the first aim. */
    fun aimAt(now: Long): Offset? {
        val to = dots.getOrNull(aimed) ?: return null
        return lerp(aimedFrom, to, easeOut((now - aimedAtMs) / AIM_GLIDE_MS.toFloat()))
    }

    /** The finger at [at], just [down] or moved there: during the player's turn it slashes the dots it reaches. */
    fun touch(at: Offset, down: Boolean) {
        if (down) inside.fill(false)
        val from = if (down || !finger.isSpecified) at else finger
        finger = at
        val r = ring?.dotR ?: return
        swipeThrough(turn.takeIf { phase == SlashPhase.TURN }, dots, r, from, at, inside, ::react)
    }

    /** The finger lifted (or the touch cancelled). */
    fun lift() {
        finger = Offset.Unspecified
        inside.fill(false)
    }

    private fun slash(dot: Int) {
        val turn = turn ?: return
        react(dot, turn.slash(dot))
    }

    /** What slashing [dot] did, [step], shown and heard; the end of the turn hands it back to [run]. */
    private fun react(dot: Int, step: SlashStep) {
        val turn = turn ?: return
        val now = env.clock.nowMs
        when (step) {
            SlashStep.IGNORED -> Unit
            SlashStep.RIGHT, SlashStep.ROUND_DONE -> {
                slashed = turn.slashed
                slashedAtMs = now
                env.sounds.sfx(tone(round.slots[dot]))
                if (step == SlashStep.ROUND_DONE) {
                    env.score.intValue = round.round
                    phase = SlashPhase.DONE
                    turnOver?.complete(step)
                }
            }
            SlashStep.WRONG -> {
                missed = turn.due ?: NONE
                missedAtMs = now
                phase = SlashPhase.MISSED
                turnOver?.complete(step)
            }
        }
    }

    /** Deals [next]: its dots pop up in their new places, the aim back on the pet, to be watched. */
    private fun deal(next: SlashRound) {
        round = next
        dealtAtMs = env.clock.nowMs
        slashed = 0
        shown = NONE
        aimed = NONE
        turn = null
        turnOver = null
        inside.fill(false)
        phase = SlashPhase.WATCH
    }

    private fun act(anim: Anim) {
        petAnim = anim
        petPlay++
    }
}

/**
 * A finger moving from [from] to [to] (the same point for a touch going down) through the [dots] (radius [dotR]):
 * slashes, in [turn], each dot it reaches, in the order it reaches them, and tells [onSlash] what that did. It reaches
 * the due dot on entering [HIT] times its radius, any other dot not yet slashed on entering [WRONG_HIT] times it (a
 * little inside the dot itself); a dot it was already in ([inside], by index, which this updates to the dots it is in
 * at [to]) is not reached again until it has left. It stops when the turn is over, and slashes nothing without a
 * [turn].
 */
internal fun swipeThrough(
    turn: SlashTurn?,
    dots: List<Offset>,
    dotR: Float,
    from: Offset,
    to: Offset,
    inside: BooleanArray,
    onSlash: (dot: Int, step: SlashStep) -> Unit,
) {
    fun reach(dot: Int) = dotR * if (turn != null && dot == turn.due) HIT else WRONG_HIT
    var after = 0f
    while (turn != null && !turn.over) {
        var next = NONE
        var nextAt = Float.MAX_VALUE
        for (i in dots.indices) {
            if (inside[i] || i in turn.path) continue
            val at = entryAlong(from, to, dots[i], reach(i)) ?: continue
            if (at >= after && at < nextAt) {
                next = i
                nextAt = at
            }
        }
        if (next == NONE) break
        inside[next] = true
        after = nextAt
        onSlash(next, turn.slash(next))
    }
    for (i in dots.indices) inside[i] = (to - dots[i]).getDistance() <= reach(i)
}

/**
 * How far along the segment from [a] to [b] (0 at [a], 1 at [b]) it first comes within [radius] of [c]: 0 when [a]
 * already is, null when it never does.
 */
private fun entryAlong(a: Offset, b: Offset, c: Offset, radius: Float): Float? {
    val fx = a.x - c.x
    val fy = a.y - c.y
    val outside = fx * fx + fy * fy - radius * radius
    if (outside <= 0f) return 0f
    val dx = b.x - a.x
    val dy = b.y - a.y
    val dd = dx * dx + dy * dy
    if (dd == 0f) return null
    val half = fx * dx + fy * dy
    val disc = half * half - dd * outside
    if (disc < 0f) return null
    val t = (-half - sqrt(disc)) / dd
    return t.takeIf { it in 0f..1f }
}

/** A dot's glow reaches this many of its radii out. */
private const val DOT_GLOW = 2.2f

/** The dark disc a dot sits on. */
private val DotBack = Color(0xC80A060C)

/** A Slasher Says dot's glow in [color], for a dot [r] in radius: centred on (0, 0), for [drawSlashDot]. */
internal fun slashDotGlow(color: Color, r: Float): Brush =
    Brush.radialGradient(0f to color.copy(alpha = 0.6f), 1f to Color.Transparent, center = Offset.Zero, radius = r * DOT_GLOW)

/**
 * A Slasher Says dot at [p], [r] in radius: a dark disc ringed in its [color], dim, with a small core of it; lit by
 * [light] (0 to 1), it grows, its ring brightens, its core goes white and it glows ([glow], from [slashDotGlow]).
 */
internal fun DrawScope.drawSlashDot(p: Offset, r: Float, color: Color, light: Float, glow: Brush?) {
    if (light > 0f && glow != null) translate(p.x, p.y) { drawCircle(glow, r * DOT_GLOW, Offset.Zero, alpha = light) }
    val rr = r * (1f + 0.15f * light)
    val alpha = 0.45f + 0.55f * light
    drawCircle(DotBack, rr, p)
    drawCircle(color, rr - r * 0.08f, p, alpha = alpha, style = Stroke(r * 0.16f))
    drawCircle(lerp(color, Color.White, light), rr * (0.4f + 0.22f * light), p, alpha = alpha)
}

/** The slash through Slasher Says' dots ([trail]; [r] their radius): a white line in a wide red glow. */
internal fun DrawScope.drawSlashTrail(trail: Path, r: Float) {
    drawPath(trail, BloodGlow, alpha = 0.22f, style = Stroke(r * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(trail, BloodGlow, alpha = 0.5f, style = Stroke(r * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(trail, Color.White, style = Stroke(r * 0.26f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The remote's aim on the dot at [p] ([r] its radius): a white ring round it, ticked at the four points, rimmed dark. */
private fun DrawScope.drawAimRing(p: Offset, r: Float) {
    val ring = r * 1.5f
    for ((color, width, alpha) in AimStrokes) {
        drawCircle(color, ring, p, alpha = alpha, style = Stroke(r * width))
        for (k in 0 until 4) {
            val (ux, uy) = AimTicks[k]
            drawLine(
                color,
                Offset(p.x + ux * r * 1.25f, p.y + uy * r * 1.25f),
                Offset(p.x + ux * r * 1.85f, p.y + uy * r * 1.85f),
                strokeWidth = r * width,
                cap = StrokeCap.Round,
                alpha = alpha,
            )
        }
    }
}

/** The aim ring's strokes, under to over: a dark rim, then the white. Widths in the dot's radii. */
private val AimStrokes = listOf(Triple(Ink, 0.26f, 0.55f), Triple(Color.White, 0.1f, 1f))
private val AimTicks = listOf(1f to 0f, 0f to 1f, -1f to 0f, 0f to -1f)

/** A right dot, flashing for [sinceMs] after the player slashed a wrong one: on, off, [FLASHES] times, then on. */
private fun flashLight(sinceMs: Long): Float {
    val cycle = FLASH_ON_MS + FLASH_OFF_MS
    if (sinceMs >= FLASHES * cycle) return 1f
    return if (sinceMs % cycle < FLASH_ON_MS) 1f else 0f
}

private fun Offset.toPair(): Pair<Float, Float> = x to y
