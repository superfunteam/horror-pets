package team.superfun.horrorpets.game.pantry

import kotlin.random.Random
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.addXp
import team.superfun.horrorpets.game.clampMoodBonus
import team.superfun.horrorpets.game.clampStat
import team.superfun.horrorpets.model.Flavor
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetState

/** How a killer takes what it is fed. It picks the pose (a happy jump or the FAIL pose) and the speech bubble's line. */
enum class Reaction {
    /** A raw food: "It's... raw." Taste does not come into it. */
    SHRUG,

    /** A dish in the flavor it is fine with. */
    CONTENT,

    /** A dish in the flavor it loves. */
    LOVED,

    /** A dish in the flavor it hates. */
    HATED,

    /** A botch, or a way to cook eaten raw. */
    YUCK,

    /** Its favorite dish. */
    FAVORITE,
}

/**
 * What feeding did: the [pet] after being paid, how it took it ([reaction] and the [line] it says), the XP the meal
 * paid and the levels that carried it past, and how far it actually moved [PetState.strength], [PetState.funLevel]
 * and [PetState.moodBonus] once clamped ([strengthGained], [funGained], [moodGained]: 0 where nothing moved, negative
 * for a loss). [firstFavorite] is true only the first time this killer is fed its favorite, which is when the game
 * remembers it ([PetState.favoriteFound]).
 */
data class FeedOutcome(
    val pet: PetState,
    val reaction: Reaction,
    val line: String,
    val xpGained: Int,
    val levelsGained: Int,
    val firstFavorite: Boolean,
    val strengthGained: Double,
    val funGained: Double,
    val moodGained: Double,
)

/** What one item pays before it is applied: the hunger change, the XP, the flavor stat and the mood bonus. */
private class Payout(val hunger: Double, val xp: Int, val stat: Double, val mood: Double, val reaction: Reaction, val line: String)

/**
 * Feeds [item] to [pet] and returns what it did, per the spec's payout table (see `Tuning`):
 * - a raw food fills a little and pays +1 of its flavor stat; the killer shrugs, whatever its taste;
 * - the killer's favorite pays its own row (the most of everything) in place of its tier's;
 * - a snack or meal pays its tier's hunger, XP and flavor stat. The flavor the killer loves pays double XP and double
 *   stat and lifts its mood; the one it is fine with pays as is; the one it hates fills by half and pays no XP and no
 *   stat but sours its mood;
 * - a botch fills a little and spoils its mood, and a way to cook eaten raw only spoils it.
 *
 * The flavor stat is [PetState.strength] for savory, [PetState.funLevel] for sweet and [PetState.moodBonus] for
 * gross, so a gross dish adds its stat and its mood to the mood bonus together. Every stat is clamped as elsewhere,
 * and the XP goes through [addXp]. A killer that is too full to eat is the caller's to turn away (see
 * `Tuning.FEED_REFUSE_BELOW`); this feeds it anyway.
 */
fun feed(pet: PetState, item: PantryItem): FeedOutcome {
    val p = payout(item, tasteOf(pet.id))
    val moodDelta = p.mood + if (item.flavor == Flavor.GROSS) p.stat else 0.0
    val base = pet.copy(
        hunger = clampStat(pet.hunger + p.hunger),
        moodBonus = clampMoodBonus(pet.moodBonus + moodDelta),
    )
    val fed = when (item.flavor) {
        Flavor.SAVORY -> base.copy(strength = clampStat(base.strength + p.stat))
        Flavor.SWEET -> base.copy(funLevel = clampStat(base.funLevel + p.stat))
        Flavor.GROSS -> base // its stat is the mood bonus, already added above
        null -> base // a way to cook or a botch has no flavor, so no flavor stat
    }
    val paid = addXp(fed, p.xp)
    val favorite = p.reaction == Reaction.FAVORITE
    return FeedOutcome(
        pet = paid.pet.copy(favoriteFound = pet.favoriteFound || favorite),
        reaction = p.reaction,
        line = p.line,
        xpGained = p.xp,
        levelsGained = paid.levelsGained,
        firstFavorite = favorite && !pet.favoriteFound,
        strengthGained = fed.strength - pet.strength,
        funGained = fed.funLevel - pet.funLevel,
        moodGained = fed.moodBonus - pet.moodBonus,
    )
}

