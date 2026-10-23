package team.superfun.horrorpets.model

import team.superfun.horrorpets.model.Flavor.GROSS
import team.superfun.horrorpets.model.Flavor.SAVORY
import team.superfun.horrorpets.model.Flavor.SWEET
import team.superfun.horrorpets.model.ItemKind.BOTCHED
import team.superfun.horrorpets.model.ItemKind.FOOD
import team.superfun.horrorpets.model.ItemKind.MEAL
import team.superfun.horrorpets.model.ItemKind.METHOD
import team.superfun.horrorpets.model.ItemKind.SNACK

/**
 * What a pantry item is. A [METHOD] (a way to cook) and a [FOOD] are the raw items: ingredients that start on the
 * shelf. A [SNACK] and a [MEAL] are cooked dishes, and a [BOTCHED] dish is a failed one (a dead end: nothing cooks
 * with it). The order is the cookbook's.
 */
enum class ItemKind { METHOD, FOOD, SNACK, MEAL, BOTCHED }

/** What a dish tastes like. Every killer loves one flavor, is fine with another and hates the third. */
enum class Flavor { SAVORY, SWEET, GROSS }

/**
 * Everything that can sit in the pantry: the five ways to cook, the foods, the snacks and meals they make, and the
 * botches, in the cookbook's order (see `docs/superpowers/specs/2026-10-23-pantry-cooking-design.md`). [title] is what
 * the player reads. A dish's [flavor] is how a killer takes it; a way to cook and a botch have none.
 *
 * The [id] is the item's save key and its art's name (`sprites/pantry/<id>.webp`, made by
 * `tools/generate_pantry_art.py`), so never rename an entry once it ships.
 */
enum class PantryItem(val title: String, val kind: ItemKind, val flavor: Flavor?) {
    // Ways to cook (5)
    GRILL("Grill", METHOD, null),
    CAMPFIRE("Campfire", METHOD, null),
    POT("Pot", METHOD, null),
    CAULDRON("Cauldron", METHOD, null),
    SUN("Sun", METHOD, null),

    // Foods (21)
    RAW_STEAK("Raw Steak", FOOD, SAVORY),
    EGG("Egg", FOOD, SAVORY),
    FLOUR("Flour", FOOD, SAVORY),
    CHEESE("Cheese", FOOD, SAVORY),
    TOMATO("Tomato", FOOD, SAVORY),
    POTATO("Potato", FOOD, SAVORY),
    SARDINES("Sardines", FOOD, SAVORY),
    CORN("Corn", FOOD, SAVORY),
    SUGAR("Sugar", FOOD, SWEET),
    MILK("Milk", FOOD, SWEET),
    ORANGE("Orange", FOOD, SWEET),
    PUMPKIN("Pumpkin", FOOD, SWEET),
    CHOCOLATE("Chocolate", FOOD, SWEET),
    MARSHMALLOW("Marshmallow", FOOD, SWEET),
    SEVERED_FOOT("Severed Foot", FOOD, GROSS),
    EYEBALL("Eyeball", FOOD, GROSS),
    BRAIN("Brain", FOOD, GROSS),
    FINGER("Finger", FOOD, GROSS),
    WORMS("Worms", FOOD, GROSS),
    BAT_WING("Bat Wing", FOOD, GROSS),
    BLOOD("Blood", FOOD, GROSS),

    // Snacks (41)
    STEAK("Steak", SNACK, SAVORY),
    FRIED_EGG("Fried Egg", SNACK, SAVORY),
    MELTED_CHEESE("Melted Cheese", SNACK, SAVORY),
    GRILLED_SARDINES("Grilled Sardines", SNACK, SAVORY),
    GRILLED_FOOT("Grilled Foot", SNACK, GROSS),
    BUFFALO_BAT_WINGS("Buffalo Bat Wings", SNACK, GROSS),
    TOASTED_MARSHMALLOW("Toasted Marshmallow", SNACK, SWEET),
    POPCORN("Popcorn", SNACK, SAVORY),
    BAKED_POTATO("Baked Potato", SNACK, SAVORY),
    CAMPFIRE_BREAD("Campfire Bread", SNACK, SAVORY),
    CARAMEL("Caramel", SNACK, SWEET),
    ROASTED_PUMPKIN("Roasted Pumpkin", SNACK, SWEET),
    FINGER_DOG("Finger Dog", SNACK, GROSS),
    TOMATO_SOUP("Tomato Soup", SNACK, SAVORY),
    BOILED_EGG("Boiled Egg", SNACK, SAVORY),
    HOT_COCOA("Hot Cocoa", SNACK, SWEET),
    WORM_SPAGHETTI("Worm Spaghetti", SNACK, GROSS),
    POACHED_EYEBALL("Poached Eyeball", SNACK, GROSS),
    BLOOD_PUDDING("Blood Pudding", SNACK, GROSS),
    BUBBLING_BRAIN_BROTH("Bubbling Brain Broth", SNACK, GROSS),
    BAT_BREW("Bat Brew", SNACK, GROSS),
    EYE_OF_NEWT_POTION("Eye of Newt Potion", SNACK, GROSS),
    PUMPKIN_SPICE_POTION("Pumpkin Spice Potion", SNACK, SWEET),
    JERKY("Jerky", SNACK, SAVORY),
    SUN_DRIED_TOMATOES("Sun-Dried Tomatoes", SNACK, SAVORY),
    DRIED_ORANGE_SLICES("Dried Orange Slices", SNACK, SWEET),
    FOOT_JERKY("Foot Jerky", SNACK, GROSS),
    ROTTEN_EGG("Rotten Egg", SNACK, GROSS),
    BATTER("Batter", SNACK, SWEET),
    MILKSHAKE("Milkshake", SNACK, SWEET),
    CHOCOLATE_ORANGE("Chocolate Orange", SNACK, SWEET),
    GUMMY_WORMS("Gummy Worms", SNACK, GROSS),
    DIRT_CUP("Dirt Cup", SNACK, GROSS),
    JELLO_BRAIN("Jello Brain", SNACK, GROSS),
    LADYFINGERS("Ladyfingers", SNACK, GROSS),
    TOE_JAM("Toe Jam", SNACK, GROSS),
    BLOOD_ORANGE_JUICE("Blood Orange Juice", SNACK, GROSS),
    NOT_STRAWBERRY_MILK("Not-Strawberry Milk", SNACK, GROSS),
    EYEBALL_CAVIAR("Eyeball Caviar", SNACK, GROSS),
    DEVILED_EGGS("Deviled Eggs", SNACK, GROSS),
    EYE_SCREAM("Eye Scream", SNACK, GROSS),

