package team.superfun.horrorpets.game.pantry

import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.Flavor
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PantryItem.*
import team.superfun.horrorpets.model.PetId

/** What cooking two pantry items makes: a [Cooked] dish (a botch included), or a [Refused] cook that uses nothing. */
sealed interface CookResult {
    data class Cooked(val item: PantryItem) : CookResult

    data object Refused : CookResult
}

/**
 * Every recipe, keyed by the two items it takes (order on the board never matters, so a set): the spec's snacks and
 * meals, then the botches it names for particular wrong pairs. A pair not listed here falls to the general rules in
 * [cook].
 */
val RECIPES: Map<Set<PantryItem>, PantryItem> = mapOf(
    // Snacks: a food and a way to cook
    setOf(RAW_STEAK, GRILL) to STEAK,
    setOf(EGG, GRILL) to FRIED_EGG,
    setOf(CHEESE, GRILL) to MELTED_CHEESE,
    setOf(SARDINES, GRILL) to GRILLED_SARDINES,
    setOf(SEVERED_FOOT, GRILL) to GRILLED_FOOT,
    setOf(BAT_WING, GRILL) to BUFFALO_BAT_WINGS,
    setOf(MARSHMALLOW, CAMPFIRE) to TOASTED_MARSHMALLOW,
    setOf(CORN, CAMPFIRE) to POPCORN,
    setOf(POTATO, CAMPFIRE) to BAKED_POTATO,
    setOf(FLOUR, CAMPFIRE) to CAMPFIRE_BREAD,
    setOf(SUGAR, CAMPFIRE) to CARAMEL,
    setOf(PUMPKIN, CAMPFIRE) to ROASTED_PUMPKIN,
    setOf(FINGER, CAMPFIRE) to FINGER_DOG,
    setOf(TOMATO, POT) to TOMATO_SOUP,
    setOf(EGG, POT) to BOILED_EGG,
    setOf(CHOCOLATE, POT) to HOT_COCOA,
    setOf(WORMS, POT) to WORM_SPAGHETTI,
    setOf(EYEBALL, POT) to POACHED_EYEBALL,
    setOf(BLOOD, POT) to BLOOD_PUDDING,
    setOf(BRAIN, CAULDRON) to BUBBLING_BRAIN_BROTH,
    setOf(BAT_WING, CAULDRON) to BAT_BREW,
    setOf(EYEBALL, CAULDRON) to EYE_OF_NEWT_POTION,
    setOf(PUMPKIN, CAULDRON) to PUMPKIN_SPICE_POTION,
    setOf(RAW_STEAK, SUN) to JERKY,
    setOf(TOMATO, SUN) to SUN_DRIED_TOMATOES,
    setOf(ORANGE, SUN) to DRIED_ORANGE_SLICES,
    setOf(SEVERED_FOOT, SUN) to FOOT_JERKY,
    setOf(EGG, SUN) to ROTTEN_EGG,
    // Snacks: two foods, mixed
    setOf(FLOUR, EGG) to BATTER,
    setOf(MILK, SUGAR) to MILKSHAKE,
    setOf(CHOCOLATE, ORANGE) to CHOCOLATE_ORANGE,
    setOf(WORMS, SUGAR) to GUMMY_WORMS,
    setOf(WORMS, CHOCOLATE) to DIRT_CUP,
    setOf(BRAIN, SUGAR) to JELLO_BRAIN,
    setOf(FINGER, SUGAR) to LADYFINGERS,
    setOf(SEVERED_FOOT, SUGAR) to TOE_JAM,
    setOf(BLOOD, ORANGE) to BLOOD_ORANGE_JUICE,
    setOf(BLOOD, MILK) to NOT_STRAWBERRY_MILK,
    setOf(EYEBALL, SARDINES) to EYEBALL_CAVIAR,
    setOf(EYEBALL, EGG) to DEVILED_EGGS,
    setOf(EYEBALL, MILK) to EYE_SCREAM,
    // Meals: two snacks, or a snack with a way to cook or a food
    setOf(STEAK, BAKED_POTATO) to STEAK_DINNER,
    setOf(STEAK, FRIED_EGG) to STEAK_AND_EGGS,
    setOf(CAMPFIRE_BREAD, MELTED_CHEESE) to GRILLED_CHEESE,
    setOf(CAMPFIRE_BREAD, TOMATO_SOUP) to PIZZA,
    setOf(JERKY, DRIED_ORANGE_SLICES) to COWBOY_TRAIL_MIX,
    setOf(GRILLED_SARDINES, BAKED_POTATO) to CAMP_CRYSTAL_FISH_FRY,
    setOf(POPCORN, MILKSHAKE) to MOVIE_NIGHT_COMBO,
    setOf(POPCORN, CARAMEL) to CARAMEL_CORN,
    setOf(BATTER, CARAMEL) to BIRTHDAY_CAKE,
    setOf(ROASTED_PUMPKIN, BATTER) to PUMPKIN_PIE,
    setOf(BATTER, GRILL) to PANCAKE_STACK,
    setOf(TOASTED_MARSHMALLOW, CHOCOLATE) to CAMPFIRE_SMORES,
    setOf(HOT_COCOA, TOASTED_MARSHMALLOW) to COCOA_AND_MARSHMALLOWS,
    setOf(FOOT_JERKY, BUFFALO_BAT_WINGS) to TEXAS_BBQ_PLATTER,
    setOf(GRILLED_FOOT, BAKED_POTATO) to FOOT_AND_POTATOES,
    setOf(WORM_SPAGHETTI, DEVILED_EGGS) to SPAGHETTI_AND_EYEBALLS,
    setOf(EYEBALL_CAVIAR, CAMPFIRE_BREAD) to CAVIAR_CANAPES,
    setOf(JELLO_BRAIN, LADYFINGERS) to BRAIN_TRIFLE,
    setOf(FINGER_DOG, CAMPFIRE_BREAD) to FINGER_DOG_IN_A_BUN,
    setOf(BLOOD_ORANGE_JUICE, FRIED_EGG) to VAMPIRE_BREAKFAST,
    setOf(EYE_SCREAM, CARAMEL) to EYE_SCREAM_SUNDAE,
    setOf(DIRT_CUP, GUMMY_WORMS) to GRAVEYARD_PUDDING,
    setOf(BUBBLING_BRAIN_BROTH, BAT_BREW) to WITCHS_SUPPER,
    // Named botches: particular wrong pairs
    setOf(SARDINES, ORANGE) to SARDINE_SMOOTHIE,
    setOf(SARDINES, CHOCOLATE) to FISH_FUDGE,
    setOf(SARDINES, MARSHMALLOW) to FISHMALLOW,
    setOf(SARDINES, SUN) to STINKY_FISH,
    setOf(MILK, ORANGE) to CURDLED_MESS,
    setOf(MILK, SUN) to SPOILED_MILK,
    setOf(CHOCOLATE, SUN) to MELTED_MESS,
    setOf(BATTER, CAULDRON) to CURSED_CAKE,
    setOf(POPCORN, MILK) to SOGGY_POPCORN,
)

