package team.superfun.horrorpets.ui.fight

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.game.pantry.MealStatus
import team.superfun.horrorpets.ui.common.GlassCorner
import team.superfun.horrorpets.ui.common.MealChip
import team.superfun.horrorpets.ui.common.bodyStyle
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Glass
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** At or below this share of max HP the bar turns red and breathes. */
private const val LOW_HP = 0.3f

/**
 * What a side's HUD shows while a turn plays, in hit points and meter points: these run behind the real
 * fight state so the bars drain in step with the damage numbers. [trail] is the red chunk that lags
 * behind [hp]. All three are only read in the draw phase (and the HP count through a derived state), so
 * a draining bar never recomposes the HUD.
 */
internal class HudValues(val hp: () -> Float, val trail: () -> Float, val meter: () -> Float)

/**
 * The lasting states a side's HUD shows under its meter, as of the turn playback so far: its [combo] (shown from
 * 2 hits in a row), the turns it has left to [bleed], and whether it is [dazed]. Read in composition: they
 * change a few times a turn at most.
 */
internal class HudStatus(val combo: () -> Int, val bleed: () -> Int, val dazed: () -> Boolean)

/** The status row under the meter is this tall whether or not anything is in it, so the HUD never jumps. */
private val StatusRowHeight = 22.dp

/**
 * The Street-Fighter-style top bar, inside the safe inset: the player's name, HP bar and special meter
 * on the left (filling from the left edge), the CPU's mirrored on the right (filling from the right
 * edge), and the turn number in a badge between them. The player's last meal's status ([Fighter.status]) leads
 * its row of lasting states; the CPU's side never has one.
 */
@Composable
internal fun FightHud(
    player: Fighter,
    cpu: Fighter,
    turn: Int,
    playerValues: HudValues,
    cpuValues: HudValues,
    playerStatus: HudStatus,
    cpuStatus: HudStatus,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        SideHud(player, playerValues, playerStatus, meal = player.status, mirrored = false, modifier = Modifier.weight(1f))
        TurnBadge(turn, Modifier.padding(horizontal = 14.dp).padding(top = 22.dp))
        SideHud(cpu, cpuValues, cpuStatus, meal = null, mirrored = true, modifier = Modifier.weight(1f))
    }
}

/**
 * Every widest form the HP count `"hp/maxHp"` can take: as many digits as [maxHp] has, each in every digit
 * (the font's figures are not all one width), and the full count itself. The count reserves the widest of
 * these, so it keeps one width as the HP drains and the name beside it is sized once per fight.
 */
internal fun hpCountCandidates(maxHp: Int): List<String> {
    val digits = maxHp.toString().length
    return (0..9).map { d -> "$d".repeat(digits) + "/$maxHp" } + "$maxHp/$maxHp"
}

