package team.superfun.horrorpets.ui.fight

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import team.superfun.horrorpets.assets.bodyScale
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.Rank
import team.superfun.horrorpets.fight.RankScore
import team.superfun.horrorpets.game.mood
import team.superfun.horrorpets.game.xpToNext
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

// ---- the timeline, in ms from the screen coming up ----

private const val TITLE_IN_MS = 280
private const val WINDOW_IN_MS = 300
private const val WINDOW_STAGGER_MS = 110L

/** The battle report's first line starts counting once the windows are in, the next ones this far apart. */
private const val REPORT_START_MS = 650L
private const val REPORT_LINE_MS = 150L
private const val COUNT_MS = 380

/** After the last line has counted, the rank stamp slams down; the XP fills after it. */
private const val STAMP_AFTER_MS = 300L
private const val STAMP_MS = 260
private const val XP_AFTER_STAMP_MS = 350L
private const val XP_FILL_MS = 1_100

/** The three windows' height, and the portrait inside the first. */
private val WindowsHeight = 330.dp
private val PortraitHeight = 124.dp
private val WindowGap = 12.dp

/**
 * The end of a fight as an arcade results screen, over the fight's darkened [arena]: `YOU WIN` / `YOU LOSE` /
 * `YOU RAN` slams in, then three framed windows slide up:
 * - FIGHTER: the player's pet (jumping and waving after a win, facepalming after a loss), its level and the XP it
 *   earned filling the bar (`LEVEL UP!` as it wraps), and its care stats with what the fight changed.
 * - BATTLE REPORT: how the fight went ([reportLines]), each line counting up in turn with a tick.
 * - RANK: after a win, its grade ([RankScore]) stamped down with a boom (and the announcer for an S or an A),
 *   the score part by part and the bonus XP it paid; after a loss or a run, why there is none.
 *
 * [before] is the pet as it went into the fight; without it (the Activity was recreated) the stats show their
 * values alone and the bar starts full. [Screen.Results.fight] is the fight as it ended; without it the report
 * is empty. One focused CONTINUE pill, or Back, calls [onDone]. OK is ignored for a moment after the screen
 * comes up (see `rememberOkReady`), so an OK mashed through the fight's last turn cannot skip it; so is a tap,
 * on a phone. The view model has already queued `you_win` / `you_lose` (and `levelup`).
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
    GameBackHandler(enabled = focusEntry != null, onBack = onDone)
    RequestFocusOnEntry(focus, focusEntry)
    val okReady = rememberOkReady(focusEntry)
    val sink = LocalSoundSink.current

    val fight = screen.fight
    val lines = remember(fight) { fight?.let(::reportLines).orEmpty() }
    val scored = screen.reward.scored
    val stampAt = REPORT_START_MS + REPORT_LINE_MS * (lines.size - 1).coerceAtLeast(0) + COUNT_MS + STAMP_AFTER_MS

    // One clock for the whole sequence; every part reads where it is from it.
    val clock = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { title.animateTo(1f, tween(TITLE_IN_MS, easing = LinearOutSlowInEasing)) }
        launch { clock.animateTo(TIMELINE_END_MS.toFloat(), tween(TIMELINE_END_MS, easing = LinearEasing)) }
        // A tick as each report line starts counting, a boom as the rank lands, the announcer for a great one.
        for (i in lines.indices) {
            delay(if (i == 0) REPORT_START_MS else REPORT_LINE_MS)
            sink.sfx("ui_move")
        }
        delay(stampAt - (REPORT_START_MS + REPORT_LINE_MS * (lines.size - 1).coerceAtLeast(0)))
        if (scored != null) {
            sink.sfx("crit")
            rankCall(scored.rank)?.let { sink.voice(listOf(it)) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().ignoreOkUnless(okReady)) {
        if (arena != null) {
            SceneBackdrop(loader = loader, scene = arena) { }
        }
        // Darken the arena so the results own the screen.
        Spacer(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.72f)))
        val safe = safeInset()
        Column(
            Modifier.fillMaxSize().padding(horizontal = safe.horizontal, vertical = safe.vertical),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Title(screen, Modifier.graphicsLayer {
                val p = title.value
                val s = lerp(1.6f, 1f, p * p)
                scaleX = s
                scaleY = s
                alpha = (p / 0.4f).coerceAtMost(1f)
            })
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(WindowsHeight), horizontalArrangement = Arrangement.spacedBy(WindowGap)) {
                ResultWindow("FIGHTER", index = 0, clock = { clock.value }, modifier = Modifier.weight(1f)) {
                    FighterPanel(loader, screen, before, xpAt = if (scored != null) stampAt + XP_AFTER_STAMP_MS else REPORT_START_MS)
                }
                ResultWindow(
                    "BATTLE REPORT",
                    index = 1,
                    clock = { clock.value },
                    modifier = Modifier.weight(1.25f),
                    aside = fight?.let { "VS ${it.cpu.id.displayName.uppercase()}" },
                ) {
                    ReportPanel(lines, clock = { clock.value })
                }
                ResultWindow("RANK", index = 2, clock = { clock.value }, modifier = Modifier.weight(0.9f)) {
                    RankPanel(screen, fight, stamp = { ((clock.value - stampAt) / STAMP_MS).coerceIn(0f, 1f) })
                }
            }
            Spacer(Modifier.height(14.dp))
            FocusPill("Continue", onClick = { if (focusEntry != null) onDone() }, modifier = Modifier.focusRequester(focus), minWidth = 200.dp)
        }
    }
}

/** Long enough for the slowest part: the XP fill after a stamp that follows a full report. */
private const val TIMELINE_END_MS = 6_000

