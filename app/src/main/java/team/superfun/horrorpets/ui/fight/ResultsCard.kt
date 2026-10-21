package team.superfun.horrorpets.ui.fight

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.SceneSpec
import team.superfun.horrorpets.game.mood
import team.superfun.horrorpets.game.xpToNext
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.GlassPanel
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SAFE_INSET_FRACTION
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.bodyStyle
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** The pet's cell in the card, as a fraction of the screen height. */
private const val PORTRAIT_HEIGHT = 0.31f

private const val CARD_IN_MS = 280
private const val XP_FILL_MS = 1_100

/** The widest the card gets; on 4:3 and CRT it is the width inside the safe inset. */
private val CardMaxWidth = 700.dp

/**
 * The results card over the fight's darkened [arena]: `YOU WIN` / `YOU LOSE` / `YOU RAN`, the player's
 * pet (jumping and waving after a win, facepalming after a loss), the XP gained filling the level bar
 * (`LEVEL UP!` when it wraps), and each stat as before → after with its change. [before] is the pet as it
 * went into the fight; without it (the Activity was recreated) the stats show their values alone.
 * One focused CONTINUE pill, or Back, calls [onDone]. OK is ignored for a moment after the card comes up
 * (see `rememberOkReady`), so an OK mashed through the fight's last turn cannot skip the results. The
 * view model has already queued `you_win` / `you_lose` (and `levelup`).
 */
@Composable
fun ResultsCard(
    screen: Screen.Results,
    before: PetState?,
    loader: AssetLoader,
    arena: SceneSpec?,
    onDone: () -> Unit,
    focusEntry: Any? = Unit,
) {
    val focus = remember { FocusRequester() }
    BackHandler(enabled = focusEntry != null, onBack = onDone)
    RequestFocusOnEntry(focus, focusEntry)
    val okReady = rememberOkReady(focusEntry)

    val after = screen.reward.pet
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(CARD_IN_MS, easing = LinearOutSlowInEasing)) }

    BoxWithConstraints(Modifier.fillMaxSize().ignoreOkUnless(okReady)) {
        val h = constraints.maxHeight.toFloat()
        if (arena != null) {
            SceneBackdrop(loader = loader, scene = arena) { }
        }
        // Darken the arena so the card owns the screen.
        Spacer(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.62f)))

        val available = maxWidth * (1f - 2f * SAFE_INSET_FRACTION)
        val cardWidth = if (available < CardMaxWidth) available else CardMaxWidth
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassPanel(
                modifier = Modifier
                    .width(cardWidth)
                    .graphicsLayer {
                        val p = enter.value
                        alpha = p
                        val s = lerp(0.92f, 1f, p)
                        scaleX = s
                        scaleY = s
                    },
                contentPadding = PaddingValues(horizontal = 34.dp, vertical = 24.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Title(screen, Modifier.graphicsLayer {
                        val p = enter.value
                        val s = lerp(1.5f, 1f, p * p)
                        scaleX = s
                        scaleY = s
                    })
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Portrait(loader, after, screen, cellH = h * PORTRAIT_HEIGHT)
                        Spacer(Modifier.width(26.dp))
                        Column(Modifier.weight(1f)) {
                            Progress(screen, before)
                            Spacer(Modifier.height(12.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))
                            for (line in statLines(before, after)) StatRow(line)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    FocusPill("Continue", onClick = { if (focusEntry != null) onDone() }, modifier = Modifier.focusRequester(focus), minWidth = 200.dp)
                }
            }
        }
    }
}

private val WinFill = Brush.verticalGradient(listOf(Color(0xFFFFE9A8), Amber, Color(0xFFB4700E)))
private val LoseFill = Brush.verticalGradient(listOf(Color(0xFFFF7A82), Color(0xFFE0262F), Color(0xFF7A0912)))
private val RanFill = Brush.verticalGradient(listOf(Color(0xFFE6E3E8), Color(0xFF9C98A0)))