/**
 * Cooks [a] and [b], in either order (the board has no left or right). In this order of rules:
 * 1. Botched dishes only go with each other: two of them (any two, the same one twice included) make one Pile of
 *    Slop, so a shelf of slop can be squashed down, two places into one. A botch with anything else is
 *    [CookResult.Refused].
 * 2. The pair's recipe, or its named botch, in [RECIPES]. This is why Batter + Grill is Pancake Stack and Batter +
 *    Cauldron is Cursed Cake, not burnt.
 * 3. Two ways to cook make a Kitchen Fire.
 * 4. A way to cook and a finished dish (a snack or a meal) make Burnt to a Crisp.
 * 5. Anything else, two of the same item included, is a Pile of Slop.
 */
fun cook(a: PantryItem, b: PantryItem): CookResult {
    val botches = listOf(a, b).count { it.kind == ItemKind.BOTCHED }
    if (botches == 2) return CookResult.Cooked(PILE_OF_SLOP)
    if (botches == 1) return CookResult.Refused
    RECIPES[setOf(a, b)]?.let { return CookResult.Cooked(it) }
    val methods = listOf(a, b).count { it.kind == ItemKind.METHOD }
    val dishes = listOf(a, b).count { it.kind == ItemKind.SNACK || it.kind == ItemKind.MEAL }
    return CookResult.Cooked(
        when {
            methods == 2 -> KITCHEN_FIRE
            methods == 1 && dishes == 1 -> BURNT_TO_A_CRISP
            else -> PILE_OF_SLOP
        },
    )
}

