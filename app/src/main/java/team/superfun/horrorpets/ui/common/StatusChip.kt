package team.superfun.horrorpets.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import team.superfun.horrorpets.game.StatusTag
import team.superfun.horrorpets.game.pantry.MealPerk
import team.superfun.horrorpets.game.pantry.MealStatus
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi

/**
 * A pet's headline status as a small solid tag: CONTENT grey, BORED/HUNGRY Amber, STARVING/GRUMPY Blood.
 * The pet switcher's; the stat card outlines its own in a [TagBox].
 */
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

/** A sugar rush's pink. */
private val SugarPink = Color(0xFFFF7AB8)

/** An iron stomach's green. */
private val IronGreen = Color(0xFF8BE37A)

/** A meal status's colour, the same everywhere it shows: gold for a feast or beefed up, pink, green, and a hangover's red. */
fun statusChipColor(status: MealStatus): Color = if (status.feast) {
    Amber
} else {
    when (status.perk) {
        MealPerk.BEEFED_UP -> Amber
        MealPerk.SUGAR_RUSH -> SugarPink
        MealPerk.IRON_STOMACH -> IronGreen
        MealPerk.HANGOVER -> BloodGlow
    }
}

/**
 * What the pantry's reaction calls out for a meal's [status]: its title ("BEEFED UP!", or "HANGOVER..." trailing off)
 * and, under it, what it does in the next fights ("+15% DAMAGE"; a feast's flavor line and "A THIRD POTION").
 */
fun statusBanner(status: MealStatus): Pair<String, String> {
    val title = status.title.uppercase() + if (status.perk == MealPerk.HANGOVER) "..." else "!"
    val meaning = status.perk.meaning.uppercase()
    return title to if (status.feast) "$meaning \u00b7 A THIRD POTION" else meaning
}

/** The sound a meal's [status] lands with: a hangover's sour wobble, or the perk's rising chime. */
fun statusSound(status: MealStatus): String = if (status.perk == MealPerk.HANGOVER) "hangover" else "perk_up"

/** A meal's [status] in a word, for a HUD side too narrow for its whole title: its perk's first word, or "FEAST". */
fun statusShortTitle(status: MealStatus): String =
    if (status.feast) "FEAST" else status.perk.title.substringBefore(' ').uppercase()

/**
 * A meal's [status] as an outlined tag in its colour ([statusChipColor]), as Home's stat card, the VS card and the HUD
 * show it: its title, or in a word ([statusShortTitle]) when [short].
 */
@Composable
fun StatusChip(status: MealStatus, modifier: Modifier = Modifier, short: Boolean = false) {
    TagBox(if (short) statusShortTitle(status) else status.title.uppercase(), statusChipColor(status), modifier)
}

/**
 * [text] in a small outlined tag with clipped corners, in [color] on a faint wash of it over a dark plate, so it reads
 * on the bright VS card and over a busy arena as well as on the stat card.
 */
@Composable
fun TagBox(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
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
                drawPath(path, Ink.copy(alpha = 0.8f))
                drawPath(path, color.copy(alpha = 0.14f))
                drawPath(path, color, style = Stroke(1.5.dp.toPx()))
            }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        BasicText(text, style = displayStyle(12.sp, color = color, tracking = 0.12f, lineHeight = 14.sp), maxLines = 1)
    }
}
