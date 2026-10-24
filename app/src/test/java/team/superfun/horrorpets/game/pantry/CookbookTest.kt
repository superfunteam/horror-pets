package team.superfun.horrorpets.game.pantry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.Flavor
import team.superfun.horrorpets.model.Flavor.GROSS
import team.superfun.horrorpets.model.Flavor.SAVORY
import team.superfun.horrorpets.model.Flavor.SWEET
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PantryItem.*
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.pantryItemById

class CookbookTest {
    /** The spec's recipes as written there: a snack or meal, then the two things it is made from. */
    private class Row(val a: PantryItem, val b: PantryItem, val out: PantryItem)

    private fun r(a: PantryItem, b: PantryItem, out: PantryItem) = Row(a, b, out)

    private val snacks = listOf(
        r(RAW_STEAK, GRILL, STEAK), r(EGG, GRILL, FRIED_EGG), r(CHEESE, GRILL, MELTED_CHEESE),
        r(SARDINES, GRILL, GRILLED_SARDINES), r(SEVERED_FOOT, GRILL, GRILLED_FOOT),
        r(BAT_WING, GRILL, BUFFALO_BAT_WINGS),
        r(MARSHMALLOW, CAMPFIRE, TOASTED_MARSHMALLOW), r(CORN, CAMPFIRE, POPCORN), r(POTATO, CAMPFIRE, BAKED_POTATO),
        r(FLOUR, CAMPFIRE, CAMPFIRE_BREAD), r(SUGAR, CAMPFIRE, CARAMEL), r(PUMPKIN, CAMPFIRE, ROASTED_PUMPKIN),
        r(FINGER, CAMPFIRE, FINGER_DOG),
        r(TOMATO, POT, TOMATO_SOUP), r(EGG, POT, BOILED_EGG), r(CHOCOLATE, POT, HOT_COCOA),
        r(WORMS, POT, WORM_SPAGHETTI), r(EYEBALL, POT, POACHED_EYEBALL), r(BLOOD, POT, BLOOD_PUDDING),
        r(BRAIN, CAULDRON, BUBBLING_BRAIN_BROTH), r(BAT_WING, CAULDRON, BAT_BREW),
        r(EYEBALL, CAULDRON, EYE_OF_NEWT_POTION), r(PUMPKIN, CAULDRON, PUMPKIN_SPICE_POTION),
        r(RAW_STEAK, SUN, JERKY), r(TOMATO, SUN, SUN_DRIED_TOMATOES), r(ORANGE, SUN, DRIED_ORANGE_SLICES),
        r(SEVERED_FOOT, SUN, FOOT_JERKY), r(EGG, SUN, ROTTEN_EGG),
        r(FLOUR, EGG, BATTER), r(MILK, SUGAR, MILKSHAKE), r(CHOCOLATE, ORANGE, CHOCOLATE_ORANGE),
        r(WORMS, SUGAR, GUMMY_WORMS), r(WORMS, CHOCOLATE, DIRT_CUP), r(BRAIN, SUGAR, JELLO_BRAIN),
        r(FINGER, SUGAR, LADYFINGERS), r(SEVERED_FOOT, SUGAR, TOE_JAM), r(BLOOD, ORANGE, BLOOD_ORANGE_JUICE),
        r(BLOOD, MILK, NOT_STRAWBERRY_MILK), r(EYEBALL, SARDINES, EYEBALL_CAVIAR), r(EYEBALL, EGG, DEVILED_EGGS),
        r(EYEBALL, MILK, EYE_SCREAM),
    )

