package team.superfun.horrorpets.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import team.superfun.horrorpets.game.StatusTag
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.mood
import team.superfun.horrorpets.game.statusOf
import team.superfun.horrorpets.game.xpToNext
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** A stat at or below this reads as low (a warning); hunger at or above HUNGRY_AT / STARVING_AT warns instead. */
private const val LOW_AT = 30.0

private const val BAR_ANIM_MS = 700

/** How many segments a gauge has: each is a tenth of the stat. */
private const val SEGMENTS = 10

/** The slant of the gauges and the XP bar, matching the fight's HP bars: run over rise. */
private const val SLANT = 0.25f

/** A warning's orange, between the gold of a fine stat and the red of a critical one. */
private val Warn = Color(0xFFFFB347)

/** How a bar is colored: fine, a warning, or critical. */
enum class BarLevel { OK, WARN, CRITICAL }

/**
 * The status window, top-left: the pet's name, its level (with progress to the next) and status, then Hunger,
 * Fun, Strength and Mood as segmented gauges, in a gold-framed window like the fight's command windows. It only
 * shows: the stable badge (top right) is where the player switches killers.
 */
@Composable
fun StatCard(pet: PetState, width: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(width)
            .gameFrame(topEnd = 12.dp, bottomStart = 12.dp)
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
    ) {
        // 30sp, stepping down only if a long name (LEATHERFACE) would not fit a narrow 4:3 window.
        BasicText(
            text = pet.id.displayName.uppercase(),
            style = displayStyle(30.sp, lineHeight = 34.sp),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 30.sp, stepSize = 1.sp),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("LV ${pet.level}", style = displayStyle(16.sp, color = Amber, tracking = 0.04f, lineHeight = 18.sp))
            Spacer(Modifier.width(8.dp))
            XpBar((pet.xp.toFloat() / xpToNext(pet.level)).coerceIn(0f, 1f), Modifier.size(54.dp, 5.dp))
            Spacer(Modifier.weight(1f))
            StatusTagBox(statusOf(pet))
        }
        Box(
            Modifier
                .padding(top = 8.dp, bottom = 6.dp, end = 2.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Amber.copy(alpha = 0.55f), Color.Transparent))),
        )
        // The labels' column is as wide as the widest label as drawn, at the font scale in use, so none is cut off
        // (STRENGTH lost its H) and the gauges still start in line.
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val labelWidth = remember(measurer, density) {
            val widest = StatIcon.entries.maxOf { measurer.measure(it.label.uppercase(), LabelStyle, maxLines = 1).size.width }
            with(density) { widest.toDp() }
        }
        val hunger = pet.hunger
        StatRow(
            icon = StatIcon.HUNGER,
            value = hunger,
            level = when {
                hunger >= Tuning.STARVING_AT -> BarLevel.CRITICAL
                hunger >= Tuning.HUNGRY_AT -> BarLevel.WARN
                else -> BarLevel.OK
            },
            labelWidth = labelWidth,
        )
        StatRow(StatIcon.FUN, pet.funLevel, lowIsWarn(pet.funLevel), labelWidth)
        StatRow(StatIcon.STRENGTH, pet.strength, lowIsWarn(pet.strength), labelWidth)
        val moodValue = mood(pet).toDouble()
        StatRow(StatIcon.MOOD, moodValue, lowIsWarn(moodValue), labelWidth)
    }
}

/** A stat's label: small, bold and spaced out. */
private val LabelStyle = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.12f)

private fun lowIsWarn(v: Double): BarLevel = if (v <= LOW_AT) BarLevel.WARN else BarLevel.OK

/** Progress to the next level: a thin slanted gold bar, as big as [modifier] makes it. */
@Composable
internal fun XpBar(fraction: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(fraction, tween(BAR_ANIM_MS), label = "xp")
    Canvas(modifier) {
        drawPath(slanted(0f, size.width, size.height), Color.White.copy(alpha = 0.12f))
        if (shown > 0f) drawPath(slanted(0f, size.width * shown, size.height), Brush.horizontalGradient(listOf(Color(0xFFC98A12), Color(0xFFF5C543))))
    }
}

/** The status as an outlined tag with clipped corners: CONTENT grey, BORED/HUNGRY orange, STARVING/GRUMPY red. */
@Composable
private fun StatusTagBox(tag: StatusTag) {
    val color = when (tag) {
        StatusTag.CONTENT -> TextLo
        StatusTag.BORED, StatusTag.HUNGRY -> Warn
        StatusTag.STARVING, StatusTag.GRUMPY -> BloodGlow
    }
    Box(
        Modifier
            .drawBehind {
                val cut = 5.dp.toPx()
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width - cut, 0f)
                    lineTo(size.width, cut)
                    lineTo(size.width, size.height)
                    lineTo(cut, size.height)
                    lineTo(0f, size.height - cut)
                    close()
                }
                drawPath(path, color.copy(alpha = 0.14f))
                drawPath(path, color, style = Stroke(1.5.dp.toPx()))
            }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        BasicText(tag.name, style = displayStyle(12.sp, color = color, tracking = 0.12f, lineHeight = 14.sp), maxLines = 1)
    }
}

/** The four stats, each with its [label] and its icon, drawn in the gold of the window's edge. */
private enum class StatIcon(val label: String) {
    HUNGER("Hunger"),
    FUN("Fun"),
    STRENGTH("Strength"),
    MOOD("Mood"),
}

/**
 * One stat: its icon and label (in a column [labelWidth] wide), a [SEGMENTS]-segment gauge that fills to [value]
 * (animated), and the number.
 */