private fun payout(item: PantryItem, taste: Taste): Payout = when {
    item.kind == ItemKind.METHOD -> Payout(0.0, 0, 0.0, Tuning.MOOD_RAW_METHOD, Reaction.YUCK, METHOD_LINES.getValue(item))
    item.kind == ItemKind.BOTCHED -> Payout(Tuning.FEED_BOTCH_HUNGER, 0, 0.0, Tuning.MOOD_BOTCH, Reaction.YUCK, botchLine(item))
    item.kind == ItemKind.FOOD -> Payout(Tuning.FEED_RAW_HUNGER, 0, Tuning.FLAVOR_RAW, 0.0, Reaction.SHRUG, "It's... raw.")
    item == taste.favorite -> Payout(
        Tuning.FEED_FAVORITE_HUNGER,
        Tuning.XP_FEED_FAVORITE,
        Tuning.FLAVOR_FAVORITE,
        Tuning.MOOD_FAVORITE,
        Reaction.FAVORITE,
        "MY FAVORITE!!",
    )
    else -> {
        val meal = item.kind == ItemKind.MEAL
        val hunger = if (meal) Tuning.FEED_MEAL_HUNGER else Tuning.FEED_SNACK_HUNGER
        val xp = if (meal) Tuning.XP_FEED_MEAL else Tuning.XP_FEED_SNACK
        val stat = if (meal) Tuning.FLAVOR_MEAL else Tuning.FLAVOR_SNACK
        val flavor = requireNotNull(item.flavor) { "${item.name} is a ${item.kind} with no flavor" }
        when (flavor) {
            taste.loves -> Payout(hunger, xp * 2, stat * 2, Tuning.MOOD_LOVED, Reaction.LOVED, lovedLine(flavor))
            taste.hates -> Payout(hunger / 2, 0, 0.0, Tuning.MOOD_HATED, Reaction.HATED, hatedLine(flavor))
            else -> Payout(hunger, xp, stat, 0.0, Reaction.CONTENT, "Not bad!")
        }
    }
}

private fun lovedLine(flavor: Flavor): String = when (flavor) {
    Flavor.SAVORY -> "Mmm, savory!"
    Flavor.SWEET -> "Mmm, sweet!"
    Flavor.GROSS -> "Gross! I love it!"
}

private fun hatedLine(flavor: Flavor): String = when (flavor) {
    Flavor.SAVORY -> "Too salty. Yuck."
    Flavor.SWEET -> "Too sweet. Yuck."
    Flavor.GROSS -> "Gross. And not the good kind."
}

/** What each way to cook says when it is fed raw (the spec's quotes). */
private val METHOD_LINES: Map<PantryItem, String> = mapOf(
    PantryItem.GRILL to "That's a grill.",
    PantryItem.CAMPFIRE to "HOT! HOT!",
    PantryItem.POT to "...It's a pot.",
    PantryItem.CAULDRON to "Bubbly. Ugh.",
    PantryItem.SUN to "Too bright!",
)

/** What a killer says to a named botch; any other botch (the Pile of Slop) is just "Ugh, slop.". */
private val BOTCH_LINES: Map<PantryItem, String> = mapOf(
    PantryItem.BURNT_TO_A_CRISP to "Ugh, charcoal.",
    PantryItem.KITCHEN_FIRE to "Ugh, ash.",
    PantryItem.SARDINE_SMOOTHIE to "Ugh, fishy juice.",
    PantryItem.FISH_FUDGE to "Ugh, fish fudge.",
    PantryItem.FISHMALLOW to "Ugh, fishy fluff.",
    PantryItem.STINKY_FISH to "Ugh, the smell!",
    PantryItem.CURDLED_MESS to "Ugh, curds.",
    PantryItem.SPOILED_MILK to "Ugh, spoiled.",
    PantryItem.MELTED_MESS to "Ugh, goop.",
    PantryItem.CURSED_CAKE to "Ugh, cursed.",
    PantryItem.SOGGY_POPCORN to "Ugh, soggy.",
)

private fun botchLine(botch: PantryItem): String = BOTCH_LINES[botch] ?: "Ugh, slop."

private val WAYS_TO_COOK: List<PantryItem> = PantryItem.entries.filter { it.kind == ItemKind.METHOD }

private val FOODS: List<PantryItem> = PantryItem.entries.filter { it.kind == ItemKind.FOOD }

/** The raw recipes made of a way to cook and a food ("raw steak on the grill"), the ones the top-up leans toward. */
private val ON_THE_HEAT: List<Set<PantryItem>> = RAW_RECIPES.filter { pair -> pair.any { it.kind == ItemKind.METHOD } }

/**
 * Brings [pantry] up to what every visit to the kitchen starts with, by adding random ingredients on the end:
 *
 * 1. [Tuning.PANTRY_PAIRS] cookable pairs: pairs of raw items that each cook into a real dish (see [cookablePairs]),
 *    sharing nothing, so there is always something good to make and a run of slop never leaves the player stuck. The
 *    pairs already on the shelf are counted first; for each one missing, a raw recipe is drawn at random and whichever
 *    of its two items the shelf can't spare is added. The draws lean toward a way to cook and a food
 *    ([Tuning.PANTRY_PAIR_METHOD_ODDS]) over two foods, and a shelf with no way to cook on it always draws one.
 * 2. The minimums, which the pairs nearly always meet already: at least [Tuning.PANTRY_MIN_RAW] raw items, at
 *    least [Tuning.PANTRY_MIN_METHODS] ways to cook and at least [Tuning.PANTRY_MIN_FOODS] foods.
 *
 * An empty pantry gets a starting handful instead (shuffled: a way to cook and a food for each of its pairs, on a
 * recipe like Raw Steak + Grill, then foods up to [Tuning.PANTRY_START_FOODS]). A shelf that already has enough comes
 * back as it was.
 *
 * The shelf never grows past [Tuning.PANTRY_MAX] (the save keeps only that many, so anything more would vanish on the
 * next load). A shelf without room for what it needs first throws out its oldest botches (the ones nearest the
 * front), as many as it takes and no more: a botch only cooks into more slop, so a shelf full of it would otherwise
 * get nothing new for days. Nothing else is ever removed or reordered: a shelf full of good dishes gets nothing, and
 * one with only a few places left fills them as best it can, a pair that only needs one more item first, and a way
 * to cook before anything when there is none.
 */
