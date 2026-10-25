package team.superfun.horrorpets.ui.fight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.game.pantry.MealStatus
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.StatusChip
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.common.mirrorToFace
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.rememberTouchTick
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow

// ---- the timeline ----

/**
 * The VS card as a timeline, in ms from its first frame, and where things on it are at a time `t`. The same beats as
 * the card it replaced, so it is never slower to get through.
 */
internal object VsTimeline {
    /** The two halves sweep in from the sides. */
    const val PANELS_MS = 380L

    /** The fighters slide in from their own sides. */
    const val FIGHTER_IN_AT = 120L
    const val FIGHTER_IN_MS = 520L

    /** The names slam in at the top, the player's first. */
    const val PLAYER_NAME_AT = 520L
    const val OPPONENT_NAME_AT = 820L
    const val NAME_SLAM_MS = 170L

    /** The opponent's VS logo slams into the centre, landing with the announcer's "versus", and the card jolts. */
    const val VS_AT = 1_150L
    const val VS_SLAM_MS = 210L
    const val VS_SHAKE_MS = 300L

    /** "FIGHT!", with the announcer's `fight` queued behind his "<player> versus <opponent>". */
    const val FIGHT_AT = 3_050L
    const val FIGHT_SLAM_MS = 180L

    /** The end: on to the fight. */
    const val DONE_AT = 3_800L

    /** One frame never advances the timeline more than this, so a stall slows it instead of skipping it. */
    const val MAX_STEP_MS = 100L

    /** How far the halves have swept in at [t], 0..1. */
    fun sweep(t: Long): Float = easeOutCubic((t / PANELS_MS.toFloat()).coerceIn(0f, 1f))

    /** How far the fighters have slid in at [t], 0..1. */
    fun fighterIn(t: Long): Float = easeOutCubic(((t - FIGHTER_IN_AT) / FIGHTER_IN_MS.toFloat()).coerceIn(0f, 1f))

    /** A slam's scale [since] it started: falling from [from] to exactly its size over [ms], then still. */
    fun slamScale(since: Long, ms: Long, from: Float): Float {
        val p = (since / ms.toFloat()).coerceIn(0f, 1f)
        return if (p >= 1f) 1f else from + (1f - from) * p * p
    }

    /** 0..1 screen shake: a hard jolt when VS lands, a lighter one for FIGHT! and each name. */
    fun shakeAt(t: Long): Float {
        fun pulse(at: Long, ms: Long, strength: Float): Float {
            val since = t - at
            if (since < 0 || since >= ms) return 0f
            val fade = 1f - since / ms.toFloat()
            return strength * fade * fade
        }
        return max(
            max(pulse(VS_AT + VS_SLAM_MS, VS_SHAKE_MS, 1f), pulse(FIGHT_AT + FIGHT_SLAM_MS, 260L, 0.6f)),
            max(pulse(PLAYER_NAME_AT + NAME_SLAM_MS, 140L, 0.3f), pulse(OPPONENT_NAME_AT + NAME_SLAM_MS, 140L, 0.3f)),
        )
    }

    /** 0..1 white flash over the card as VS, then FIGHT!, lands. */
    fun flashAt(t: Long): Float {
        fun pulse(at: Long, ms: Long, strength: Float): Float {
            val since = t - at
            if (since < 0 || since >= ms) return 0f
            return strength * (1f - since / ms.toFloat())
        }
        return max(pulse(VS_AT + VS_SLAM_MS, 220L, 0.32f), pulse(FIGHT_AT + FIGHT_SLAM_MS, 260L, 0.4f))
    }
}

private fun easeOutCubic(p: Float): Float = 1f - (1f - p) * (1f - p) * (1f - p)

/** What a frame of the card asks for, besides drawing. */
internal enum class VsCue {
    /** First frame: the announcer drops anything an earlier screen left him saying, for the card's own lines. */
    CLAIM_ANNOUNCER,

    /** The announcer's "fight!". */
    FIGHT_CALL,

    /** The card is over by itself: on to the fight. */
    DONE,
}