    // Meals (23)
    STEAK_DINNER("Steak Dinner", MEAL, SAVORY),
    STEAK_AND_EGGS("Steak & Eggs", MEAL, SAVORY),
    GRILLED_CHEESE("Grilled Cheese", MEAL, SAVORY),
    PIZZA("Pizza", MEAL, SAVORY),
    COWBOY_TRAIL_MIX("Cowboy Trail Mix", MEAL, SAVORY),
    CAMP_CRYSTAL_FISH_FRY("Camp Crystal Fish Fry", MEAL, SAVORY),
    MOVIE_NIGHT_COMBO("Movie Night Combo", MEAL, SAVORY),
    CARAMEL_CORN("Caramel Corn", MEAL, SWEET),
    BIRTHDAY_CAKE("Birthday Cake", MEAL, SWEET),
    PUMPKIN_PIE("Pumpkin Pie", MEAL, SWEET),
    PANCAKE_STACK("Pancake Stack", MEAL, SWEET),
    CAMPFIRE_SMORES("Campfire S'mores", MEAL, SWEET),
    COCOA_AND_MARSHMALLOWS("Cocoa & Marshmallows", MEAL, SWEET),
    TEXAS_BBQ_PLATTER("Texas BBQ Platter", MEAL, GROSS),
    FOOT_AND_POTATOES("Foot & Potatoes", MEAL, GROSS),
    SPAGHETTI_AND_EYEBALLS("Spaghetti & Eyeballs", MEAL, GROSS),
    CAVIAR_CANAPES("Caviar Canapés", MEAL, GROSS),
    BRAIN_TRIFLE("Brain Trifle", MEAL, GROSS),
    FINGER_DOG_IN_A_BUN("Finger Dog in a Bun", MEAL, GROSS),
    VAMPIRE_BREAKFAST("Vampire Breakfast", MEAL, GROSS),
    EYE_SCREAM_SUNDAE("Eye Scream Sundae", MEAL, GROSS),
    GRAVEYARD_PUDDING("Graveyard Pudding", MEAL, GROSS),
    WITCHS_SUPPER("Witch's Supper", MEAL, GROSS),

    // Botched (12): the three every pair can fall into, then the named disasters for particular wrong pairs
    PILE_OF_SLOP("Pile of Slop", BOTCHED, null),
    BURNT_TO_A_CRISP("Burnt to a Crisp", BOTCHED, null),
    KITCHEN_FIRE("Kitchen Fire", BOTCHED, null),
    SARDINE_SMOOTHIE("Sardine Smoothie", BOTCHED, null),
    FISH_FUDGE("Fish Fudge", BOTCHED, null),
    FISHMALLOW("Fishmallow", BOTCHED, null),
    STINKY_FISH("Stinky Fish", BOTCHED, null),
    CURDLED_MESS("Curdled Mess", BOTCHED, null),
    SPOILED_MILK("Spoiled Milk", BOTCHED, null),
    MELTED_MESS("Melted Mess", BOTCHED, null),
    CURSED_CAKE("Cursed Cake", BOTCHED, null),
    SOGGY_POPCORN("Soggy Popcorn", BOTCHED, null),
    ;

    /** The item's save key and art name: `raw_steak`, `eye_of_newt_potion`. */
    val id: String = name.lowercase()

    /** Whether this is an ingredient (a food or a way to cook), as opposed to something cooked. */
    val raw: Boolean get() = kind == METHOD || kind == FOOD
}

private val itemsById: Map<String, PantryItem> = PantryItem.entries.associateBy { it.id }

/** The item whose [PantryItem.id] is [id], or null for an id this build does not know (a later build's item). */
fun pantryItemById(id: String): PantryItem? = itemsById[id]