/** Whether [pet] can eat: hunger at or above [Tuning.FEED_REFUSE_BELOW]. The pantry turns a fuller killer away. */
fun canEat(pet: PetState): Boolean = pet.hunger >= Tuning.FEED_REFUSE_BELOW

fun topUp(pantry: List<PantryItem>, rng: Random): List<PantryItem> {
    if (pantry.isEmpty()) return startingHandful(rng)
    val shelf = pantry.toMutableList()
    // Everything but the botches stays, so that is all the room there can be.
    val added = topUpItems(shelf, Tuning.PANTRY_MAX - shelf.count { it.kind != ItemKind.BOTCHED }, rng)
    if (added.isEmpty()) return shelf
    // Room for them, made by throwing out the oldest botches, and only those.
    repeat((added.size - (Tuning.PANTRY_MAX - shelf.size)).coerceAtLeast(0)) {
        shelf.removeAt(shelf.indexOfFirst { it.kind == ItemKind.BOTCHED })
    }
    return shelf + added
}

/** An empty pantry's first handful: two pairs of a way to cook and a food that cook together, then foods, shuffled. */
private fun startingHandful(rng: Random): List<PantryItem> {
    val handful = mutableListOf<PantryItem>()
    repeat(Tuning.PANTRY_PAIRS) { handful += ON_THE_HEAT.random(rng) }
    while (handful.count { it.kind == ItemKind.FOOD } < Tuning.PANTRY_START_FOODS) handful += FOODS.random(rng)
    handful.shuffle(rng)
    return handful
}

/** What [topUp] adds to [shelf] (in the order it goes on the end), [room] items at most. */
private fun topUpItems(shelf: List<PantryItem>, room: Int, rng: Random): List<PantryItem> {
    val added = mutableListOf<PantryItem>()
    fun count(kind: ItemKind) = shelf.count { it.kind == kind } + added.count { it.kind == kind }

    // The cookable pairs: the ones there, then the missing ones' items, using the raw items no pair has taken.
    val pairs = cookablePairs(shelf, Tuning.PANTRY_PAIRS)
    val spare = shelf.filter { it.raw }.toMutableList()
    pairs.flatten().forEach { spare.remove(it) }
    for (missing in Tuning.PANTRY_PAIRS - pairs.size downTo 1) {
        val recipe = drawPair(spare, room - added.size, missing, onTheHeat = count(ItemKind.METHOD) == 0, rng) ?: break
        added += recipe.filterNot { spare.remove(it) }.sortedBy { it.kind }
    }

    // The minimums, in order of importance.
    while (added.size < room && count(ItemKind.METHOD) < Tuning.PANTRY_MIN_METHODS) added += WAYS_TO_COOK.random(rng)
    while (added.size < room && count(ItemKind.FOOD) < Tuning.PANTRY_MIN_FOODS) added += FOODS.random(rng)
    // No fill for PANTRY_MIN_RAW: two pairs are four raw items, and the tests' assertMeetsTheMinimums keeps it so.
    return added
}

/**
 * A raw recipe to complete the next of [missing] cookable pairs, with [room] places left and the [spare] raw items
 * no pair has taken (an input there costs no place), or null when none fits. With room for every missing pair at two
 * new items each, any recipe will do; with less, only the ones that cost the fewest places. [onTheHeat] (the shelf
 * has no way to cook) takes only a way to cook and a food; otherwise those still come up [Tuning.PANTRY_PAIR_METHOD_ODDS]
 * of the time, and two foods the rest.
 */
private fun drawPair(spare: List<PantryItem>, room: Int, missing: Int, onTheHeat: Boolean, rng: Random): Set<PantryItem>? {
    fun cost(recipe: Set<PantryItem>) = recipe.count { it !in spare }
    val allowed = if (onTheHeat) ON_THE_HEAT else RAW_RECIPES
    val fitting = if (room >= 2 * missing) {
        allowed
    } else {
        val cheapest = allowed.filter { cost(it) <= room }.minOfOrNull(::cost) ?: return null
        allowed.filter { cost(it) == cheapest }
    }
    val (heat, mixes) = fitting.partition { it in ON_THE_HEAT }
    val pool = when {
        mixes.isEmpty() -> heat
        heat.isEmpty() -> mixes
        rng.nextDouble() < Tuning.PANTRY_PAIR_METHOD_ODDS -> heat
        else -> mixes
    }
    return pool.randomOrNull(rng)
}
