package team.superfun.horrorpets.model

import kotlinx.serialization.Serializable

/**
 * Every killer, in roster order (the pet switcher's order). A pet's facts (its passive, its special, how it
 * walks) live in its [Roster] entry; its art and sounds live in folders named `name.lowercase()`.
 */
enum class PetId(val displayName: String) {
    JASON("Jason"),
    FREDDY("Freddy"),
    PINHEAD("Pinhead"),
    GHOSTFACE("Ghostface"),
    CHUCKY("Chucky"),
    MICHAEL("Michael"),
    LEATHERFACE("Leatherface"),
}

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
)

/**
 * The whole save. [pets] is the player's stable: only the killers they have unlocked (the starter they picked,
 * then each killer they beat), so a fresh game has none and no [selected] one. [selected] is always in [pets].
 * Version 2 introduced the stable; a version-1 save (every killer unlocked) is read as a fresh game.
 */
@Serializable
data class GameState(
    val version: Int = 2,
    val pets: Map<PetId, PetState> = emptyMap(),
    val selected: PetId? = null,
    val lastSeenMs: Long? = null,
) {
    /** Whether [id] is in the stable. */
    fun isUnlocked(id: PetId): Boolean = id in pets
}

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