    private val meals = listOf(
        r(STEAK, BAKED_POTATO, STEAK_DINNER), r(STEAK, FRIED_EGG, STEAK_AND_EGGS),
        r(CAMPFIRE_BREAD, MELTED_CHEESE, GRILLED_CHEESE), r(CAMPFIRE_BREAD, TOMATO_SOUP, PIZZA),
        r(JERKY, DRIED_ORANGE_SLICES, COWBOY_TRAIL_MIX), r(GRILLED_SARDINES, BAKED_POTATO, CAMP_CRYSTAL_FISH_FRY),
        r(POPCORN, MILKSHAKE, MOVIE_NIGHT_COMBO), r(POPCORN, CARAMEL, CARAMEL_CORN), r(BATTER, CARAMEL, BIRTHDAY_CAKE),
        r(ROASTED_PUMPKIN, BATTER, PUMPKIN_PIE), r(BATTER, GRILL, PANCAKE_STACK),
        r(TOASTED_MARSHMALLOW, CHOCOLATE, CAMPFIRE_SMORES), r(HOT_COCOA, TOASTED_MARSHMALLOW, COCOA_AND_MARSHMALLOWS),
        r(FOOT_JERKY, BUFFALO_BAT_WINGS, TEXAS_BBQ_PLATTER), r(GRILLED_FOOT, BAKED_POTATO, FOOT_AND_POTATOES),
        r(WORM_SPAGHETTI, DEVILED_EGGS, SPAGHETTI_AND_EYEBALLS), r(EYEBALL_CAVIAR, CAMPFIRE_BREAD, CAVIAR_CANAPES),
        r(JELLO_BRAIN, LADYFINGERS, BRAIN_TRIFLE), r(FINGER_DOG, CAMPFIRE_BREAD, FINGER_DOG_IN_A_BUN),
        r(BLOOD_ORANGE_JUICE, FRIED_EGG, VAMPIRE_BREAKFAST), r(EYE_SCREAM, CARAMEL, EYE_SCREAM_SUNDAE),
        r(DIRT_CUP, GUMMY_WORMS, GRAVEYARD_PUDDING), r(BUBBLING_BRAIN_BROTH, BAT_BREW, WITCHS_SUPPER),
    )

    /** The botches the spec names for particular wrong pairs. */
    private val namedBotches = listOf(
        r(SARDINES, ORANGE, SARDINE_SMOOTHIE), r(SARDINES, CHOCOLATE, FISH_FUDGE), r(SARDINES, MARSHMALLOW, FISHMALLOW),
        r(SARDINES, SUN, STINKY_FISH), r(MILK, ORANGE, CURDLED_MESS), r(MILK, SUN, SPOILED_MILK),
        r(CHOCOLATE, SUN, MELTED_MESS), r(BATTER, CAULDRON, CURSED_CAKE), r(POPCORN, MILK, SOGGY_POPCORN),
    )

    private fun assertCooks(expected: PantryItem, a: PantryItem, b: PantryItem) {
        assertEquals("$a + $b", CookResult.Cooked(expected), cook(a, b))
        assertEquals("$b + $a", CookResult.Cooked(expected), cook(b, a))
    }

