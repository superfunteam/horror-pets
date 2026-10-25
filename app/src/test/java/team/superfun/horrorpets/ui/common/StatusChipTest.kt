package team.superfun.horrorpets.ui.common

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.game.pantry.MealPerk
import team.superfun.horrorpets.game.pantry.MealStatus
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.BloodGlow

/** The meal's status as the player sees it: its chip's colour, the reaction's banner and its sound. */
class StatusChipTest {
    @Test
    fun eachStatusHasItsColour() {
        assertEquals(Amber, statusChipColor(MealStatus(MealPerk.BEEFED_UP)))
        assertEquals(Color(0xFFFF7AB8), statusChipColor(MealStatus(MealPerk.SUGAR_RUSH)))
        assertEquals(Color(0xFF8BE37A), statusChipColor(MealStatus(MealPerk.IRON_STOMACH)))
        assertEquals("a hangover reads in red", BloodGlow, statusChipColor(MealStatus(MealPerk.HANGOVER)))
        // A feast is gold whatever its flavor.
        for (perk in listOf(MealPerk.BEEFED_UP, MealPerk.SUGAR_RUSH, MealPerk.IRON_STOMACH)) {
            assertEquals("$perk feast", Amber, statusChipColor(MealStatus(perk, feast = true)))
        }
    }

    @Test
    fun theBannerSaysWhatItIsAndWhatItDoes() {
        assertEquals("BEEFED UP!" to "+15% DAMAGE", statusBanner(MealStatus(MealPerk.BEEFED_UP)))
        assertEquals("SUGAR RUSH!" to "METER STARTS HALF FULL", statusBanner(MealStatus(MealPerk.SUGAR_RUSH)))
        assertEquals("IRON STOMACH!" to "+10% CRIT CHANCE", statusBanner(MealStatus(MealPerk.IRON_STOMACH)))
        assertEquals("a hangover trails off, no bang", "HANGOVER..." to "MISSES MORE, HITS SOFTER", statusBanner(MealStatus(MealPerk.HANGOVER)))
        // A feast: its flavor's line, and the third potion on top.
        assertEquals(
            "FAVORITE FEAST!" to "+15% DAMAGE \u00b7 A THIRD POTION",
            statusBanner(MealStatus(MealPerk.BEEFED_UP, feast = true)),
        )
        assertEquals(
            "FAVORITE FEAST!" to "METER STARTS HALF FULL \u00b7 A THIRD POTION",
            statusBanner(MealStatus(MealPerk.SUGAR_RUSH, feast = true)),
        )
        assertEquals(
            "FAVORITE FEAST!" to "+10% CRIT CHANCE \u00b7 A THIRD POTION",
            statusBanner(MealStatus(MealPerk.IRON_STOMACH, feast = true)),
        )
    }

    @Test
    fun aHangoverSoundsSourAndEverythingElseChimes() {
        assertEquals("hangover", statusSound(MealStatus(MealPerk.HANGOVER)))
        for (perk in listOf(MealPerk.BEEFED_UP, MealPerk.SUGAR_RUSH, MealPerk.IRON_STOMACH)) {
            assertEquals("$perk", "perk_up", statusSound(MealStatus(perk)))
            assertEquals("$perk feast", "perk_up", statusSound(MealStatus(perk, feast = true)))
        }
    }
}