/** One side's HUD: [f]'s name, HP bar and meter, and its row of lasting states, led by its [meal] status if any. */
@Composable
private fun SideHud(f: Fighter, values: HudValues, status: HudStatus, meal: MealStatus?, mirrored: Boolean, modifier: Modifier = Modifier) {
    val hpShown by remember(values) { derivedStateOf { values.hp().roundToInt().coerceAtLeast(0) } }
    val full by remember(values) { derivedStateOf { values.meter() >= FightTuning.METER_MAX - 0.5f } }
    val pulse = rememberInfiniteTransition(label = "hudPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(560), RepeatMode.Reverse),
        label = "hudPulseValue",
    )
    val align = if (mirrored) Alignment.End else Alignment.Start
    val countStyle = displayStyle(22.sp, color = TextHi, tracking = 0.04f, halo = true, lineHeight = 22.sp)
        .copy(textAlign = if (mirrored) TextAlign.Start else TextAlign.End)
    // The count's widest form, measured once per fighter (and font scale): it never changes width mid-fight.
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val countWidth = remember(f.maxHp, measurer, density) {
        val px = hpCountCandidates(f.maxHp).maxOf { measurer.measure(it, countStyle, maxLines = 1).size.width }
        with(density) { px.toDp() }
    }
    Column(modifier, horizontalAlignment = align) {
        // Name and level at the outer edge, the HP count at the inner end, over the bar. The count (at its
        // reserved width) and the level are measured first; the name gets the room left between them and
        // steps down to fit it, so a long name (LEATHERFACE on 4:3) never pushes the count off its end, and
        // keeps its size while the HP drains.
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.Bottom) {
            val name = @Composable {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = if (mirrored) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    BasicText(
                        text = f.id.displayName.uppercase(),
                        style = displayStyle(32.sp, tracking = 0.06f, halo = true, lineHeight = 32.sp),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 32.sp, stepSize = 1.sp),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText("LV ${f.level}", style = displayStyle(20.sp, color = TextLo, tracking = 0.1f, halo = true, lineHeight = 20.sp), maxLines = 1)
                }
            }
            val count = @Composable {
                BasicText(
                    text = "$hpShown/${f.maxHp}",
                    style = countStyle,
                    maxLines = 1,
                    modifier = Modifier.width(countWidth),
                )
            }
            if (mirrored) {
                count()
                Spacer(Modifier.width(8.dp))
                name()
            } else {
                name()
                Spacer(Modifier.width(8.dp))
                count()
            }
        }
        Spacer(Modifier.height(5.dp))
        HpBar(values, f.maxHp, mirrored, pulse = { pulse.value })
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tag = @Composable {
                BasicText(
                    text = "SPECIAL",
                    style = displayStyle(18.sp, color = if (full) BloodGlow else TextLo.copy(alpha = 0.55f), tracking = 0.14f, halo = true, lineHeight = 18.sp),
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = if (full) 0.55f + 0.45f * pulse.value else 1f },
                )
            }
            if (mirrored) {
                tag()
                Spacer(Modifier.width(10.dp))
            }
            MeterBar(values.meter, mirrored, full, pulse = { pulse.value }, modifier = Modifier.width(150.dp))
            if (!mirrored) {
                Spacer(Modifier.width(10.dp))
                tag()
            }
        }
        Spacer(Modifier.height(6.dp))
        StatusRow(status, meal, mirrored)
    }
}

private val ComboFill = Brush.verticalGradient(listOf(Color(0xFFFFE08A), Color(0xFFF5A623), Color(0xFFB8650F)))
private val BleedFill = Brush.verticalGradient(listOf(Color(0xFFFF6A72), Color(0xFFC1121F), Color(0xFF6E0710)))
private val DazedFill = Brush.verticalGradient(listOf(Color(0xFFE3C8FF), Color(0xFF9B5CFF), Color(0xFF4B1E9A)))

/**
 * A side's lasting states as small chips, from the outer edge in: the player's [meal] status (the stat card's outlined
 * chip; the CPU's side, [mirrored], never has one), then COMBO ×N, BLEED N and DAZED, slanted. The fight's own states
 * keep their room: on a side too narrow for them all (a 4:3 screen), the meal chip says it in a word
 * ([statusShortTitle]), and where even that would not fit it steps aside.
 */