/** The cues, in order, and when each falls due ([CUE_AT]): side by side, so a frame walks them without allocating. */
private val CUES = arrayOf(VsCue.CLAIM_ANNOUNCER, VsCue.FIGHT_CALL, VsCue.DONE)
private val CUE_AT = longArrayOf(0L, VsTimeline.FIGHT_AT, VsTimeline.DONE_AT)

/**
 * One run of the card, frame by frame: where it is ([t]) and what falls due as it gets there, each once. It has no
 * clock of its own; the screen steps it once a frame. Once [over] (by itself at [VsTimeline.DONE_AT], or [end]ed by OK
 * or Back) nothing moves and nothing more is asked for.
 */
internal class VsRun {
    var t: Long = 0L
        private set

    var over: Boolean = false
        private set

    private var started = false

    /**
     * A frame: the first (at 0, whatever [elapsedMs] says) or the next, [elapsedMs] after the last (at most
     * [VsTimeline.MAX_STEP_MS] of it counts). Returns the cues that fall due on it.
     */
    fun frame(elapsedMs: Long): List<VsCue> {
        if (over) return emptyList()
        val from = if (started) t else -1L
        if (started) t = (t + elapsedMs.coerceIn(0L, VsTimeline.MAX_STEP_MS)).coerceAtMost(VsTimeline.DONE_AT)
        started = true
        if (t >= VsTimeline.DONE_AT) over = true
        var due: MutableList<VsCue>? = null
        for (i in CUES.indices) {
            val at = CUE_AT[i]
            if (at > from && at <= t) (due ?: ArrayList<VsCue>(CUES.size).also { due = it }).add(CUES[i])
        }
        return due ?: emptyList()
    }

    /** OK or Back: ends the run where it is. Returns whether it did (false if it was already over). */
    fun end(): Boolean {
        if (over) return false
        over = true
        return true
    }
}

// ---- who goes where ----

/**
 * Who is where on the card: the player [left], the opponent [right] (in PvP, the other player's killer), the VS [logo]
 * (always the opponent's: the killer stepping up to fight you), whether either is mirrored to face the other, and the
 * announcer's [lines] (as the view model queues them when the card opens).
 */
internal data class VsLineup(
    val left: PetId,
    val right: PetId,
    val logo: PetId,
    val mirrorLeft: Boolean,
    val mirrorRight: Boolean,
    val lines: List<String>,
)

internal fun vsLineup(player: PetId, opponent: PetId) = VsLineup(
    left = player,
    right = opponent,
    logo = opponent,
    mirrorLeft = mirrorToFace(player, onLeft = true),
    mirrorRight = mirrorToFace(opponent, onLeft = false),
    lines = listOf(player.name.lowercase(), "versus", opponent.name.lowercase()),
)

// ---- staging ----

/** Each fighter's whole cell is this tall, as a fraction of the screen height. */
private const val CELL = 0.70f

/** The fighters' feet, just above the bottom safe inset. */
private const val FEET = 0.94f

/** Each name is tilted this far toward the middle, in degrees: its inner end higher. */
private const val NAME_TILT = 6f

/** A name is at most this wide, of the screen's width and of its height, and at most [NAME_MAX_H] of its height tall. */
private const val NAME_W = 0.34f
private const val NAME_W_BY_H = 0.62f
private const val NAME_MAX_H = 0.25f

/** A name slams down from this big. */
private const val NAME_FROM = 2.2f

/** The player's meal status chip starts this far in under its name (a fraction of the name's width). */
private const val CHIP_IN = 0.06f

/** The VS logo fits a box this big, of the screen's width and height, around the middle of the seam. */
private const val LOGO_W = 0.27f
private const val LOGO_W_BY_H = 0.5f
private const val LOGO_MAX_H = 0.44f
private const val LOGO_FROM = 3.2f

/** The art turns white for a moment as it lands, fading over this long. */
private const val LAND_FLASH_MS = 220L

/** The shock rings spread from the VS logo over this long. */
private const val RING_MS = 380L

private val WhiteOut = ColorFilter.tint(Color.White, BlendMode.SrcIn)
private val Silhouette = ColorFilter.tint(Color.Black, BlendMode.SrcIn)
private val FightFill = Brush.verticalGradient(listOf(Color(0xFFFF8A90), BloodGlow, Blood))

