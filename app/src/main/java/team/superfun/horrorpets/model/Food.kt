package team.superfun.horrorpets.model

import kotlin.random.Random

/**
 * The food a feed drops in front of the pet: its art is `sprites/food/<file>` (made by tools/generate_food.py
 * and picked and trimmed by tools/finalize_food.py).
 */
enum class Food {
    MEAT,
    DRUMSTICK,
    SPINACH,
    CAKE,
    POPCORN,
    BRAIN,
    ;

    val file: String get() = "${name.lowercase()}.webp"
}

/** How many times a pet plays its munch (its feed animation) for one feed; the food's bites spread across them. */
const val FEED_MUNCHES = 2

/** A random food other than [last], so two feeds in a row never drop the same thing. */
fun nextFood(last: Food?, rng: Random): Food = Food.entries.filter { it != last }.random(rng)
