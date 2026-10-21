package team.superfun.horrorpets.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
}

/**
 * One pet's persistent state. Stats are 0..100 doubles so real-time decay composes exactly.
 *
 * hunger is a need meter: 0 = full, 100 = starving.
 * funLevel is the "Fun" stat (`fun` is a Kotlin keyword); the UI label stays "Fun".
 * moodBonus is a transient -30..30 nudge on mood that decays back to 0 over time.
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
)

/**
 * The whole save. [newcomers] is never saved: it marks the pets a loaded save had no entry for (killers an
 * update added since it was written, filled in at their defaults by `SaveCodec`). They join fresh, so the
 * next [applyElapsed][team.superfun.horrorpets.game.applyElapsed] leaves them untouched (no decay, no fake
 * death for time the player spent away before they existed) and clears the mark.
 */
@Serializable
data class GameState(
    val version: Int = 1,
    val pets: Map<PetId, PetState> = PetId.entries.associateWith { PetState(it) },
    val selected: PetId? = null,
    val lastSeenMs: Long? = null,
    @Transient val newcomers: Set<PetId> = emptySet(),
)

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