/**
 * The Street Fighter Alpha VS card, about 3.8s:
 * - the screen splits diagonally, each half in its killer's [colours][vsPalette], hottest in a burst behind the
 *   fighter, with speed streaks racing across it and halftone dots thickening toward a white-hot seam ([VsBackdrop]);
 * - both fighters slide in idle from their sides at 70% of the screen height (Jaws, side-on, turned to face his
 *   opponent when he is the player's);
 * - each killer's name lettering slams in at the top of its half, tilted toward the middle, the player's first, with
 *   the player's meal [status] under it when there is one (never the opponent's, and none in PvP: the caller's to
 *   leave null);
 * - the opponent's VS logo (in PvP, the other player's) slams into the centre with a jolt, a flash and shock rings,
 *   as the announcer says "versus";
 * - then FIGHT! (with the announcer's `fight`) and [onDone].
 *
 * The announcer's "<player> VERSUS <opponent>" was queued by the view model as the card opened; on its first frame the
 * card claims the announcer ([LocalSoundSink]'s `skipVoiceTo`), cutting whatever an earlier screen left him saying (the
 * challenger entrance's lines, skipped mid-way), so VS lands with its "versus" and "fight!" comes as the fight does
 * (with the longest names, up to 0.2 s into it, as on the card this one replaced).
 *
 * OK (or a tap) skips straight to the fight; Back calls [onBack] (no fight exists yet, so the player just goes back to
 * choosing an opponent). The first of the two ends the card: [onDone] or [onBack] is called exactly once, and the
 * timeline stops. [focusEntry] gates OK and Back (see `RequestFocusOnEntry`); a held Back's repeats are dropped by
 * `MainActivity`. The streaks, the burst and every slam read the card's clock as they draw, so nothing recomposes per
 * frame, and once the card stops its clock nothing moves.
 *
 * The names and the logo decode off the main thread as the card opens. While the card plays, the fight's strips and
 * the arena scene are decoded, so the first turn starts at once.
 */
