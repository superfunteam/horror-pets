package team.superfun.horrorpets.ui.fight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
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
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.assets.has
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.fight.other
import team.superfun.horrorpets.fight.attackFor
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.Attack
import team.superfun.horrorpets.model.SpecialEffect
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.ui.PetAnimRequest
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextHi

// ---- staging, as fractions of the screen ----

/** Each fighter's whole sprite cell is this tall (the same on 4:3 and 16:9). */
private const val FIGHTER_HEIGHT = 0.38f

/**
 * Where the fighters stand. Neither is mirrored: the attack sprites face the camera. On a narrow screen the
 * player stands a little further in, clear of the command window (see [playerHomeFraction]).
 */
private const val PLAYER_X = 0.30f
private const val CPU_X = 0.70f

/** How far the widest idle pose reaches out from a fighter's centre, in dp: Leatherface's saw (Jason's machete is 56). */
private const val FIGHTER_REACH_DP = 60f

/** The gap kept between the command window and the player's widest pose, in dp. */
private const val MENU_CLEARANCE_DP = 10f

/** How far an attacker steps toward the other fighter, as a fraction of the screen width. */
private const val STEP = 0.12f

/**
 * Floating numbers start this far up the defender's cell (about its chest). Like the two below, it is a
 * fraction of the cell as drawn, display scale included, so on a half-height Chucky they find his body.
 */
private const val FLOAT_AT = 0.42f

/** The bottom of a move callout sits just over the fighter's head, this far up its cell. */
private const val CALLOUT_AT = 0.95f

/** The tell's thought dots end about at the top of the CPU's head, this far up its cell. */
private const val TELL_AT = 0.84f

/** The body's centre, for the shield ring and the special effects, this far up the cell. */
private const val BODY_AT = 0.45f

/** A potion bursts this far up the drinker's cell (about the top of its head), and is this tall in flight. */
private const val POTION_LANDS_AT = 0.85f
private const val POTION_SIZE = 0.3f

/** The potion's picture (made by tools/generate_potion.py). */
private const val POTION_ART = "sprites/fight/potion.webp"

// ---- timing, in ms ----

private const val CALLOUT_MS = 600

/** The first beat starts while the callouts fade out. */
private const val CALLOUT_LEAD_MS = 470L
private const val STEP_IN_MS = 240

private const val STEP_BACK_MS = 260
private const val FLASH_MS = 250
private const val SHAKE_MS = 250
private const val DRAIN_MS = 450
private const val TRAIL_DELAY_MS = 380L
private const val TRAIL_MS = 560
private const val METER_MS = 360
private const val HEAL_MS = 600

/** The potion's flight onto the drinker, and its poof. */
private const val POTION_FLIGHT_MS = 620
private const val POTION_POOF_MS = 650
private const val MENU_MS = 200

/** How long a sprite may take beyond its own length before the turn moves on without it. */
private const val ANIM_SLACK_MS = 1_200L

/**
 * Strips decoded during the VS card: everything a swing and the K.O. finale need, for both fighters. That
 * is 12, the whole of the loader's strip cache. WAIT (a guard) and the run-off are left to decode when a
 * turn first needs them (see [turnStrips]), during its callouts.
 */
private val PLAYER_FIGHT_ANIMS = listOf(Anim.IDLE, Anim.RUN_RIGHT, Anim.ATTACK, Anim.FAIL, Anim.JUMP, Anim.WAVE)
private val CPU_FIGHT_ANIMS = listOf(Anim.IDLE, Anim.RUN_LEFT, Anim.ATTACK, Anim.FAIL, Anim.JUMP, Anim.WAVE)

/** The longest a turn waits, after its callouts, for its strips to finish decoding. */
private const val STRIPS_WAIT_MS = 1_500L

/** The longest a swing waits for its ATTACK (or DANCE) to come on screen before timing its impact anyway. */
private const val SWING_START_WAIT_MS = 600L

/** The arena: one of the [opponent]'s scenes, picked by the fight's [roll] (see `HorrorPetsApp`). */
internal fun arenaScene(loader: AssetLoader, opponent: PetId, roll: Int): SceneSpec {
    val scenes = loader.scenes().getValue(opponent)
    return scenes[roll.mod(scenes.size)]
}

/** Whether [pet] performs its special with its DANCE: [dancesSpecial] for its special and its sprites. */
private fun petDancesSpecial(loader: AssetLoader, pet: PetId): Boolean =
    dancesSpecial(pet.spec.special, loader.manifest(pet).has(Anim.DANCE))

/**
 * Decodes the strips a fight between [player] and [cpu] starts with. Blocking: call it off the main thread.
 * A dancer's DANCE is not among them: it decodes during the callouts of the turn it first dances in.
 */
internal fun warmFightStrips(loader: AssetLoader, player: PetId, cpu: PetId) {
    for (anim in PLAYER_FIGHT_ANIMS) loader.strip(player, anim)
    for (anim in CPU_FIGHT_ANIMS) loader.strip(cpu, anim)
}

