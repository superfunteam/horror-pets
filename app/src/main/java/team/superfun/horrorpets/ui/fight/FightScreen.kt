package team.superfun.horrorpets.ui.fight

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.SceneSpec
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.other
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.PetAnimRequest
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.FocusPillSurface
import team.superfun.horrorpets.ui.common.GlassPanel
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.bodyStyle
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.safeInsetPadding
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

// ---- staging, as fractions of the screen ----

/** Each fighter's whole sprite cell is this tall (the same on 4:3 and 16:9). */
private const val FIGHTER_HEIGHT = 0.38f

/** Where the fighters stand. Neither is mirrored: the attack sprites face the camera. */
private const val PLAYER_X = 0.30f
private const val CPU_X = 0.70f

/** How far an attacker steps toward the other fighter, as a fraction of the screen width. */
private const val STEP = 0.12f

/** Floating numbers start this far up the defender's cell (about its chest). */
private const val FLOAT_AT = 0.42f

/** The bottom of a move callout sits just over the fighter's head, this far up its cell. */
private const val CALLOUT_AT = 0.95f

/** The body's centre, for the shield ring and the special effects, this far up the cell. */
private const val BODY_AT = 0.45f

// ---- timing, in ms ----

private const val CALLOUT_MS = 600

/** The first beat starts while the callouts fade out. */
private const val CALLOUT_LEAD_MS = 470L
private const val STEP_IN_MS = 240

/** The attacker hops back this long after the impact, cutting the end of its swing's recovery short. */
private const val AFTER_IMPACT_MS = 420L
private const val STEP_BACK_MS = 260
private const val FLASH_MS = 250
private const val SHAKE_MS = 250
private const val DRAIN_MS = 450
private const val TRAIL_DELAY_MS = 380L
private const val TRAIL_MS = 560
private const val METER_MS = 360
private const val MENU_MS = 200

/** How long a sprite may take beyond its own length before the turn moves on without it. */
private const val ANIM_SLACK_MS = 1_200L

/** Strips a fight needs from the first turn: decoded during the VS card so the first swing starts at once. */
private val PLAYER_FIGHT_ANIMS = listOf(Anim.IDLE, Anim.RUN_RIGHT, Anim.ATTACK, Anim.WAIT)
private val CPU_FIGHT_ANIMS = listOf(Anim.IDLE, Anim.RUN_LEFT, Anim.ATTACK, Anim.WAIT)

/** The arena: one of the [opponent]'s scenes, picked by the fight's [roll] (see `HorrorPetsApp`). */
internal fun arenaScene(loader: AssetLoader, opponent: PetId, roll: Int): SceneSpec {
    val scenes = loader.scenes().getValue(opponent)
    return scenes[roll.mod(scenes.size)]
}

/** Decodes the strips a fight between [player] and [cpu] starts with. Blocking: call it off the main thread. */
internal fun warmFightStrips(loader: AssetLoader, player: PetId, cpu: PetId) {
    for (anim in PLAYER_FIGHT_ANIMS) loader.strip(player, anim)
    for (anim in CPU_FIGHT_ANIMS) loader.strip(cpu, anim)
}

/**
 * The turn-based fight in the opponent's [arena][arenaScene]: the player's pet at 30% of the width, the
 * CPU's at 70%, both cells 38% of the height and neither mirrored; Street-Fighter HP bars, names and
 * special meters along the top; the move pills `STRIKE HEAVY BLOCK [SPECIAL] | RUN` along the bottom.
 *
 * **A turn.** When the view model resolves a move it hands over the turn's [Screen.Fight.lastEvents] and
 * a marker [PetAnimRequest]. This screen plays the turn out and then reports the marker finished, exactly
 * once, with [onAnimFinished] (the view model ignores moves until then):
 * 1. Both chosen moves pop up over the fighters (600ms).
 * 2. Each event in order (see [beatsOf]): the attacker runs 12% of the width in, swings (ATTACK) and hops
 *    back. At the impact the defender flashes white and shakes, the damage number floats up and the HP bar
 *    drains with it; a crit is a big Amber number with CRITICAL! and a screen shake, a HEAVY flashes the
 *    screen, a SPECIAL darkens the arena and plays its pet's effect ([SpecialVfx]); a miss shows a grey MISS
 *    as the defender sways away, a block a shield ring and BLOCK before the blocker's counter. Each event's
 *    sound plays at its impact ([TurnSounds]).
 * 3. If the fight is over (always read from `fight.winner`), the loser plays FAIL and greys out under a
 *    slammed-in K.O. while the winner plays JUMP then WAVE; after a RUN the player runs off instead.
 *    Otherwise the bars settle on the real values and the move pills come back, focus on the last move.
 *
 * Back (or the RUN pill) asks "Run away?"; Yes runs ([Move.RUN]). [focusEntry] is the router's entry key
 * (see `RequestFocusOnEntry`); null (the screen is fading out) turns off Back.
 */