@Composable
fun VersusScreen(
    player: PetId,
    opponent: PetId,
    loader: AssetLoader,
    arenaRoll: Int,
    onDone: () -> Unit,
    onBack: () -> Unit,
    focusEntry: Any? = Unit,
    status: MealStatus? = null,
) {
    val sounds = LocalSoundSink.current
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val latestOnDone by rememberUpdatedState(onDone)
    val latestOnBack by rememberUpdatedState(onBack)
    val skipTick = rememberTouchTick("ui_select")
    val lineup = remember(player, opponent) { vsLineup(player, opponent) }
    val leftPalette = remember(player) { vsPalette(player) }
    val rightPalette = remember(opponent) { vsPalette(opponent) }
    val logoGlow = remember(lineup) { vsPalette(lineup.logo).hot }
    val arena = remember(loader, opponent, arenaRoll) { arenaScene(loader, opponent, arenaRoll) }
    val art by produceState<VsArt?>(null, loader, lineup) {
        value = withContext(Dispatchers.IO) { VsArt.load(loader, lineup) }
    }

    // One run of the card per entry. The router can bring back a card that is still fading out (Back, then a quick
    // re-pick of an opponent): that one plays again from the top instead of staying finished.
    val run = remember(focusEntry) { VsRun() }
    val clock = remember { mutableLongStateOf(0L) }
    val fightShown by remember { derivedStateOf { clock.longValue >= VsTimeline.FIGHT_AT } }

    GameBackHandler(enabled = focusEntry != null) {
        if (run.end()) latestOnBack() // no "fight!" call, no onDone, as the card fades out
    }
    RequestFocusOnEntry(focus, focusEntry)

    LaunchedEffect(run) {
        if (focusEntry == null) return@LaunchedEffect // leaving: the card has already ended
        clock.longValue = 0L
        var elapsed = 0L
        var last = withFrameMillis { it }
        while (!run.over) {
            for (cue in run.frame(elapsed)) {
                when (cue) {
                    VsCue.CLAIM_ANNOUNCER -> sounds.skipVoiceTo(lineup.lines)
                    VsCue.FIGHT_CALL -> sounds.voice(listOf("fight"))
                    VsCue.DONE -> latestOnDone()
                }
            }
            clock.longValue = run.t
            if (run.over) break
            val now = withFrameMillis { it }
            elapsed = now - last
            last = now
        }
    }
    // The fight's strips and arena decode during the card. Delayed a moment, so a roll that changes as the
    // card opens (see HorrorPetsApp) decodes only the arena it settles on.
    LaunchedEffect(loader, player, opponent, arena) {
        delay(250)
        withContext(Dispatchers.IO) {
            warmFightStrips(loader, player, opponent)
            loader.sceneBitmap(arena)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .clickable(interactionSource = interaction, indication = null) {
                if (focusEntry != null && run.end()) {
                    skipTick()
                    latestOnDone()
                }
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val shakePx = with(density) { 16.dp.toPx() }
        val cellH = h * CELL
        val safe = safeInset()
        val safeX = with(density) { safe.horizontal.toPx() }
        val safeY = with(density) { safe.vertical.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = VsTimeline.shakeAt(clock.longValue)
                    if (k > 0f) {
                        val seed = (clock.longValue / 28L).toInt()
                        translationX = (hash01(seed, 1) - 0.5f) * 2f * shakePx * k
                        translationY = (hash01(seed, 2) - 0.5f) * 2f * shakePx * k
                        val zoom = 1f + 0.035f * k
                        scaleX = zoom
                        scaleY = zoom
                    }
                },
        ) {
            VsBackdrop(leftPalette, rightPalette, clock = { clock.longValue })

            // The fighters slide in from their own sides, idle, each facing the other (a side-on one a little further
            // in on a 4:3 screen, so its nose or tail stays clear of the overscan).
            for (side in listOf(-1, 1)) {
                val pet = if (side < 0) lineup.left else lineup.right
                val mirror = if (side < 0) lineup.mirrorLeft else lineup.mirrorRight
                val homeX = vsFighterX(side, w, cellH, safeX, pet.spec.facing)
                SpriteView(
                    loader = loader,
                    pet = pet,
                    anim = Anim.IDLE,
                    heightPx = cellH,
                    tint = null,
                    tintStrength = 0f,
                    shadowOpacity = 0.75f,
                    loopOffsetMs = if (side < 0) 0L else 420L,
                    modifier = Modifier
                        .placeAt(x = { homeX }, y = { h * FEET })
                        .graphicsLayer {
                            val p = VsTimeline.fighterIn(clock.longValue)
                            translationX = side * (1f - p) * w * 0.45f
                            alpha = p
                            if (mirror) scaleX = -1f
                        },
                )
            }
        }

        // The names and the VS logo hold still over the shaking card, inside the safe inset.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val words = art?.let { Words(it, size.width, size.height, safeX, safeY, 1.dp.toPx(), logoGlow) }
                    onDrawBehind { if (words != null) drawWords(clock.longValue, words) }
                },
        )
        // The player's meal status under its name, from the name's outer end (clear of the fighter's head under its
        // middle), landing with it.
        val name = art?.leftName
        if (status != null && name != null) {
            val place = remember(name, w, h, safeX, safeY) { nameRect(name, w, h, safeX, safeY, w * VS_PLAYER_X) }
            val gap = with(density) { 6.dp.toPx() }
            StatusChip(
                status,
                Modifier
                    .placeAt(x = { place.left + place.width * CHIP_IN }, y = { place.bottom + gap }, pivotX = 0f, pivotY = 0f)
                    .graphicsLayer {
                        val since = clock.longValue - VsTimeline.PLAYER_NAME_AT
                        val s = VsTimeline.slamScale(since, VsTimeline.NAME_SLAM_MS, NAME_FROM)
                        scaleX = s
                        scaleY = s
                        alpha = (since / (VsTimeline.NAME_SLAM_MS * 0.4f)).coerceIn(0f, 1f)
                    },
            )
        }
        if (fightShown) {
            Box(Modifier.fillMaxSize().padding(horizontal = safe.horizontal), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .graphicsLayer {
                            val since = clock.longValue - VsTimeline.FIGHT_AT
                            val s = VsTimeline.slamScale(since, VsTimeline.FIGHT_SLAM_MS, 2.6f)
                            scaleX = s
                            scaleY = s
                            alpha = (since / (VsTimeline.FIGHT_SLAM_MS * 0.35f)).coerceIn(0f, 1f)
                        }
                        .drawWithCache {
                            val r = size.width * 0.8f
                            val bloom = Brush.radialGradient(
                                0f to BloodGlow.copy(alpha = 0.4f),
                                0.5f to Blood.copy(alpha = 0.14f),
                                1f to Color.Transparent,
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = r,
                            )
                            onDrawBehind { drawCircle(bloom, radius = r, center = Offset(size.width / 2f, size.height / 2f)) }
                        }
                        .padding(horizontal = 50.dp, vertical = 6.dp),
                ) {
                    OutlinedText("FIGHT!", displayStyle(150.sp, tracking = 0.02f, lineHeight = 150.sp).copy(brush = FightFill), outline = 8.dp)
                }
            }
        }
        // The white flash as VS, then FIGHT!, lands.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val a = VsTimeline.flashAt(clock.longValue)
                    if (a > 0f) drawRect(Color.White, alpha = a)
                },
        )
    }
}

