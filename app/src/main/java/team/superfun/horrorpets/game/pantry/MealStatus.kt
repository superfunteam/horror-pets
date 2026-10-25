package team.superfun.horrorpets.game.pantry

import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.Flavor
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.pantryItemById

/** What a meal does to the next fights: the [title] on its chip and, in a line, what it does ([meaning]). */
enum class MealPerk(val title: String, val meaning: String) {
    BEEFED_UP("Beefed up", "+15% damage"),
    SUGAR_RUSH("Sugar rush", "Meter starts half full"),
    IRON_STOMACH("Iron stomach", "+10% crit chance"),
    HANGOVER("Hangover", "Misses more, hits softer"),
}

/** [perk], and whether it was the killer's favorite dish ([feast]: a third potion on top). */
data class MealStatus(val perk: MealPerk, val feast: Boolean = false) {
    /** What the chip says: the perk's name, or "Favorite feast" for the killer's favorite dish. */
    val title: String get() = if (feast) "Favorite feast" else perk.title
}

/**
 * The status [pet]'s last meal gives at [nowMs], or null when it gives none:
 * - no meal on record, a meal older than [Tuning.MEAL_PERK_HOURS] (a status lasts a day, and not a millisecond more),
 *   or an item this build does not know;
 * - a raw food or a way to cook eaten raw, which is no meal at all;
 * - a dish in the flavor the killer hates, which wipes out any status it had before (it is the last meal now).
 *
 * Otherwise a botch is a hangover, and a snack or meal is a perk of its flavor (savory beefs up, sweet is a sugar
 * rush, gross is an iron stomach), a feast when it was the killer's favorite dish.
 */
fun mealStatusOf(pet: PetState, nowMs: Long): MealStatus? {
    val item = pet.lastMeal?.let(::pantryItemById) ?: return null
    val ateAtMs = pet.lastMealAtMs ?: return null
    if (nowMs - ateAtMs >= Tuning.MEAL_PERK_HOURS * Tuning.HOUR_MS) return null
    return when (item.kind) {
        ItemKind.METHOD, ItemKind.FOOD -> null
        ItemKind.BOTCHED -> MealStatus(MealPerk.HANGOVER)
        ItemKind.SNACK, ItemKind.MEAL -> {
            val flavor = item.flavor ?: return null
            val taste = tasteOf(pet.id)
            when {
                item == taste.favorite -> MealStatus(perkOf(flavor), feast = true)
                flavor == taste.hates -> null
                else -> MealStatus(perkOf(flavor))
            }
        }
    }
}

private fun perkOf(flavor: Flavor): MealPerk = when (flavor) {
    Flavor.SAVORY -> MealPerk.BEEFED_UP
    Flavor.SWEET -> MealPerk.SUGAR_RUSH
    Flavor.GROSS -> MealPerk.IRON_STOMACH
}