/**
 * The recipes that cook two raw items into a real dish (a snack, never a botch): a way to cook and a food ("raw steak
 * on the grill"), or two foods mixed. A named botch for two raw items (Sardines + Orange) is not one. In the
 * cookbook's order; each is the two items it takes, always two different ones.
 */
val RAW_RECIPES: List<Set<PantryItem>> =
    RECIPES.filter { (inputs, result) -> inputs.all { it.raw } && result.kind != ItemKind.BOTCHED }.keys.toList()

/**
 * Up to [most] pairs of raw items on [shelf] that each cook into a real dish (see [RAW_RECIPES]), no shelf item in
 * two of them: two copies of an item are two items, so Egg + Grill twice needs two eggs and two grills. As many pairs
 * as the shelf can make at once, up to [most], found by trying every way of pairing (a shelf holds a few dozen items
 * and the cookbook has 41 such recipes, so it is cheap). Each pair is its recipe's inputs; which pairs come back, when
 * there is a choice, is the first way in the cookbook's order. Cooked dishes and botches are never in a pair.
 */
fun cookablePairs(shelf: List<PantryItem>, most: Int = Tuning.PANTRY_PAIRS): List<Set<PantryItem>> {
    val left = shelf.filter { it.raw }.groupingBy { it }.eachCount().toMutableMap()
    // The most pairs (up to [want]) from the recipes at [from] on, with what is [left]; a recipe may come up again,
    // on two more copies of its items.
    fun search(from: Int, want: Int): List<Set<PantryItem>> {
        var best = emptyList<Set<PantryItem>>()
        if (want <= 0) return best
        for (i in from until RAW_RECIPES.size) {
            val pair = RAW_RECIPES[i]
            if (pair.any { (left[it] ?: 0) == 0 }) continue
            pair.forEach { left[it] = left.getValue(it) - 1 }
            val rest = search(i, want - 1)
            pair.forEach { left[it] = left.getValue(it) + 1 }
            if (rest.size + 1 > best.size) best = listOf(pair) + rest
            if (best.size == want) break
        }
        return best
    }
    return search(0, most)
}

/**
 * What a killer likes to eat: the flavor it [loves], the one it is [fine] with, the one it [hates], and its
 * [favorite] dish, a meal that takes exactly four raw ingredients to make. The player is never shown any of it
 * before feeding.
 */
data class Taste(val loves: Flavor, val fine: Flavor, val hates: Flavor, val favorite: PantryItem)

/** [pet]'s taste, from the spec's table. */
fun tasteOf(pet: PetId): Taste = when (pet) {
    PetId.JASON -> Taste(Flavor.SAVORY, Flavor.SWEET, Flavor.GROSS, CAMP_CRYSTAL_FISH_FRY)
    PetId.FREDDY -> Taste(Flavor.GROSS, Flavor.SAVORY, Flavor.SWEET, SPAGHETTI_AND_EYEBALLS)
    PetId.PINHEAD -> Taste(Flavor.GROSS, Flavor.SWEET, Flavor.SAVORY, CAVIAR_CANAPES)
    PetId.GHOSTFACE -> Taste(Flavor.SAVORY, Flavor.SWEET, Flavor.GROSS, MOVIE_NIGHT_COMBO)
    PetId.CHUCKY -> Taste(Flavor.SWEET, Flavor.GROSS, Flavor.SAVORY, BIRTHDAY_CAKE)
    PetId.MICHAEL -> Taste(Flavor.SWEET, Flavor.SAVORY, Flavor.GROSS, PUMPKIN_PIE)
    PetId.LEATHERFACE -> Taste(Flavor.GROSS, Flavor.SAVORY, Flavor.SWEET, TEXAS_BBQ_PLATTER)
    PetId.JAWS -> Taste(Flavor.GROSS, Flavor.SAVORY, Flavor.SWEET, FOOT_AND_POTATOES)
}