@Composable
fun FightScreen(
    screen: Screen.Fight,
    petAnim: PetAnimRequest?,
    loader: AssetLoader,
    arenaRoll: Int,
    onMove: (Move) -> Unit,
    onAnimFinished: (Long) -> Unit,
    focusEntry: Any? = Unit,
) {
    val fight = screen.fight
    val playerId = fight.player.id
    val cpuId = fight.cpu.id
    val sink = LocalSoundSink.current
    val arena = remember(loader, cpuId, arenaRoll) { arenaScene(loader, cpuId, arenaRoll) }
    val tint = remember(arena) { hexColor(arena.tint) }
    val stage = remember { FightStage(screen, turnPending = petAnim != null) }
    val finished by rememberUpdatedState(onAnimFinished)
    val entry by rememberUpdatedState(focusEntry)

    val requesters = remember { Move.entries.associateWith { FocusRequester() } }
    var lastMove by remember { mutableStateOf(Move.STRIKE) }
    var focusedMove by remember { mutableStateOf(Move.STRIKE) }
    var askRun by remember { mutableStateOf(false) }
    // The pill that had focus when "Run away?" opened, to give it back on No.
    var returnTo by remember { mutableStateOf(Move.STRIKE) }
    // A request to put focus back on the menu, served after the recomposition that makes its pills
    // focusable again (they are not while the dialog is up). A new instance is a new request.
    var refocus by remember { mutableStateOf<Refocus?>(null) }
    val over = fight.winner != null
    // Read from the meter as shown, which only moves while a turn plays: the pill comes and goes while
    // the menu is hidden, never under the player's eyes. Not cleared when the fight ends either: the menu
    // is fading out then, and a pill vanishing mid-fade flickers.
    val specialReady by remember { derivedStateOf { stage.player.meter.value >= FightTuning.METER_MAX - 0.5f } }

    fun focusMenu(move: Move) {
        val target = if (move == Move.SPECIAL && !specialReady) Move.STRIKE else move
        // Not attached yet (or any more) only means there is nothing to focus.
        runCatching { requesters.getValue(target).requestFocus() }
    }

    BackHandler(enabled = focusEntry != null && !askRun) {
        if (!stage.playing && !over) {
            returnTo = focusedMove
            askRun = true
        }
    }
    RequestFocusOnEntry(requesters.getValue(Move.STRIKE), focusEntry)

    LaunchedEffect(loader, playerId, cpuId) {
        withContext(Dispatchers.IO) { warmFightStrips(loader, playerId, cpuId) }
    }
    // Asked to run: decode the run-off (the player's run left, the CPU's goodbye wave) while it decides.
    LaunchedEffect(askRun) {
        if (askRun) {
            withContext(Dispatchers.IO) {
                loader.strip(playerId, Anim.RUN_LEFT)
                loader.strip(cpuId, Anim.WAVE)
            }
        }
    }

    LaunchedEffect(refocus) {
        refocus?.let { focusMenu(it.move) }
    }

    // One turn's playback per marker token, reported once when it has played out.
    LaunchedEffect(petAnim?.token) {
        val request = petAnim ?: return@LaunchedEffect
        stage.playTurn(screen, loader, sink)
        finished(request.token)
        if (screen.fight.winner == null && entry != null && !askRun) focusMenu(lastMove)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val cellH = h * FIGHTER_HEIGHT
        val density = LocalDensity.current
        val shakePx = with(density) { 14.dp.toPx() }
        val jitterPx = with(density) { 4.dp.toPx() }
        val halfWidth = with(density) { (w * 0.4f).toDp() }
        stage.width = w
        stage.height = h

        // The arena (scene, fighters, effects) shakes as one; the HUD and menu hold still.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = stage.shake.value
                    if (k > 0f) {
                        val seed = (k * 60f).toInt()
                        translationX = (hash01(seed, 1) - 0.5f) * 2f * shakePx * k
                        translationY = (hash01(seed, 2) - 0.5f) * 2f * shakePx * k
                        // A touch of zoom keeps the scene's edges off screen while it moves.
                        val zoom = 1f + 0.035f * k
                        scaleX = zoom
                        scaleY = zoom
                    }
                },
        ) {
            SceneBackdrop(loader = loader, scene = arena) { floorY ->
                SideEffect { stage.floorY = floorY }
                // The arena dims while a special plays, so its effect owns the screen.
                Spacer(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val d = stage.darken.value
                            if (d > 0f) drawRect(Color.Black, alpha = d)
                        },
                )
                // The fighters in a box of their own, so the attacker's z-order stays among the fighters
                // and every effect and number below still draws over both.
                Box(Modifier.fillMaxSize()) {
                    FightersLayer(stage, loader, w, floorY, cellH, tint, arena.tintStrength, arena.shadowOpacity, jitterPx)
                }
                for (side in Side.entries) {
                    val rig = stage.rig(side)
                    key(side) {
                        ShieldRing(
                            center = { Offset(w * homeFraction(side) + rig.dir * rig.step.value * w, floorY - cellH * BODY_AT) },
                            radius = cellH * 0.44f,
                            progress = { rig.shield.value },
                        )
                    }
                }
                SpecialVfx(stage.special, progress = { stage.specialT.value })
                for (side in Side.entries) {
                    val rig = stage.rig(side)
                    val move = rig.calloutMove
                    if (move != null) {
                        key(side, rig.calloutKey) {
                            MoveCallout(
                                text = calloutText(move, rig.pet),
                                move = move,
                                progress = { rig.callout.value },
                                x = { w * homeFraction(side) },
                                y = floorY - cellH * CALLOUT_AT,
                                maxWidth = halfWidth,
                            )
                        }
                    }
                }
                FloatingNumbers(stage.floats, onDone = { stage.floats.remove(it) })
            }
        }

        // The full-screen flash of a heavy blow or the K.O.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val a = stage.flash.value
                    if (a > 0f) drawRect(stage.flashColor, alpha = a)
                },
        )

        Box(Modifier.fillMaxSize().padding(safeInsetPadding())) {
            FightHud(
                player = fight.player,
                cpu = fight.cpu,
                // The turn being played (or the last one, once it is over), not the next one.
                turn = if (stage.playing || over) (fight.turn - 1).coerceAtLeast(1) else fight.turn,
                playerValues = stage.player.hud,
                cpuValues = stage.cpu.hud,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            MoveMenu(
                pet = playerId,
                specialReady = specialReady,
                requesters = requesters,
                canFocus = !askRun,
                focused = focusedMove,
                onFocused = { focusedMove = it },
                onPick = { move ->
                    if (!stage.playing && !over) {
                        if (move == Move.RUN) {
                            returnTo = Move.RUN
                            askRun = true
                        } else {
                            lastMove = move
                            onMove(move)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        val m = stage.menu.value
                        alpha = m
                        translationY = (1f - m) * 36.dp.toPx()
                    },
            )
        }

        KoSlam(visible = stage.koShown, progress = { stage.ko.value }, lift = { stage.koLift.value }, liftPx = h * KO_LIFT)

        AnimatedVisibility(
            visible = askRun,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160)),
        ) {
            RunDialog(
                onYes = {
                    askRun = false
                    lastMove = Move.RUN
                    onMove(Move.RUN)
                },
                onNo = {
                    askRun = false
                    refocus = Refocus(returnTo)
                },
            )
        }
    }
}