/** The announcer's word for a [rank], if it gets one: only the great ones do. */
internal fun rankCall(rank: Rank): String? = when (rank) {
    Rank.S -> "unbelievable"
    Rank.A -> "brutal"
    Rank.B, Rank.C -> null
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
    OutlinedText(text, displayStyle(66.sp, tracking = 0.06f, lineHeight = 66.sp).copy(brush = fill), outline = 6.dp, modifier = modifier)
}

/**
 * One of the results' framed windows: a gold [title] (and an [aside] at its other end) over a fading rule, then
 * its [content]. The [index]th window slides up and fades in a little after the one before it.
 */
@Composable
private fun ResultWindow(
    title: String,
    index: Int,
    clock: () -> Float,
    modifier: Modifier = Modifier,
    aside: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .graphicsLayer {
                val p = ((clock() - TITLE_IN_MS - index * WINDOW_STAGGER_MS) / WINDOW_IN_MS).coerceIn(0f, 1f)
                val eased = 1f - (1f - p) * (1f - p)
                alpha = eased
                translationY = (1f - eased) * 28.dp.toPx()
            }
            .gameFrame(topEnd = 12.dp, bottomStart = 12.dp)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = displayStyle(12.sp, FontWeight.Bold, color = Amber, tracking = 0.24f), maxLines = 1)
            if (aside != null) {
                // All the room the title leaves, the aside at its far end (stepping down to fit on 4:3).
                Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    BasicText(
                        aside,
                        style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.16f),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 1.sp),
                    )
                }
            }
        }
        Box(
            Modifier
                .padding(top = 6.dp, bottom = 8.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Amber.copy(alpha = 0.5f), Color.Transparent))),
        )
        content()
    }
}

// ---- FIGHTER ----