/** The card's lettering and logo, decoded. */
@Immutable
private class VsArt(val leftName: ImageBitmap, val rightName: ImageBitmap, val logo: ImageBitmap) {
    companion object {
        /** Blocking: call it off the main thread. */
        fun load(loader: AssetLoader, lineup: VsLineup) = VsArt(
            leftName = loader.vsName(lineup.left),
            rightName = loader.vsName(lineup.right),
            logo = loader.vsLogo(lineup.logo),
        )
    }
}

/** Where the [art] goes on a [w] x [h] screen with its safe insets ([safeX], [safeY]), made once per size. */
private class Words(val art: VsArt, w: Float, h: Float, safeX: Float, safeY: Float, val unit: Float, glow: Color) {
    val leftName = nameRect(art.leftName, w, h, safeX, safeY, w * VS_PLAYER_X)
    val rightName = nameRect(art.rightName, w, h, safeX, safeY, w * VS_OPPONENT_X)
    val logo: Rect
    val logoGlow: Brush
    private val shadow = 5f * unit

    /** Where each piece's drop shadow goes: just below it. */
    val leftNameShadow = leftName.translate(0f, shadow)
    val rightNameShadow = rightName.translate(0f, shadow)
    val logoShadow: Rect

    /** The shock rings' outlines, thinning as they spread: one per whole dp of width, from [RING_MIN_DP]. */
    val ringStrokes = Array(RING_STEPS) { Stroke(width = (RING_MIN_DP + it) * unit) }

    init {
        val aspect = art.logo.height / art.logo.width.toFloat()
        var lw = min(w * LOGO_W, h * LOGO_W_BY_H)
        if (lw * aspect > h * LOGO_MAX_H) lw = h * LOGO_MAX_H / aspect
        val c = Offset(w / 2f, h / 2f)
        logo = Rect(c.x - lw / 2f, c.y - lw * aspect / 2f, c.x + lw / 2f, c.y + lw * aspect / 2f)
        logoShadow = logo.translate(0f, shadow * 1.4f)
        logoGlow = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.75f),
            0.3f to glow.copy(alpha = 0.5f),
            1f to Color.Transparent,
            center = c,
            radius = lw * 0.85f,
        )
    }
}

/** A name's place: over its fighter, as big as fits, at the top of the safe area even when tilted. */
private fun nameRect(img: ImageBitmap, w: Float, h: Float, safeX: Float, safeY: Float, centreX: Float): Rect {
    val aspect = img.height / img.width.toFloat()
    var nw = min(min(w * NAME_W, h * NAME_W_BY_H), w / 2f - safeX)
    if (nw * aspect > h * NAME_MAX_H) nw = h * NAME_MAX_H / aspect
    val nh = nw * aspect
    val cx = centreX.coerceIn(safeX + nw / 2f, w - safeX - nw / 2f)
    // Tilted, its inner end rises this much above where it would be level.
    val top = safeY + nw / 2f * sin(NAME_TILT * PI.toFloat() / 180f)
    return Rect(cx - nw / 2f, top, cx + nw / 2f, top + nh)
}