private fun homeFraction(side: Side): Float = if (side == Side.PLAYER) PLAYER_X else CPU_X

/** One request to focus the menu on [move]. */
@Stable
private class Refocus(val move: Move)

/** Both fighters, the one attacking drawn in front. */
@Composable
private fun FightersLayer(
    stage: FightStage,
    loader: AssetLoader,
    w: Float,
    floorY: Float,
    cellH: Float,
    tint: Color,
    tintStrength: Float,
    shadowOpacity: Float,
    jitterPx: Float,
) {
    for (side in Side.entries) {
        key(side) {
            FighterView(
                loader = loader,
                rig = stage.rig(side),
                homeX = w * homeFraction(side),
                width = w,
                floorY = floorY,
                cellH = cellH,
                tint = tint,
                tintStrength = tintStrength,
                shadowOpacity = shadowOpacity,
                jitterPx = jitterPx,
                front = stage.front == side,
            )
        }
    }
}

// ---- the fighters ----

/** One showing of an animation; a new instance restarts playback even for the same [anim]. */
@Stable
private class Pose(val anim: Anim)

/**
 * Everything one fighter shows that the turn playback drives: its animation, where it stands (a step
 * toward the other fighter and a hop), its hit flash and shake, its K.O. greying, its block shield, its
 * move callout, and its HUD values. All of it is animated state read in layout or draw, so a turn never
 * recomposes the screen frame by frame.
 */
@Stable
private class FighterRig(val side: Side, val pet: PetId, hp: Float, meter: Float) {
    /** +1 when stepping toward the other fighter moves right (the player), -1 for the CPU. */
    val dir = if (side == Side.PLAYER) 1f else -1f

    var pose by mutableStateOf(Pose(Anim.IDLE))
        private set
    val step = Animatable(0f)
    val hop = Animatable(0f)
    val alpha = Animatable(1f)
    val flash = Animatable(0f)
    val shake = Animatable(0f)
    val ko = Animatable(0f)
    val shield = Animatable(0f)
    val callout = Animatable(0f)
    var calloutMove by mutableStateOf<Move?>(null)
    var calloutKey by mutableIntStateOf(0)

    val hp = Animatable(hp)
    val trail = Animatable(hp)
    val meter = Animatable(meter)
    val hud = HudValues(hp = { this.hp.value }, trail = { trail.value }, meter = { this.meter.value })

    /** Where the bars are heading, ahead of their animations. */
    var hpTarget = hp
    var meterTarget = meter

    private var awaited: Pose? = null
    private var done: CompletableDeferred<Unit>? = null

    /** Shows [anim] without waiting for it (a loop, or a pose to hold). */
    fun show(anim: Anim) {
        done?.complete(Unit)
        awaited = null
        done = null
        pose = Pose(anim)
    }

    /** Plays [anim] once and returns when it ends, or after [timeoutMs] if it never reports. */
    suspend fun play(anim: Anim, timeoutMs: Long) {
        done?.complete(Unit)
        val p = Pose(anim)
        val d = CompletableDeferred<Unit>()
        awaited = p
        done = d
        pose = p
        withTimeoutOrNull(timeoutMs) { d.await() }
    }

