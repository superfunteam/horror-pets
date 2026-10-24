package team.superfun.horrorpets.game.pantry

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.xpToNext
import team.superfun.horrorpets.model.Flavor.GROSS
import team.superfun.horrorpets.model.Flavor.SAVORY
import team.superfun.horrorpets.model.Flavor.SWEET
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PantryItem.*
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class FeedingTest {
    /** A pet with room to move every stat both ways. Jason loves savory, is fine with sweet and hates gross. */
    private fun pet(
        id: PetId = PetId.JASON,
        hunger: Double = 60.0,
        fn: Double = 50.0,
        strength: Double = 50.0,
        bonus: Double = 0.0,
        level: Int = 1,
        xp: Int = 0,
        favoriteFound: Boolean = false,
    ) = PetState(
        id,
        hunger = hunger,
        funLevel = fn,
        strength = strength,
        moodBonus = bonus,
        level = level,
        xp = xp,
        favoriteFound = favoriteFound,
    )

    private fun assertStats(
        p: PetState,
        hunger: Double = 60.0,
        fn: Double = 50.0,
        strength: Double = 50.0,
        bonus: Double = 0.0,
    ) {
        assertEquals("hunger", hunger, p.hunger, 1e-9)
        assertEquals("fun", fn, p.funLevel, 1e-9)
        assertEquals("strength", strength, p.strength, 1e-9)
        assertEquals("moodBonus", bonus, p.moodBonus, 1e-9)
    }

    // The spec's payout table, one test per row, with a mid-stat Jason.

    @Test
    fun aRawFoodFillsALittleAndShrugs() {
        val out = feed(pet(), EGG)
        assertEquals(Reaction.SHRUG, out.reaction)
        assertEquals("It's... raw.", out.line)
        assertStats(out.pet, hunger = 40.0, strength = 51.0)
        assertEquals(0, out.xpGained)
        assertEquals(0, out.pet.xp)
        assertEquals(0, out.levelsGained)
        assertFalse(out.firstFavorite)
    }

    @Test
    fun aRawFoodIgnoresTaste() {
        // Jason hates gross, and still only shrugs at a raw eyeball; it counts as +1 of the mood bonus.
        val gross = feed(pet(), EYEBALL)
        assertEquals(Reaction.SHRUG, gross.reaction)
        assertStats(gross.pet, hunger = 40.0, bonus = 1.0)
        // Freddy loves gross, and shrugs at a raw savory steak just the same.
        val steak = feed(pet(PetId.FREDDY), RAW_STEAK)
        assertEquals(Reaction.SHRUG, steak.reaction)
        assertStats(steak.pet, hunger = 40.0, strength = 51.0)
    }

    @Test
    fun aSnackPaysTheSnackRow() {
        // Sweet is the flavor Jason is fine with: the base numbers.
        val out = feed(pet(), CARAMEL)
        assertEquals(Reaction.CONTENT, out.reaction)
        assertEquals("Not bad!", out.line)
        assertStats(out.pet, hunger = 25.0, fn = 53.0)
        assertEquals(5, out.xpGained)
        assertEquals(5, out.pet.xp)
    }

    @Test
    fun aMealPaysTheMealRow() {
        val out = feed(pet(), PUMPKIN_PIE)
        assertEquals(Reaction.CONTENT, out.reaction)
        assertStats(out.pet, hunger = 5.0, fn = 56.0)
        assertEquals(15, out.xpGained)
        assertEquals(15, out.pet.xp)
    }

    @Test
    fun aMealItLovesPaysDoubleAndLiftsTheMood() {
        // Steak Dinner is savory and not Jason's favorite: -55 hunger, 15 xp doubled, 6 strength doubled, mood +10.
        val out = feed(pet(), STEAK_DINNER)
        assertEquals(Reaction.LOVED, out.reaction)
        assertEquals("Mmm, savory!", out.line)
        assertStats(out.pet, hunger = 5.0, strength = 62.0, bonus = 10.0)
        assertEquals(30, out.xpGained)
        assertEquals(30, out.pet.xp)

        val snack = feed(pet(), STEAK)
        assertEquals(Reaction.LOVED, snack.reaction)
        assertStats(snack.pet, hunger = 25.0, strength = 56.0, bonus = 10.0)
        assertEquals(10, snack.xpGained)
    }

    @Test
    fun aDishItIsFineWithPaysTheBaseRow() {
        val out = feed(pet(PetId.FREDDY), STEAK_DINNER) // Freddy is fine with savory
        assertEquals(Reaction.CONTENT, out.reaction)
        assertEquals("Not bad!", out.line)
        assertStats(out.pet, hunger = 5.0, strength = 56.0)
        assertEquals(15, out.xpGained)
    }

    @Test
    fun aDishItHatesPaysHalfTheHungerAndSours() {
        // A hated snack fills by half of -35.
        val snack = feed(pet(), GRILLED_FOOT) // Jason hates gross
        assertEquals(Reaction.HATED, snack.reaction)
        assertEquals("Gross. And not the good kind.", snack.line)
        assertStats(snack.pet, hunger = 42.5, bonus = -10.0)
        assertEquals(0, snack.xpGained)
        assertEquals(0, snack.pet.xp)
        assertFalse(snack.firstFavorite)

        // A hated meal fills by half of -55, and gives none of the sweet it is made of.
        val meal = feed(pet(PetId.FREDDY), BIRTHDAY_CAKE) // Freddy hates sweet
        assertEquals(Reaction.HATED, meal.reaction)
        assertEquals("Too sweet. Yuck.", meal.line)
        assertStats(meal.pet, hunger = 32.5, bonus = -10.0)
        assertEquals(0, meal.xpGained)
    }

    @Test
    fun aBotchFillsALittleAndSpoilsTheMood() {
        val out = feed(pet(), PILE_OF_SLOP)
        assertEquals(Reaction.YUCK, out.reaction)
        assertEquals("Ugh, slop.", out.line)
        assertStats(out.pet, hunger = 50.0, bonus = -15.0)
        assertEquals(0, out.xpGained)
        assertEquals(0, out.pet.xp)
    }

    @Test
    fun aRawWayToCookOnlySpoilsTheMood() {
        val out = feed(pet(), CAMPFIRE)
        assertEquals(Reaction.YUCK, out.reaction)
        assertEquals("HOT! HOT!", out.line)
        assertStats(out.pet, bonus = -10.0)
        assertEquals(0, out.xpGained)
    }

    @Test
    fun aFavoritePaysTheFavoriteRowNotTheLovedOne() {
        // Camp Crystal Fish Fry is a savory meal Jason loves; the favorite row replaces the loved meal's
        // -55 hunger, +30 xp, +12 strength and +10 mood.
        val out = feed(pet(hunger = 80.0), CAMP_CRYSTAL_FISH_FRY)
        assertEquals(Reaction.FAVORITE, out.reaction)
        assertEquals("MY FAVORITE!!", out.line)
        assertStats(out.pet, hunger = 10.0, strength = 60.0, bonus = 25.0)
        assertEquals(40, out.xpGained)
        assertEquals(40, out.pet.xp)
        assertTrue(out.firstFavorite)
        assertTrue(out.pet.favoriteFound)
    }

    @Test
    fun theFlavorStatFollowsTheFlavor() {
        // A raw food of each flavor (+1): savory feeds strength, sweet fun, gross the mood bonus; nothing else moves.
        assertStats(feed(pet(), EGG).pet, hunger = 40.0, strength = 51.0)
        assertStats(feed(pet(), SUGAR).pet, hunger = 40.0, fn = 51.0)
        assertStats(feed(pet(), EYEBALL).pet, hunger = 40.0, bonus = 1.0)
        // A snack its killer loves (3 doubled, and mood +10): the gross stat is the mood bonus itself, so the
        // stat and the mood gain both land there.
        assertStats(feed(pet(PetId.JASON), STEAK).pet, hunger = 25.0, strength = 56.0, bonus = 10.0)
        assertStats(feed(pet(PetId.CHUCKY), CARAMEL).pet, hunger = 25.0, fn = 56.0, bonus = 10.0)
        assertStats(feed(pet(PetId.FREDDY), GRILLED_FOOT).pet, hunger = 25.0, bonus = 16.0)
        // A gross favorite adds its stat and its mood to the mood bonus before the clamp: 10 + 25 = 35 -> 30.
        val spaghetti = feed(pet(PetId.FREDDY), SPAGHETTI_AND_EYEBALLS)
        assertEquals(Reaction.FAVORITE, spaghetti.reaction)
        assertStats(spaghetti.pet, hunger = 0.0, bonus = Tuning.MOOD_BONUS_MAX)
        assertStats(feed(pet(PetId.FREDDY, bonus = -20.0), SPAGHETTI_AND_EYEBALLS).pet, hunger = 0.0, bonus = 15.0)
    }

    @Test
    fun everythingClamps() {
        // Full stomach, maxed stats and mood: nothing leaves its range.
        val topped = feed(pet(hunger = 5.0, fn = 98.0, strength = 99.0, bonus = 25.0), STEAK_DINNER)
        assertStats(topped.pet, hunger = 0.0, fn = 98.0, strength = 100.0, bonus = 30.0)
        // The mood bonus floors at -30, and half a snack's hunger cannot take a nearly full stomach below empty.
        val sour = feed(pet(hunger = 3.0, bonus = -28.0), GRILLED_FOOT)
        assertStats(sour.pet, hunger = 0.0, bonus = -30.0)
        // A way to cook changes no hunger at all.
        assertStats(feed(pet(hunger = 3.0), GRILL).pet, hunger = 3.0, bonus = -10.0)
    }

    @Test
    fun itReportsWhatItGaveAsClamped() {
        fun gains(out: FeedOutcome) = Triple(out.strengthGained, out.funGained, out.moodGained)
        // A loved meal: 6 strength doubled, and the mood a loved dish lifts.
        assertEquals(Triple(12.0, 0.0, 10.0), gains(feed(pet(), STEAK_DINNER)))
        // A sweet snack Jason is fine with: fun only.
        assertEquals(Triple(0.0, 3.0, 0.0), gains(feed(pet(), CARAMEL)))
        // A botch: only a loss of mood. A gross dish Freddy loves: its stat and its mood both in the mood.
        assertEquals(Triple(0.0, 0.0, -15.0), gains(feed(pet(), PILE_OF_SLOP)))
        assertEquals(Triple(0.0, 0.0, 16.0), gains(feed(pet(PetId.FREDDY), GRILLED_FOOT)))
        // Clamped: strength 99 can only go to 100, a mood of 25 only to 30, and -28 only down to -30.
        assertEquals(Triple(1.0, 0.0, 5.0), gains(feed(pet(hunger = 5.0, strength = 99.0, bonus = 25.0), STEAK_DINNER)))
        assertEquals(Triple(0.0, 0.0, -2.0), gains(feed(pet(bonus = -28.0), GRILLED_FOOT)))
        // Already at the top: nothing moved, so nothing to show.
        assertEquals(Triple(0.0, 0.0, 0.0), gains(feed(pet(strength = 100.0, bonus = 30.0), STEAK_DINNER)))
        // Always exactly the change in the pet.
        val rng = Random(17)
        repeat(300) {
            val before = pet(
                id = PetId.entries.random(rng),
                strength = rng.nextDouble(0.0, 100.0),
                fn = rng.nextDouble(0.0, 100.0),
                bonus = rng.nextDouble(-30.0, 30.0),
            )
            val out = feed(before, PantryItem.entries.random(rng))
            assertEquals(out.pet.strength - before.strength, out.strengthGained, 0.0)
            assertEquals(out.pet.funLevel - before.funLevel, out.funGained, 0.0)
            assertEquals(out.pet.moodBonus - before.moodBonus, out.moodGained, 0.0)
        }
    }

    @Test
    fun firstFavoriteOnlyOnce() {
        val first = feed(pet(), CAMP_CRYSTAL_FISH_FRY)
        assertTrue(first.firstFavorite)
        val second = feed(first.pet, CAMP_CRYSTAL_FISH_FRY)
        assertFalse(second.firstFavorite)
        assertTrue(second.pet.favoriteFound)
        // It is still its favorite, and still pays the favorite row.
        assertEquals(Reaction.FAVORITE, second.reaction)
        assertEquals(40, second.xpGained)
        // Anything else leaves the flag as it was, and never sets it.
        assertFalse(feed(pet(), STEAK_DINNER).pet.favoriteFound)
        assertTrue(feed(first.pet, STEAK_DINNER).pet.favoriteFound)
        assertFalse(feed(pet(PetId.FREDDY), CAMP_CRYSTAL_FISH_FRY).pet.favoriteFound)
    }

    @Test
    fun aMealCanLevelUp() {
        // 90 xp + a loved meal's 30 = 120: level 2 with 20 over.
        val out = feed(pet(xp = 90), STEAK_DINNER)
        assertEquals(1, out.levelsGained)
        assertEquals(2, out.pet.level)
        assertEquals(20, out.pet.xp)
        assertEquals(30, out.xpGained)

        // A favorite's 40 on the brink of a level: the overflow carries into the next one.
        val favorite = feed(pet(level = 1, xp = 99), CAMP_CRYSTAL_FISH_FRY)
        assertEquals(1, favorite.levelsGained)
        assertEquals(2, favorite.pet.level)
        assertEquals(99 + 40 - xpToNext(1), favorite.pet.xp)

        // No xp, no level.
        assertEquals(0, feed(pet(xp = 99), GRILLED_FOOT).levelsGained)
        assertEquals(99, feed(pet(xp = 99), GRILLED_FOOT).pet.xp)
    }

    @Test
    fun theLinesNameTheFlavor() {
        val loved = mapOf(SAVORY to "Mmm, savory!", SWEET to "Mmm, sweet!", GROSS to "Gross! I love it!")
        val hated = mapOf(SAVORY to "Too salty. Yuck.", SWEET to "Too sweet. Yuck.", GROSS to "Gross. And not the good kind.")
        val snackOf = mapOf(SAVORY to STEAK, SWEET to CARAMEL, GROSS to GRILLED_FOOT)
        for (id in PetId.entries) {
            val taste = tasteOf(id)
            for ((flavor, snack) in snackOf) {
                val out = feed(pet(id), snack)
                val expected = when (flavor) {
                    taste.loves -> loved.getValue(flavor)
                    taste.hates -> hated.getValue(flavor)
                    else -> "Not bad!"
                }
                assertEquals("$id fed $snack", expected, out.line)
            }
        }
        // The reaction goes with the line.
        assertEquals(Reaction.LOVED, feed(pet(PetId.CHUCKY), CARAMEL).reaction)
        assertEquals(Reaction.HATED, feed(pet(PetId.PINHEAD), STEAK).reaction)
        assertEquals("Too salty. Yuck.", feed(pet(PetId.PINHEAD), STEAK).line)
        assertEquals(Reaction.CONTENT, feed(pet(PetId.JASON), CARAMEL).reaction)
    }

    @Test
    fun eachWayToCookHasItsOwnLine() {
        val lines = mapOf(
            GRILL to "That's a grill.",
            CAMPFIRE to "HOT! HOT!",
            POT to "...It's a pot.",
            CAULDRON to "Bubbly. Ugh.",
            SUN to "Too bright!",
        )
        for ((method, line) in lines) {
            val out = feed(pet(), method)
            assertEquals(method.name, Reaction.YUCK, out.reaction)
            assertEquals(method.name, line, out.line)
        }
        assertEquals(PantryItem.entries.filter { it.kind == ItemKind.METHOD }.toSet(), lines.keys)
    }

    @Test
    fun everyBotchIsYuckAndHasALine() {
        val botches = PantryItem.entries.filter { it.kind == ItemKind.BOTCHED }
        assertEquals(12, botches.size)
        for (botch in botches) {
            val out = feed(pet(), botch)
            assertEquals(botch.name, Reaction.YUCK, out.reaction)
            assertTrue(botch.name, out.line.isNotBlank())
            assertStats(out.pet, hunger = 50.0, bonus = -15.0)
            assertEquals(botch.name, 0, out.xpGained)
        }
        assertEquals("Ugh, slop.", feed(pet(), PILE_OF_SLOP).line)
    }

    @Test
    fun everyItemForEveryKillerPaysSanelyAndByItsKind() {
        for (id in PetId.entries) {
            val taste = tasteOf(id)
            for (item in PantryItem.entries) {
                val start = pet(id, hunger = 50.0, fn = 50.0, strength = 50.0, bonus = 0.0)
                val out = feed(start, item)
                val what = "$id fed $item"
                assertTrue(what, out.pet.hunger in 0.0..50.0)
                assertTrue(what, out.pet.funLevel in 0.0..100.0 && out.pet.strength in 0.0..100.0)
                assertTrue(what, out.pet.moodBonus in -Tuning.MOOD_BONUS_MAX..Tuning.MOOD_BONUS_MAX)
                assertEquals(what, out.xpGained, out.pet.xp)
                assertEquals(what, item == taste.favorite, out.reaction == Reaction.FAVORITE)
                assertEquals(what, item == taste.favorite, out.firstFavorite)
                assertTrue(what, out.line.isNotBlank())
                val expected = when {
                    item == taste.favorite -> Reaction.FAVORITE
                    item.kind == ItemKind.METHOD || item.kind == ItemKind.BOTCHED -> Reaction.YUCK
                    item.kind == ItemKind.FOOD -> Reaction.SHRUG
                    item.flavor == taste.loves -> Reaction.LOVED
                    item.flavor == taste.hates -> Reaction.HATED
                    else -> Reaction.CONTENT
                }
                assertEquals(what, expected, out.reaction)
            }
        }
    }

    @Test
    fun feedingTouchesNothingButWhatItPays() {
        val before = pet(id = PetId.MICHAEL)
            .copy(wins = 4, losses = 2, sceneIndex = 1, known = setOf("michael_light_1"), trainingBest = mapOf("SLASH_SAYS" to 3))
        // Michael is fine with savory: a meal's base numbers.
        assertEquals(before.copy(hunger = 5.0, strength = 56.0, xp = 15), feed(before, STEAK_DINNER).pet)
    }

    // The top-up.

    private fun raw(shelf: List<PantryItem>) = shelf.count { it.raw }

    private fun methods(shelf: List<PantryItem>) = shelf.count { it.kind == ItemKind.METHOD }

    private fun foods(shelf: List<PantryItem>) = shelf.count { it.kind == ItemKind.FOOD }

    private fun assertMeetsTheMinimums(shelf: List<PantryItem>, what: String) {
        assertTrue("$what raw $shelf", raw(shelf) >= Tuning.PANTRY_MIN_RAW)
        assertTrue("$what ways to cook $shelf", methods(shelf) >= Tuning.PANTRY_MIN_METHODS)
        assertTrue("$what foods $shelf", foods(shelf) >= Tuning.PANTRY_MIN_FOODS)
    }

    /** Shelves that fall short in each of the ways there are, and an empty one. */
    private val partialShelves: List<List<PantryItem>> = listOf(
        emptyList(),
        listOf(CHEESE),
        listOf(GRILL),
        listOf(STEAK, PILE_OF_SLOP, BIRTHDAY_CAKE), // nothing raw at all
        listOf(EGG, EGG, EGG, EGG, EGG), // plenty raw, no way to cook
        listOf(GRILL, POT, SUN, CAULDRON), // four raw, all ways to cook
        listOf(GRILL, EGG), // one of each, short on raw
        listOf(GRILL, EGG, STEAK, STEAK_DINNER, MILK), // the cooked ones do not count toward the four raw
        listOf(PILE_OF_SLOP),
    )

    @Test
    fun topUpMeetsTheMinimums() {
        assertEquals(4, Tuning.PANTRY_MIN_RAW)
        assertEquals(1, Tuning.PANTRY_MIN_METHODS)
        assertEquals(2, Tuning.PANTRY_MIN_FOODS)
        for (seed in 0 until 1000) {
            for (shelf in partialShelves) {
                val topped = topUp(shelf, Random(seed))
                assertMeetsTheMinimums(topped, "seed $seed")
            }
        }
    }

    @Test
    fun anEmptyPantryGetsAStartingHandfulOfSix() {
        for (seed in 0 until 1000) {
            val handful = topUp(emptyList(), Random(seed))
            assertEquals(6, handful.size)
            assertEquals(2, methods(handful))
            assertEquals(4, foods(handful))
            assertEquals("seed $seed $handful", 2, cookablePairs(handful).size)
        }
    }

    @Test
    fun theStartingHandfulIsRandom() {
        val handfuls = (0 until 200).map { topUp(emptyList(), Random(it)).sorted() }.toSet()
        assertTrue("${handfuls.size}", handfuls.size > 150)
    }

    @Test
    fun aShelfThatAlreadyHasEnoughIsLeftAlone() {
        // Two cookable pairs (Grill + Egg, Sun + Tomato) and the minimums.
        val enough = listOf(STEAK, GRILL, EGG, MILK, SUN, PILE_OF_SLOP, TOMATO)
        for (seed in 0 until 100) assertEquals(enough, topUp(enough, Random(seed)))
        // Just the minimums (one way to cook, three foods), and just two pairs: Grill + Egg, Milk + Sugar.
        val exact = listOf(GRILL, EGG, MILK, SUGAR)
        for (seed in 0 until 100) assertEquals(exact, topUp(exact, Random(seed)))
    }

    @Test
    fun topUpKeepsWhatIsThere() {
        for (seed in 0 until 1000) {
            for (shelf in partialShelves) {
                val topped = topUp(shelf, Random(seed))
                // With room on the shelf it never removes or reorders: the shelf is the front of the result, and only
                // ingredients are added.
                assertEquals("seed $seed", shelf, topped.take(shelf.size))
                assertTrue("seed $seed", topped.drop(shelf.size).all { it.raw })
            }
        }
    }

    /** [n] cooked items and botches: a shelf with nothing raw on it, so a top-up wants to add the most. */
    private fun dishes(n: Int): List<PantryItem> = PantryItem.entries.filterNot { it.raw }.take(n)

    @Test
    fun topUpNeverGrowsTheShelfPastTheCap() {
        assertEquals(40, Tuning.PANTRY_MAX)
        for (size in listOf(Tuning.PANTRY_MAX, Tuning.PANTRY_MAX - 1, Tuning.PANTRY_MAX - 2)) {
            val shelf = dishes(size)
            assertEquals(size, shelf.size)
            for (seed in 0 until 1000) {
                val topped = topUp(shelf, Random(seed))
                assertTrue("size $size seed $seed", topped.size <= Tuning.PANTRY_MAX)
                assertEquals("size $size seed $seed", shelf, topped.take(shelf.size))
            }
        }
    }

    @Test
    fun aFullShelfComesBackUnchanged() {
        // No way to cook on it and nothing raw, and still it gets nothing: the save would drop it anyway.
        val full = dishes(Tuning.PANTRY_MAX)
        for (seed in 0 until 100) assertEquals(full, topUp(full, Random(seed)))
        // A shelf past the cap (a later build's) is not trimmed here either.
        val over = dishes(Tuning.PANTRY_MAX + 5)
        for (seed in 0 until 100) assertEquals(over, topUp(over, Random(seed)))
    }

    @Test
    fun aShelfOneShortOfFullGetsExactlyOneWayToCook() {
        // The most important minimum comes first: of the one place left, a way to cook takes it.
        val shelf = dishes(Tuning.PANTRY_MAX - 1)
        for (seed in 0 until 1000) {
            val topped = topUp(shelf, Random(seed))
            assertEquals("seed $seed", Tuning.PANTRY_MAX, topped.size)
            assertEquals("seed $seed", shelf, topped.take(shelf.size))
            assertEquals("seed $seed", ItemKind.METHOD, topped.last().kind)
        }
        // Two places left: a way to cook, then a food.
        val two = dishes(Tuning.PANTRY_MAX - 2)
        for (seed in 0 until 1000) {
            val added = topUp(two, Random(seed)).drop(two.size)
            assertEquals("seed $seed", listOf(ItemKind.METHOD, ItemKind.FOOD), added.map { it.kind })
        }
    }

    /** [n] botches, the oldest first: every kind there is, round and round. */
    private fun botches(n: Int): List<PantryItem> {
        val all = PantryItem.entries.filter { it.kind == ItemKind.BOTCHED }
        return List(n) { all[it % all.size] }
    }

    @Test
    fun aFullShelfOfBotchesThrowsOutItsOldestToMakeRoom() {
        // Slop only cooks into more slop and can't be thrown away, only eaten one a visit or squashed two into one: a
        // shelf full of it would get nothing new for days. It makes room for the top-up's two cookable pairs (4
        // places here, which meet every minimum too) by throwing out its oldest botches, the ones at the front, and no
        // more than that.
        val full = botches(Tuning.PANTRY_MAX)
        for (seed in 0 until 500) {
            val topped = topUp(full, Random(seed))
            assertEquals("seed $seed", Tuning.PANTRY_MAX, topped.size)
            assertMeetsTheMinimums(topped, "seed $seed")
            assertEquals("the oldest four went, the rest kept in order", full.drop(4), topped.take(Tuning.PANTRY_MAX - 4))
            assertTrue(topped.drop(Tuning.PANTRY_MAX - 4).all { it.raw })
        }
        // A shelf of botches with its two pairs on it (Milk + Sugar, Flour + Egg) but no way to cook only needs one
        // place: one botch goes, for a way to cook.
        val nearly = listOf(MILK, SUGAR, FLOUR, EGG) + botches(Tuning.PANTRY_MAX - 4)
        for (seed in 0 until 200) {
            val topped = topUp(nearly, Random(seed))
            assertEquals(Tuning.PANTRY_MAX, topped.size)
            assertEquals(listOf(MILK, SUGAR, FLOUR, EGG) + botches(Tuning.PANTRY_MAX - 4).drop(1), topped.take(Tuning.PANTRY_MAX - 1))
            assertEquals(ItemKind.METHOD, topped.last().kind)
        }
        // One pair on it and a spare Milk: the second pair takes the Milk or not, so one or two botches go.
        val onePair = listOf(GRILL, EGG, MILK) + botches(Tuning.PANTRY_MAX - 3)
        for (seed in 0 until 200) {
            val topped = topUp(onePair, Random(seed))
            assertEquals(Tuning.PANTRY_MAX, topped.size)
            val added = topped.size - 1 - topped.indexOfLast { it.kind == ItemKind.BOTCHED }
            assertTrue("seed $seed $topped", added in 1..2)
            val kept = listOf(GRILL, EGG, MILK) + botches(Tuning.PANTRY_MAX - 3).drop(added)
            assertEquals("the oldest botches went", kept, topped.take(Tuning.PANTRY_MAX - added))
            assertEquals("seed $seed $topped", 2, cookablePairs(topped).size)
        }
    }

    @Test
    fun aFullShelfLosesOnlyItsBotches() {
        // Two botches among good dishes, on a shelf that wants four places: both botches go (and nothing else), and
        // the two places go to the most important minimums, a way to cook and then a food.
        val good = dishes(Tuning.PANTRY_MAX - 2)
        val shelf = good.take(10) + PILE_OF_SLOP + good.drop(10).take(15) + KITCHEN_FIRE + good.drop(25)
        assertEquals(Tuning.PANTRY_MAX, shelf.size)
        for (seed in 0 until 500) {
            val topped = topUp(shelf, Random(seed))
            assertEquals(Tuning.PANTRY_MAX, topped.size)
            assertEquals("every good dish kept, in order", good, topped.take(good.size))
            assertEquals(listOf(ItemKind.METHOD, ItemKind.FOOD), topped.drop(good.size).map { it.kind })
            assertTrue(topped.none { it.kind == ItemKind.BOTCHED })
        }
    }

    @Test
    fun aFullShelfWithNoBotchesIsUnchanged() {
        val full = dishes(Tuning.PANTRY_MAX)
        assertTrue(full.none { it.kind == ItemKind.BOTCHED })
        for (seed in 0 until 100) assertEquals(full, topUp(full, Random(seed)))
    }

    @Test
    fun topUpNeverLeavesTheShelfPastTheCap() {
        val rng = Random(23)
        val kinds = PantryItem.entries
        repeat(2_000) {
            val size = rng.nextInt(0, Tuning.PANTRY_MAX + 1)
            val shelf = List(size) { kinds.random(rng) }
            val topped = topUp(shelf, Random(it))
            assertTrue("$size: ${topped.size}", topped.size <= Tuning.PANTRY_MAX)
            // Only botches ever go, and only from a shelf too full for its minimums.
            val gone = shelf.groupingBy { it }.eachCount().mapValues { (item, n) -> n - topped.count { it == item } }
            assertTrue(gone.filterValues { it > 0 }.keys.all { it.kind == ItemKind.BOTCHED })
            if (Tuning.PANTRY_MAX - size >= 4) assertEquals(shelf, topped.take(shelf.size))
        }
    }

    @Test
    fun topUpDoesNotChangeTheShelfItIsGiven() {
        val shelf = listOf(PILE_OF_SLOP)
        topUp(shelf, Random(1))
        assertEquals(listOf(PILE_OF_SLOP), shelf)
    }

    @Test
    fun topUpIsDeterministicForASeed() {
        for (seed in 0 until 50) {
            assertEquals(topUp(emptyList(), Random(seed)), topUp(emptyList(), Random(seed)))
            assertEquals(topUp(listOf(GRILL), Random(seed)), topUp(listOf(GRILL), Random(seed)))
        }
    }

    @Test
    fun topUpDrawsEveryIngredient() {
        val drawn = mutableSetOf<PantryItem>()
        for (seed in 0 until 1000) drawn += topUp(listOf(PILE_OF_SLOP), Random(seed))
        val everyIngredient = PantryItem.entries.filter { it.raw }.toSet()
        assertEquals(everyIngredient, drawn - PILE_OF_SLOP)
    }

    @Test
    fun waysToCookComeUpOften() {
        // This shelf has one cookable pair and nothing spare, so each top-up draws exactly one more pair, both of its
        // items new. 28 of the 41 raw recipes are a way to cook and a food; the top-up leans further toward them.
        assertEquals(41, RAW_RECIPES.size)
        assertEquals(28, RAW_RECIPES.count { pair -> pair.any { it.kind == ItemKind.METHOD } })
        var onTheHeat = 0
        val draws = 4000
        for (seed in 0 until draws) {
            val extra = topUp(listOf(GRILL, EGG), Random(seed)).drop(2)
            assertEquals(2, extra.size)
            assertTrue("$extra", extra.toSet() in RAW_RECIPES)
            if (extra.any { it.kind == ItemKind.METHOD }) onTheHeat++
        }
        val share = onTheHeat.toDouble() / draws
        assertTrue("share $share", share in 0.75..0.85)
    }

    // The cookable pairs.

    /** Whether [shelf] holds two cookable pairs, checked the long way (every two pairs of shelf places), not with [cookablePairs]. */
    private fun hasTwoPairs(shelf: List<PantryItem>): Boolean {
        fun good(a: PantryItem, b: PantryItem): Boolean {
            if (!a.raw || !b.raw) return false
            val kind = (cook(a, b) as? CookResult.Cooked)?.item?.kind
            return kind == ItemKind.SNACK || kind == ItemKind.MEAL
        }
        val pairs = shelf.indices.flatMap { i -> (i + 1 until shelf.size).map { j -> i to j } }.filter { (i, j) -> good(shelf[i], shelf[j]) }
        return pairs.any { (a, b) -> pairs.any { (c, d) -> setOf(a, b, c, d).size == 4 } }
    }

    @Test
    fun topUpAlwaysLeavesTwoCookablePairs() {
        assertEquals(2, Tuning.PANTRY_PAIRS)
        val shelves = partialShelves + listOf(listOf(MILK), listOf(MILK, MILK, MILK), listOf(SARDINES, ORANGE), listOf(EGG, EGG, GRILL))
        for (seed in 0 until 1000) {
            for (shelf in shelves) {
                val topped = topUp(shelf, Random(seed))
                assertTrue("seed $seed $shelf: $topped", hasTwoPairs(topped))
                assertEquals(2, cookablePairs(topped).size)
            }
        }
    }

    @Test
    fun theShelfsOwnPairsAreCountedFirst() {
        // Raw Steak + Grill is there already: only one more pair is added.
        for (seed in 0 until 500) {
            val extra = topUp(listOf(RAW_STEAK, GRILL, PILE_OF_SLOP), Random(seed)).drop(3)
            assertEquals("seed $seed $extra", 2, extra.size)
        }
        // A spare item a drawn recipe takes is used, not added again: the Pumpkin and the Pot pair up with nothing
        // here, and a pair drawn on one of them only adds its other item.
        var reused = 0
        for (seed in 0 until 500) {
            val extra = topUp(listOf(RAW_STEAK, GRILL, PUMPKIN, POT), Random(seed)).drop(4)
            assertTrue("seed $seed $extra", extra.size in 1..2)
            if (extra.size == 1) reused++
        }
        assertTrue("$reused", reused > 0)
    }

    @Test
    fun aShelfWithNoWayToCookGetsAPairOnTheHeat() {
        // Nothing raw on it: the first pair drawn is always a way to cook and a food, so no minimum needs more.
        for (seed in 0 until 500) {
            val extra = topUp(listOf(STEAK, PILE_OF_SLOP), Random(seed)).drop(2)
            assertEquals("seed $seed $extra", 4, extra.size)
            assertEquals(ItemKind.METHOD, extra[0].kind)
            assertTrue(extra.all { it.raw })
        }
    }

    @Test
    fun aFullShelfOfGoodDishesFallsShort() {
        // No botches and no room: nothing can go, so nothing comes.
        val full = dishes(Tuning.PANTRY_MAX - 1) + EGG
        for (seed in 0 until 100) assertEquals(full, topUp(full, Random(seed)))
        // One place and a spare Egg, but no way to cook: the place goes to a way to cook, and pairs with the Egg when
        // it can.
        val oneLeft = dishes(Tuning.PANTRY_MAX - 2) + EGG
        for (seed in 0 until 500) {
            val topped = topUp(oneLeft, Random(seed))
            assertEquals(Tuning.PANTRY_MAX, topped.size)
            assertEquals(ItemKind.METHOD, topped.last().kind)
            assertEquals(1, cookablePairs(topped).size)
        }
    }

    /**
     * One visit as a player might make it, on [shelf]: a few cooks of random pairs (a refused one uses nothing), maybe
     * one item fed, then some random food from elsewhere stocked on the end as it fits (what fights may pay one day).
     */
    private fun visit(shelf: List<PantryItem>, rng: Random): List<PantryItem> {
        val s = shelf.toMutableList()
        repeat(rng.nextInt(0, 5)) {
            if (s.size < 2) return@repeat
            val i = rng.nextInt(s.size)
            val j = (i + 1 + rng.nextInt(s.size - 1)) % s.size
            val result = (cook(s[i], s[j]) as? CookResult.Cooked)?.item ?: return@repeat
            s.removeAt(max(i, j))
            s[min(i, j)] = result
        }
        if (s.isNotEmpty() && rng.nextBoolean()) s.removeAt(rng.nextInt(s.size))
        repeat(rng.nextInt(0, 8)) { if (s.size < Tuning.PANTRY_MAX) s += PantryItem.entries.random(rng) }
        return s
    }

    @Test
    fun visitAfterVisitTheShelfKeepsTwoPairsUnderItsCap() {
        var full = 0
        for (seed in 0 until 500) {
            val rng = Random(seed)
            var shelf = topUp(emptyList(), rng)
            assertTrue("seed $seed fresh $shelf", hasTwoPairs(shelf))
            repeat(40) { n ->
                val before = visit(shelf, rng)
                shelf = topUp(before, rng)
                val what = "seed $seed visit $n: $before -> $shelf"
                assertTrue(what, shelf.size <= Tuning.PANTRY_MAX)
                if (shelf.size == Tuning.PANTRY_MAX) full++
                // Only botches ever go.
                val gone = before.groupingBy { it }.eachCount().mapValues { (item, k) -> k - shelf.count { it == item } }
                assertTrue(what, gone.filterValues { it > 0 }.keys.all { it.kind == ItemKind.BOTCHED })
                // When room allows (four places, once every botch could go), the two pairs and the minimums are there.
                if (Tuning.PANTRY_MAX - before.count { it.kind != ItemKind.BOTCHED } >= 4) {
                    assertTrue(what, hasTwoPairs(shelf))
                    assertMeetsTheMinimums(shelf, what)
                }
            }
        }
        assertTrue("the shelves fill up, so the cap is tested: $full", full > 1000)
    }
}