    @Test
    fun theCookbookHasEveryItem() {
        val all = PantryItem.entries
        fun count(kind: ItemKind) = all.count { it.kind == kind }
        assertEquals(5, count(ItemKind.METHOD))
        assertEquals(21, count(ItemKind.FOOD))
        assertEquals(41, count(ItemKind.SNACK))
        assertEquals(23, count(ItemKind.MEAL))
        assertEquals(12, count(ItemKind.BOTCHED))
        assertEquals(102, all.size)
        // The spec's order: ways to cook, foods, snacks, meals, botches.
        assertEquals(all.map { it.kind }.sorted(), all.map { it.kind })
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.title }.toSet().size)
        for (item in all) {
            assertEquals(item.name.lowercase(), item.id)
            assertEquals(item.name, item.kind == ItemKind.METHOD || item.kind == ItemKind.FOOD, item.raw)
            val flavorless = item.kind == ItemKind.METHOD || item.kind == ItemKind.BOTCHED
            assertEquals(item.name, flavorless, item.flavor == null)
        }
    }

    @Test
    fun titlesAreWrittenAsTheSpecWritesThem() {
        assertEquals("Caviar Canapés", CAVIAR_CANAPES.title)
        assertEquals("Witch's Supper", WITCHS_SUPPER.title)
        assertEquals("Campfire S'mores", CAMPFIRE_SMORES.title)
        assertEquals("Spaghetti & Eyeballs", SPAGHETTI_AND_EYEBALLS.title)
        assertEquals("Steak & Eggs", STEAK_AND_EGGS.title)
        assertEquals("Cocoa & Marshmallows", COCOA_AND_MARSHMALLOWS.title)
        assertEquals("Foot & Potatoes", FOOT_AND_POTATOES.title)
        assertEquals("Finger Dog in a Bun", FINGER_DOG_IN_A_BUN.title)
        assertEquals("Eye of Newt Potion", EYE_OF_NEWT_POTION.title)
        assertEquals("Not-Strawberry Milk", NOT_STRAWBERRY_MILK.title)
        assertEquals("Sun-Dried Tomatoes", SUN_DRIED_TOMATOES.title)
        assertEquals("Burnt to a Crisp", BURNT_TO_A_CRISP.title)
        assertEquals("Pile of Slop", PILE_OF_SLOP.title)
        assertEquals("Raw Steak", RAW_STEAK.title)
        assertEquals("Severed Foot", SEVERED_FOOT.title)
        assertEquals("Camp Crystal Fish Fry", CAMP_CRYSTAL_FISH_FRY.title)
    }

    @Test
    fun everyDishHasTheFlavorTheSpecGivesIt() {
        val savory = setOf(
            RAW_STEAK, EGG, FLOUR, CHEESE, TOMATO, POTATO, SARDINES, CORN,
            STEAK, FRIED_EGG, MELTED_CHEESE, GRILLED_SARDINES, POPCORN, BAKED_POTATO, CAMPFIRE_BREAD, TOMATO_SOUP,
            BOILED_EGG, JERKY, SUN_DRIED_TOMATOES,
            STEAK_DINNER, STEAK_AND_EGGS, GRILLED_CHEESE, PIZZA, COWBOY_TRAIL_MIX, CAMP_CRYSTAL_FISH_FRY,
            MOVIE_NIGHT_COMBO,
        )
        val sweet = setOf(
            SUGAR, MILK, ORANGE, PUMPKIN, CHOCOLATE, MARSHMALLOW,
            TOASTED_MARSHMALLOW, CARAMEL, ROASTED_PUMPKIN, HOT_COCOA, PUMPKIN_SPICE_POTION, DRIED_ORANGE_SLICES, BATTER,
            MILKSHAKE, CHOCOLATE_ORANGE,
            CARAMEL_CORN, BIRTHDAY_CAKE, PUMPKIN_PIE, PANCAKE_STACK, CAMPFIRE_SMORES, COCOA_AND_MARSHMALLOWS,
        )
        val gross = setOf(
            SEVERED_FOOT, EYEBALL, BRAIN, FINGER, WORMS, BAT_WING, BLOOD,
            GRILLED_FOOT, BUFFALO_BAT_WINGS, FINGER_DOG, WORM_SPAGHETTI, POACHED_EYEBALL, BLOOD_PUDDING,
            BUBBLING_BRAIN_BROTH, BAT_BREW, EYE_OF_NEWT_POTION, FOOT_JERKY, ROTTEN_EGG, GUMMY_WORMS, DIRT_CUP,
            JELLO_BRAIN, LADYFINGERS, TOE_JAM, BLOOD_ORANGE_JUICE, NOT_STRAWBERRY_MILK, EYEBALL_CAVIAR, DEVILED_EGGS,
            EYE_SCREAM,
            TEXAS_BBQ_PLATTER, FOOT_AND_POTATOES, SPAGHETTI_AND_EYEBALLS, CAVIAR_CANAPES, BRAIN_TRIFLE,
            FINGER_DOG_IN_A_BUN, VAMPIRE_BREAKFAST, EYE_SCREAM_SUNDAE, GRAVEYARD_PUDDING, WITCHS_SUPPER,
        )
        assertEquals(26, savory.size)
        assertEquals(21, sweet.size)
        assertEquals(38, gross.size)
        fun withFlavor(flavor: Flavor) = PantryItem.entries.filter { it.flavor == flavor }.toSet()
        assertEquals(savory, withFlavor(SAVORY))
        assertEquals(sweet, withFlavor(SWEET))
        assertEquals(gross, withFlavor(GROSS))
    }

    @Test
    fun itemsAreFoundByTheirIds() {
        for (item in PantryItem.entries) assertEquals(item, pantryItemById(item.id))
        assertEquals(WITCHS_SUPPER, pantryItemById("witchs_supper"))
        assertEquals(RAW_STEAK, pantryItemById("raw_steak"))
        assertNull(pantryItemById("RAW_STEAK"))
        assertNull(pantryItemById("hamburger"))
        assertNull(pantryItemById(""))
    }

    @Test
    fun everyRecipeMatchesTheSpec() {
        assertEquals(41, snacks.size)
        assertEquals(23, meals.size)
        assertEquals(9, namedBotches.size)
        val table = snacks + meals + namedBotches
        assertEquals(73, table.size)
        // A pair makes one thing, two different things go into it, and a dish has one recipe.
        assertEquals(73, table.map { setOf(it.a, it.b) }.toSet().size)
        assertTrue(table.all { it.a != it.b })
        assertEquals(73, table.map { it.out }.toSet().size)
        assertEquals(table.associate { setOf(it.a, it.b) to it.out }, RECIPES)
        for (row in table) assertCooks(row.out, row.a, row.b)
        // What the recipes make: every snack and meal, and the named botches, nothing else.
        val named = namedBotches.map { it.out }.toSet()
        assertEquals(
            PantryItem.entries.filter { it.kind == ItemKind.SNACK || it.kind == ItemKind.MEAL }.toSet() + named,
            RECIPES.values.toSet(),
        )
        assertTrue(snacks.all { it.out.kind == ItemKind.SNACK })
        assertTrue(meals.all { it.out.kind == ItemKind.MEAL })
        assertTrue(namedBotches.all { it.out.kind == ItemKind.BOTCHED })
    }

    @Test
    fun methodsChangeTheDish() {
        assertCooks(FRIED_EGG, EGG, GRILL)
        assertCooks(BOILED_EGG, EGG, POT)
        assertCooks(ROTTEN_EGG, EGG, SUN)
        assertCooks(STEAK, RAW_STEAK, GRILL)
        assertCooks(JERKY, RAW_STEAK, SUN)
    }

    @Test
    fun theRulesForEverythingElse() {
        assertCooks(KITCHEN_FIRE, GRILL, SUN)
        assertCooks(KITCHEN_FIRE, POT, POT)
        assertCooks(BURNT_TO_A_CRISP, STEAK, CAMPFIRE)
        assertCooks(BURNT_TO_A_CRISP, STEAK_DINNER, GRILL)
        // A recipe or a named botch beats the burnt rule.
        assertCooks(PANCAKE_STACK, BATTER, GRILL)
        assertCooks(CURSED_CAKE, BATTER, CAULDRON)
        assertCooks(PILE_OF_SLOP, EGG, EGG)
        assertCooks(PILE_OF_SLOP, CHEESE, WORMS)
        assertCooks(PILE_OF_SLOP, STEAK_DINNER, PIZZA)
        // A raw food and a way to cook that make nothing are slop, not a fire or a crisp.
        assertCooks(PILE_OF_SLOP, CHEESE, CAMPFIRE)
        assertCooks(PILE_OF_SLOP, BATTER, EGG)
    }

    @Test
    fun everyPairCooksSomethingExceptABotchWithAnythingElse() {
        val items = PantryItem.entries
        for (a in items) for (b in items) {
            val result = cook(a, b)
            assertEquals("$a + $b both ways", result, cook(b, a))
            val botches = listOf(a, b).count { it.kind == ItemKind.BOTCHED }
            if (botches == 1) continue
            assertTrue("$a + $b", result is CookResult.Cooked)
            assertTrue("$a + $b", !(result as CookResult.Cooked).item.raw) // a dish or a botch, never an ingredient
        }
    }

    @Test
    fun aBotchCooksWithNothingButAnotherBotch() {
        val botches = PantryItem.entries.filter { it.kind == ItemKind.BOTCHED }
        assertEquals(12, botches.size)
        for (botch in botches) for (other in PantryItem.entries.filter { it.kind != ItemKind.BOTCHED }) {
            assertEquals("$botch + $other", CookResult.Refused, cook(botch, other))
            assertEquals("$other + $botch", CookResult.Refused, cook(other, botch))
        }
    }

    @Test
    fun twoBotchesMakeOnePileOfSlop() {
        // The player's ask: slop squashes down, two shelf places into one. Any two botches, the same one twice
        // included, named ones and all, make the plain Pile of Slop and nothing better.
        val botches = PantryItem.entries.filter { it.kind == ItemKind.BOTCHED }
        for (a in botches) for (b in botches) assertCooks(PILE_OF_SLOP, a, b)
        assertCooks(PILE_OF_SLOP, PILE_OF_SLOP, PILE_OF_SLOP)
        assertCooks(PILE_OF_SLOP, KITCHEN_FIRE, BURNT_TO_A_CRISP)
        assertCooks(PILE_OF_SLOP, CURSED_CAKE, FISHMALLOW)
        // No recipe names a botch as an input, so the botch rule never hides one.
        assertTrue(RECIPES.keys.none { inputs -> inputs.any { it.kind == ItemKind.BOTCHED } })
    }

    // The cookable pairs.

    @Test
    fun rawRecipesAreTheSnacksMadeFromTwoIngredients() {
        // Every snack but none of the meals, and no named botch (Sardines + Orange is two raw items too).
        val expected = snacks.filter { it.a.raw && it.b.raw }.map { setOf(it.a, it.b) }
        assertEquals(41, expected.size)
        assertEquals(expected, RAW_RECIPES)
        for (pair in RAW_RECIPES) {
            assertEquals(2, pair.size)
            val made = (cook(pair.first(), pair.last()) as CookResult.Cooked).item
            assertEquals("$pair", ItemKind.SNACK, made.kind)
        }
        assertTrue(setOf(SARDINES, ORANGE) !in RAW_RECIPES)
        // Every ingredient is in at least one, so a top-up can draw any of them.
        assertEquals(PantryItem.entries.filter { it.raw }.toSet(), RAW_RECIPES.flatten().toSet())
        // Two ways to cook never cook into anything good.
        assertTrue(RAW_RECIPES.all { pair -> pair.count { it.kind == ItemKind.METHOD } <= 1 })
    }

    @Test
    fun cookablePairsFindsUpToTwoThatShareNothing() {
        assertEquals(emptyList<Set<PantryItem>>(), cookablePairs(emptyList()))
        assertEquals(emptyList<Set<PantryItem>>(), cookablePairs(listOf(PILE_OF_SLOP, STEAK, BAKED_POTATO))) // dishes don't count
        // Raw items that only make botches (named ones, slop, a fire) make no pair.
        assertEquals(emptyList<Set<PantryItem>>(), cookablePairs(listOf(SARDINES, MILK, MARSHMALLOW, CHOCOLATE, SUN, CAULDRON)))
        assertEquals(listOf(setOf(RAW_STEAK, GRILL)), cookablePairs(listOf(GRILL, PILE_OF_SLOP, RAW_STEAK)))
        // One Grill can't make two pairs; a second one can, and the same recipe twice counts twice.
        assertEquals(1, cookablePairs(listOf(GRILL, EGG, RAW_STEAK)).size)
        assertEquals(2, cookablePairs(listOf(GRILL, EGG, RAW_STEAK, GRILL)).size)
        assertEquals(listOf(setOf(EGG, GRILL), setOf(EGG, GRILL)), cookablePairs(listOf(EGG, GRILL, EGG, GRILL)))
        assertEquals(1, cookablePairs(listOf(EGG, GRILL, EGG)).size)
        // It finds two whenever two can be had, even where the first pair in the cookbook's order spoils it: Egg +
        // Grill comes before Flour + Egg, but only Cheese + Grill with Flour + Egg makes two.
        val tricky = listOf(EGG, GRILL, FLOUR, CHEESE)
        assertEquals(setOf(setOf(CHEESE, GRILL), setOf(FLOUR, EGG)), cookablePairs(tricky).toSet())
        // At most [most], whatever the shelf holds.
        val lots = listOf(GRILL, RAW_STEAK, POT, TOMATO, SUN, ORANGE, MILK, SUGAR)
        assertEquals(2, cookablePairs(lots).size)
        assertEquals(4, cookablePairs(lots, most = 4).size)
        assertEquals(1, cookablePairs(lots, most = 1).size)
        assertEquals(0, cookablePairs(lots, most = 0).size)
    }

    @Test
    fun cookablePairsAreReallyOnTheShelfAndCookReally() {
        val rng = kotlin.random.Random(7)
        repeat(3_000) {
            val shelf = List(rng.nextInt(0, 12)) { PantryItem.entries.random(rng) }
            val pairs = cookablePairs(shelf)
            assertTrue(pairs.size <= 2)
            // No shelf item in two pairs.
            val used = pairs.flatten().groupingBy { it }.eachCount()
            for ((item, n) in used) assertTrue("$shelf $pairs", shelf.count { it == item } >= n)
            for (pair in pairs) {
                val made = (cook(pair.first(), pair.last()) as CookResult.Cooked).item
                assertTrue("$pair", made.kind == ItemKind.SNACK || made.kind == ItemKind.MEAL)
            }
            // And as many as there are, up to two: checked the long way, over every two pairs of shelf places.
            fun good(i: Int, j: Int): Boolean {
                if (!shelf[i].raw || !shelf[j].raw) return false
                val kind = (cook(shelf[i], shelf[j]) as CookResult.Cooked).item.kind
                return kind == ItemKind.SNACK || kind == ItemKind.MEAL
            }
            val all = shelf.indices.flatMap { i -> (i + 1 until shelf.size).map { j -> i to j } }.filter { (i, j) -> good(i, j) }
            val most = when {
                all.any { (a, b) -> all.any { (c, d) -> setOf(a, b, c, d).size == 4 } } -> 2
                all.isNotEmpty() -> 1
                else -> 0
            }
            assertEquals("$shelf", most, pairs.size)
        }
    }

    @Test
    fun everySnackAndMealCanBeMadeFromRawIngredients() {
        val made = PantryItem.entries.filter { it.raw }.toMutableSet()
        do {
            val before = made.size
            for ((inputs, output) in RECIPES) if (made.containsAll(inputs)) made += output
        } while (made.size > before)
        val dishes = PantryItem.entries.filter { it.kind == ItemKind.SNACK || it.kind == ItemKind.MEAL }
        assertEquals(64, dishes.size)
        assertEquals(emptyList<PantryItem>(), dishes.filterNot { it in made })
        assertTrue(dishes.all { it in RECIPES.values })
    }

    /** How many raw items (foods and ways to cook, one each time it is used) [item] takes to make. */
    private fun rawLeaves(item: PantryItem): Int =
        if (item.raw) 1 else RECIPES.entries.single { it.value == item }.key.sumOf { rawLeaves(it) }

    @Test
    fun everyFavoriteTakesExactlyFourIngredients() {
        assertEquals(1, rawLeaves(EGG))
        assertEquals(3, rawLeaves(PANCAKE_STACK))
        assertEquals(4, rawLeaves(BIRTHDAY_CAKE))
        assertEquals(4, rawLeaves(WITCHS_SUPPER))
        val favorites = mapOf(
            PetId.JASON to CAMP_CRYSTAL_FISH_FRY,
            PetId.FREDDY to SPAGHETTI_AND_EYEBALLS,
            PetId.PINHEAD to CAVIAR_CANAPES,
            PetId.GHOSTFACE to MOVIE_NIGHT_COMBO,
            PetId.CHUCKY to BIRTHDAY_CAKE,
            PetId.MICHAEL to PUMPKIN_PIE,
            PetId.LEATHERFACE to TEXAS_BBQ_PLATTER,
            PetId.JAWS to FOOT_AND_POTATOES,
        )
        assertEquals(PetId.entries.toSet(), favorites.keys)
        for (pet in PetId.entries) {
            val favorite = tasteOf(pet).favorite
            assertEquals(pet.name, favorites[pet], favorite)
            assertEquals(pet.name, ItemKind.MEAL, favorite.kind)
            assertEquals(pet.name, 4, rawLeaves(favorite))
        }
        assertEquals(PetId.entries.size, PetId.entries.map { tasteOf(it).favorite }.toSet().size)
    }

    @Test
    fun everyKillerHasTheTastesTheSpecGives() {
        val tastes = mapOf(
            PetId.JASON to Taste(SAVORY, SWEET, GROSS, CAMP_CRYSTAL_FISH_FRY),
            PetId.FREDDY to Taste(GROSS, SAVORY, SWEET, SPAGHETTI_AND_EYEBALLS),
            PetId.PINHEAD to Taste(GROSS, SWEET, SAVORY, CAVIAR_CANAPES),
            PetId.GHOSTFACE to Taste(SAVORY, SWEET, GROSS, MOVIE_NIGHT_COMBO),
            PetId.CHUCKY to Taste(SWEET, GROSS, SAVORY, BIRTHDAY_CAKE),
            PetId.MICHAEL to Taste(SWEET, SAVORY, GROSS, PUMPKIN_PIE),
            PetId.LEATHERFACE to Taste(GROSS, SAVORY, SWEET, TEXAS_BBQ_PLATTER),
            PetId.JAWS to Taste(GROSS, SAVORY, SWEET, FOOT_AND_POTATOES),
        )
        for (pet in PetId.entries) {
            val taste = tasteOf(pet)
            assertEquals(pet.name, tastes[pet], taste)
            // Loves one flavor, is fine with another, hates the third; the favorite is in the flavor it loves.
            assertEquals(pet.name, Flavor.entries.toSet(), setOf(taste.loves, taste.fine, taste.hates))
            assertEquals(pet.name, taste.loves, taste.favorite.flavor)
        }
    }
}
