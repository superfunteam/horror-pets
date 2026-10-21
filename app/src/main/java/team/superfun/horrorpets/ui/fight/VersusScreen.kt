package team.superfun.horrorpets.ui.fight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SAFE_INSET_FRACTION
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.hash01
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.common.safeInsetPadding
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink

// ---- the timeline, in ms from the first frame ----

private const val PANELS_MS = 380L
private const val FIGHTER_IN_AT = 120L
private const val FIGHTER_IN_MS = 520L
private const val PLAYER_NAME_AT = 520L
private const val OPPONENT_NAME_AT = 820L
private const val NAME_SLAM_MS = 170L
private const val VS_AT = 1_150L
private const val VS_SLAM_MS = 210L
private const val VS_SHAKE_MS = 300L

/** "FIGHT!" waits for the announcer's "<player> versus <opponent>" (up to about 3.4s) to finish. */
private const val FIGHT_AT = 3_050L
private const val FIGHT_SLAM_MS = 180L
private const val DONE_AT = 3_800L

/** One frame never advances the timeline more than this, so a stall slows it instead of skipping it. */
private const val MAX_STEP_MS = 100L

// ---- staging ----

/** Each fighter's whole cell is this tall, as a fraction of the screen height. */
private const val CELL = 0.70f

/** The fighters' feet, just above the bottom safe inset. */
private const val FEET = 0.94f
private const val PLAYER_X = 0.25f
private const val OPPONENT_X = 0.75f

/** The split leans: at the top it is this far right of centre, at the bottom as far left. */
private const val SEAM_LEAN = 0.07f

/**
 * The Street-Fighter VS card, about 3.8s: the screen splits diagonally, each half tinted with its
 * fighter's scene accent (the player's current scene, the opponent's [arena][arenaScene]); both fighters
 * slide in idle from their sides at 70% of the screen height; the names slam in at the top, then VS slams
 * into the centre with a 300ms screen shake; then FIGHT! (with the announcer's `fight`) and [onDone].
 *
 * The announcer's "<player> VERSUS <opponent>" was queued by the view model as the card opened, and VS
 * lands with its "versus". OK skips straight to the fight; Back calls [onBack] (no fight exists yet, so
 * the player just goes back to choosing an opponent). The first of the two ends the card: [onDone] or
 * [onBack] is called exactly once, and the timeline stops. [focusEntry] gates OK and Back (see
 * `RequestFocusOnEntry`); a held Back's repeats are dropped by `MainActivity`.
 *
 * While the card plays, the fight's strips and the arena scene are decoded, so the first turn starts at once.
 */
