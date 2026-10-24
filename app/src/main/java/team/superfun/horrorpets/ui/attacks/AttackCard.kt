package team.superfun.horrorpets.ui.attacks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.SpriteManifest
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.assets.has
import team.superfun.horrorpets.assets.spec
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Side
import team.superfun.horrorpets.model.ATTACK_POWER
import team.superfun.horrorpets.model.Attack
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.SpecialStyle
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.placeAt
import team.superfun.horrorpets.ui.fight.AttackMarks
import team.superfun.horrorpets.ui.fight.HOP_BACK
import team.superfun.horrorpets.ui.fight.MarkFx
import team.superfun.horrorpets.ui.fight.STEP_BACK_MS
import team.superfun.horrorpets.ui.fight.STEP_IN_MS
import team.superfun.horrorpets.ui.fight.SpecialFx
import team.superfun.horrorpets.ui.fight.SpecialVfx
import team.superfun.horrorpets.ui.fight.TANK_ART
import team.superfun.horrorpets.ui.fight.attackSwing
import team.superfun.horrorpets.ui.fight.markPlan
import team.superfun.horrorpets.ui.fight.mirroredInFight
import team.superfun.horrorpets.ui.fight.specialLandsAtMs
import team.superfun.horrorpets.ui.fight.specialMs
import team.superfun.horrorpets.ui.fight.swingHoldMs
import team.superfun.horrorpets.ui.fight.swingImpactMs
import team.superfun.horrorpets.ui.fight.tankMouthX
import team.superfun.horrorpets.ui.fight.tankMouthY
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

/** What a locked attack's name, and each of its stats, show until it unlocks. */
internal const val LOCKED_TEXT = "???"

/**
 * What an attack's card shows: its [name], the [sub] line under it (its slot and level), its [stats], and whether its
 * [preview] plays.
 */
internal data class CardFace(val name: String, val sub: String, val stats: List<StatLine>, val preview: Boolean)

/**
 * The face of [attack]'s card against [vs]. A [locked] attack keeps its secret: no name, no numbers, no effect and no
 * preview, only its slot, the level it unlocks at, and its stats' labels; nothing is compared with [vs].
 */
internal fun cardFace(attack: Attack, vs: Attack?, locked: Boolean = false): CardFace {
    val slot = attack.slot.label.uppercase()
    return if (locked) {
        CardFace(
            name = LOCKED_TEXT,
            sub = "$slot  ·  UNLOCKS AT LEVEL ${attack.level}",
            stats = statLines(attack, null).map { StatLine(it.label, LOCKED_TEXT) },
            preview = false,
        )
    } else {
        CardFace(
            name = attack.name,
            sub = "$slot  ·  ${if (attack.tier == 0) "STARTING ATTACK" else "LEVEL ${attack.level}"}",
            stats = statLines(attack, vs),
            preview = true,
        )
    }
}

/**
 * An attack in a framed card: a gold [title] (NOW, NEW, the slot), its name (stepping down to fit the longest), its
 * slot and level, a looping [preview][AttackPreview] of its killer landing it, and its [statLines] against [vs].
 * A [locked] attack shows none of that (see [cardFace]): a [LockedPreview] and the level it unlocks at.
 * [lit] brightens the frame. [footer] goes under the stats. [loader] supplies the killer's sprites and any picture
 * the preview throws.
 */
@Composable
internal fun AttackCard(
    attack: Attack,
    vs: Attack?,
    title: String,
    loader: AssetLoader,
    modifier: Modifier = Modifier,
    lit: Boolean = false,
    locked: Boolean = false,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val face = cardFace(attack, vs, locked)
    Column(
        modifier
            .gameFrame(topEnd = 12.dp, bottomStart = 12.dp, lit = { if (lit) 0.7f else 0f })
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicText(title, style = displayStyle(12.sp, FontWeight.Bold, color = Amber, tracking = 0.24f), maxLines = 1)
        Spacer(Modifier.height(4.dp))
        AttackName(face.name, Modifier.fillMaxWidth())
        BasicText(face.sub, style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f), maxLines = 1)
        Spacer(Modifier.height(8.dp))
        // The preview gives up height first, so on a short screen (a phone's insets) the stats always fit.
        val previewSize = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 34.dp, max = PreviewHeight)
        if (face.preview) AttackPreview(attack, loader, previewSize) else LockedPreview(attack, loader, previewSize)
        Spacer(Modifier.height(8.dp))
        for (line in face.stats) StatRow(line)
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