@Composable
private fun Title(screen: Screen.Results, modifier: Modifier = Modifier) {
    val (text, fill) = when {
        screen.ran -> "YOU RAN" to RanFill
        screen.won -> "YOU WIN" to WinFill
        else -> "YOU LOSE" to LoseFill
    }
    OutlinedText(text, displayStyle(78.sp, tracking = 0.06f, lineHeight = 78.sp).copy(brush = fill), outline = 6.dp, modifier = modifier)
}

/**
 * The player's pet in a soft pool of light: after a win it jumps and waves (again every few seconds),
 * after a loss it plays FAIL once, after running away it just stands there.
 */
@Composable
private fun Portrait(loader: AssetLoader, pet: PetState, screen: Screen.Results, cellH: Float) {
    val script = when {
        screen.ran -> emptyList()
        screen.won -> listOf(Anim.JUMP, Anim.WAVE)
        else -> listOf(Anim.FAIL)
    }
    var step by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(step, round) {
        if (screen.won && step >= script.size) {
            delay(2_600)
            step = 0
            round++
        }
    }
    val anim = script.getOrNull(step) ?: Anim.IDLE
    Box(
        Modifier.drawWithCache {
            val glow = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.12f),
                0.6f to Color.White.copy(alpha = 0.03f),
                1f to Color.Transparent,
                center = Offset(size.width / 2f, size.height * 0.55f),
                radius = size.height * 0.62f,
            )
            onDrawBehind { drawCircle(glow, radius = size.height * 0.62f, center = Offset(size.width / 2f, size.height * 0.55f)) }
        },
    ) {
        SpriteView(
            loader = loader,
            pet = pet.id,
            anim = anim,
            heightPx = cellH,
            tint = null,
            tintStrength = 0f,
            dim = if (screen.won) 0f else 0.15f,
            saturation = if (screen.won) 1f else 0.7f,
            shadowOpacity = 0.7f,
            playKey = round * 10 + step,
            onFinished = { if (step < script.size) step++ },
        )
    }
}

/** Name and level, the XP gained, and the level bar filling with it (wrapping with LEVEL UP!). */
@Composable
private fun Progress(screen: Screen.Results, before: PetState?) {
    val after = screen.reward.pet
    // The bar runs in "levels": 3.4 is level 3 and 40% of the way to 4. A level-up wraps it round.
    val end = levels(after)
    val start = before?.let(::levels) ?: end
    val progress = remember { Animatable(start) }
    val gained = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(CARD_IN_MS.toLong())
        launch { gained.animateTo(screen.reward.xpGained.toFloat(), tween(XP_FILL_MS, easing = FastOutSlowInEasing)) }
        progress.animateTo(end, tween(XP_FILL_MS, easing = FastOutSlowInEasing))
    }
    val level by remember { derivedStateOf { floor(progress.value + 1e-4f).toInt() } }
    // With the pet before the fight, LEVEL UP! pops as the bar wraps; without it (the Activity was
    // recreated on this card) there is no fill to watch, so it shows at once.
    val leveledUp by remember {
        derivedStateOf { screen.reward.levelsGained > 0 && (before == null || level > floor(start + 1e-4f).toInt()) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            after.id.displayName.uppercase(),
            style = displayStyle(38.sp, tracking = 0.05f, lineHeight = 38.sp),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 38.sp, stepSize = 1.sp),
        )
        Spacer(Modifier.width(10.dp))
        BasicText("LV $level", style = displayStyle(24.sp, color = TextLo, tracking = 0.1f, lineHeight = 24.sp), maxLines = 1)
        if (leveledUp) {
            Spacer(Modifier.width(12.dp))
            LevelUpChip()
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val xp by remember { derivedStateOf { gained.value.roundToInt() } }
        BasicText(
            if (screen.ran) "NO XP" else "+$xp XP",
            style = displayStyle(28.sp, color = if (screen.ran) TextLo else Amber, tracking = 0.06f, lineHeight = 28.sp),
            maxLines = 1,
            modifier = Modifier.width(96.dp),
        )
        XpBar(fraction = { progress.value - floor(progress.value + 1e-4f) }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        BasicText(
            "${after.xp}/${xpToNext(after.level)}",
            style = displayStyle(20.sp, color = TextLo, tracking = 0.04f, lineHeight = 20.sp),
            maxLines = 1,
        )
    }
}