@Composable
fun VersusScreen(
    player: PetId,
    opponent: PetId,
    game: GameState,
    loader: AssetLoader,
    arenaRoll: Int,
    onDone: () -> Unit,
    onBack: () -> Unit,
    focusEntry: Any? = Unit,
) {
    val sounds = LocalSoundSink.current
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val latestOnDone by rememberUpdatedState(onDone)
    val latestOnBack by rememberUpdatedState(onBack)
    val playerScenes = remember(loader, player) { loader.scenes().getValue(player) }
    val playerScene = playerScenes[(game.pets[player]?.sceneIndex ?: 0).mod(playerScenes.size)]
    val arena = remember(loader, opponent, arenaRoll) { arenaScene(loader, opponent, arenaRoll) }
    val leftAccent = remember(playerScene) { hexColor(playerScene.accent, BloodGlow) }
    val rightAccent = remember(arena) { hexColor(arena.accent, BloodGlow) }

    val clock = remember { mutableLongStateOf(0L) }
    val done = remember { BooleanArray(1) }
    val fightShown by remember { derivedStateOf { clock.longValue >= FIGHT_AT } }
    val playerName by remember { derivedStateOf { clock.longValue >= PLAYER_NAME_AT } }
    val opponentName by remember { derivedStateOf { clock.longValue >= OPPONENT_NAME_AT } }
    val vsShown by remember { derivedStateOf { clock.longValue >= VS_AT } }

    fun finish() {
        if (done[0]) return
        done[0] = true
        latestOnDone()
    }

    BackHandler(enabled = focusEntry != null) {
        if (!done[0]) {
            done[0] = true // stops the timeline: no "fight!" call, no onDone, as the card fades out
            latestOnBack()
        }
    }
    RequestFocusOnEntry(focus, focusEntry)

    // One run of the card per entry. The router can bring back a card that is still fading out (Back, then
    // a quick re-pick of an opponent): that one has to play again from the top instead of staying finished.
    LaunchedEffect(focusEntry) {
        if (focusEntry == null) return@LaunchedEffect // leaving: the card has already ended
        done[0] = false
        clock.longValue = 0L
        var t = 0L
        var last = withFrameMillis { it }
        var called = false
        while (!done[0]) {
            if (!called && t >= FIGHT_AT) {
                called = true
                sounds.voice(listOf("fight"))
            }
            if (t >= DONE_AT) {
                finish()
                break
            }
            val now = withFrameMillis { it }
            t += (now - last).coerceIn(0L, MAX_STEP_MS)
            last = now
            clock.longValue = t
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
                if (focusEntry != null) finish()
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val shakePx = with(density) { 16.dp.toPx() }
        val cellH = h * CELL
        val halfWidth = with(density) { (w * (0.5f - SAFE_INSET_FRACTION) - 24.dp.toPx()).coerceAtLeast(0f).toDp() }
        // Both names at one size, set by the longer name, so JASON never towers over GHOSTFACE.
        val nameSize = nameSizeFor(halfWidth, max(player.displayName.length, opponent.displayName.length))

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = shakeAt(clock.longValue)
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
            Split(leftAccent, rightAccent, clock = { clock.longValue })

            // The fighters slide in from their own sides, idle.
            for ((pet, side) in listOf(player to -1, opponent to 1)) {
                val homeX = w * if (side < 0) PLAYER_X else OPPONENT_X
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
                            val p = easeOutCubic(((clock.longValue - FIGHTER_IN_AT) / FIGHTER_IN_MS.toFloat()).coerceIn(0f, 1f))
                            translationX = side * (1f - p) * w * 0.45f
                            alpha = p
                        },
                )
            }
        }

        // Names, VS and FIGHT! hold still over the shaking card, inside the safe inset.
        Box(Modifier.fillMaxSize().padding(safeInsetPadding())) {
            if (playerName) {
                FighterName(
                    name = player.displayName.uppercase(),
                    size = nameSize,
                    accent = leftAccent,
                    alignEnd = false,
                    since = { clock.longValue - PLAYER_NAME_AT },
                    modifier = Modifier.align(Alignment.TopStart).widthIn(max = halfWidth),
                )
            }
            if (opponentName) {
                FighterName(
                    name = opponent.displayName.uppercase(),
                    size = nameSize,
                    accent = rightAccent,
                    alignEnd = true,
                    since = { clock.longValue - OPPONENT_NAME_AT },
                    modifier = Modifier.align(Alignment.TopEnd).widthIn(max = halfWidth),
                )
            }
        }
        if (vsShown) {
            Slam(
                text = "VS",
                textSize = 190,
                fill = VsFill,
                since = { clock.longValue - VS_AT },
                slamMs = VS_SLAM_MS,
                fromScale = 3.2f,
                fadeOutAt = FIGHT_AT - VS_AT,
            )
        }
        if (fightShown) {
            Slam(
                text = "FIGHT!",
                textSize = 150,
                fill = FightFill,
                since = { clock.longValue - FIGHT_AT },
                slamMs = FIGHT_SLAM_MS,
                fromScale = 2.6f,
                fadeOutAt = Long.MAX_VALUE,
            )
        }
        // The white flash as VS, then FIGHT!, lands.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val a = flashAt(clock.longValue)
                    if (a > 0f) drawRect(Color.White, alpha = a)
                },
        )
    }
}

private fun easeOutCubic(p: Float): Float = 1f - (1f - p) * (1f - p) * (1f - p)