@Composable
private fun ColumnScope.FighterPanel(loader: AssetLoader, screen: Screen.Results, before: PetState?, xpAt: Long) {
    val after = screen.reward.pet
    // The bar runs in "levels": 3.4 is level 3 and 40% of the way to 4. A level-up wraps it round.
    val end = levels(after)
    val start = before?.let(::levels) ?: end
    val progress = remember { Animatable(start) }
    val gained = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(xpAt)
        launch { gained.animateTo(screen.reward.xpGained.toFloat(), tween(XP_FILL_MS, easing = FastOutSlowInEasing)) }
        progress.animateTo(end, tween(XP_FILL_MS, easing = FastOutSlowInEasing))
    }
    val level by remember { derivedStateOf { floor(progress.value + 1e-4f).toInt() } }
    // With the pet before the fight, LEVEL UP! pops as the bar wraps; without it (the Activity was
    // recreated on this screen) there is no fill to watch, so it shows at once.
    val leveledUp by remember {
        derivedStateOf { screen.reward.levelsGained > 0 && (before == null || level > floor(start + 1e-4f).toInt()) }
    }

    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
        Portrait(loader, after, screen, cellH = with(LocalDensity.current) { PortraitHeight.toPx() })
        if (leveledUp) LevelUpChip(Modifier.align(Alignment.TopCenter))
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(
            after.id.displayName.uppercase(),
            style = displayStyle(28.sp, tracking = 0.05f, lineHeight = 28.sp),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 28.sp, stepSize = 1.sp),
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        BasicText("LV $level", style = displayStyle(18.sp, color = TextLo, tracking = 0.1f, lineHeight = 20.sp), maxLines = 1)
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val xp by remember { derivedStateOf { gained.value.roundToInt() } }
        BasicText(
            if (screen.ran) "NO XP" else "+$xp XP",
            style = displayStyle(20.sp, color = if (screen.ran) TextLo else Amber, tracking = 0.06f, lineHeight = 20.sp),
            maxLines = 1,
            modifier = Modifier.width(68.dp),
        )
        XpBar(fraction = { progress.value - floor(progress.value + 1e-4f) }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        BasicText("${after.xp}/${xpToNext(after.level)}", style = displayStyle(14.sp, color = TextLo, lineHeight = 14.sp), maxLines = 1)
    }
    Spacer(Modifier.height(8.dp))
    for (line in statLines(before, after)) StatRow(line)
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
    // The portrait keeps a full-size cell's room; a half-height Chucky stands at its foot, and the light
    // pools around his body rather than above his head.
    val body = remember(loader, pet.id) { loader.manifest(pet.id).bodyScale }
    Box(
        Modifier.drawWithCache {
            val center = Offset(size.width / 2f, size.height * (1f - 0.45f * body))
            val radius = size.height * 0.62f * body
            val glow = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.12f),
                0.6f to Color.White.copy(alpha = 0.03f),
                1f to Color.Transparent,
                center = center,
                radius = radius,
            )
            onDrawBehind { drawCircle(glow, radius = radius, center = center) }
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

private fun levels(p: PetState): Float = p.level + p.xp.toFloat() / xpToNext(p.level).coerceAtLeast(1)

private val XpFill = Brush.horizontalGradient(listOf(Color(0xFFB4700E), Amber, Color(0xFFFFE9A8)))

@Composable
private fun XpBar(fraction: () -> Float, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .height(10.dp)
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
private fun LevelUpChip(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "levelUp").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "levelUpPulse",
    )
    Box(
        modifier
            .drawBehind {
                softGlow(Amber, alpha = 0.6f * pulse.value, cornerRadius = 6.dp.toPx(), spread = 10.dp, layers = 5)
            }
            .background(Amber, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        BasicText("LEVEL UP!", style = displayStyle(18.sp, color = Ink, tracking = 0.08f, lineHeight = 20.sp), maxLines = 1)
    }
}

/** One care stat's change: [before] is null when the pet before the fight is not known. */
private class StatLine(val label: String, val before: Int?, val after: Int, val higherIsBetter: Boolean)

private fun statLines(before: PetState?, after: PetState): List<StatLine> = listOf(
    StatLine("Hunger", before?.hunger?.roundToInt(), after.hunger.roundToInt(), higherIsBetter = false),
    StatLine("Fun", before?.funLevel?.roundToInt(), after.funLevel.roundToInt(), higherIsBetter = true),
    StatLine("Strength", before?.strength?.roundToInt(), after.strength.roundToInt(), higherIsBetter = true),
    StatLine("Mood", before?.let(::mood), mood(after), higherIsBetter = true),
)

/** `HUNGER   35   +15`: the value now and what the fight did to it, worse in Amber, better bright, none dimmed. */
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
    Row(Modifier.fillMaxWidth().height(19.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            line.label.uppercase(),
            style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f, lineHeight = 13.sp),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        BasicText("${line.after}", style = displayStyle(16.sp, color = TextHi, lineHeight = 16.sp), maxLines = 1)
        Box(Modifier.width(DeltaWidth), contentAlignment = Alignment.CenterEnd) {
            val text = when {
                !changed -> if (line.before == null) "" else "—"
                delta > 0 -> "+$delta"
                else -> "−${abs(delta)}"
            }
            BasicText(text, style = displayStyle(15.sp, color = deltaColor, lineHeight = 15.sp), maxLines = 1)
        }
    }
}