private fun levels(p: PetState): Float = p.level + p.xp.toFloat() / xpToNext(p.level).coerceAtLeast(1)

private val XpFill = Brush.horizontalGradient(listOf(Color(0xFFB4700E), Amber, Color(0xFFFFE9A8)))

@Composable
private fun XpBar(fraction: () -> Float, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .height(12.dp)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.13f), cornerRadius = r)
                val w = size.width * fraction().coerceIn(0f, 1f)
                if (w <= 0f) return@drawBehind
                val fill = Size(w.coerceAtLeast(size.height), size.height)
                drawRoundRect(XpFill, size = fill, cornerRadius = r)
                drawRoundRect(Color.White.copy(alpha = 0.25f), size = Size(fill.width, size.height * 0.4f), cornerRadius = r)
            },
    )
}

@Composable
private fun LevelUpChip() {
    val pulse = rememberInfiniteTransition(label = "levelUp").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "levelUpPulse",
    )
    Box(
        Modifier
            .drawBehind {
                softGlow(Amber, alpha = 0.6f * pulse.value, cornerRadius = 6.dp.toPx(), spread = 10.dp, layers = 5)
            }
            .background(Amber, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        BasicText("LEVEL UP!", style = displayStyle(20.sp, color = Ink, tracking = 0.08f, lineHeight = 22.sp), maxLines = 1)
    }
}

@Composable
private fun Divider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Color.White.copy(alpha = 0.1f)),
    )
}

/** One stat's change: [before] is null when the pet before the fight is not known. */
private class StatLine(val label: String, val before: Int?, val after: Int, val higherIsBetter: Boolean)

private fun statLines(before: PetState?, after: PetState): List<StatLine> = listOf(
    StatLine("Hunger", before?.hunger?.roundToInt(), after.hunger.roundToInt(), higherIsBetter = false),
    StatLine("Fun", before?.funLevel?.roundToInt(), after.funLevel.roundToInt(), higherIsBetter = true),
    StatLine("Strength", before?.strength?.roundToInt(), after.strength.roundToInt(), higherIsBetter = true),
    StatLine("Mood", before?.let(::mood), mood(after), higherIsBetter = true),
)

private val ValueWidth: Dp = 42.dp

/** `HUNGER   20 → 35   +15`: worse changes in Amber, better ones bright, no change dimmed. */
@Composable
private fun StatRow(line: StatLine) {
    val delta = line.before?.let { line.after - it } ?: 0
    val changed = line.before != null && delta != 0
    val better = if (line.higherIsBetter) delta > 0 else delta < 0
    val deltaColor = when {
        !changed -> TextLo
        better -> TextHi
        else -> Amber
    }
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            line.label.uppercase(),
            style = bodyStyle(14.sp, color = TextLo, tracking = 1.2.sp),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (changed) {
            BasicText("${line.before}", style = displayStyle(22.sp, color = TextLo, lineHeight = 22.sp), maxLines = 1)
            BasicText("  →  ", style = bodyStyle(16.sp, color = TextLo), maxLines = 1)
        }
        BasicText(
            "${line.after}",
            style = displayStyle(24.sp, color = TextHi, lineHeight = 24.sp),
            maxLines = 1,
        )
        Box(Modifier.width(ValueWidth + 20.dp), contentAlignment = Alignment.CenterEnd) {
            val text = when {
                !changed -> if (line.before == null) "" else "—"
                delta > 0 -> "+$delta"
                else -> "−${abs(delta)}"
            }
            BasicText(text, style = displayStyle(22.sp, color = deltaColor, lineHeight = 22.sp), maxLines = 1)
        }
    }
}
