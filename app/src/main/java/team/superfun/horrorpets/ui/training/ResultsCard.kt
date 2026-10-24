package team.superfun.horrorpets.ui.training

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.ui.TrainingResult
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.fight.OutlinedText
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** The card slides up and fades in this fast; the score lands a beat into it. */
private const val CARD_IN_MS = 320
private const val SCORE_IN_MS = 260
private const val SCORE_AFTER_MS = 120L

private val ScoreFill = Brush.verticalGradient(listOf(Color(0xFFFFE9A8), Amber, Color(0xFFB4700E)))

/**
 * A finished training game's results, in a game window over the frozen game: [game]'s title, the score big, the pet's
 * best (NEW BEST! in gold when this score set it), what training paid (`+N STRENGTH`, `+N XP`, and LEVEL UP! when it
 * was worth a level), then TRY AGAIN and a focused CONTINUE pill: OK or a tap on CONTINUE calls [onDone] (the shell's
 * Back does too), TRY AGAIN calls [onAgain] for the same game once more. OK and taps are ignored for a moment after the
 * card comes up (see `rememberOkReady`), so one mashed through the game's end cannot skip it; nothing under the card
 * takes a touch. `game_over` plays as it appears, unless the view model has queued
 * `levelup` for a level gained. [focusEntry] is the screen's: null while it leaves, when the card stops taking input.
 */
@Composable
internal fun ResultsCard(game: TrainingGame, result: TrainingResult, onDone: () -> Unit, onAgain: () -> Unit, focusEntry: Any?) {
    val focus = remember { FocusRequester() }
    val entry = if (focusEntry != null) result else null
    RequestFocusOnEntry(focus, entry)
    val okReady = rememberOkReady(entry)
    val sounds = LocalSoundSink.current
    val appear = remember { Animatable(0f) }
    val score = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (result.levelsGained == 0) sounds.sfx("game_over")
        appear.animateTo(1f, tween(CARD_IN_MS, easing = LinearOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(SCORE_AFTER_MS)
        score.animateTo(1f, tween(SCORE_IN_MS, easing = LinearOutSlowInEasing))
    }

    Box(
        Modifier
            .fillMaxSize()
            .ignoreOkUnless(okReady)
            .swallowTouches()
            .background(Ink.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    val p = appear.value
                    alpha = p
                    translationY = (1f - p) * 26.dp.toPx()
                }
                .widthIn(min = 300.dp)
                .gameFrame(topEnd = 16.dp, bottomStart = 16.dp)
                .padding(horizontal = 30.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("TRAINING", style = displayStyle(12.sp, FontWeight.Bold, color = Amber, tracking = 0.24f), maxLines = 1)
            Spacer(Modifier.height(2.dp))
            GlowText(game.title.uppercase(), displayStyle(30.sp, tracking = 0.04f, lineHeight = 32.sp))
            OutlinedText(
                "${result.score}",
                displayStyle(84.sp, tracking = 0.02f, lineHeight = 84.sp).copy(brush = ScoreFill),
                outline = 5.dp,
                modifier = Modifier.graphicsLayer {
                    val p = score.value
                    val s = lerp(1.6f, 1f, p * p)
                    scaleX = s
                    scaleY = s
                    alpha = (p / 0.4f).coerceAtMost(1f)
                },
            )
            BasicText("SCORE", style = displayStyle(13.sp, FontWeight.Bold, color = TextLo, tracking = 0.2f, lineHeight = 14.sp), maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "BEST ${result.best}",
                    style = displayStyle(19.sp, color = if (result.newBest) TextHi else TextLo, tracking = 0.08f, lineHeight = 22.sp),
                    maxLines = 1,
                )
                if (result.newBest) NewBest()
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "+${strengthText(result.strength)} STRENGTH",
                    style = displayStyle(19.sp, color = TextHi, tracking = 0.06f, lineHeight = 22.sp),
                    maxLines = 1,
                )
                BasicText("+${result.xp} XP", style = displayStyle(19.sp, color = Amber, tracking = 0.06f, lineHeight = 22.sp), maxLines = 1)
            }
            if (result.levelsGained > 0) {
                Spacer(Modifier.height(8.dp))
                LevelUpChip()
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusPill("Try again", onClick = { if (focusEntry != null) onAgain() }, minWidth = 150.dp)
                FocusPill(
                    "Continue",
                    onClick = { if (focusEntry != null) onDone() },
                    modifier = Modifier.focusRequester(focus),
                    minWidth = 150.dp,
                )
            }
        }
    }
}

/** Strength gained as the card shows it: to a tenth, without a trailing `.0` (`10.5`, `6`). */
internal fun strengthText(gained: Double): String {
    val tenths = (gained * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${abs(tenths % 10)}"
}

/** NEW BEST!, in gold, pulsing. */
@Composable
private fun NewBest() {
    val pulse = rememberInfiniteTransition(label = "newBest").animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "newBestPulse",
    )
    GlowText(
        "NEW BEST!",
        displayStyle(22.sp, color = TrainingGold, tracking = 0.08f, lineHeight = 24.sp),
        modifier = Modifier.graphicsLayer { alpha = pulse.value },
        glow = Amber,
    )
}

/** LEVEL UP! in dark letters on a gold chip, its halo pulsing. */
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
            .drawBehind { softGlow(Amber, alpha = 0.6f * pulse.value, cornerRadius = 6.dp.toPx(), spread = 10.dp, layers = 5) }
            .background(Amber, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        BasicText("LEVEL UP!", style = displayStyle(18.sp, color = Ink, tracking = 0.08f, lineHeight = 20.sp), maxLines = 1)
    }
}