/** Before each showing the attacker stands this long, and after it (its effect played out) this long: the rest. */
private const val PREVIEW_LEAD_MS = 250
private const val PREVIEW_PAUSE_MS = 600

/**
 * One showing of a preview's attacker, in ms from its start, on the fight's own clock (see `FightScreen`'s lunge): it
 * stands for [PREVIEW_LEAD_MS], runs in ([STEP_IN_MS]), swings [swing] from [swingAtMs] and lands its blow at
 * [impactAtMs] (see [swingImpactMs]), where the attack's effect starts (see [PreviewTiming]); it hops back from
 * [backAtMs] ([STEP_BACK_MS]) and stands until [lengthMs], once the effect has played out and the rest after it
 * ([PREVIEW_PAUSE_MS]) is over.
 */
internal data class PreviewLunge(val swing: Anim, val swingAtMs: Int, val impactAtMs: Int, val backAtMs: Int, val lengthMs: Int) {
    /** The animation it shows [ms] in. */
    fun animAt(ms: Float): Anim = when {
        ms < PREVIEW_LEAD_MS -> Anim.IDLE
        ms < swingAtMs -> Anim.RUN_RIGHT
        ms < backAtMs -> swing
        else -> Anim.IDLE
    }

    /** How far in it has stepped [ms] in: 0 on its mark, 1 all the way in (eased as a fight's step is). */
    fun stepAt(ms: Float): Float = when {
        ms < PREVIEW_LEAD_MS -> 0f
        ms < swingAtMs -> FastOutSlowInEasing.transform((ms - PREVIEW_LEAD_MS) / STEP_IN_MS)
        ms < backAtMs -> 1f
        ms < backAtMs + STEP_BACK_MS -> 1f - FastOutSlowInEasing.transform((ms - backAtMs) / STEP_BACK_MS)
        else -> 0f
    }

    /** How high it is in its hop back [ms] in: 0 on the floor, 1 at the top ([HOP_BACK] of its cell). */
    fun hopAt(ms: Float): Float {
        val t = (ms - backAtMs) / STEP_BACK_MS
        return when {
            t <= 0f || t >= 1f -> 0f
            t < 0.5f -> FastOutSlowInEasing.transform(t * 2f)
            else -> 1f - FastOutLinearInEasing.transform((t - 0.5f) * 2f)
        }
    }

    /** How long after the blow [ms] in is (negative before it lands): the effect's clock. */
    fun sinceImpactAt(ms: Float): Float = ms - impactAtMs
}

/**
 * The attacker of [attack]'s preview, whose effect runs as [timing] says, for its killer's sprites ([manifest]): the
 * swing a fight lands it with ([attackSwing]: an ATTACK, a danced special's DANCE, Jaws's barrels or thrash), its blow
 * on that swing's snap ([swingImpactMs]) and held as long as a fight holds it ([swingHoldMs]).
 */
internal fun previewLunge(attack: Attack, manifest: SpriteManifest, timing: PreviewTiming = previewTiming(attack)): PreviewLunge {
    val swing = attackSwing(attack, manifest::has)
    val spec = manifest.spec(swing)
    val impact = swingImpactMs(swing, spec)
    val swingAt = PREVIEW_LEAD_MS + STEP_IN_MS
    val impactAt = swingAt + impact.toInt()
    val backAt = impactAt + swingHoldMs(swing, spec, impact).toInt()
    val end = maxOf(backAt + STEP_BACK_MS, impactAt + timing.lengthMs)
    return PreviewLunge(swing, swingAt, impactAt, backAt, end + PREVIEW_PAUSE_MS)
}

/**
 * Where a preview [w] by [h] px stages an attack: the attacker's cell [cellH] tall, its feet on the [floor] at [homeX],
 * stepping [stepPx] in; the target (not drawn) as tall, its body centred on ([targetX], [targetY]).
 */
internal data class PreviewStage(val cellH: Float, val floor: Float, val homeX: Float, val stepPx: Float, val targetX: Float, val targetY: Float)

/** The attacker's cell is this share of the preview's height, its feet this far down it. */
private const val PREVIEW_CELL = 0.92f
private const val PREVIEW_FLOOR = 0.95f

