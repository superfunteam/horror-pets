package team.superfun.horrorpets.ui.attacks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.model.ATTACK_POWER
import team.superfun.horrorpets.model.Attack
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.SpecialStyle
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.fight.AttackMarks
import team.superfun.horrorpets.ui.fight.MarkFx
import team.superfun.horrorpets.ui.fight.SpecialFx
import team.superfun.horrorpets.ui.fight.SpecialVfx
import team.superfun.horrorpets.ui.fight.TANK_ART
import team.superfun.horrorpets.ui.fight.markPlan
import team.superfun.horrorpets.ui.fight.specialLandsAtMs
import team.superfun.horrorpets.ui.fight.specialMs
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** How a stat compares with the same stat of the attack it is shown against. */
internal enum class Change { BETTER, WORSE, SAME, NONE }

/** One stat line of an attack card: its [label], its [value] as shown, a [bar] (0..1) for power, and its [change]. */
internal data class StatLine(val label: String, val value: String, val bar: Float? = null, val change: Change = Change.NONE)

/** A STRIKE's or HEAVY's chance to land, in percent, before the target's evasion. */
internal fun hitPercent(a: Attack): Int {
    val base = if (a.slot == Slot.HEAVY) FightTuning.MISS_HEAVY else FightTuning.MISS_STRIKE
    return (100 * (1.0 - (base + a.missChange).coerceIn(0.0, 1.0))).roundToInt()
}

/** How many landed hits fill the special meter with [a]. */
internal fun hitsToSpecial(a: Attack): Int = ceil(FightTuning.METER_MAX / a.meterGain.toDouble()).toInt()

/** What [a] leaves on a target, in a few words ("Bleeds 2 turns", "25% daze, shatters 25"), or null for nothing. */
internal fun effectText(a: Attack): String? {
    val parts = buildList {
        a.bleed?.let { b ->
            add(if (a.slot == Slot.SPECIAL) "Bleeds ${b.turns} turns × ${(b.share * 100).roundToInt()}%" else "Bleeds ${b.turns} turns")
        }
        if (a.drain > 0) add("Drains ${(a.drain * 100).roundToInt()}%")
        when {
            a.daze >= 1.0 -> add("Dazes")
            a.daze > 0 -> add("${(a.daze * 100).roundToInt()}% daze")
        }
        when {
            a.shatter >= FightTuning.METER_MAX -> add("empties their meter")
            a.shatter > 0 -> add("shatters ${a.shatter}")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.uppercase() }
}

private fun compare(mine: Double, theirs: Double?, higherIsBetter: Boolean = true): Change = when {
    theirs == null -> Change.NONE
    mine == theirs -> Change.SAME
    (mine > theirs) == higherIsBetter -> Change.BETTER
    else -> Change.WORSE
}

/** [a]'s stat lines (power, accuracy, crit, meter, effect), each compared with [vs] when there is one. */
internal fun statLines(a: Attack, vs: Attack?): List<StatLine> {
    val top = ATTACK_POWER.getValue(a.slot).last()
    val special = a.slot == Slot.SPECIAL
    return listOf(
        StatLine("Power", "×${"%.2f".format(a.power).trimEnd('0').trimEnd('.')}", bar = (a.power / top).toFloat(), change = compare(a.power, vs?.power)),
        if (special) StatLine("Accuracy", "Never misses", change = if (vs == null) Change.NONE else Change.SAME)
        else StatLine("Accuracy", "${hitPercent(a)}%", change = compare(hitPercent(a).toDouble(), vs?.let { hitPercent(it).toDouble() })),
        StatLine("Crit", if (a.critBonus > 0) "+${(a.critBonus * 100).roundToInt()}%" else "—", change = compare(a.critBonus, vs?.critBonus)),
        if (special) StatLine("Meter", "Spends it all", change = if (vs == null) Change.NONE else Change.SAME)
        else StatLine("Meter", "${hitsToSpecial(a)} hits", change = compare(hitsToSpecial(a).toDouble(), vs?.let { hitsToSpecial(it).toDouble() }, higherIsBetter = false)),
        StatLine(
            "Effect",
            effectText(a) ?: "—",
            change = when {
                vs == null -> Change.NONE
                effectText(a) == effectText(vs) -> Change.SAME
                effectText(vs) == null -> Change.BETTER
                effectText(a) == null -> Change.WORSE
                special -> if (a.tier > vs.tier) Change.BETTER else Change.WORSE
                else -> Change.NONE
            },
        ),
    )
}

internal val Better = Color(0xFF7CFF6B)
private val PowerFill = Brush.horizontalGradient(listOf(Color(0xFFB4700E), Amber, Color(0xFFFFE9A8)))

/**
 * An attack in a framed card: a gold [title] (NOW, NEW, the slot), its name (stepping down to fit the longest), its
 * slot and level, a looping [preview][AttackPreview] of what it does to a target, and its [statLines] against [vs].
 * [lit] brightens the frame. [footer] goes under the stats. [loader] supplies any picture the preview throws.
 */
@Composable
internal fun AttackCard(
    attack: Attack,
    vs: Attack?,
    title: String,
    loader: AssetLoader,
    modifier: Modifier = Modifier,
    lit: Boolean = false,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .gameFrame(topEnd = 12.dp, bottomStart = 12.dp, lit = { if (lit) 0.7f else 0f })
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicText(title, style = displayStyle(12.sp, FontWeight.Bold, color = Amber, tracking = 0.24f), maxLines = 1)
        Spacer(Modifier.height(4.dp))
        AttackName(attack.name, Modifier.fillMaxWidth())
        BasicText(
            "${attack.slot.label.uppercase()}  ·  ${if (attack.tier == 0) "STARTING ATTACK" else "LEVEL ${attack.level}"}",
            style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f),
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        // The preview gives up height first, so on a short screen (a phone's insets) the stats always fit.
        AttackPreview(attack, loader, Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 34.dp, max = PreviewHeight))
        Spacer(Modifier.height(8.dp))
        for (line in statLines(attack, vs)) StatRow(line)
        footer()
    }
}

