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

/**
 * Brings [pantry] up to what every visit to the kitchen starts with, by adding random ingredients on the end: at
 * least [Tuning.PANTRY_MIN_RAW] raw items, at least [Tuning.PANTRY_MIN_METHODS] ways to cook and at least
 * [Tuning.PANTRY_MIN_FOODS] foods, so there is always something to cook. An empty pantry gets a starting handful
 * instead (shuffled: [Tuning.PANTRY_START_METHODS] ways to cook and [Tuning.PANTRY_START_FOODS] foods). Never removes
 * or reorders what is there, and a shelf that already has enough comes back as it was. The open draws lean toward a
 * way to cook ([Tuning.PANTRY_METHOD_ODDS]), since a food alone can only be fed raw.
 *
 * The shelf never grows past [Tuning.PANTRY_MAX] (the save keeps only that many, so anything more would vanish on the
 * next load): a full shelf gets nothing, and one with only a few places left fills them in order of importance, a way
 * to cook first, then foods, then open draws.
 */
fun topUp(pantry: List<PantryItem>, rng: Random): List<PantryItem> {
    val shelf = pantry.toMutableList()
    if (shelf.size >= Tuning.PANTRY_MAX) return shelf
    if (shelf.isEmpty()) {
        repeat(Tuning.PANTRY_START_METHODS) { shelf += WAYS_TO_COOK.random(rng) }
        repeat(Tuning.PANTRY_START_FOODS) { shelf += FOODS.random(rng) }
        shelf.shuffle(rng)
        return shelf
    }
    while (shelf.size < Tuning.PANTRY_MAX && shelf.count { it.kind == ItemKind.METHOD } < Tuning.PANTRY_MIN_METHODS) {
        shelf += WAYS_TO_COOK.random(rng)
    }
    while (shelf.size < Tuning.PANTRY_MAX && shelf.count { it.kind == ItemKind.FOOD } < Tuning.PANTRY_MIN_FOODS) {
        shelf += FOODS.random(rng)
    }
    while (shelf.size < Tuning.PANTRY_MAX && shelf.count { it.raw } < Tuning.PANTRY_MIN_RAW) {
        shelf += if (rng.nextDouble() < Tuning.PANTRY_METHOD_ODDS) WAYS_TO_COOK.random(rng) else FOODS.random(rng)
    }
    return shelf
}