@Composable
private fun StatusRow(status: HudStatus, meal: MealStatus?, mirrored: Boolean) {
    val chips = buildList {
        val combo = status.combo()
        if (combo >= 2) add(Triple("COMBO \u00d7$combo", ComboFill, Color(0xFF2A1405)))
        val bleed = status.bleed()
        if (bleed > 0) add(Triple("BLEED $bleed", BleedFill, Color.White))
        if (status.dazed()) add(Triple("DAZED", DazedFill, Color.White))
    }
    Layout(
        content = {
            if (meal != null) {
                MealChip(meal)
                MealChip(meal, short = true)
            }
            for ((text, fill, ink) in if (mirrored) chips.reversed() else chips) HudChip(text, fill, ink, mirrored)
        },
        modifier = Modifier.height(StatusRowHeight),
    ) { measurables, constraints ->
        val gap = StatusChipGap.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
        val mealCount = if (meal != null) 2 else 0
        val meals = placeables.take(mealCount) // the whole title, then the word
        val others = placeables.drop(mealCount)
        val othersWidth = others.sumOf { it.width } + gap * (others.size - 1).coerceAtLeast(0)
        // The whole title if it fits beside the fight's own states, else the word, else nothing.
        val mealChip = meals.firstOrNull { othersWidth + it.width + (if (others.isEmpty()) 0 else gap) <= constraints.maxWidth }
        val shown = listOfNotNull(mealChip) + others
        val width = (shown.sumOf { it.width } + gap * (shown.size - 1).coerceAtLeast(0)).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = constraints.maxHeight
        layout(width, height) {
            var x = 0
            for (p in shown) {
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

/** The gap between a side's status chips. */
private val StatusChipGap = 8.dp

/** One status chip: [text] in [ink] on a slanted [fill] (leaning like the bars), with a light rim. */
@Composable
private fun HudChip(text: String, fill: Brush, ink: Color, mirrored: Boolean) {
    Box(
        Modifier
            .drawBehind {
                val skew = size.height * 0.35f
                val path = Path().apply {
                    if (mirrored) {
                        moveTo(0f, 0f)
                        lineTo(size.width - skew, 0f)
                        lineTo(size.width, size.height)
                        lineTo(skew, size.height)
                    } else {
                        moveTo(skew, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width - skew, size.height)
                        lineTo(0f, size.height)
                    }
                    close()
                }
                softGlow(Color.Black, alpha = 0.5f, cornerRadius = 2.dp.toPx(), spread = 6.dp, offsetY = 2.dp.toPx())
                drawPath(path, fill)
                drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(1.dp.toPx()))
            }
            .padding(horizontal = 12.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = displayStyle(15.sp, color = ink, tracking = 0.1f, lineHeight = 16.sp), maxLines = 1)
    }
}

private val HpFill = Brush.verticalGradient(listOf(Color(0xFFFFF0B0), Color(0xFFF5C543), Color(0xFFC98A12)))
private val HpLow = Brush.verticalGradient(listOf(Color(0xFFFF7A82), Color(0xFFE0262F), Color(0xFF8E0B15)))
private val Trail = Color(0xFFE8333D)

/** Over a red (low) fill the red trail would vanish, so there it is pale bone instead. */
private val TrailOnLow = Color(0xFFF4ECE4)
private val BarBack = Color(0xE6120D10)
private val Gloss = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.38f), 1f to Color.Transparent)

/**
 * A slanted HP bar: the fill (gold, red when low) over a "trail" that lags behind it, so a hit shows as a
 * chunk draining away after the fill: red over the gold fill, pale over the red one. [mirrored] anchors the fill at the right edge (the CPU).
 */
@Composable
private fun HpBar(values: HudValues, maxHp: Int, mirrored: Boolean, pulse: () -> Float) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val skew = h * 0.75f
                val frame = slanted(Path(), 0f, 1f, w, h, skew, mirrored)
                // Rebuilt in place each frame (the fill and trail move), so drawing never allocates.
                val fillPath = Path()
                val trailPath = Path()
                val gloss = Size(w, h * 0.42f)
                val rim = Stroke(2.dp.toPx())
                val shadowDy = 3.dp.toPx()
                onDrawBehind {
                    translate(top = shadowDy) { drawPath(frame, Color.Black, alpha = 0.5f) }
                    val scale = 1f / maxHp.coerceAtLeast(1)
                    val f = values.hp() * scale
                    val trail = values.trail() * scale
                    val low = f <= LOW_HP
                    if (low) {
                        // A breathing red halo, so a fighter near the end is noticed from the sofa.
                        softGlow(BloodGlow, alpha = 0.45f * pulse(), cornerRadius = 4f, spread = 10.dp, layers = 5)
                    }
                    clipPath(frame) {
                        drawRect(BarBack)
                        slanted(trailPath, 0f, trail.coerceIn(0f, 1f), w, h, skew, mirrored)
                        drawPath(trailPath, if (low) TrailOnLow else Trail)
                        slanted(fillPath, 0f, f.coerceIn(0f, 1f), w, h, skew, mirrored)
                        drawPath(fillPath, if (low) HpLow else HpFill)
                        drawRect(Gloss, size = gloss)
                    }
                    drawPath(frame, Color.White.copy(alpha = if (low) 0.5f else 0.32f), style = rim)
                }
            },
    )
}