/**
 * The turn-based fight in the opponent's [arena][arenaScene]: the player's pet at 30% of the width (a little
 * more on a narrow screen, see [playerHomeFraction]), the CPU's at 70%, both cells 38% of the height and
 * neither mirrored; Street-Fighter HP bars, names and special meters along the top; the moves in an RPG
 * command window down the left (`STRIKE HEAVY BLOCK [SPECIAL] | RUN`, see [CommandWindow]) with the
 * move's explanation in a help window under it. While the player picks, the CPU's tell for the coming turn
 * (`fight.cpuPlan`) sits in a thought bubble over its head ([TellBubble]).
 *
 * **A turn.** When the view model resolves a move it hands over the turn's [Screen.Fight.lastEvents] and
 * a marker [PetAnimRequest]. This screen plays the turn out and then reports the marker finished, exactly
 * once, with [onAnimFinished] (the view model ignores moves until then):
 * 1. Both chosen moves pop up over the fighters (600ms), with FEINT! over the CPU when its tell lied.
 * 2. Each event in order (see [beatsOf]): the attacker runs 12% of the width in, swings (ATTACK, or its
 *    DANCE for a danced special, see [swingAnim]) and hops back. At the impact the defender flashes white
 *    and shakes, the damage number floats up and the HP bar drains with it; a crit is a big Amber number
 *    with CRITICAL! and a screen shake, a HEAVY flashes the screen, a SPECIAL darkens the arena and plays its
 *    pet's effect ([SpecialVfx]); a miss shows a grey MISS as the defender sways away, a block a shield ring
 *    and BLOCK before the blocker's counter. Each event's sound plays at its impact ([TurnSounds]), and now and
 *    then the announcer reacts to the turn's biggest moment as it lands ([Callouts]); a special
 *    whose blow comes later in its effect (The Shape's slash) lands its sound, number and drain then (see
 *    [specialLandsAtMs]).
 * 3. If the fight is over (always read from `fight.winner`), the loser plays FAIL and greys out under a
 *    slammed-in K.O. while the winner plays JUMP then WAVE; after a RUN the player runs off instead.
 *    Otherwise the bars settle on the real values and the command window comes back, the cursor on the last move.
 *
 * Back (or the RUN command, or a phone's back gesture) asks "Run away?"; Yes runs ([Move.RUN]). On a phone the
 * commands take taps, and the help window follows the command last pressed, as it follows the cursor on a TV.
 * [focusEntry] is the router's entry key
 * (see `RequestFocusOnEntry`); null (the screen is fading out) turns off Back. [isBusy] reads the view
 * model's `busy` live, so a key that arrives before the screen has recomposed is still turned down.
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
    isBusy: () -> Boolean = { false },
) {
    val fight = screen.fight
    val playerId = fight.player.id
    val cpuId = fight.cpu.id
    val sink = LocalSoundSink.current
    val arena = remember(loader, cpuId, arenaRoll) { arenaScene(loader, cpuId, arenaRoll) }
    val potionImage = remember(loader) { loader.prop(POTION_ART) }
    val tint = remember(arena) { hexColor(arena.tint) }
    val stage = remember {
        FightStage(
            screen,
            turnPending = petAnim != null,
            playerLook = FighterLook(loader.manifest(playerId).bodyScale, petDancesSpecial(loader, playerId)),
            cpuLook = FighterLook(loader.manifest(cpuId).bodyScale, petDancesSpecial(loader, cpuId)),
        )
    }
    val finished by rememberUpdatedState(onAnimFinished)
    val entry by rememberUpdatedState(focusEntry)

    val requesters = remember { Move.entries.associateWith { FocusRequester() } }
    var lastMove by remember { mutableStateOf(Move.STRIKE) }
    var focusedMove by remember { mutableStateOf(Move.STRIKE) }
    var askRun by remember { mutableStateOf(false) }
    // The command that had focus when "Run away?" opened, to give it back on No.
    var returnTo by remember { mutableStateOf(Move.STRIKE) }
    // A request to put focus back on the menu, served after the recomposition that makes its commands
    // focusable again (they are not while the dialog is up). A new instance is a new request.
    var refocus by remember { mutableStateOf<Refocus?>(null) }
    // A turn is on from the moment the view model hands it over (its marker) until it has played out. The
    // marker alone covers the frame before playback starts; `playing` covers a recreated screen replaying.
    val turnActive = petAnim != null || stage.playing
    // Keys are handled between frames, so a Back right behind OK can arrive before the recomposition that
    // shows the new turn: ask the view model itself whether a move is still being played.
    val canAct = { !isBusy() && !stage.playing && fight.winner == null && screen.pvp?.waiting != true }
    // Read from the meter as shown, which only moves while a turn plays: SPECIAL comes and goes while
    // the menu is hidden, never under the player's eyes. Not cleared when the fight ends either: the menu
    // is fading out then, and a row vanishing mid-fade flickers.
    val specialReady by remember { derivedStateOf { stage.player.meter.value >= FightTuning.METER_MAX - 0.5f } }
    // A potion is offered while any are left, and is worth drinking only below full health (as the bar shows it).
    val potions = fight.playerPotions
    val maxHp = fight.player.maxHp
    val potionUsable by remember(maxHp) { derivedStateOf { stage.player.hp.value < maxHp - 0.5f } }

    fun focusMenu(move: Move) {
        val target = when {
            move == Move.SPECIAL && !specialReady -> Move.STRIKE
            move == Move.POTION && potions == 0 -> Move.STRIKE
            else -> move
        }
        // Not attached yet (or any more) only means there is nothing to focus.
        runCatching { requesters.getValue(target).requestFocus() }
    }

    GameBackHandler(enabled = focusEntry != null && !askRun) {
        if (!turnActive && canAct()) {
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

    // PvP: the menu steps aside once this player has locked in, until the turn plays (which brings it back).
    val waiting = screen.pvp?.waiting == true
    LaunchedEffect(waiting) {
        if (waiting) stage.menu.animateTo(0f, tween(MENU_MS))
    }

    // One turn's playback per marker token, reported once when it has played out.
    LaunchedEffect(petAnim?.token) {
        val request = petAnim ?: return@LaunchedEffect
        stage.playTurn(screen, loader, sink)
        finished(request.token)
        if (screen.fight.winner == null && entry != null && !askRun) focusMenu(lastMove)
    }

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { stage.screenTop = it.positionInRoot().y }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val cellH = h * FIGHTER_HEIGHT
        val density = LocalDensity.current
        val shakePx = with(density) { 14.dp.toPx() }
        val jitterPx = with(density) { 4.dp.toPx() }
        val halfWidth = with(density) { (w * 0.4f).toDp() }
        val safe = safeInset()
        val insetPx = with(density) { safe.horizontal.toPx() }
        // Read below as the fighters, their effects and their callouts are placed; it only follows the screen's
        // size, so writing it here gives every reader this layout's value.
        stage.playerHome = playerHomeFraction(maxWidth.value, safe.horizontal.value + CommandWindowWidth.value)
        SideEffect {
            stage.width = w
            stage.height = h
        }

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
                            center = { Offset(w * stage.homeFraction(side) + rig.dir * rig.step.value * w, floorY - cellH * rig.body * BODY_AT) },
                            radius = cellH * rig.body * 0.44f,
                            progress = { rig.shield.value },
                        )
                    }
                }
                SpecialVfx(stage.special, progress = { stage.specialT.value })
                // Learned attacks' marks on whoever they land on.
                for (m in stage.markings) key(m) { AttackMarks(m.fx, progress = { m.t.value }) }
                PotionLayer(stage.toss, potionImage, sizePx = cellH * POTION_SIZE)
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

        // The callouts and floating numbers hold still while the arena shakes, so they stay inside the
        // safe inset, and they keep below the HUD (see placeInBounds).
        val clear = with(density) { 6.dp.toPx() }
        val belowHud = { stage.hudBottom + clear }
        for (side in Side.entries) {
            val rig = stage.rig(side)
            val move = rig.calloutMove
            if (move != null) {
                key(side, rig.calloutKey) {
                    MoveCallout(
                        text = calloutText(move, rig.kit),
                        move = move,
                        caption = calloutCaption(move),
                        progress = { rig.callout.value },
                        x = { w * stage.homeFraction(side) },
                        y = { stage.floor - cellH * rig.body * CALLOUT_AT },
                        top = belowHud,
                        inset = insetPx,
                        maxWidth = halfWidth,
                    )
                }
            }
        }
        // PvP: the other player has locked in their move (which stays hidden until both have).
        if (screen.pvp?.opponentIn == true && !turnActive && fight.winner == null) {
            ThoughtBubble(
                caption = "READY",
                text = "LOCKED IN!",
                accent = PotionGreen,
                ink = TextHi,
                visible = { stage.menu.value.coerceAtLeast(if (screen.pvp.waiting) 1f else 0f) },
                x = { w * stage.homeFraction(Side.CPU) },
                y = { stage.floor - cellH * stage.cpu.body * TELL_AT },
                top = belowHud,
                inset = insetPx,
            )
        }
        // The CPU's tell for the coming turn, over its head while the player picks.
        stage.shownTell?.let { tell ->
            key(tell) {
                TellBubble(
                    tell = tell,
                    visible = { stage.menu.value },
                    x = { w * stage.homeFraction(Side.CPU) },
                    y = { stage.floor - cellH * stage.cpu.body * TELL_AT },
                    top = belowHud,
                    inset = insetPx,
                )
            }
        }
        FloatingNumbers(stage.floats, top = belowHud, inset = insetPx, onDone = { stage.floats.remove(it) })

        Box(Modifier.fillMaxSize().padding(horizontal = safe.horizontal, vertical = safe.vertical)) {
            FightHud(
                player = fight.player,
                cpu = fight.cpu,
                turn = shownTurn(fight, turnActive),
                playerValues = stage.player.hud,
                cpuValues = stage.cpu.hud,
                playerStatus = stage.player.status,
                cpuStatus = stage.cpu.status,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onGloballyPositioned { stage.hudBottom = it.boundsInRoot().bottom - stage.screenTop },
            )
            // The command window down the left, its help window along the floor under it. Both slide in
            // from the left as the menu comes back after a turn.
            val menuIn = Modifier.graphicsLayer {
                val m = stage.menu.value
                alpha = m
                translationX = -(1f - m) * 40.dp.toPx()
            }
            CommandWindow(
                specialReady = specialReady,
                potions = potions,
                potionUsable = potionUsable,
                requesters = requesters,
                canFocus = !askRun,
                onFocused = { focusedMove = it },
                onPick = { move ->
                    if (!turnActive && canAct()) {
                        if (move == Move.RUN) {
                            returnTo = Move.RUN
                            askRun = true
                        } else if (move == Move.POTION && !potionUsable) {
                            sink.sfx("refuse") // at full health a potion would be wasted
                        } else {
                            lastMove = move
                            onMove(move)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = HelpHeight + HelpGap)
                    .then(menuIn),
            )
            val hint = if (focusedMove == Move.POTION && !potionUsable) POTION_FULL_HINT else moveHint(focusedMove, fight.player.kit)
            HelpWindow(hint, Modifier.align(Alignment.BottomStart).then(menuIn))
        }

        // PvP: this player has locked in and the other has not yet.
        if (screen.pvp?.waiting == true && !turnActive) {
            Box(Modifier.fillMaxSize().padding(bottom = safe.vertical + 18.dp), contentAlignment = Alignment.BottomCenter) {
                WaitingBanner(fight.cpu.id.displayName)
            }
        }

        KoSlam(visible = stage.koShown, progress = { stage.ko.value }, lift = { stage.koLift.value }, liftPx = h * KO_LIFT)
        // A tier 3 special's cut-in, over everything but the run dialog.
        SuperCutIn(stage.superFx, progress = { stage.superT.value }, loader = loader)

        AnimatedVisibility(
            visible = askRun,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160)),
        ) {
            RunDialog(
                // The window grows in as the scrim fades in, and settles back as it fades out.
                modifier = Modifier.animateEnterExit(
                    enter = scaleIn(tween(200), initialScale = 0.9f),
                    exit = scaleOut(tween(160), targetScale = 0.95f),
                ),
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

/**
 * Where the player stands, as a fraction of a screen [widthDp] wide: [PLAYER_X], or as far in as it takes for
 * its widest pose to clear the command window, whose right edge is [menuRightDp] from the left (on a 4:3
 * screen, about 0.32).
 */