    /** The sprite finished playing [p] (once through, for a loop). */
    fun finished(p: Pose) {
        if (p === awaited) done?.complete(Unit)
    }
}

/** Steps of the white hit flash: the sprite's color filter is rebuilt per step, not per frame. */
private const val FLASH_STEPS = 6f

@Composable
private fun FighterView(
    loader: AssetLoader,
    rig: FighterRig,
    homeX: Float,
    width: Float,
    floorY: Float,
    cellH: Float,
    tint: Color,
    tintStrength: Float,
    shadowOpacity: Float,
    jitterPx: Float,
    front: Boolean,
) {
    val flash by remember(rig) { derivedStateOf { (rig.flash.value * FLASH_STEPS).roundToInt() / FLASH_STEPS } }
    val ko by remember(rig) { derivedStateOf { (rig.ko.value * FLASH_STEPS).roundToInt() / FLASH_STEPS } }
    val pose = rig.pose
    SpriteView(
        loader = loader,
        pet = rig.pet,
        anim = pose.anim,
        heightPx = cellH,
        tint = tint,
        tintStrength = tintStrength,
        dim = 0.35f * ko,
        saturation = 1f - 0.8f * ko,
        flashWhite = flash,
        shadowOpacity = shadowOpacity,
        loopOffsetMs = if (rig.side == Side.CPU) 380L else 0L,
        playKey = pose,
        onFinished = { rig.finished(pose) },
        modifier = Modifier
            .zIndex(if (front) 1f else 0f)
            .placeAt(x = { homeX + rig.dir * rig.step.value * width }, y = { floorY })
            .graphicsLayer {
                val k = rig.shake.value
                if (k > 0f) translationX = (hash01((k * 50f).toInt(), 7 + rig.side.ordinal) - 0.5f) * 2f * jitterPx * k
                translationY = rig.hop.value
                alpha = rig.alpha.value
            },
    )
}

// ---- the stage and the turn playback ----

/** Everything the turn playback animates, remembered for the life of one fight screen. */
@Stable
private class FightStage(screen: Screen.Fight, turnPending: Boolean) {
    val player: FighterRig
    val cpu: FighterRig

    init {
        val f = screen.fight
        // Normally the bars start at the fight's real values. A screen built mid-turn (the Activity was
        // recreated while a turn played) replays that turn, so it starts from the values before it.
        val (playerHp, playerMeter) = if (turnPending) before(f.player, Side.PLAYER, screen.lastEvents) else now(f.player)
        val (cpuHp, cpuMeter) = if (turnPending) before(f.cpu, Side.CPU, screen.lastEvents) else now(f.cpu)
        player = FighterRig(Side.PLAYER, f.player.id, playerHp, playerMeter)
        cpu = FighterRig(Side.CPU, f.cpu.id, cpuHp, cpuMeter)
    }

    val shake = Animatable(0f)
    val flash = Animatable(0f)
    var flashColor by mutableStateOf(Color.White)
    val darken = Animatable(0f)
    val ko = Animatable(0f)

    /** 0 while K.O. holds the centre, 1 once it has lifted above the fighters' heads. */
    val koLift = Animatable(0f)
    var koShown by mutableStateOf(false)
    var special by mutableStateOf<SpecialFx?>(null)
    val specialT = Animatable(0f)
    val menu = Animatable(if (turnPending) 0f else 1f)
    var playing by mutableStateOf(turnPending)
    var front by mutableStateOf(Side.PLAYER)
    val floats = mutableStateListOf<FloatText>()
    private var nextFloat = 0L

    // The last layout's geometry, for placing effects (main thread only).
    var width = 0f
    var height = 0f
    var floorY = 0f

    fun rig(side: Side): FighterRig = if (side == Side.PLAYER) player else cpu

    private val cellH: Float get() = height * FIGHTER_HEIGHT
    private val floor: Float get() = if (floorY > 0f) floorY else height * 0.75f

    private fun homeX(side: Side): Float = width * homeFraction(side)

    fun float(kind: FloatKind, text: String, labels: List<String>, side: Side) {
        floats += FloatText(nextFloat++, kind, text, labels, homeX(side), floor - cellH * FLOAT_AT)
    }

    fun bodyCenter(side: Side): Offset = Offset(homeX(side), floor - cellH * BODY_AT)

    fun specialOn(pet: PetId, target: Side): SpecialFx {
        val c = bodyCenter(target)
        return SpecialFx(pet, c.x, c.y, cellH)
    }

    /** How much a sprite of [rig] playing [anim] may take before the turn stops waiting for it. */
    fun timeoutFor(loader: AssetLoader, rig: FighterRig, anim: Anim): Long =
        loader.manifest(rig.pet).spec(anim).durationsMs.sumOf { it.coerceAtLeast(0) }.toLong() + ANIM_SLACK_MS
}

private fun now(f: Fighter): Pair<Float, Float> = f.hp.toFloat() to f.meter.toFloat()