@Composable
private fun StatRow(icon: StatIcon, value: Double, level: BarLevel, labelWidth: Dp) {
    val label = icon.label
    val fraction by animateFloatAsState(
        targetValue = (value / Tuning.STAT_MAX).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(BAR_ANIM_MS),
        label = "bar$label",
    )
    val valueColor by animateColorAsState(levelColor(level), tween(BAR_ANIM_MS), label = "barValue$label")
    // A critical gauge breathes, so a starving pet is noticed from the sofa. The pulse is only read in the draw
    // phase below, so it repaints the gauge each frame without recomposing the window.
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
    val brush = levelBrush(level)

    Row(Modifier.fillMaxWidth().height(27.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(16.dp)) { drawStatIcon(icon, Amber) }
        Spacer(Modifier.width(6.dp))
        BasicText(label.uppercase(), style = LabelStyle, maxLines = 1, modifier = Modifier.width(labelWidth))
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.weight(1f).height(11.dp)) {
            val gap = 1.6.dp.toPx()
            val segW = (size.width - gap * (SEGMENTS - 1) - size.height * SLANT) / SEGMENTS
            val filled = fraction * SEGMENTS
            val glow = pulse.value
            if (glow > 0f && filled > 0f) {
                softGlow(BloodGlow, alpha = 0.5f * glow, cornerRadius = 0f, spread = 6.dp, layers = 4)
            }
            for (i in 0 until SEGMENTS) {
                val left = i * (segW + gap)
                drawPath(slanted(left, left + segW, size.height), Color.White.copy(alpha = 0.1f))
                val part = (filled - i).coerceIn(0f, 1f)
                if (part > 0f) drawPath(slanted(left, left + segW * part, size.height), brush)
            }
        }
        Spacer(Modifier.width(6.dp))
        BasicText(
            value.roundToInt().toString(),
            style = displayStyle(17.sp, color = valueColor, lineHeight = 18.sp),
            maxLines = 1,
            modifier = Modifier.width(24.dp),
        )
    }
}

/** A parallelogram from [left] to [right] along the bottom, [height] tall, leaning right like the fight's HP bars. */
private fun slanted(left: Float, right: Float, height: Float): Path = Path().apply {
    val s = height * SLANT
    moveTo(left + s, 0f)
    lineTo(right + s, 0f)
    lineTo(right, height)
    lineTo(left, height)
    close()
}

/** The pulse of a gauge that is not critical: always off. */
private object NoPulse : State<Float> {
    override val value: Float = 0f
}

private val OkBrush = Brush.verticalGradient(listOf(Color(0xFFFFF0B0), Color(0xFFF5C543), Color(0xFFC98A12)))
private val WarnBrush = Brush.verticalGradient(listOf(Color(0xFFFFD08A), Color(0xFFFF9F1C), Color(0xFFB85E00)))
private val CriticalBrush = Brush.verticalGradient(listOf(Color(0xFFFF7A82), Color(0xFFE0262F), Color(0xFF8E0B15)))

private fun levelBrush(level: BarLevel): Brush = when (level) {
    BarLevel.OK -> OkBrush
    BarLevel.WARN -> WarnBrush
    BarLevel.CRITICAL -> CriticalBrush
}

private fun levelColor(level: BarLevel): Color = when (level) {
    BarLevel.OK -> TextHi
    BarLevel.WARN -> Warn
    BarLevel.CRITICAL -> BloodGlow
}

/**
 * A stat's icon in a 22-unit box scaled to this canvas: a drumstick for Hunger, a star for Fun, a dumbbell for
 * Strength and a heart for Mood.
 */
private fun DrawScope.drawStatIcon(icon: StatIcon, color: Color) {
    val k = size.minDimension / 22f
    scale(k, k, pivot = Offset.Zero) {
        val p = Path()
        when (icon) {
            StatIcon.HUNGER -> {
                // The meat, a circle up and right, and the bone running down-left from it to a knob.
                drawCircle(color, 5.6f, Offset(14.5f, 8f))
                p.moveTo(10.3f, 10.2f)
                p.lineTo(12.3f, 12.2f)
                p.lineTo(6.6f, 17.9f)
                p.lineTo(4.6f, 15.9f)
                p.close()
                drawPath(p, color)
                drawCircle(color, 1.9f, Offset(4.2f, 18.6f))
                drawCircle(color, 1.9f, Offset(3.4f, 16f))
            }
            StatIcon.FUN -> {
                val points = listOf(11f to 1.8f, 13.8f to 7.6f, 20.2f to 8.5f, 15.6f to 13f, 16.7f to 19.3f, 11f to 16.3f,
                    5.3f to 19.3f, 6.4f to 13f, 1.8f to 8.5f, 8.2f to 7.6f)
                p.moveTo(points[0].first, points[0].second)
                for ((x, y) in points.drop(1)) p.lineTo(x, y)
                p.close()
                drawPath(p, color)
            }
            StatIcon.STRENGTH -> {
                drawRect(color, Offset(1.5f, 9f), Size(2f, 4f))
                drawRect(color, Offset(3.5f, 6.5f), Size(3f, 9f))
                drawRect(color, Offset(7.5f, 9.6f), Size(7f, 2.8f))
                drawRect(color, Offset(15.5f, 6.5f), Size(3f, 9f))
                drawRect(color, Offset(18.5f, 9f), Size(2f, 4f))
            }
            StatIcon.MOOD -> {
                p.moveTo(11f, 19.5f)
                p.cubicTo(11f, 19.5f, 2.8f, 14.6f, 2.8f, 8.7f)
                p.cubicTo(2.8f, 4.4f, 8.4f, 2.6f, 11f, 5.9f)
                p.cubicTo(13.6f, 2.6f, 19.2f, 4.4f, 19.2f, 8.7f)
                p.cubicTo(19.2f, 14.6f, 11f, 19.5f, 11f, 19.5f)
                p.close()
                drawPath(p, color)
            }
        }
    }
}
