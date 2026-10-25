package team.superfun.horrorpets.game.pantry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.Flavor
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PantryItem.*
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class MealStatusTest {
    private val ate = 1_760_000_000_000L

    /** [id] having eaten [item] at [at]. Jason loves savory, is fine with sweet and hates gross. */
    private fun after(item: PantryItem?, id: PetId = PetId.JASON, at: Long? = ate) =
        PetState(id, lastMeal = item?.id, lastMealAtMs = at)

    private fun statusAfter(item: PantryItem, id: PetId = PetId.JASON): MealStatus? = mealStatusOf(after(item, id), ate)

    @Test
    fun aSavoryDishBeefsUp() {
        // The killer who loves savory (Jason) and the one fine with it (Freddy) both get it; a snack and a meal alike.
        assertEquals(MealStatus(MealPerk.BEEFED_UP), statusAfter(STEAK, PetId.JASON))
        assertEquals(MealStatus(MealPerk.BEEFED_UP), statusAfter(STEAK_DINNER, PetId.JASON))
        assertEquals(MealStatus(MealPerk.BEEFED_UP), statusAfter(STEAK, PetId.FREDDY))
    }

    @Test
    fun aSweetDishIsASugarRush() {
        // Chucky loves sweet; Jason is fine with it.
        assertEquals(MealStatus(MealPerk.SUGAR_RUSH), statusAfter(MILKSHAKE, PetId.CHUCKY))
        assertEquals(MealStatus(MealPerk.SUGAR_RUSH), statusAfter(MILKSHAKE, PetId.JASON))
        assertEquals(MealStatus(MealPerk.SUGAR_RUSH), statusAfter(PUMPKIN_PIE, PetId.JASON))
    }

    @Test
    fun aGrossDishIsAnIronStomachUnlessHated() {
        // Freddy loves gross and Chucky is fine with it.
        assertEquals(MealStatus(MealPerk.IRON_STOMACH), statusAfter(WORM_SPAGHETTI, PetId.FREDDY))
        assertEquals(MealStatus(MealPerk.IRON_STOMACH), statusAfter(WORM_SPAGHETTI, PetId.CHUCKY))
        // Jason hates gross: the dish is not a status at all, not a hangover either.
        assertNull(statusAfter(WORM_SPAGHETTI, PetId.JASON))
        // And so a killer gets nothing from a dish it hates, whatever the flavor.
        assertNull(statusAfter(STEAK, PetId.CHUCKY))
        assertNull(statusAfter(MILKSHAKE, PetId.FREDDY))
    }

    @Test
    fun theFavoriteIsAFeastOfItsFlavor() {
        // Each killer's favorite is a feast of its own flavor, whichever it is.
        val feasts = mapOf(
            PetId.JASON to MealPerk.BEEFED_UP, // Camp Crystal Fish Fry, savory
            PetId.CHUCKY to MealPerk.SUGAR_RUSH, // Birthday Cake, sweet
            PetId.FREDDY to MealPerk.IRON_STOMACH, // Spaghetti & Eyeballs, gross
        )
        for ((id, perk) in feasts) {
            val favorite = tasteOf(id).favorite
            val status = statusAfter(favorite, id)
            assertEquals("$id", MealStatus(perk, feast = true), status)
            assertEquals("Favorite feast", status!!.title)
        }
        // A feast is only the favorite itself: the same flavor of any other dish is the plain perk.
        assertEquals(MealStatus(MealPerk.BEEFED_UP), statusAfter(STEAK_DINNER, PetId.JASON))
        assertEquals("Beefed up", statusAfter(STEAK_DINNER, PetId.JASON)!!.title)
    }

    @Test
    fun anyBotchIsAHangover() {
        assertEquals(MealStatus(MealPerk.HANGOVER), statusAfter(PILE_OF_SLOP))
        assertEquals(MealStatus(MealPerk.HANGOVER), statusAfter(KITCHEN_FIRE))
        assertEquals(MealStatus(MealPerk.HANGOVER), statusAfter(CURSED_CAKE))
        for (botch in PantryItem.entries.filter { it.kind == ItemKind.BOTCHED }) {
            for (id in PetId.entries) {
                assertEquals("$botch for $id", MealStatus(MealPerk.HANGOVER), statusAfter(botch, id))
            }
        }
    }

    @Test
    fun rawFoodAndWaysToCookLeaveNoStatus() {
        assertNull(statusAfter(RAW_STEAK))
        assertNull(statusAfter(GRILL))
        for (raw in PantryItem.entries.filter { it.raw }) {
            for (id in PetId.entries) assertNull("$raw for $id", statusAfter(raw, id))
        }
    }

    @Test
    fun aStatusLastsADayNotLonger() {
        val day = Tuning.MEAL_PERK_HOURS * 3_600_000L
        assertEquals(24, Tuning.MEAL_PERK_HOURS)
        val pet = after(STEAK)
        assertEquals(MealStatus(MealPerk.BEEFED_UP), mealStatusOf(pet, ate))
        assertEquals(MealStatus(MealPerk.BEEFED_UP), mealStatusOf(pet, ate + day - 1))
        assertNull(mealStatusOf(pet, ate + day))
        assertNull(mealStatusOf(pet, ate + day + 1))
        // A hangover wears off the same way.
        assertNotNull(mealStatusOf(after(PILE_OF_SLOP), ate + day - 1))
        assertNull(mealStatusOf(after(PILE_OF_SLOP), ate + day))
    }

    @Test
    fun noMealNoStatus() {
        assertNull(mealStatusOf(PetState(PetId.JASON), ate))
        // Half a record is no record: a meal with no time, or a time with no meal.
        assertNull(mealStatusOf(after(STEAK, at = null), ate))
        assertNull(mealStatusOf(after(null), ate))
        // An id this build does not know (a later build's dish) is no status either.
        assertNull(mealStatusOf(PetState(PetId.JASON, lastMeal = "ghost_pepper_pie", lastMealAtMs = ate), ate))
    }

    @Test
    fun everyKillerAndEveryDishFollowsTheTasteTable() {
        for (id in PetId.entries) {
            val taste = tasteOf(id)
            for (dish in PantryItem.entries.filter { it.kind == ItemKind.SNACK || it.kind == ItemKind.MEAL }) {
                val what = "$id $dish"
                val flavor = dish.flavor!!
                val expectedPerk = when (flavor) {
                    Flavor.SAVORY -> MealPerk.BEEFED_UP
                    Flavor.SWEET -> MealPerk.SUGAR_RUSH
                    Flavor.GROSS -> MealPerk.IRON_STOMACH
                }
                val expected = when {
                    dish == taste.favorite -> MealStatus(expectedPerk, feast = true)
                    flavor == taste.hates -> null
                    else -> MealStatus(expectedPerk)
                }
                assertEquals(what, expected, statusAfter(dish, id))
            }
        }
    }

    @Test
    fun theTitlesAndMeaningsAreTheCopy() {
        assertEquals("Beefed up", MealPerk.BEEFED_UP.title)
        assertEquals("+15% damage", MealPerk.BEEFED_UP.meaning)
        assertEquals("Sugar rush", MealPerk.SUGAR_RUSH.title)
        assertEquals("Meter starts half full", MealPerk.SUGAR_RUSH.meaning)
        assertEquals("Iron stomach", MealPerk.IRON_STOMACH.title)
        assertEquals("+10% crit chance", MealPerk.IRON_STOMACH.meaning)
        assertEquals("Hangover", MealPerk.HANGOVER.title)
        assertEquals("Misses more, hits softer", MealPerk.HANGOVER.meaning)
        assertEquals("Hangover", MealStatus(MealPerk.HANGOVER).title)
        assertEquals("Favorite feast", MealStatus(MealPerk.SUGAR_RUSH, feast = true).title)
        // What the killer says of each in the pantry's bubble; the favorite says its own whatever its flavor.
        assertEquals("Now I'm strong!", MealStatus(MealPerk.BEEFED_UP).line)
        assertEquals("I could run through a wall!", MealStatus(MealPerk.SUGAR_RUSH).line)
        assertEquals("Nothing can hurt me.", MealStatus(MealPerk.IRON_STOMACH).line)
        assertEquals("Ugh... my head.", MealStatus(MealPerk.HANGOVER).line)
        for (perk in listOf(MealPerk.BEEFED_UP, MealPerk.SUGAR_RUSH, MealPerk.IRON_STOMACH)) {
            assertEquals("$perk feast", "My favorite. I'm unstoppable.", MealStatus(perk, feast = true).line)
        }
    }
}