/** 0..1 screen shake: a hard jolt when VS lands, a lighter one for FIGHT! and each name. */
private fun shakeAt(t: Long): Float {
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

private fun flashAt(t: Long): Float {
    fun pulse(at: Long, ms: Long, strength: Float): Float {
        val since = t - at
        if (since < 0 || since >= ms) return 0f
        return strength * (1f - since / ms.toFloat())
    }
    return max(pulse(VS_AT + VS_SLAM_MS, 220L, 0.32f), pulse(FIGHT_AT + FIGHT_SLAM_MS, 260L, 0.4f))
}

// ---- the split background ----

private const val STREAKS = 16

/**
 * The two halves of the card: each a dark field lit from its outer edge by its fighter's [accent][leftAccent],
 * with a glow behind the fighter and speed streaks drifting outward along the diagonal; a hot seam
 * between them. The halves sweep in from the sides over [PANELS_MS]. The streaks move every frame, so this
 * layer redraws each frame: a handful of gradient fills and lines, nothing allocated.
 */
@Composable
private fun Split(leftAccent: Color, rightAccent: Color, clock: () -> Long) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val top = w * (0.5f + SEAM_LEAN)
                val bottom = w * (0.5f - SEAM_LEAN)
                val left = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(top, 0f)
                    lineTo(bottom, h)
                    lineTo(0f, h)
                    close()
                }
                val right = Path().apply {
                    moveTo(top, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    lineTo(bottom, h)
                    close()
                }
                val leftField = Brush.horizontalGradient(
                    0f to lerp(Ink, leftAccent, 0.55f),
                    0.45f to lerp(Ink, leftAccent, 0.22f),
                    1f to Ink,
                    startX = 0f,
                    endX = top,
                )
                val rightField = Brush.horizontalGradient(
                    0f to Ink,
                    0.55f to lerp(Ink, rightAccent, 0.22f),
                    1f to lerp(Ink, rightAccent, 0.55f),
                    startX = bottom,
                    endX = w,
                )
                val glowR = h * 0.55f
                val leftGlow = Brush.radialGradient(
                    0f to leftAccent.copy(alpha = 0.42f),
                    0.5f to leftAccent.copy(alpha = 0.12f),
                    1f to Color.Transparent,
                    center = Offset(w * PLAYER_X, h * 0.55f),
                    radius = glowR,
                )
                val rightGlow = Brush.radialGradient(
                    0f to rightAccent.copy(alpha = 0.42f),
                    0.5f to rightAccent.copy(alpha = 0.12f),
                    1f to Color.Transparent,
                    center = Offset(w * OPPONENT_X, h * 0.55f),
                    radius = glowR,
                )
                val shade = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    0.3f to Color.Transparent,
                    0.75f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.7f),
                )
                val unit = 1.dp.toPx()
                // The seam's direction, for the streaks that run parallel to it.
                val sx = bottom - top
                val sy = h
                onDrawBehind {
                    val t = clock()
                    val sweep = easeOutCubic((t / PANELS_MS.toFloat()).coerceIn(0f, 1f))
                    val offset = (1f - sweep) * w * 0.6f
                    drawRect(Ink)
                    // Left half, sliding in from the left; the right half from the right.
                    translate(left = -offset) {
                        clipPath(left) {
                            drawRect(leftField)
                            drawRect(leftGlow)
                            streaks(t, -1f, top, sx, sy, leftAccent, unit)
                        }
                    }
                    translate(left = offset) {
                        clipPath(right) {
                            drawRect(rightField)
                            drawRect(rightGlow)
                            streaks(t, 1f, top, sx, sy, rightAccent, unit)
                        }
                    }
                    drawRect(shade)
                    // The seam: a hot white core in a red glow, flaring as VS lands.
                    val flare = 1f + 1.5f * flashAt(t)
                    val a = Offset(top, -unit * 4f)
                    val b = Offset(bottom, h + unit * 4f)
                    drawLine(BloodGlow, a, b, strokeWidth = 26f * unit * flare, alpha = 0.22f * sweep, cap = StrokeCap.Butt)
                    drawLine(BloodGlow, a, b, strokeWidth = 10f * unit, alpha = 0.7f * sweep, cap = StrokeCap.Butt)
                    drawLine(Color.White, a, b, strokeWidth = 3f * unit, alpha = 0.95f * sweep, cap = StrokeCap.Butt)
                }
            },
    )
}

