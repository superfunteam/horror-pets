package team.superfun.horrorpets.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.ui.common.KeyCap
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.keyCapHeight
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.fight.OutlinedText
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink

/** The training games' gold: live status lines and callouts (WATCH..., GET READY, NEW BEST!). */
internal val TrainingGold = Color(0xFFF5C543)

/**
 * How [TrainingHud] sets its title and status (sizes in sp, letter spacing in em): the title [HUD_TITLE_SP] in a
 * [HUD_TITLE_LINE_SP] line, and under it the status, [HUD_STATUS_SP] in the face's own 1.2 em line. A game laying a
 * callout out against the HUD's corner takes its measures from these.
 */
internal const val HUD_TITLE_SP = 32f
internal const val HUD_TITLE_LINE_SP = 34f
internal const val HUD_TITLE_TRACKING = 0.03f
internal const val HUD_STATUS_SP = 17f
internal const val HUD_STATUS_TRACKING = 0.06f

/**
 * The best a game's HUD shows as it is played: the pet's [best] before the game, or the [score] so far once it beats
 * that, so a new best counts up in the corner as it is set, as the results card will have it.
 */
internal fun liveBest(best: Int, score: Int): Int = max(best, score)

/** One entry on the help strip: the [keys] (shown as key caps, in order) and what they do, the [label]. */
data class HelpItem(val keys: List<TrainingKey>, val label: String)

/**
 * A training game's HUD over its play area: the game's [title] top left with its live [status] in gold under it
 * (round, streak, caught...; nothing when blank), the pet's [best] score top right, and a help strip at the bottom: the
 * [help] items as key caps on a TV (arrows drawn, OK as text), or the [touchHelp] line under a finger. All of it keeps
 * inside the play area's safe inset. It fills the play area and takes no input.
 */
@Composable
fun TrainingHud(title: String, status: String, best: Int, help: List<HelpItem>, touchHelp: String) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val safe = safeInset()
        Column(Modifier.align(Alignment.TopStart).padding(start = safe.horizontal, top = safe.vertical)) {
            GlowText(
                title.uppercase(),
                displayStyle(HUD_TITLE_SP.sp, tracking = HUD_TITLE_TRACKING, lineHeight = HUD_TITLE_LINE_SP.sp),
            )
            if (status.isNotBlank()) {
                BasicText(
                    status.uppercase(),
                    style = displayStyle(
                        HUD_STATUS_SP.sp,
                        FontWeight.Bold,
                        color = TrainingGold,
                        tracking = HUD_STATUS_TRACKING,
                        halo = true,
                    ),
                    maxLines = 1,
                )
            }
        }
        BasicText(
            "BEST $best",
            style = displayStyle(20.sp, FontWeight.Bold, tracking = 0.05f, halo = true),
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = safe.horizontal, top = safe.vertical + 4.dp),
        )
        HelpStrip(help, touchHelp, Modifier.align(Alignment.BottomCenter).padding(bottom = safe.vertical))
    }
}

/** The help strip in a small game window: [help]'s key caps and labels, or [touchHelp] under a finger (the pantry's too). */
@Composable
internal fun HelpStrip(help: List<HelpItem>, touchHelp: String, modifier: Modifier = Modifier) {
    val touch = isTouchInput()
    if (if (touch) touchHelp.isBlank() else help.isEmpty()) return
    val label = displayStyle(14.sp, FontWeight.Bold, tracking = 0.06f)
    Row(
        modifier
            .gameFrame(topEnd = 7.dp, bottomStart = 7.dp)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (touch) {
            BasicText(touchHelp.uppercase(), style = label, maxLines = 1)
            return@Row
        }
        help.forEachIndexed { i, item ->
            if (i > 0) Spacer(Modifier.width(20.dp))
            if (item.keys.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (key in item.keys) {
                        if (key == TrainingKey.OK) KeyCap("OK") else ArrowCap(key)
                    }
                }
                Spacer(Modifier.width(7.dp))
            }
            BasicText(item.label.uppercase(), style = label, maxLines = 1)
        }
    }
}

/** A direction's key: an arrow drawn in dark on a square gold cap, the same height as a [KeyCap]. */
@Composable
private fun ArrowCap(key: TrainingKey) {
    Box(
        Modifier.size(keyCapHeight()).drawBehind {
            val cut = 5.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width - cut, 0f)
                    lineTo(size.width, cut)
                    lineTo(size.width, size.height)
                    lineTo(cut, size.height)
                    lineTo(0f, size.height - cut)
                    close()
                },
                Amber,
            )
            drawPath(arrowPath(Offset(size.width / 2f, size.height / 2f), size.width * 0.66f, directionDegrees(key)), Ink)
        },
    )
}

/** The way an arrow for [key] points, in degrees (0 right, clockwise, y down); OK has none and points right. */
internal fun directionDegrees(key: TrainingKey): Float = when (key) {
    TrainingKey.RIGHT, TrainingKey.OK -> 0f
    TrainingKey.DOWN -> 90f
    TrainingKey.LEFT -> 180f
    TrainingKey.UP -> 270f
}

/**
 * A chunky arrow [length] long centred on [center], pointing [degrees] (0 right, clockwise, y down): a head as wide as
 * it is long over a shaft 40% as thick. The training games' arrows (key caps, the reel's pointers, pickers).
 */
internal fun arrowPath(center: Offset, length: Float, degrees: Float): Path {
    val a = Math.toRadians(degrees.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return Path().apply {
        ArrowShape.forEachIndexed { i, (x, y) ->
            val px = center.x + length * (x * c - y * s)
            val py = center.y + length * (x * s + y * c)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

/** The arrow pointing right, in its own length: tip, head, shaft. */
private val ArrowShape = listOf(
    0.5f to 0f, 0f to -0.48f, 0f to -0.2f, -0.5f to -0.2f, -0.5f to 0.2f, 0f to 0.2f, 0f to 0.48f,
)

/**
 * [text] in [style] with the training games' title look: a dark [outline] and a soft [glow] (Blood red by default)
 * behind it. The HUD's title, the reel's, the results card's and the games' callouts.
 */
@Composable
internal fun GlowText(text: String, style: TextStyle, modifier: Modifier = Modifier, glow: Color = BloodGlow, outline: Dp = 2.dp) {
    val blur = with(LocalDensity.current) { style.fontSize.toPx() * 0.55f }
    // The glow is the text itself in the glow's colour, its shadow blurred wide; the outlined text covers its core.
    val halo = remember(style, glow, blur) {
        style.copy(color = glow.copy(alpha = 0.6f), shadow = Shadow(glow, Offset.Zero, blurRadius = blur))
    }
    Box(modifier) {
        BasicText(text, style = halo, maxLines = 1, softWrap = false)
        OutlinedText(text, style, outline = outline)
    }
}
