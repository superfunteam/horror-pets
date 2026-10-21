package team.superfun.horrorpets.model

import kotlin.random.Random

/**
 * What the player's cartoon glove does to the pet when it plays (ui/home/PlayHand.kt): tickles it, pokes it,
 * dangles keys over it, or picks it up by the head and shakes it. The pet's happy PLAY animation follows.
 */
enum class PlayGag {
    TICKLE,
    POKE,
    KEYS,
    SHAKE,
}

/** A random gag other than [last], so two plays in a row never do the same thing. */
fun nextPlayGag(last: PlayGag?, rng: Random): PlayGag = PlayGag.entries.filter { it != last }.random(rng)