/** An attack's name on one line, as big as it fits: 30sp down to 15sp for the longest. */
@Composable
internal fun AttackName(name: String, modifier: Modifier = Modifier, max: Float = 30f, center: Boolean = false) {
    BasicText(
        name.uppercase(),
        style = displayStyle(max.sp, tracking = 0.04f, lineHeight = max.sp).copy(textAlign = if (center) TextAlign.Center else TextAlign.Start),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = max.sp, stepSize = 1.sp),
        modifier = modifier,
    )
}

private val PreviewHeight: Dp = 92.dp

/**
 * One showing of an attack's preview, in ms: its special's effect runs [effectMs] (0 for a STRIKE or HEAVY), its marks
 * start [marksAtMs] in, as its blow lands in a fight, and it all lasts [lengthMs] before the loop's pause.
 */
internal data class PreviewTiming(val effectMs: Int, val marksAtMs: Int, val lengthMs: Int)

/**
 * [attack]'s preview on the fight's own clock: a special's effect for as long as it runs in a fight ([specialMs]: Jaws's
 * tank flies, then blows up), and its marks from the moment its blow lands ([specialLandsAtMs]: with the tank's blast,
 * The Shape's slash), until they have faded.
 */
internal fun previewTiming(attack: Attack): PreviewTiming {
    val plan = markPlan(attack.slot, attack.tier)
    val style = attack.pet.spec.special
    val special = attack.slot == Slot.SPECIAL
    val effect = if (special) specialMs(style) else 0
    val marksAt = if (special) specialLandsAtMs(style).toInt() else 0
    return PreviewTiming(effect, marksAt, maxOf(effect, if (plan != null) marksAt + plan.ms else 0))
}

/** In the preview, a thrown special (Jaws's tank) is thrown from this many of the target's heights to its left, low. */
private const val PREVIEW_THROW = 1.3f
private const val PREVIEW_THROW_Y = 0.8f

/**
 * What [attack] does to a target, on a loop, in a dark inset: its marks (see [MarkFx]), and for a special its
 * killer's own effect under them, timed as in a fight (see [previewTiming]). Jaws's tank, its one picture (from
 * [loader]), is thrown in from the left, where the player's killer stands. A starting STRIKE or HEAVY leaves no marks:
 * it just says so.
 */
