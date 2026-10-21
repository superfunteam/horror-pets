package team.superfun.horrorpets.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import team.superfun.horrorpets.game.StatusTag
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.mood
import team.superfun.horrorpets.game.statusOf
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.common.GlassPanel
import team.superfun.horrorpets.ui.common.bodyStyle
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** A stat at or below this reads as low (Amber); hunger at or above HUNGRY_AT / STARVING_AT warns instead. */
private const val LOW_AT = 30.0

private const val BAR_ANIM_MS = 700

/** How a bar is colored: fine, a warning, or critical. */
enum class BarLevel { OK, WARN, CRITICAL }

/**
 * The stat card, top-left: name, level and status, then Hunger / Fun / Strength / Mood bars. It is
 * focusable (Up from the action bar); focused it glows and OK opens the pet switcher.
 */
@Composable
fun StatCard(
    pet: PetState,
    onOpenSwitcher: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "cardGlow")
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(200), label = "cardScale")

    GlassPanel(
        modifier = modifier
            .width(width)
            .graphicsLayer {
                // Grow away from the screen corner so the card never leaves the safe inset.
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(focusRequester)
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                up = FocusRequester.Cancel
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onOpenSwitcher),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
        glow = glow,
    ) {
        Column {
            // 40sp, stepping down only if a long name (GHOSTFACE) would not fit a narrow 4:3 card.
            BasicText(
                text = pet.id.displayName.uppercase(),
                style = displayStyle(40.sp, lineHeight = 40.sp),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = 40.sp, stepSize = 1.sp),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "LV ${pet.level}", style = displayStyle(24.sp, color = TextLo, tracking = 0.08f, lineHeight = 24.sp))
                Spacer(Modifier.width(10.dp))
                StatusChip(statusOf(pet))
            }
            Spacer(Modifier.height(14.dp))
            val hunger = pet.hunger
            StatBar(
                label = "Hunger",
                value = hunger,
                level = when {
                    hunger >= Tuning.STARVING_AT -> BarLevel.CRITICAL
                    hunger >= Tuning.HUNGRY_AT -> BarLevel.WARN
                    else -> BarLevel.OK
                },
            )
            StatBar("Fun", pet.funLevel, lowIsWarn(pet.funLevel))
            StatBar("Strength", pet.strength, lowIsWarn(pet.strength))
            val moodValue = mood(pet).toDouble()
            StatBar("Mood", moodValue, lowIsWarn(moodValue), last = true)

            AnimatedVisibility(
                visible = focused,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
            ) {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyCap("OK")
                    Spacer(Modifier.width(10.dp))
                    Text("SWITCH PET", style = displayStyle(20.sp, tracking = 0.1f))
                }
            }
        }
    }
}

private fun lowIsWarn(v: Double): BarLevel = if (v <= LOW_AT) BarLevel.WARN else BarLevel.OK

/** One labelled bar, at least 10dp tall: label and value above, the fill below, animated on change. */
@Composable
private fun StatBar(label: String, value: Double, level: BarLevel, last: Boolean = false) {
    val fraction by animateFloatAsState(
        targetValue = (value / Tuning.STAT_MAX).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(BAR_ANIM_MS),
        label = "bar$label",
    )
    val warnColor = if (level == BarLevel.OK) TextHi else levelColor(level)
    val valueColor by animateColorAsState(warnColor, tween(BAR_ANIM_MS), label = "barValue$label")

    // A critical bar breathes, so a starving pet is noticed from the sofa. The pulse is only read in the
    // draw phase below, so it repaints the bar each frame without recomposing the card.
    val pulse: State<Float> = if (level == BarLevel.CRITICAL) {
        rememberInfiniteTransition(label = "critical$label").animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "criticalPulse$label",
        )
    } else {
        NoPulse
    }

    Column(Modifier.padding(bottom = if (last) 0.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label.uppercase(),
                style = bodyStyle(14.sp, color = TextLo, tracking = 1.2.sp),
                modifier = Modifier.weight(1f),
            )
            Text(text = value.roundToInt().toString(), style = displayStyle(21.sp, color = valueColor, lineHeight = 21.sp))
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .drawBehind {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(color = Color.White.copy(alpha = 0.13f), cornerRadius = r)
                    val w = size.width * fraction
                    if (w <= 0f) return@drawBehind
                    val fillSize = Size(w.coerceAtLeast(size.height), size.height)
                    val glow = pulse.value
                    if (glow > 0f) {
                        softGlow(BloodGlow, alpha = 0.55f * glow, cornerRadius = r.x, spread = 8.dp, layers = 5, size = fillSize)
                    }
                    drawRoundRect(brush = levelBrush(level), size = fillSize, cornerRadius = r)
                    // A thin specular line along the top makes the fill read as glossy, not flat.
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.22f),
                        size = Size(fillSize.width, size.height * 0.4f),
                        cornerRadius = r,
                    )
                },
        )
    }
}

/** The pulse of a bar that is not critical: always off. */
private object NoPulse : State<Float> {
    override val value: Float = 0f
}

private val OkBrush = Brush.horizontalGradient(listOf(Color(0xFF9C978F), Color(0xFFF4F0EA)))
private val WarnBrush = Brush.horizontalGradient(listOf(Color(0xFF8A5A0C), Amber))
private val CriticalBrush = Brush.horizontalGradient(listOf(Color(0xFF6E0710), Color(0xFFE0262F)))

private fun levelBrush(level: BarLevel): Brush = when (level) {
    BarLevel.OK -> OkBrush
    BarLevel.WARN -> WarnBrush
    BarLevel.CRITICAL -> CriticalBrush
}

private fun levelColor(level: BarLevel): Color = when (level) {
    BarLevel.OK -> TextHi
    BarLevel.WARN -> Amber
    BarLevel.CRITICAL -> BloodGlow
}

/** The pet's headline status as a small solid tag: CONTENT grey, BORED/HUNGRY Amber, STARVING/GRUMPY Blood. */
@Composable
fun StatusChip(tag: StatusTag, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tag) {
        StatusTag.CONTENT -> Color.White.copy(alpha = 0.16f) to TextHi
        StatusTag.BORED, StatusTag.HUNGRY -> Amber to Ink
        StatusTag.STARVING, StatusTag.GRUMPY -> Blood to TextHi
    }
    Box(
        modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Text(text = tag.name, style = displayStyle(17.sp, color = fg, tracking = 0.08f, lineHeight = 22.sp))
    }
}

/** A remote-key hint: the key's name in dark letters on a small solid light cap. */
@Composable
fun KeyCap(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(TextHi, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = displayStyle(17.sp, color = Ink, tracking = 0.06f))
    }
}