private val DeltaWidth: Dp = 44.dp

// ---- BATTLE REPORT ----

/**
 * One line of the battle report: [label], and the [value] it counts up to, shown through [format] (which is
 * handed the count so far). A [hot] line (a crit landed, a real combo) shows its value in Amber.
 */
internal class ReportLine(val label: String, val value: Int, val hot: Boolean = false, val format: (Int) -> String = { "$it" })

/** The battle report of [fight], as the player fought it: damage both ways, accuracy, crits, combo, specials, healing, turns. */
internal fun reportLines(fight: FightState): List<ReportLine> {
    val st = fight.stats
    val swings = st.hits + st.misses + st.stopped
    return listOf(
        ReportLine("Damage dealt", st.damageDealt),
        ReportLine("Damage taken", st.damageTaken),
        ReportLine("Hits landed", st.hits) { "$it/$swings" },
        ReportLine("Critical hits", st.crits, hot = st.crits > 0),
        ReportLine("Best combo", st.bestCombo, hot = st.bestCombo >= 2) { if (it >= 2) "×$it" else "—" },
        ReportLine("Specials", st.specials, hot = st.specials > 0),
        ReportLine("HP healed", st.healed),
        ReportLine("Turns", fight.turnsPlayed),
    )
}

@Composable
private fun ColumnScope.ReportPanel(lines: List<ReportLine>, clock: () -> Float) {
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            BasicText("—", style = displayStyle(28.sp, color = TextLo), maxLines = 1)
        }
        return
    }
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        lines.forEachIndexed { i, line ->
            // Each line fades in as it starts counting, and counts up with an ease-out.
            val p by remember(line) {
                derivedStateOf { ((clock() - REPORT_START_MS - i * REPORT_LINE_MS) / COUNT_MS).coerceIn(0f, 1f) }
            }
            val shown = (line.value * (1f - (1f - p) * (1f - p))).roundToInt()
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = 0.25f + 0.75f * (p / 0.2f).coerceAtMost(1f) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    line.label.uppercase(),
                    style = displayStyle(15.sp, FontWeight.Bold, color = TextLo, tracking = 0.12f, lineHeight = 16.sp),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    if (p > 0f) line.format(shown) else "",
                    style = displayStyle(22.sp, color = if (line.hot) Amber else TextHi, tracking = 0.04f, lineHeight = 22.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

// ---- RANK ----

private val RankS = Brush.verticalGradient(listOf(Color(0xFFFFF4C9), Color(0xFFF5C543), Color(0xFFB4700E)))
private val RankA = Brush.verticalGradient(listOf(Color(0xFFFF9AA0), BloodGlow, Color(0xFF8E0B15)))
private val RankB = Brush.verticalGradient(listOf(Color(0xFFE2F1FF), ShieldBlue, Color(0xFF4F79A8)))
private val RankC = Brush.verticalGradient(listOf(Color(0xFFE6E3E8), Color(0xFF9C98A0), Color(0xFF5C5960)))

private fun rankFill(rank: Rank): Brush = when (rank) {
    Rank.S -> RankS
    Rank.A -> RankA
    Rank.B -> RankB
    Rank.C -> RankC
}

/** The glow under each rank's letter. */
private fun rankGlow(rank: Rank): Color = when (rank) {
    Rank.S -> Amber
    Rank.A -> BloodGlow
    Rank.B -> ShieldBlue
    Rank.C -> Color(0xFF9C98A0)
}

/** One part of a win's score as the rank window lists it: `BEST COMBO   +20`. */
internal class ScoreLine(val label: String, val points: Int)

/** A win's score part by part, as the rank window lists it. */
internal fun scoreLines(s: RankScore): List<ScoreLine> = listOf(
    ScoreLine("HP left ${s.hpPercent}%", s.hpPoints),
    ScoreLine("Best combo", s.comboPoints),
    ScoreLine("Speed", s.speedPoints),
    ScoreLine("Potions", s.potionPoints),
)

@Composable
private fun ColumnScope.RankPanel(screen: Screen.Results, fight: FightState?, stamp: () -> Float) {
    val scored = screen.reward.scored
    if (scored == null) {
        // A loss, a run or a win by forfeit earns no rank: say why, without a letter.
        val theyRan = fight?.cpuRan == true
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    when {
                        screen.ran -> "FLED"
                        theyRan -> "FORFEIT"
                        else -> "K.O."
                    },
                    style = displayStyle(if (theyRan) 44.sp else 54.sp, color = if (theyRan) Amber else TextLo, tracking = 0.06f, lineHeight = 56.sp),
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    if (theyRan) "THEY RAN \u00b7 NO RANK" else "WIN TO EARN A RANK",
                    style = displayStyle(13.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 13.sp, stepSize = 1.sp),
                )
                if (!screen.ran && fight != null) {
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        "BEATEN BY ${fight.cpu.id.displayName.uppercase()}",
                        style = displayStyle(13.sp, FontWeight.Bold, color = BloodGlow, tracking = 0.14f),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 13.sp, stepSize = 1.sp),
                    )
                }
            }
        }
        return
    }
    val rank = scored.rank
    // The letter slams down from big and settles; the rest of the window comes up once it has landed.
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .graphicsLayer {
                    val p = stamp()
                    val s = if (p <= 0f) 0f else lerp(2.6f, 1f, (p * p).coerceAtMost(1f))
                    scaleX = s
                    scaleY = s
                    alpha = (p / 0.25f).coerceAtMost(1f)
                    transformOrigin = TransformOrigin.Center
                }
                .drawWithCache {
                    val r = size.minDimension * 0.75f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val bloom = Brush.radialGradient(
                        0f to rankGlow(rank).copy(alpha = 0.45f),
                        0.55f to rankGlow(rank).copy(alpha = 0.12f),
                        1f to Color.Transparent,
                        center = c,
                        radius = r,
                    )
                    onDrawBehind { drawCircle(bloom, radius = r, center = c) }
                }
                .padding(horizontal = 18.dp),
        ) {
            OutlinedText(rank.name, displayStyle(112.sp, tracking = 0.02f, lineHeight = 112.sp).copy(brush = rankFill(rank)), outline = 7.dp)
        }
    }
    val after = Modifier.graphicsLayer { alpha = ((stamp() - 0.6f) / 0.4f).coerceIn(0f, 1f) }
    Column(after.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            rank.title,
            style = displayStyle(22.sp, tracking = 0.16f, lineHeight = 22.sp).copy(brush = rankFill(rank)),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 22.sp, stepSize = 1.sp),
        )
        Spacer(Modifier.height(8.dp))
        for (line in scoreLines(scored)) ScoreRow(line.label, line.points)
        Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Amber.copy(alpha = 0.3f)))
        ScoreRow("Score", scored.total, total = true)
        Spacer(Modifier.height(6.dp))
        BasicText(
            if (rank.bonusXp > 0) "+${rank.bonusXp} BONUS XP" else "NO BONUS XP",
            style = displayStyle(16.sp, color = if (rank.bonusXp > 0) Amber else TextLo, tracking = 0.1f, lineHeight = 16.sp),
            maxLines = 1,
        )
    }
}

/** `BEST COMBO   +20`: a part of the score (or, as the [total], the score itself). */
@Composable
private fun ScoreRow(label: String, points: Int, total: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            label.uppercase(),
            style = displayStyle(12.sp, FontWeight.Bold, color = if (total) TextHi else TextLo, tracking = 0.12f, lineHeight = 13.sp),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 1.sp),
            modifier = Modifier.weight(1f),
        )
        val text = when {
            total -> "$points"
            points > 0 -> "+$points"
            points < 0 -> "−${abs(points)}"
            else -> "0"
        }
        BasicText(
            text,
            style = displayStyle(if (total) 17.sp else 15.sp, color = if (points < 0 && !total) Amber else TextHi, lineHeight = 17.sp),
            maxLines = 1,
        )
    }
}