@Composable
internal fun AttackPreview(attack: Attack, loader: AssetLoader, modifier: Modifier = Modifier) {
    val plan = markPlan(attack.slot, attack.tier)
    val special = attack.slot == Slot.SPECIAL
    val timing = remember(attack) { previewTiming(attack) }
    val length = timing.lengthMs
    val style = attack.pet.spec.special
    val tank by produceState<ImageBitmap?>(null, loader, special, style) {
        if (special && style == SpecialStyle.OXYGEN_TANK) value = withContext(Dispatchers.IO) { loader.prop(TANK_ART) }
    }
    val loop = rememberInfiniteTransition(label = "preview").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(length + PREVIEW_PAUSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "previewLoop",
    )
    // The loop's time in ms, and how far through the marks (or the special's effect) that is.
    val at = { loop.value * (length + PREVIEW_PAUSE_MS) }
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .drawBehind {
                val r = CornerRadius(6.dp.toPx())
                drawRoundRect(Color(0xFF0B0709), cornerRadius = r)
                drawCircle(
                    Brush.radialGradient(listOf(Color(0x33FFFFFF), Color.Transparent), center = center, radius = size.height * 0.6f),
                    radius = size.height * 0.6f,
                    center = center,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        if (plan == null && !special) {
            BasicText("A PLAIN HIT, NO MARKS", style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f), maxLines = 1)
            return@BoxWithConstraints
        }
        val body = h * 0.9f
        if (special) {
            val fx = remember(attack, w, h) {
                SpecialFx(style, w / 2f, h / 2f, body, fromX = w / 2f - body * PREVIEW_THROW, fromY = h * PREVIEW_THROW_Y, throwerH = body)
            }
            SpecialVfx(fx, progress = { (at() / timing.effectMs).coerceIn(0f, 1f).let { if (it >= 1f) 0f else it } }, tank = tank)
        }
        if (plan != null) {
            val fx = remember(attack, w, h) { MarkFx(attack.pet, attack.slot, attack.tier, w / 2f, h / 2f, body, seed = attack.tier * 7) }
            AttackMarks(fx, progress = { ((at() - timing.marksAtMs) / plan.ms).coerceIn(0f, 1f).let { if (it >= 1f) 0f else it } })
        }
    }
}

private const val PREVIEW_PAUSE_MS = 700

/** `POWER  ████░░  ×2.2  ▲`: a stat, its value (and bar), and a better ▲ or worse ▼ against the other attack. */
@Composable
private fun StatRow(line: StatLine) {
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            line.label.uppercase(),
            style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f),
            maxLines = 1,
            modifier = Modifier.width(78.dp),
        )
        if (line.bar != null) {
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .drawBehind {
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = r)
                        drawRoundRect(PowerFill, size = Size(size.width * line.bar.coerceIn(0.05f, 1f), size.height), cornerRadius = r)
                    },
            )
            Spacer(Modifier.width(8.dp))
            BasicText(line.value, style = displayStyle(16.sp, color = TextHi, lineHeight = 16.sp), maxLines = 1)
        } else {
            BasicText(
                line.value.uppercase(),
                style = displayStyle(14.sp, color = TextHi, tracking = 0.04f, lineHeight = 15.sp),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp, stepSize = 1.sp),
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterEnd) {
            when (line.change) {
                Change.BETTER -> BasicText("▲", style = displayStyle(12.sp, color = Better), maxLines = 1)
                Change.WORSE -> BasicText("▼", style = displayStyle(12.sp, color = Amber), maxLines = 1)
                Change.SAME, Change.NONE -> Unit
            }
        }
    }
}

/** A thin gold rule across a card or window. */
@Composable
internal fun GoldRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Amber.copy(alpha = 0.5f), Color.Transparent))))
}

/** The same frame for a whole screen's title area. */
@Composable
internal fun ScreenTitle(text: String, sub: String?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            text.uppercase(),
            style = displayStyle(36.sp, tracking = 0.08f, lineHeight = 38.sp).copy(textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 36.sp, stepSize = 1.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (sub != null) {
            BasicText(sub.uppercase(), style = displayStyle(14.sp, FontWeight.Bold, color = Amber, tracking = 0.2f), maxLines = 1)
        }
    }
}