/** A side's HP and meter before the turn whose [events] produced [f], as near as the events tell. */
private fun before(f: Fighter, side: Side, events: List<FightEvent>): Pair<Float, Float> {
    val taken = events.sumOf { if (it is FightEvent.Hit && it.attacker != side) it.damage else 0 }
    val usedSpecial = events.any { it is FightEvent.Hit && it.attacker == side && it.move == Move.SPECIAL }
    val landed = events.count { it is FightEvent.Hit && it.attacker == side && it.move != Move.SPECIAL }
    val meter = if (usedSpecial) FightTuning.METER_MAX else max(0, f.meter - FightTuning.METER_PER_HIT * landed)
    return min(f.maxHp, f.hp + taken).toFloat() to meter.toFloat()
}

/** Plays [turn] out; returns when it is over (the caller then reports its marker finished). */
private suspend fun FightStage.playTurn(turn: Screen.Fight, loader: AssetLoader, sink: SoundSink) = coroutineScope {
    val fight = turn.fight
    val winner = fight.winner
    playing = true
    launch { menu.animateTo(0f, tween(MENU_MS)) }
    if (winner != null && !fight.playerRan) {
        // The finale's strips decode while the turn plays.
        launch(Dispatchers.IO) {
            loader.strip(rig(winner.other()).pet, Anim.FAIL)
            loader.strip(rig(winner).pet, Anim.JUMP)
            loader.strip(rig(winner).pet, Anim.WAVE)
        }
    }
    val sounds = TurnSounds(fight)
    val play: (FightSounds) -> Unit = { s ->
        s.sfx.forEach(sink::sfx)
        if (s.voice.isNotEmpty()) sink.voice(s.voice)
    }
    // A side that chose BLOCK raises its guard (the wait pose) for the turn.
    val guarding = mutableSetOf<Side>()
    if (turn.playerMove == Move.BLOCK) guarding += Side.PLAYER
    if (turn.cpuMove == Move.BLOCK) guarding += Side.CPU
    for (side in guarding) rig(side).show(Anim.WAIT)

    // 1. Both moves pop up over the fighters.
    callout(this, player, turn.playerMove)
    callout(this, cpu, turn.cpuMove)
    delay(CALLOUT_LEAD_MS)

    if (turn.playerMove == Move.RUN) {
        runAway(this, loader)
        playing = false
        return@coroutineScope
    }

    // 2. The events, one beat at a time. An attacker hops back to its mark while the next beat starts.
    val beats = beatsOf(turn.lastEvents, winner)
    var tail: Job? = null
    var tailSide: Side? = null
    var i = 0
    while (i < beats.size) {
        val beat = beats[i++]
        if (beat is Beat.Lunge) {
            val riders = mutableListOf<Beat>()
            while (i < beats.size && beats[i] !is Beat.Lunge) riders += beats[i++]
            // The same fighter must be back on its mark first, and a special's effect must have played
            // out; otherwise the other fighter may set off as the last one lands.
            if (tailSide == beat.attacker || special != null) tail?.join() else if (tail?.isActive == true) delay(140)
            tail = lunge(this, beat, riders, guarding, loader, sounds, play)
            tailSide = beat.attacker
        } else {
            rider(this, beat, landedOn = null)
            delay(450)
        }
    }
    tail?.join()

    // 3. The finale, or back to the menu.
    if (winner != null && !fight.playerRan) {
        finale(this, winner, loader, sounds, play)
    } else {
        for (side in Side.entries) {
            val r = rig(side)
            val f = if (side == Side.PLAYER) fight.player else fight.cpu
            if (r.pose.anim == Anim.WAIT) r.show(Anim.IDLE)
            // The bars should already agree with the fight; make sure they do.
            if (r.hpTarget != f.hp.toFloat() || r.hp.value != f.hp.toFloat()) {
                r.hpTarget = f.hp.toFloat()
                launch { r.hp.animateTo(f.hp.toFloat(), tween(200)) }
                launch { r.trail.animateTo(f.hp.toFloat(), tween(300)) }
            }
            if (r.meterTarget != f.meter.toFloat() || r.meter.value != f.meter.toFloat()) {
                r.meterTarget = f.meter.toFloat()
                launch { r.meter.animateTo(f.meter.toFloat(), tween(200)) }
            }
        }
        menu.animateTo(1f, tween(MENU_MS))
    }
    playing = false
}

private fun callout(scope: CoroutineScope, rig: FighterRig, move: Move?) {
    if (move == null) return
    rig.calloutKey++
    rig.calloutMove = move
    val key = rig.calloutKey
    scope.launch {
        rig.callout.snapTo(0f)
        rig.callout.animateTo(1f, tween(CALLOUT_MS, easing = LinearEasing))
        if (rig.calloutKey == key) rig.calloutMove = null
    }
}

/**
 * One attacker's swing: run in, ATTACK, the impact at its snap frame, then a hop back to the mark.
 * Returns once the swing has played; the returned job is the hop back (and any special effect still
 * running), which the next beat may overlap.
 */