/** Speed streaks parallel to the seam, drifting toward the outer edge ([dir] -1 left, +1 right). */
private fun DrawScope.streaks(
    t: Long,
    dir: Float,
    top: Float,
    sx: Float,
    sy: Float,
    accent: Color,
    unit: Float,
) {
    val w = size.width
    val seconds = t / 1000f
    for (i in 0 until STREAKS) {
        val speed = 0.05f + 0.08f * hash01(i, 41)
        // How far from the seam toward the outer edge, 0..1, wrapping.
        val p = (hash01(i, 42) + seconds * speed) % 1f
        val dx = dir * p * w * 0.55f
        val along = hash01(i, 43)
        val len = (0.08f + 0.18f * hash01(i, 44))
        val x0 = top + sx * along + dx
        val y0 = sy * along
        val x1 = x0 + sx * len
        val y1 = y0 + sy * len
        val fade = 1f - p
        drawLine(
            if (i % 3 == 0) Color.White else accent,
            Offset(x0, y0),
            Offset(x1, y1),
            strokeWidth = (1.5f + 2.5f * hash01(i, 45)) * unit,
            alpha = (0.1f + 0.18f * hash01(i, 46)) * fade,
            cap = StrokeCap.Round,
        )
    }
}

// ---- the words ----

/** The names' size: up to 92sp, as large as the longer name ([chars] letters) fits in [width]. */
private fun nameSizeFor(width: Dp, chars: Int): TextUnit {
    // Barlow Condensed ExtraBold with 0.05em tracking averages about 0.58em per capital.
    val fits = width.value / (chars.coerceAtLeast(1) * 0.58f)
    return fits.coerceIn(44f, 92f).sp
}

private val VsFill = Brush.verticalGradient(listOf(Color(0xFFFFE9A8), Amber, Blood))
private val FightFill = Brush.verticalGradient(listOf(Color(0xFFFF8A90), BloodGlow, Blood))

/**
 * A fighter's name, slammed in at the top of its half: it drops from 2x to size in [NAME_SLAM_MS], over
 * an accent underline. Long names (GHOSTFACE on 4:3) step down to fit the half.
 */
@Composable
private fun FighterName(
    name: String,
    size: TextUnit,
    accent: Color,
    alignEnd: Boolean,
    since: () -> Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.graphicsLayer {
            val p = (since() / NAME_SLAM_MS.toFloat()).coerceIn(0f, 1f)
            val s = 1f + (1f - p * p) * 1.1f
            scaleX = s
            scaleY = s
            alpha = (p / 0.4f).coerceAtMost(1f)
            transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
        },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        // Padded by the outline's width, so the dark rim stays inside the safe inset too.
        Box(Modifier.padding(horizontal = 6.dp)) {
            // The stroke and the fill step down together: both autosize against the same width.
            val style = displayStyle(size, tracking = 0.05f, lineHeight = size)
                .copy(textAlign = if (alignEnd) TextAlign.End else TextAlign.Start)
            // A safety net only: the size above already fits.
            val autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = size, stepSize = 2.sp)
            val px = with(LocalDensity.current) { 6.dp.toPx() }
            BasicText(
                text = name,
                style = style.copy(
                    color = Color(0xFF0B0709),
                    drawStyle = Stroke(width = px * 2f, join = StrokeJoin.Round),
                    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, px), blurRadius = px * 3f),
                ),
                maxLines = 1,
                autoSize = autoSize,
            )
            BasicText(text = name, style = style, maxLines = 1, autoSize = autoSize)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .widthIn(min = 140.dp)
                .height(5.dp)
                .drawWithCache {
                    val fill = Brush.horizontalGradient(
                        if (alignEnd) listOf(Color.Transparent, accent) else listOf(accent, Color.Transparent),
                    )
                    onDrawBehind { drawRect(fill) }
                },
        )
    }
}

/**
 * A word slammed into the centre: it falls from [fromScale] to size in [slamMs] and stays, outlined dark
 * over a glow; from [fadeOutAt] (ms after it started) it shrinks away.
 */
@Composable
private fun Slam(text: String, textSize: Int, fill: Brush, since: () -> Long, slamMs: Long, fromScale: Float, fadeOutAt: Long) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .graphicsLayer {
                    val t = since()
                    val p = (t / slamMs.toFloat()).coerceIn(0f, 1f)
                    var s = fromScale + (1f - fromScale) * p * p
                    var a = (p / 0.35f).coerceAtMost(1f)
                    if (t >= fadeOutAt) {
                        val q = ((t - fadeOutAt) / 160f).coerceIn(0f, 1f)
                        s *= 1f - 0.25f * q
                        a *= 1f - q
                    }
                    scaleX = s
                    scaleY = s
                    alpha = a
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
            OutlinedText(text, displayStyle(textSize.sp, tracking = 0.02f, lineHeight = textSize.sp).copy(brush = fill), outline = 8.dp)
        }
    }
}
