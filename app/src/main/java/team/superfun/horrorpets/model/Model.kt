package team.superfun.horrorpets.model

import kotlinx.serialization.Serializable

/**
 * Every killer, in roster order (the pet switcher's order). A pet's facts (its passive, its special, how it
 * walks) live in its [Roster] entry; its art and sounds live in folders named `name.lowercase()`.
 *
 * A [secret] killer is in the roster but nowhere a player can see (the stable, the opponent switcher, the counters)
 * until the save reveals it or it joins the stable: see [GameState.isVisible]. Every list a player picks from and
 * every counter goes through [GameState.visiblePets]. What looks past that does so on purpose:
 * - the starter pick ([Starters]), which never offers a secret killer, even once it is revealed;
 * - [GameState.challengerDue], which needs every killer that is not secret and finds the secret one due;
 * - a fight, its VS card and the challenger entrance, which show whoever is fighting;
 * - the save, the PvP relay, the scene catalog and the debug puppet, which know every killer ([PetId.entries]).
 */
enum class PetId(val displayName: String, val secret: Boolean = false) {
    JASON("Jason"),
    FREDDY("Freddy"),
    PINHEAD("Pinhead"),
    GHOSTFACE("Ghostface"),
    CHUCKY("Chucky"),
    MICHAEL("Michael"),
    LEATHERFACE("Leatherface"),
    JAWS("Jaws", secret = true),
}

/** The killers a new game may start with: every one but the secret ones, even once they are revealed. */
val Starters: List<PetId> = PetId.entries.filterNot { it.secret }

/**
 * One pet's persistent state. Stats are 0..100 doubles so real-time decay composes exactly.
 *
 * hunger is a need meter: 0 = full, 100 = starving.
 * funLevel is the "Fun" stat (`fun` is a Kotlin keyword); the UI label stays "Fun".
 * moodBonus is a transient -30..30 nudge on mood that decays back to 0 over time.
 * freshJoin marks a pet that has just joined the stable (unlocked by beating it), so time that passed before it
 * joined never decays it nor fakes its death. It is saved, so it survives a launch under a stale clock, and
 * cleared the first time the time away is actually applied (see `applyElapsed`).
 * [equipped] is the attack it fights with in each slot, by id (a slot missing here uses its starting attack), and
 * [known] every attack it has been offered as it levelled up, learned or not, so it can equip any of them later.
 * [trainingBest] is its best score in each training game, by [TrainingGame.name] (a game it has not played is absent).
 * [trainedGames] is the training games it has finished at least once, by [TrainingGame.name], at any score; a new
 * killer needs both solo games here before its first fight (see `mustTrainBeforeFighting`).
 * [favoriteFound] is whether it has been fed its favorite dish; once it has, that dish wears a heart on the pantry
 * shelf while this killer is the one in the kitchen (see `feed`).
 */
@Serializable
data class PetState(
    val id: PetId,
    val hunger: Double = 20.0,
    val funLevel: Double = 80.0,
    val strength: Double = 20.0,
    val level: Int = 1,
    val xp: Int = 0,
    val moodBonus: Double = 0.0,
    val wins: Int = 0,
    val losses: Int = 0,
    val sceneIndex: Int = 0,
    val fakeDeathPending: Boolean = false,
    val freshJoin: Boolean = false,
    val equipped: Map<Slot, String> = emptyMap(),
    val known: Set<String> = emptySet(),
    val trainingBest: Map<String, Int> = emptyMap(),
    val trainedGames: Set<String> = emptySet(),
    val favoriteFound: Boolean = false,
)

/**
 * The whole save. [pets] is the player's stable: only the killers they have unlocked (the starter they picked,
 * then each killer they beat), so a fresh game has none and no [selected] one. [selected] is always in [pets].
 * Version 2 introduced the stable; a version-1 save (every killer unlocked) is read as a fresh game.
 *
 * [pantry] is the player's one shelf for the whole stable: the [PantryItem.id] of everything on it, in shelf order
 * (the same item can appear more than once). It is ids, not items, so a save written by a later build that knows
 * more items still loads here; [pantryItems] is the shelf as items.
 *
 * [revealed] is the [PetId.secret] killers the player has met, by [PetId.name]: each one's challenger entrance has
 * started, so it is in every list and counter from then on (see [isVisible]). Names, not ids, like [pantry]; a build
 * that does not know the field ignores it.
 */
@Serializable
data class GameState(
    val version: Int = 2,
    val pets: Map<PetId, PetState> = emptyMap(),
    val selected: PetId? = null,
    val lastSeenMs: Long? = null,
    val pantry: List<String> = emptyList(),
    val revealed: Set<String> = emptySet(),
) {
    /** Whether [id] is in the stable. */
    fun isUnlocked(id: PetId): Boolean = id in pets
}

/** Whether the player may see [pet] anywhere yet: it is not [PetId.secret], or it has been [GameState.revealed], or it is in the stable. */
fun GameState.isVisible(pet: PetId): Boolean = !pet.secret || pet.name in revealed || pet in pets

/** The killers the player may see, in [PetId] order: what every list and "n / N" counter in the game counts. */
fun GameState.visiblePets(): List<PetId> = PetId.entries.filter { isVisible(it) }

/**
 * The secret killer whose challenger entrance the next FIGHT plays: one not yet visible, once every killer that is
 * not secret is in the stable (however long ago that happened, so a save that had them all before he existed gets
 * him too). Null while any of them is still to beat, or once he has been revealed or has joined.
 */
fun GameState.challengerDue(): PetId? {
    val nonSecret = PetId.entries.filterNot { it.secret }
    return if (nonSecret.all { it in pets }) PetId.entries.firstOrNull { it.secret && !isVisible(it) } else null
}

/** The [GameState.pantry] as items, in shelf order. An id this build does not know is skipped. */
fun GameState.pantryItems(): List<PantryItem> = pantry.mapNotNull(::pantryItemById)

/**
 * What happened while the app was closed. [biggestStat] is the display label ("Hunger", "Fun" or
 * "Strength") of the selected pet's largest change and [biggestDelta] its rounded signed change;
 * both are null/0 when nothing noticeable moved.
 */
data class AwaySummary(
    val elapsedMs: Long,
    val biggestStat: String?,
    val biggestDelta: Int,
    val fakeDeath: Set<PetId>,
)