private suspend fun FightStage.lunge(
    scope: CoroutineScope,
    beat: Beat.Lunge,
    riders: List<Beat>,
    guarding: MutableSet<Side>,
    loader: AssetLoader,
    sounds: TurnSounds,
    play: (FightSounds) -> Unit,
): Job {
    val a = rig(beat.attacker)
    val d = rig(beat.attacker.other())
    val event = beat.event
    val special = event is FightEvent.Hit && event.move == Move.SPECIAL
    front = beat.attacker
    guarding -= beat.attacker
    if (special) {
        // The meter is spent the moment the special starts.
        a.meterTarget = 0f
        scope.launch { a.meter.animateTo(0f, tween(METER_MS)) }
    }

    a.show(if (beat.attacker == Side.PLAYER) Anim.RUN_RIGHT else Anim.RUN_LEFT)
    a.step.animateTo(STEP, tween(STEP_IN_MS, easing = FastOutSlowInEasing))

    val attack = loader.manifest(a.pet).spec(Anim.ATTACK)
    val impact = impactMs(attack)
    val swing = scope.launch { a.play(Anim.ATTACK, timeoutFor(loader, a, Anim.ATTACK)) }
    if (special) scope.launch {
        delay(max(0L, impact - 220L))
        darken.animateTo(0.5f, tween(220))
    }
    delay(impact)
    val effect = impactOf(scope, a, d, event, riders, guarding, sounds, play)
    // The swing's last frames only ease back to standing: hop back on the way out of them instead.
    delay(AFTER_IMPACT_MS)
    a.show(Anim.IDLE)
    swing.join()

    return scope.launch {
        launch {
            val up = -cellHeight() * 0.05f
            a.hop.animateTo(up, tween(STEP_BACK_MS / 2, easing = FastOutSlowInEasing))
            a.hop.animateTo(0f, tween(STEP_BACK_MS / 2, easing = FastOutLinearInEasing))
        }
        a.step.animateTo(0f, tween(STEP_BACK_MS, easing = FastOutSlowInEasing))
        effect?.join()
    }
}

private fun FightStage.cellHeight(): Float = height * FIGHTER_HEIGHT

/** What lands at the impact. Returns the special effect's job, if one is playing. */
private fun FightStage.impactOf(
    scope: CoroutineScope,
    a: FighterRig,
    d: FighterRig,
    event: FightEvent,
    riders: List<Beat>,
    guarding: MutableSet<Side>,
    sounds: TurnSounds,
    play: (FightSounds) -> Unit,
): Job? {
    play(sounds.of(event))
    // An interrupt is the defender's: say it on the defender's number rather than over it.
    val interrupted = riders.any { it is Beat.Interrupt && it.side == d.side }
    var effect: Job? = null
    when (event) {
        is FightEvent.Hit -> {
            val breaksGuard = event.move == Move.HEAVY && d.side in guarding
            if (d.side in guarding && event.move != Move.STRIKE) {
                guarding -= d.side
                d.show(Anim.IDLE)
            }
            val labels = buildList {
                if (event.crit) add("CRITICAL!")
                if (event.counter) add("COUNTER")
                if (breaksGuard) add("GUARD BREAK")
                if (interrupted) add("INTERRUPTED")
            }
            float(if (event.crit) FloatKind.CRIT else FloatKind.DAMAGE, "${event.damage}", labels, d.side)
            hurt(scope, d, strength = 1f)
            drain(scope, d, event.damage)
            if (event.move != Move.SPECIAL) charge(scope, a)
            when {
                event.crit -> scope.launch {
                    shake.snapTo(1f)
                    shake.animateTo(0f, tween(SHAKE_MS, easing = LinearEasing))
                }
                event.move == Move.HEAVY -> flash(scope, Color.White, 0.22f, 160)
            }
            if (event.move == Move.SPECIAL) {
                special = specialOn(a.pet, d.side)
                effect = scope.launch {
                    specialT.snapTo(0f)
                    specialT.animateTo(1f, tween(SPECIAL_MS, easing = LinearEasing))
                    special = null
                    specialT.snapTo(0f)
                    darken.animateTo(0f, tween(260))
                }
                // The target keeps reeling through the effect (a lighter flash, so the effect still reads over it).
                scope.launch {
                    delay(SPECIAL_MS / 3L)
                    hurt(scope, d, strength = 0.4f)
                }
            }
        }

        is FightEvent.Miss -> {
            float(FloatKind.MISS, "MISS", if (interrupted) listOf("INTERRUPTED") else emptyList(), d.side)
            // The defender sways out of the way.
            scope.launch {
                d.step.animateTo(-0.03f, tween(110, easing = FastOutSlowInEasing))
                d.step.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
            }
        }

        is FightEvent.Blocked -> {
            // Here the defender is the blocker; it keeps its guard up until its counter.
            float(FloatKind.BLOCK, "BLOCK", emptyList(), d.side)
            scope.launch {
                d.shield.snapTo(0.001f)
                d.shield.animateTo(1f, tween(SHIELD_MS, easing = LinearEasing))
                d.shield.snapTo(0f)
            }
            // The striker is knocked back off the shield.
            scope.launch {
                a.shake.snapTo(0.6f)
                a.shake.animateTo(0f, tween(SHAKE_MS, easing = LinearEasing))
            }
        }

        else -> Unit
    }
    for (r in riders) rider(scope, r, landedOn = if (interrupted) d.side else null)
    return effect
}