internal fun playerHomeFraction(widthDp: Float, menuRightDp: Float): Float =
    max(PLAYER_X, (menuRightDp + MENU_CLEARANCE_DP + FIGHTER_REACH_DP) / widthDp)

/**
 * The turn the badge shows: the one being played while a turn is on, or the last one played once the
 * fight is over, never the next. Running away plays no turn (the engine leaves `turn` as it was).
 */
private fun shownTurn(fight: FightState, turnActive: Boolean): Int {
    val played = (turnActive || fight.winner != null) && !fight.playerRan && !fight.cpuRan
    return if (played) (fight.turn - 1).coerceAtLeast(1) else fight.turn
}

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
                homeX = w * stage.homeFraction(side),
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

/**
 * One showing of an animation; a new instance restarts playback even for the same [anim]. [started]
 * completes when its first frame is on screen, [ended] when it has played once (or was replaced).
 */
@Stable
private class Pose(val anim: Anim) {
    val started = CompletableDeferred<Unit>()
    val ended = CompletableDeferred<Unit>()
}

/**
 * Everything one fighter shows that the turn playback drives: its animation, where it stands (a step
 * toward the other fighter and a hop), its hit flash and shake, its K.O. greying, its block shield, its
 * move callout, and its HUD values. All of it is animated state read in layout or draw, so a turn never
 * recomposes the screen frame by frame.
 */