/**
 * Fills [path] with the slanted segment of a bar from [from] to [to] (fractions of its length, from its
 * anchored outer edge). Both ends lean the same way, like the bar itself, so a draining fill keeps the
 * bar's shape. Returns the path.
 */
private fun slanted(path: Path, from: Float, to: Float, w: Float, h: Float, skew: Float, mirrored: Boolean): Path {
    path.reset()
    if (to <= from) return path
    val len = w - skew
    if (!mirrored) {
        // Anchored left: "/" ends.
        path.moveTo(skew + len * from, 0f)
        path.lineTo(skew + len * to, 0f)
        path.lineTo(len * to, h)
        path.lineTo(len * from, h)
    } else {
        // Anchored right: "\" ends.
        path.moveTo(len - len * to, 0f)
        path.lineTo(len - len * from, 0f)
        path.lineTo(w - len * from, h)
        path.lineTo(w - len * to, h)
    }
    path.close()
    return path
}

private val MeterFill = Brush.verticalGradient(listOf(Color(0xFFE0262F), Blood, Color(0xFF7A0912)))
private val MeterFull = Brush.verticalGradient(listOf(Color(0xFFFFA0A6), BloodGlow, Blood))

/** The special meter: four cells (a landed hit fills one), glowing and pulsing BloodGlow when full. */
@Composable
private fun MeterBar(meter: () -> Float, mirrored: Boolean, full: Boolean, pulse: () -> Float, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .height(12.dp)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val skew = h * 0.75f
                val frame = slanted(Path(), 0f, 1f, w, h, skew, mirrored)
                val fillPath = Path()
                val rim = Stroke(2.dp.toPx())
                val divider = 2.dp.toPx()
                val cells = FightTuning.METER_MAX / FightTuning.METER_PER_HIT
                onDrawBehind {
                    val m = (meter() / FightTuning.METER_MAX).coerceIn(0f, 1f)
                    if (full) softGlow(BloodGlow, alpha = 0.75f * pulse(), cornerRadius = 3f, spread = 10.dp, layers = 5)
                    clipPath(frame) {
                        drawRect(BarBack)
                        slanted(fillPath, 0f, m, w, h, skew, mirrored)
                        drawPath(fillPath, if (full) MeterFull else MeterFill, alpha = if (full) 0.7f + 0.3f * pulse() else 1f)
                        // Cell dividers, slanted like the bar.
                        val len = w - skew
                        for (c in 1 until cells) {
                            val t = c.toFloat() / cells
                            val top = if (mirrored) len - len * t else skew + len * t
                            val bottom = if (mirrored) w - len * t else len * t
                            drawLine(Color.Black, Offset(top, 0f), Offset(bottom, h), strokeWidth = divider, alpha = 0.7f)
                        }
                    }
                    drawPath(frame, if (full) BloodGlow else Color.White.copy(alpha = 0.28f), style = rim)
                }
            },
    )
}

/** The turn counter between the two bars. */
@Composable
private fun TurnBadge(turn: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 74.dp, height = 62.dp)
            .drawBehind { drawBadge() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            BasicText("TURN", style = bodyStyle(13.sp, color = TextLo, tracking = 1.6.sp), maxLines = 1)
            BasicText("$turn", style = displayStyle(32.sp, color = Amber, lineHeight = 32.sp), maxLines = 1)
        }
    }
}

private fun DrawScope.drawBadge() {
    val r = CornerRadius(GlassCorner.toPx() * 0.75f)
    softGlow(Color.Black, alpha = 0.55f, cornerRadius = r.x, spread = 12.dp, offsetY = 4.dp.toPx())
    drawRoundRect(Glass, cornerRadius = r)
    drawRoundRect(Color.Black.copy(alpha = 0.3f), cornerRadius = r)
    val stroke = 2.dp.toPx()
    drawRoundRect(
        Color.White.copy(alpha = 0.16f),
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(r.x - stroke / 2f),
        style = Stroke(stroke),
    )
}