/** The beats that ride on a swing: an interrupt not already shown on a number, and a filled meter. */
private fun FightStage.rider(scope: CoroutineScope, beat: Beat, landedOn: Side?) {
    when (beat) {
        is Beat.Interrupt -> if (beat.side != landedOn) {
            float(FloatKind.MISS, "INTERRUPTED", emptyList(), beat.side)
            hurt(scope, rig(beat.side), strength = 0.5f)
        }
        is Beat.Ready -> scope.launch {
            delay(380)
            float(FloatKind.READY, "SPECIAL READY", emptyList(), beat.side)
        }
        is Beat.Lunge -> Unit
    }
}

private fun hurt(scope: CoroutineScope, d: FighterRig, strength: Float) {
    scope.launch {
        d.flash.snapTo(strength)
        d.flash.animateTo(0f, tween(FLASH_MS))
    }
    scope.launch {
        d.shake.snapTo(strength)
        d.shake.animateTo(0f, tween(SHAKE_MS, easing = LinearEasing))
    }
}

private fun drain(scope: CoroutineScope, d: FighterRig, damage: Int) {
    val target = max(0f, d.hpTarget - damage)
    d.hpTarget = target
    scope.launch { d.hp.animateTo(target, tween(DRAIN_MS, easing = FastOutSlowInEasing)) }
    scope.launch {
        delay(TRAIL_DELAY_MS)
        d.trail.animateTo(target, tween(TRAIL_MS, easing = FastOutSlowInEasing))
    }
}

private fun charge(scope: CoroutineScope, a: FighterRig) {
    val target = min(FightTuning.METER_MAX.toFloat(), a.meterTarget + FightTuning.METER_PER_HIT)
    a.meterTarget = target
    scope.launch { a.meter.animateTo(target, tween(METER_MS, easing = FastOutSlowInEasing)) }
}

private fun FightStage.flash(scope: CoroutineScope, color: Color, alpha: Float, ms: Int) {
    flashColor = color
    scope.launch {
        flash.snapTo(alpha)
        flash.animateTo(0f, tween(ms))
    }
}

/** The player turns tail: it runs off to the left and fades, and the CPU waves it goodbye. */
private suspend fun FightStage.runAway(scope: CoroutineScope, loader: AssetLoader) {
    front = Side.PLAYER
    player.show(Anim.RUN_LEFT)
    scope.launch {
        delay(200)
        cpu.play(Anim.WAVE, timeoutFor(loader, cpu, Anim.WAVE))
        cpu.show(Anim.IDLE)
    }
    scope.launch { player.alpha.animateTo(0f, tween(500, delayMillis = 250)) }
    player.step.animateTo(-0.4f, tween(800, easing = FastOutLinearInEasing))
}

/** The K.O.: the loser plays FAIL and greys out as K.O. slams in, then the winner jumps and waves. */
private suspend fun FightStage.finale(
    scope: CoroutineScope,
    winner: Side,
    loader: AssetLoader,
    sounds: TurnSounds,
    play: (FightSounds) -> Unit,
) {
    val champ = rig(winner)
    val loser = rig(winner.other())
    front = winner
    delay(220)
    play(sounds.of(FightEvent.Ko(loser.side)))
    val fall = scope.launch { loser.play(Anim.FAIL, timeoutFor(loader, loser, Anim.FAIL)) }
    scope.launch { loser.ko.animateTo(1f, tween(900)) }
    koShown = true
    ko.snapTo(0f)
    ko.animateTo(1f, tween(190, easing = FastOutLinearInEasing))
    // It lands: the screen jolts and flashes red.
    flash(scope, Blood, 0.38f, 420)
    scope.launch {
        shake.snapTo(1f)
        shake.animateTo(0f, tween(420, easing = LinearEasing))
    }
    delay(600)
    // K.O. rises out of the way so the winner's celebration is seen.
    scope.launch { koLift.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    champ.play(Anim.JUMP, timeoutFor(loader, champ, Anim.JUMP))
    champ.play(Anim.WAVE, timeoutFor(loader, champ, Anim.WAVE))
    fall.join()
    delay(500)
}

// ---- K.O. ----

private val KoFill = Brush.verticalGradient(listOf(Color(0xFFFF6A72), Color(0xFFE0262F), Color(0xFF7A0912)))

/** Where K.O. settles after its slam: this far up from the centre (a fraction of the height), over the heads. */
private const val KO_LIFT = 0.17f
private const val KO_SETTLED_SCALE = 0.62f

/** K.O. slams into the centre ([progress]), then lifts above the fighters and shrinks ([lift]). */
@Composable
private fun KoSlam(visible: Boolean, progress: () -> Float, lift: () -> Float, liftPx: Float) {
    if (!visible) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .graphicsLayer {
                    val p = progress()
                    val l = lift()
                    val s = lerp(3.4f, 1f, p * p) * lerp(1f, KO_SETTLED_SCALE, l)
                    scaleX = s
                    scaleY = s
                    translationY = -liftPx * l
                    alpha = (p / 0.3f).coerceAtMost(1f)
                }
                .drawWithCache {
                    // A red bloom behind the letters.
                    val r = size.width * 0.6f
                    val bloom = Brush.radialGradient(
                        0f to BloodGlow.copy(alpha = 0.3f),
                        0.5f to Blood.copy(alpha = 0.1f),
                        1f to Color.Transparent,
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = r,
                    )
                    onDrawBehind { drawCircle(bloom, radius = r, center = Offset(size.width / 2f, size.height / 2f)) }
                }
                .padding(horizontal = 60.dp, vertical = 10.dp),
        ) {
            OutlinedText(
                text = "K.O.",
                style = displayStyle(190.sp, tracking = 0.04f, lineHeight = 190.sp).copy(brush = KoFill),
                outline = 9.dp,
            )
        }
    }
}