private fun DrawScope.drawWords(t: Long, k: Words) {
    drawName(k.art.leftName, k.leftName, k.leftNameShadow, t - VsTimeline.PLAYER_NAME_AT, -NAME_TILT)
    drawName(k.art.rightName, k.rightName, k.rightNameShadow, t - VsTimeline.OPPONENT_NAME_AT, NAME_TILT)
    drawLogo(t - VsTimeline.VS_AT, k)
}

/** How white a piece of art is [since] it landed: a pop that clears in [LAND_FLASH_MS]. */
private fun landFlash(since: Long): Float {
    if (since < 0 || since >= LAND_FLASH_MS) return 0f
    val f = 1f - since / LAND_FLASH_MS.toFloat()
    return 0.85f * f * f
}

/**
 * A name, [since] it started to slam in: falling from [NAME_FROM] to its [place] (its [shadow] below), tilted [tilt]
 * degrees.
 */
private fun DrawScope.drawName(img: ImageBitmap, place: Rect, shadow: Rect, since: Long, tilt: Float) {
    if (since < 0) return
    val s = VsTimeline.slamScale(since, VsTimeline.NAME_SLAM_MS, NAME_FROM)
    val a = (since / (VsTimeline.NAME_SLAM_MS * 0.4f)).coerceIn(0f, 1f)
    withTransform({
        rotate(tilt, place.center)
        scale(s, s, place.center)
    }) {
        art(img, shadow, a * 0.55f, Silhouette)
        art(img, place, a)
        val flash = landFlash(since - VsTimeline.NAME_SLAM_MS)
        if (flash > 0f) art(img, place, flash, WhiteOut)
    }
}

/** The VS logo, [since] it started to slam in: a glow, shock rings as it lands, and out again as FIGHT! comes. */
private fun DrawScope.drawLogo(since: Long, k: Words) {
    if (since < 0) return
    var s = VsTimeline.slamScale(since, VsTimeline.VS_SLAM_MS, LOGO_FROM)
    var a = (since / (VsTimeline.VS_SLAM_MS * 0.35f)).coerceIn(0f, 1f)
    val out = since - (VsTimeline.FIGHT_AT - VsTimeline.VS_AT)
    if (out >= 0) {
        val q = (out / 160f).coerceIn(0f, 1f)
        s *= 1f - 0.25f * q
        a *= 1f - q
    }
    if (a <= 0f) return
    val c = k.logo.center
    val r = k.logo.width * 0.85f
    drawCircle(k.logoGlow, radius = r * s, center = c, alpha = a)
    // Two shock rings off the landing: white, then the glow's colour a beat behind.
    val landed = since - VsTimeline.VS_SLAM_MS
    for (i in RING_DELAYS.indices) {
        val ring = landed - RING_DELAYS[i]
        if (ring < 0 || ring >= RING_MS) continue
        val q = ring / RING_MS.toFloat()
        drawCircle(
            Color.White,
            radius = k.logo.width * (0.4f + 0.9f * q + 0.15f * i),
            center = c,
            alpha = (1f - q) * (1f - q) * 0.9f,
            style = k.ringStrokes[(RING_SPAN_DP * (1f - q)).roundToInt().coerceIn(0, RING_STEPS - 1)],
        )
    }
    withTransform({ scale(s, s, c) }) {
        art(k.art.logo, k.logoShadow, a * 0.55f, Silhouette)
        art(k.art.logo, k.logo, a)
        val flash = landFlash(landed)
        if (flash > 0f) art(k.art.logo, k.logo, flash, WhiteOut)
    }
}

private val RING_DELAYS = longArrayOf(0L, 90L)

/** A shock ring's outline thins from [RING_MIN_DP] + [RING_SPAN_DP] to [RING_MIN_DP] as it spreads. */
private const val RING_MIN_DP = 2
private const val RING_SPAN_DP = 16
private const val RING_STEPS = RING_SPAN_DP + 1

/** [img] drawn whole into [place]. */
private fun DrawScope.art(img: ImageBitmap, place: Rect, alpha: Float, filter: ColorFilter? = null) {
    drawImage(
        img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(place.left.roundToInt(), place.top.roundToInt()),
        dstSize = IntSize(place.width.roundToInt(), place.height.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = filter,
        filterQuality = FilterQuality.High,
    )
}