/**
 * The attacker stands this many cells from the target (closer in a narrow preview, so half a cell either side of the
 * pair still fits), and steps in [PREVIEW_STEP] of that: a fight's step (12% of the screen) over the 40% between its
 * fighters.
 */
private const val PREVIEW_GAP_CELLS = 1.5f
private const val PREVIEW_STEP = 0.3f

/** The target's body is centred this far up its cell, as a fighter's is. */
private const val PREVIEW_BODY_AT = 0.45f

/** How [w] by [h] px stage an attack: attacker on the left, target on the right, the pair centred. */
internal fun previewStage(w: Float, h: Float): PreviewStage {
    val cellH = h * PREVIEW_CELL
    val floor = h * PREVIEW_FLOOR
    val gap = minOf(cellH * PREVIEW_GAP_CELLS, w - cellH).coerceAtLeast(0f)
    return PreviewStage(
        cellH = cellH,
        floor = floor,
        homeX = (w - gap) / 2f,
        stepPx = gap * PREVIEW_STEP,
        targetX = (w + gap) / 2f,
        targetY = floor - cellH * PREVIEW_BODY_AT,
    )
}

/** A preview's inset: dark, with a soft light in the middle. */
private fun Modifier.previewInset(): Modifier = clipToBounds().drawBehind {
    drawRoundRect(Color(0xFF0B0709), cornerRadius = CornerRadius(6.dp.toPx()))
    drawCircle(
        Brush.radialGradient(listOf(Color(0x33FFFFFF), Color.Transparent), center = center, radius = size.height * 0.6f),
        radius = size.height * 0.6f,
        center = center,
    )
}

/**
 * [attack] landed by its killer, on a loop, in a dark inset, as a fight plays it: the killer (its sprites from
 * [loader]) stands on the left facing right, runs in and swings (see [previewLunge]), and as its blow lands the
 * attack's effect plays on the right, where a target would stand: its marks (see [MarkFx]), and for a special its
 * killer's own effect under them, timed as in a fight (see [previewTiming]). Jaws's tank, its one picture, leaves his
 * mouth. A starting STRIKE or HEAVY leaves no marks: the target's spot just says so.
 */
@Composable
internal fun AttackPreview(attack: Attack, loader: AssetLoader, modifier: Modifier = Modifier) {
    val plan = markPlan(attack.slot, attack.tier)
    val special = attack.slot == Slot.SPECIAL
    val pet = attack.pet
    val style = pet.spec.special
    val timing = remember(attack) { previewTiming(attack) }
    val manifest = remember(loader, pet) { loader.manifest(pet) }
    val lunge = remember(attack, manifest) { previewLunge(attack, manifest, timing) }
    val tank by produceState<ImageBitmap?>(null, loader, special, style) {
        if (special && style == SpecialStyle.OXYGEN_TANK) value = withContext(Dispatchers.IO) { loader.prop(TANK_ART) }
    }
    // The showing's clock, in ms. It starts once the killer's strips are decoded, so each blow lands on its swing.
    val clock = remember(lunge) { Animatable(0f) }
    LaunchedEffect(loader, lunge) {
        withContext(Dispatchers.IO) { for (anim in listOf(Anim.IDLE, Anim.RUN_RIGHT, lunge.swing)) loader.strip(pet, anim) }
        while (true) {
            clock.snapTo(0f)
            clock.animateTo(lunge.lengthMs.toFloat(), tween(lunge.lengthMs, easing = LinearEasing))
        }
    }
    val anim by remember(lunge) { derivedStateOf { lunge.animAt(clock.value) } }
    // The animation on screen: the killer's facing follows it, so it never turns before its new strip is up.
    var shown by remember(lunge) { mutableStateOf(Anim.IDLE) }
    val since = { lunge.sinceImpactAt(clock.value) }
    BoxWithConstraints(modifier.previewInset()) {
        val density = LocalDensity.current
        val stage = previewStage(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() })
        val body = manifest.bodyScale
        val asked = anim
        SpriteView(
            loader = loader,
            pet = pet,
            anim = asked,
            heightPx = stage.cellH,
            tint = null,
            tintStrength = 0f,
            shadowOpacity = 0.5f,
            onStarted = { shown = asked },
            modifier = Modifier
                .placeAt(x = { stage.homeX + stage.stepPx * lunge.stepAt(clock.value) }, y = { stage.floor })
                .graphicsLayer {
                    translationY = -stage.cellH * body * HOP_BACK * lunge.hopAt(clock.value)
                    // Turned about its feet, as in a fight: only side-on art (Jaws) turns, to face right.
                    scaleX = if (mirroredInFight(pet, Side.PLAYER, shown)) -1f else 1f
                },
        )
        if (plan == null && !special) {
            BasicText(
                "A PLAIN HIT\nNO MARKS",
                style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f, lineHeight = 14.sp).copy(textAlign = TextAlign.Center),
                modifier = Modifier.placeAt(x = { stage.targetX }, y = { stage.targetY }, pivotY = 0.5f),
            )
            return@BoxWithConstraints
        }
        if (special) {
            val fx = remember(attack, stage, body) {
                // Anything it throws (Jaws's tank) leaves its mouth, stepped in, as in a fight.
                val thrower = stage.cellH * body
                SpecialFx(
                    style,
                    stage.targetX,
                    stage.targetY,
                    stage.cellH,
                    fromX = tankMouthX(stage.homeX + stage.stepPx, thrower, dir = 1f),
                    fromY = tankMouthY(stage.floor, thrower),
                    throwerH = thrower,
                )
            }
            SpecialVfx(fx, progress = { showing(since() / timing.effectMs) }, tank = tank)
        }
        if (plan != null) {
            val fx = remember(attack, stage) { MarkFx(pet, attack.slot, attack.tier, stage.targetX, stage.targetY, stage.cellH, seed = attack.tier * 7) }
            AttackMarks(fx, progress = { showing((since() - timing.marksAtMs) / plan.ms) })
        }
    }
}