@Stable
private class FighterRig(val side: Side, val pet: PetId, hp: Float, meter: Float, look: FighterLook, start: Fighter) {
    /** Its display scale (see `bodyScale`). */
    val body = look.body

    /** Whether it performs its special with its DANCE (see [swingAnim]). */
    val dancesSpecial = look.dancesSpecial

    /** The attacks it fights with (see [Fighter.kit]): what its callouts name, its marks and its meter fills. */
    val kit = start.kit

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

    /**
     * Its lasting states as the HUD shows them: a combo broken the moment it is hit or whiffs and built up as the
     * turn ends, a bleed or daze put on when the special lands and worn off as it plays out.
     */
    var combo by mutableIntStateOf(start.combo)
    var bleed by mutableIntStateOf(start.bleed)
    var dazed by mutableStateOf(start.dazed)
    val status = HudStatus(combo = { combo }, bleed = { bleed }, dazed = { dazed })

    /** Where the bars are heading, ahead of their animations. */
    var hpTarget = hp
    var meterTarget = meter

    /** Shows [anim] (a loop, or a pose to hold) and returns its showing, to wait on. */
    fun show(anim: Anim): Pose {
        // Whoever waits on the showing being replaced is let go.
        pose.started.complete(Unit)
        pose.ended.complete(Unit)
        return Pose(anim).also { pose = it }
    }