// ---- the move menu ----

/** Small print under-explaining each move: the rock-paper-scissors at a glance. */
private fun moveHint(move: Move, pet: PetId): String = when (move) {
    Move.STRIKE -> "Quick hit  ·  interrupts a Heavy"
    Move.HEAVY -> "Big hit  ·  breaks a Block, but a Strike cuts it off"
    Move.BLOCK -> "Stops a Strike and counters  ·  a Heavy breaks it"
    Move.SPECIAL -> "${specialName(pet)}  ·  never misses"
    Move.RUN -> "Forfeit the fight  ·  costs a little mood"
}

private val MovePillWidth = 96.dp
private val MovePillPadding = 18.dp

/**
 * The move pills, bottom centre: `STRIKE HEAVY BLOCK [SPECIAL] | RUN`, with the focused move explained
 * above them. SPECIAL is there only with a full meter, and glows Amber until picked.
 */
@Composable
private fun MoveMenu(
    pet: PetId,
    specialReady: Boolean,
    requesters: Map<Move, FocusRequester>,
    canFocus: Boolean,
    focused: Move,
    onFocused: (Move) -> Unit,
    onPick: (Move) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            text = moveHint(focused, pet),
            style = bodyStyle(15.sp, color = TextLo, halo = true).copy(textAlign = TextAlign.Center),
            maxLines = 1,
        )
        Spacer(Modifier.height(14.dp))
        // No focus restorer here: focus only ever comes back to the menu by request (on entry, after a
        // turn, after the dialog), and a restorer would redirect those requests to its own choice.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val moves = buildList {
                add(Move.STRIKE)
                add(Move.HEAVY)
                add(Move.BLOCK)
                if (specialReady) add(Move.SPECIAL)
            }
            for (move in moves) {
                key(move) {
                    MovePill(move, requesters.getValue(move), canFocus, onFocused, onPick)
                }
            }
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = 3.dp, height = 30.dp)
                    .background(Color.White.copy(alpha = 0.28f)),
            )
            MovePill(Move.RUN, requesters.getValue(Move.RUN), canFocus, onFocused, onPick)
        }
    }
}

@Composable
private fun MovePill(
    move: Move,
    requester: FocusRequester,
    canFocus: Boolean,
    onFocused: (Move) -> Unit,
    onPick: (Move) -> Unit,
) {
    val base = Modifier
        .onFocusChanged { if (it.isFocused) onFocused(move) }
        .focusRequester(requester)
        .focusProperties { this.canFocus = canFocus }
    if (move != Move.SPECIAL) {
        FocusPill(
            label = move.name,
            onClick = { onPick(move) },
            modifier = base,
            minWidth = MovePillWidth,
            horizontalPadding = MovePillPadding,
        )
        return
    }
    // SPECIAL: an Amber label and a breathing Amber halo while it waits to be used.
    val pulse = rememberInfiniteTransition(label = "specialPill").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "specialPillPulse",
    )
    FocusPillSurface(
        onClick = { onPick(move) },
        modifier = base.drawBehind {
            softGlow(Amber, alpha = 0.55f * pulse.value, cornerRadius = size.height / 2f, spread = 16.dp)
        },
        minWidth = MovePillWidth,
        horizontalPadding = MovePillPadding,
    ) { focused ->
        BasicText(
            text = "SPECIAL",
            style = displayStyle(24.sp, tracking = 0.06f, color = if (focused) TextHi else Amber),
            maxLines = 1,
        )
    }
}

// ---- "Run away?" ----

@Composable
private fun RunDialog(onYes: () -> Unit, onNo: () -> Unit) {
    val noFocus = remember { FocusRequester() }
    BackHandler(onBack = onNo)
    LaunchedEffect(Unit) { runCatching { noFocus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = scaleIn(tween(200), initialScale = 0.9f),
        ) {
            GlassPanel(contentPadding = PaddingValues(horizontal = 56.dp, vertical = 30.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("RUN AWAY?", style = displayStyle(60.sp, tracking = 0.08f, lineHeight = 60.sp), maxLines = 1)
                    Spacer(Modifier.height(10.dp))
                    BasicText(
                        "You forfeit the fight and lose a little mood.",
                        style = bodyStyle(16.sp, color = TextLo).copy(textAlign = TextAlign.Center),
                    )
                    Spacer(Modifier.height(26.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        FocusPill("No", onNo, Modifier.focusRequester(noFocus), minWidth = 120.dp)
                        FocusPill("Yes", onYes, minWidth = 120.dp)
                    }
                }
            }
        }
    }
}