/** An effect's progress while it shows, and 0 (nothing drawn) before it starts and once it is over. */
private fun showing(p: Float): Float = if (p <= 0f || p >= 1f) 0f else p

/**
 * A locked attack's preview, which gives nothing away: its killer (its sprites from [loader]) stands on the left in
 * shadow, and a padlock hangs where the attack would land.
 */
@Composable
internal fun LockedPreview(attack: Attack, loader: AssetLoader, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.previewInset()) {
        val density = LocalDensity.current
        val stage = previewStage(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() })
        SpriteView(
            loader = loader,
            pet = attack.pet,
            anim = Anim.IDLE,
            heightPx = stage.cellH,
            tint = null,
            tintStrength = 0f,
            dim = 0.78f,
            saturation = 0f,
            shadowOpacity = 0.4f,
            modifier = Modifier
                .placeAt(x = { stage.homeX }, y = { stage.floor })
                .graphicsLayer { scaleX = if (mirroredInFight(attack.pet, Side.PLAYER, Anim.IDLE)) -1f else 1f },
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind { drawPadlock(Offset(stage.targetX, stage.targetY), stage.cellH * 0.42f, TextLo) },
        )
    }
}

/**
 * A padlock [height] px tall, centred on [center], in [color]: a rounded body under its shackle, with a keyhole of
 * [hole] once it is big enough to see one.
 */
internal fun DrawScope.drawPadlock(center: Offset, height: Float, color: Color, hole: Color = Color(0xFF0B0709)) {
    val top = center.y - height / 2f
    val bodyW = height * 0.78f
    val bodyH = height * 0.56f
    val bodyTop = top + height - bodyH
    val stroke = height * 0.13f
    // The shackle's centre line: an arch whose outer edge touches the top, its legs running down into the body.
    val r = (bodyW * 0.62f - stroke) / 2f
    val archY = top + stroke / 2f + r
    val shackle = Path().apply {
        moveTo(center.x - r, bodyTop + stroke)
        lineTo(center.x - r, archY)
        arcTo(Rect(center.x - r, archY - r, center.x + r, archY + r), 180f, 180f, false)
        lineTo(center.x + r, bodyTop + stroke)
    }
    drawPath(shackle, color, style = Stroke(stroke))
    drawRoundRect(color, topLeft = Offset(center.x - bodyW / 2f, bodyTop), size = Size(bodyW, bodyH), cornerRadius = CornerRadius(height * 0.1f))
    if (height >= 16.dp.toPx()) {
        val keyY = bodyTop + bodyH * 0.42f
        drawCircle(hole, radius = height * 0.07f, center = Offset(center.x, keyY))
        drawRect(hole, topLeft = Offset(center.x - height * 0.03f, keyY), size = Size(height * 0.06f, bodyH * 0.3f))
    }
}

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