    /** Plays [anim] once and returns when it ends, or after [timeoutMs] if it never reports. */
    suspend fun play(anim: Anim, timeoutMs: Long) {
        val p = show(anim)
        withTimeoutOrNull(timeoutMs) { p.ended.await() }
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
        onStarted = { pose.started.complete(Unit) },
        onFinished = { pose.ended.complete(Unit) },
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

/** What a fighter's sprites say about how it is staged: its display [body] scale and whether it [dancesSpecial]. */
@Immutable
private class FighterLook(val body: Float, val dancesSpecial: Boolean)

/** Everything the turn playback animates, remembered for the life of one fight screen. */
@Stable
private class FightStage(screen: Screen.Fight, turnPending: Boolean, playerLook: FighterLook, cpuLook: FighterLook) {
    val player: FighterRig
    val cpu: FighterRig

    init {
        val f = screen.fight
        // Normally the bars start at the fight's real values. A screen built mid-turn (the Activity was
        // recreated while a turn played) replays that turn, so it starts from the values before it.
        val p = if (turnPending) hudBeforeTurn(f.player, Side.PLAYER, screen.lastEvents) else HudStart(f.player.hp, f.player.meter)
        val c = if (turnPending) hudBeforeTurn(f.cpu, Side.CPU, screen.lastEvents) else HudStart(f.cpu.hp, f.cpu.meter)
        player = FighterRig(Side.PLAYER, f.player.id, p.hp.toFloat(), p.meter.toFloat(), playerLook, f.player)
        cpu = FighterRig(Side.CPU, f.cpu.id, c.hp.toFloat(), c.meter.toFloat(), cpuLook, f.cpu)
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

    /** The potion in the air or bursting, while a Drink beat plays. */
    var toss by mutableStateOf<PotionToss?>(null)

    /** Learned attacks' marks still showing, each on its own clock. */
    val markings = mutableStateListOf<Marking>()

    /** A tier 3 special's cut-in while it plays, and its clock. */
    var superFx by mutableStateOf<SuperFx?>(null)
    val superT = Animatable(0f)
    val specialT = Animatable(0f)
    val menu = Animatable(if (turnPending) 0f else 1f)
    var playing by mutableStateOf(turnPending)

    /** The tell over the CPU while the menu is up: the plan's, swapped only while the menu is away. */
    var shownTell by mutableStateOf(if (turnPending) null else screen.fight.cpuPlan?.tell)
    var front by mutableStateOf(Side.PLAYER)

    /** The announcer's reactions, for the whole fight so they never repeat back to back. */
    val callouts = Callouts(Random.Default)
    val floats = mutableStateListOf<FloatText>()
    private var nextFloat = 0L

    // The last layout's geometry, for placing effects (main thread only).
    var width = 0f
    var height = 0f

    /** The arena's floor line, once its scene is up (0 until then). Read in layout by the callouts. */
    var floorY by mutableFloatStateOf(0f)

    /** The bottom of the HUD and the top of this screen, in root pixels: floating words keep below the HUD. */
    var hudBottom by mutableFloatStateOf(0f)
    var screenTop = 0f

    fun rig(side: Side): FighterRig = if (side == Side.PLAYER) player else cpu

    private val cellH: Float get() = height * FIGHTER_HEIGHT
    val floor: Float get() = if (floorY > 0f) floorY else height * 0.75f

    /** Where the player stands, as a fraction of the width: see [playerHomeFraction]. */
    var playerHome = PLAYER_X

    fun homeFraction(side: Side): Float = if (side == Side.PLAYER) playerHome else CPU_X

    private fun homeX(side: Side): Float = width * homeFraction(side)

    fun float(kind: FloatKind, text: String, labels: List<String>, side: Side) {
        floats += FloatText(nextFloat++, kind, text, labels, homeX(side), floor - cellH * rig(side).body * FLOAT_AT)
    }

    /** The middle of [side]'s body as drawn (a half-height Chucky's is lower). */
    fun bodyCenter(side: Side): Offset = Offset(homeX(side), floor - cellH * rig(side).body * BODY_AT)

    fun specialOn(pet: PetId, target: Side): SpecialFx {
        val c = bodyCenter(target)
        return SpecialFx(pet.spec.special, c.x, c.y, cellH * rig(target).body)
    }

    /** How much a sprite of [rig] playing [anim] may take before the turn stops waiting for it. */
    fun timeoutFor(loader: AssetLoader, rig: FighterRig, anim: Anim): Long =
        loader.manifest(rig.pet).spec(anim).durationsMs.sumOf { it.coerceAtLeast(0) }.toLong() + ANIM_SLACK_MS
}

/** The strips [turn] shows, by pet: idles, guards, swings, and the finale or the run-off. At most 11. */
private fun FightStage.turnStrips(turn: Screen.Fight): List<Pair<PetId, Anim>> = buildList {
    val fight = turn.fight
    for (side in Side.entries) add(rig(side).pet to Anim.IDLE)
    if (turn.playerMove == Move.BLOCK) add(player.pet to Anim.WAIT)
    if (turn.cpuMove == Move.BLOCK) add(cpu.pet to Anim.WAIT)
    for (beat in beatsOf(turn.lastEvents, fight.winner)) {
        if (beat is Beat.Drink) add(rig(beat.side).pet to Anim.JUMP)
        if (beat !is Beat.Lunge) continue
        val r = rig(beat.attacker)
        add(r.pet to (if (beat.attacker == Side.PLAYER) Anim.RUN_RIGHT else Anim.RUN_LEFT))
        add(r.pet to swingAnim(beat.event, r.dancesSpecial))
    }
    val winner = fight.winner
    when {
        fight.playerRan -> {
            add(player.pet to Anim.RUN_LEFT)
            add(cpu.pet to Anim.WAVE)
        }
        fight.cpuRan -> {
            add(cpu.pet to Anim.RUN_RIGHT)
            add(player.pet to Anim.WAVE)
        }
        winner != null -> {
            add(rig(winner.other()).pet to Anim.FAIL)
            add(rig(winner).pet to Anim.JUMP)
            add(rig(winner).pet to Anim.WAVE)
        }
    }
}.distinct()

/** Plays [turn] out; returns when it is over (the caller then reports its marker finished). */
private suspend fun FightStage.playTurn(turn: Screen.Fight, loader: AssetLoader, sink: SoundSink) = coroutineScope {
    val fight = turn.fight
    val winner = fight.winner
    playing = true
    // The tell has been acted on: it goes with the menu (the next turn's must not show yet).
    shownTell = null
    launch { menu.animateTo(0f, tween(MENU_MS)) }
    // Every strip this turn shows decodes during the callouts (most are cached since the VS card), so
    // no decode ever holds up a swing and puts its impact out of step with the sprite.
    val strips = launch(Dispatchers.IO) {
        for ((pet, anim) in turnStrips(turn)) loader.strip(pet, anim)
    }
    val sounds = TurnSounds(fight, turn.lastEvents, callouts)
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
    // A tell that lied is called out as the real move pops up.
    if (turn.cpuTell != null && turn.cpuMove != null && turn.cpuTell != turn.cpuMove) {
        float(FloatKind.FEINT, "FEINT!", emptyList(), Side.CPU)
    }
    delay(CALLOUT_LEAD_MS)
    withTimeoutOrNull(STRIPS_WAIT_MS) { strips.join() }

    if (turn.playerMove == Move.RUN) {
        runAway(this, loader)
        playing = false
        return@coroutineScope
    }
    if (turn.cpuMove == Move.RUN) {
        opponentRunsAway(this, loader)
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
            while (i < beats.size && beats[i].ridesOnLunge) riders += beats[i++]
            // The same fighter must be back on its mark first, and a special's effect must have played
            // out; otherwise the other fighter may set off as the last one lands.
            if (tailSide == beat.attacker || special != null) tail?.join() else if (tail?.isActive == true) delay(140)
            tail = lunge(this, beat, riders, guarding, loader, sounds, play)
            tailSide = beat.attacker
        } else if (beat is Beat.Drink) {
            tail?.join()
            drink(this, beat, loader, sounds, play)
        } else if (beat is Beat.Bleed) {
            tail?.join()
            bleed(this, beat, sounds, play)
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
            r.combo = f.combo
            r.bleed = f.bleed
            r.dazed = f.dazed
        }
        shownTell = fight.cpuPlan?.tell
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
 * One attacker's swing: run in, ATTACK (or its DANCE, see [swingAnim]), the impact (see [swingImpactMs]),
 * then a hop back to the mark. Returns once the swing has played; the returned job is the hop back (and any
 * special effect still running), which the next beat may overlap.
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
    // A tier 3 special is a super: its cut-in plays before the attacker sets off.
    if (special && a.kit.special.tier == 3) {
        superFx = SuperFx(a.pet, a.kit.special.name, fromLeft = a.side == Side.PLAYER)
        scope.launch {
            delay((SUPER_MS * 0.26f).toLong())
            play(FightSounds(listOf("crit"), emptyList()))
        }
        superT.snapTo(0f)
        superT.animateTo(1f, tween(SUPER_MS, easing = LinearEasing))
        superFx = null
        superT.snapTo(0f)
    }
    if (special) {
        // The meter is spent the moment the special starts.
        a.meterTarget = 0f
        scope.launch { a.meter.animateTo(0f, tween(METER_MS)) }
    }

    a.show(if (beat.attacker == Side.PLAYER) Anim.RUN_RIGHT else Anim.RUN_LEFT)
    a.step.animateTo(STEP, tween(STEP_IN_MS, easing = FastOutSlowInEasing))

    val swingAnim = swingAnim(event, a.dancesSpecial)
    val swingSpec = loader.manifest(a.pet).spec(swingAnim)
    val impact = swingImpactMs(swingAnim, swingSpec)
    val swingPose = a.show(swingAnim)
    val swing = scope.launch { withTimeoutOrNull(timeoutFor(loader, a, swingAnim)) { swingPose.ended.await() } }
    // The impact is timed from the swing's first frame on screen, not from asking for it.
    withTimeoutOrNull(SWING_START_WAIT_MS) { swingPose.started.await() }
    if (special) scope.launch {
        delay(max(0L, impact - 220L))
        darken.animateTo(0.5f + 0.08f * a.kit.special.tier, tween(220))
    }
    delay(impact)
    val effect = impactOf(scope, a, d, event, riders, guarding, sounds, play)
    // An ATTACK's last frames only ease back to standing: hop back on the way out of them instead. A dance
    // is danced to its end.
    delay(swingHoldMs(swingAnim, swingSpec, impact))
    a.show(Anim.IDLE)
    swing.join()

    return scope.launch {
        launch {
            val up = -cellHeight() * a.body * 0.05f
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
    // A special whose blow lands partway through its effect (The Shape's slash) holds its number, flash and
    // HP drain until then, and starts its sound so the clip's hit lands with them (see specialSoundAtMs);
    // everything else lands right at the impact.
    val specialHit = event as? FightEvent.Hit
    val isSpecial = specialHit?.move == Move.SPECIAL
    val landMs = if (isSpecial) specialLandsAtMs(a.pet.spec.special) else 0L
    val soundMs = if (specialHit != null && isSpecial) specialSoundAtMs(landMs, a.pet.spec.specialHitMs, specialHit.crit) else 0L
    val sound = sounds.of(event)
    if (soundMs == 0L) {
        play(sound)
    } else {
        scope.launch {
            delay(soundMs)
            play(sound)
        }
    }
    // An interrupt is the defender's: say it on the defender's number rather than over it.
    val interrupted = riders.any { it is Beat.Interrupt && it.side == d.side }
    var effect: Job? = null
    when (event) {
        is FightEvent.Hit -> {
            // The attack that hit (none for a counter, the same for every kit).
            val attack = if (event.counter) null else a.kit.attackFor(event.move)
            val breaksGuard = event.move == Move.HEAVY && d.side in guarding
            // A dazed guard holds nothing: the strike goes straight through it.
            val dazedGuard = d.side in guarding && d.dazed
            if (d.side in guarding && (event.move != Move.STRIKE || dazedGuard)) {
                guarding -= d.side
                d.show(Anim.IDLE)
            }
            // Being hit ends the defender's combo; the attacker's grows once the turn is over.
            d.combo = 0
            val labels = buildList {
                if (event.combo >= 1) add("COMBO \u00d7${event.combo + 1}")
                if (event.crit) add("CRITICAL!")
                if (event.counter) add("COUNTER")
                if (breaksGuard) add("GUARD BREAK")
                if (dazedGuard && !breaksGuard) add("DAZED")
                if (interrupted) add("INTERRUPTED")
                when (event.effect) {
                    SpecialEffect.BLEED -> add("BLEEDING")
                    SpecialEffect.DAZE -> add("DAZED")
                    SpecialEffect.DRAIN, null -> Unit
                }
            }
            val blow = {
                float(if (event.crit) FloatKind.CRIT else FloatKind.DAMAGE, "\u2212${event.damage}", labels, d.side)
                hurt(scope, d, strength = 1f)
                drain(scope, d, event.damage)
                when (event.effect) {
                    SpecialEffect.BLEED -> d.bleed = maxOf(d.bleed, attack?.bleed?.turns ?: FightTuning.BLEED_TURNS)
                    SpecialEffect.DAZE -> d.dazed = true
                    SpecialEffect.DRAIN, null -> Unit
                }
                if (event.shattered > 0) shatter(scope, d, event.shattered)
                if (attack != null) mark(scope, a, d, attack)
                when {
                    event.crit -> scope.launch {
                        shake.snapTo(1f)
                        shake.animateTo(0f, tween(SHAKE_MS, easing = LinearEasing))
                    }
                    event.move == Move.HEAVY -> flash(scope, Color.White, 0.22f, 160)
                }
            }
            if (landMs == 0L) {
                blow()
            } else {
                scope.launch {
                    delay(landMs)
                    blow()
                }
            }
            if (event.move != Move.SPECIAL) charge(scope, a, if (event.counter) FightTuning.METER_PER_HIT else attack?.meterGain ?: FightTuning.METER_PER_HIT)
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
                    delay(landMs + SPECIAL_MS / 3L)
                    hurt(scope, d, strength = 0.4f)
                }
            }
        }

        is FightEvent.Miss -> {
            a.combo = 0
            if (event.dazed) a.dazed = false
            val labels = listOfNotNull("DAZED".takeIf { event.dazed }, "INTERRUPTED".takeIf { interrupted })
            float(FloatKind.MISS, "MISS", labels, d.side)
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
    // A drain flows back to the attacker just after its blow lands.
    for (r in riders.filterIsInstance<Beat.Drain>()) scope.launch {
        delay(landMs + DRAIN_AFTER_MS)
        play(sounds.of(FightEvent.Drain(r.side, r.amount)))
        float(FloatKind.HEAL, "+${r.amount}", listOf("DRAIN"), r.side)
        a.flash.snapTo(0.45f)
        launch { a.flash.animateTo(0f, tween(450)) }
        heal(scope, a, r.amount)
    }
    return effect
}

/** A drain's +HP follows its special's blow by this much, so the two numbers read one after the other. */
private const val DRAIN_AFTER_MS = 420L

/** [beat]'s side bleeds at the end of the turn: a smaller red number with BLEED, a shudder, and its bar drains. */
private suspend fun FightStage.bleed(scope: CoroutineScope, beat: Beat.Bleed, sounds: TurnSounds, play: (FightSounds) -> Unit) {
    val r = rig(beat.side)
    play(sounds.of(FightEvent.Bleed(beat.side, beat.damage)))
    float(FloatKind.DAMAGE, "\u2212${beat.damage}", listOf("BLEED"), beat.side)
    hurt(scope, r, strength = 0.45f)
    drain(scope, r, beat.damage)
    r.bleed = (r.bleed - 1).coerceAtLeast(0)
    delay(BLEED_BEAT_MS)
}

private const val BLEED_BEAT_MS = 560L

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
        is Beat.Lunge, is Beat.Drink, is Beat.Drain, is Beat.Bleed -> Unit
    }
}

/**
 * A potion is tossed onto [beat]'s side: it arcs in from that side's edge of the screen, spinning, with a whoosh;
 * it bursts on the drinker's head in a green poof, the drinker lights up and jumps for joy, a green +HP number
 * floats up and the HP bar fills. Returns when the poof has cleared, so the opponent's move comes after it.
 */
private suspend fun FightStage.drink(
    scope: CoroutineScope,
    beat: Beat.Drink,
    loader: AssetLoader,
    sounds: TurnSounds,
    play: (FightSounds) -> Unit,
) {
    val r = rig(beat.side)
    val center = bodyCenter(beat.side)
    val head = Offset(center.x, floor - cellHeight() * r.body * POTION_LANDS_AT)
    val edge = if (beat.side == Side.PLAYER) -cellHeight() * 0.4f else width + cellHeight() * 0.4f
    val toss = PotionToss(from = Offset(edge, floor - cellHeight() * 0.15f), to = head, spin = if (beat.side == Side.PLAYER) 1f else -1f)
    this.toss = toss
    play(FightSounds(listOf("hand_whoosh"), emptyList()))
    toss.flight.animateTo(1f, tween(POTION_FLIGHT_MS, easing = LinearEasing))
    play(sounds.of(FightEvent.Heal(beat.side, beat.amount)))
    flash(scope, PotionGreen, alpha = 0.2f, ms = 350)
    scope.launch {
        r.flash.snapTo(0.55f)
        r.flash.animateTo(0f, tween(450))
    }
    heal(scope, r, beat.amount)
    float(FloatKind.HEAL, "+${beat.amount}", emptyList(), beat.side)
    scope.launch {
        r.play(Anim.JUMP, timeoutFor(loader, r, Anim.JUMP))
        if (r.pose.anim == Anim.JUMP) r.show(Anim.IDLE)
    }
    toss.poof.animateTo(1f, tween(POTION_POOF_MS, easing = LinearEasing))
    if (this.toss === toss) this.toss = null
}

/** [d]'s HP bar fills by [amount]; its trail rides up with it (hidden under the fill), ready for the next hit. */
private fun heal(scope: CoroutineScope, d: FighterRig, amount: Int) {
    val target = d.hpTarget + amount
    d.hpTarget = target
    scope.launch { d.hp.animateTo(target, tween(HEAL_MS, easing = FastOutSlowInEasing)) }
    scope.launch { d.trail.animateTo(target, tween(HEAL_MS, easing = FastOutSlowInEasing)) }
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

/** One showing of a learned attack's marks: its [fx] and its clock. */
@Stable
private class Marking(val fx: MarkFx) {
    val t = Animatable(0f)
}

/** [attack]'s marks on [d] as it lands, if it leaves any (see [markPlan]): they play out and clear. */
private fun FightStage.mark(scope: CoroutineScope, a: FighterRig, d: FighterRig, attack: Attack) {
    val plan = markPlan(attack.slot, attack.tier) ?: return
    val c = bodyCenter(d.side)
    val m = Marking(MarkFx(a.pet, attack.slot, attack.tier, c.x, c.y, cellHeight() * d.body, seed = Random.nextInt(1_000)))
    markings += m
    scope.launch {
        m.t.animateTo(1f, tween(plan.ms, easing = LinearEasing))
        markings.remove(m)
    }
}

/** [d]'s special meter loses [amount] to a shatter. */
private fun shatter(scope: CoroutineScope, d: FighterRig, amount: Int) {
    val target = max(0f, d.meterTarget - amount)
    d.meterTarget = target
    scope.launch { d.meter.animateTo(target, tween(METER_MS, easing = FastOutSlowInEasing)) }
}

private fun charge(scope: CoroutineScope, a: FighterRig, gain: Int) {
    val target = min(FightTuning.METER_MAX.toFloat(), a.meterTarget + gain)
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

/** PvP: the other player turns tail: it runs off to the right and fades, and the player waves it goodbye. */
private suspend fun FightStage.opponentRunsAway(scope: CoroutineScope, loader: AssetLoader) {
    front = Side.CPU
    cpu.show(Anim.RUN_RIGHT)
    scope.launch {
        delay(200)
        player.play(Anim.WAVE, timeoutFor(loader, player, Anim.WAVE))
        player.show(Anim.IDLE)
    }
    scope.launch { cpu.alpha.animateTo(0f, tween(500, delayMillis = 250)) }
    // Its "step" runs toward the player; negative is away, off the right edge.
    cpu.step.animateTo(-0.4f, tween(800, easing = FastOutLinearInEasing))
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
