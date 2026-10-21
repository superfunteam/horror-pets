package team.superfun.horrorpets.model

import kotlinx.serialization.Serializable

enum class PetId(val displayName: String) {
    JASON("Jason"),
    FREDDY("Freddy"),
    PINHEAD("Pinhead"),
    GHOSTFACE("Ghostface"),
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

@Serializable
data class GameState(
    val version: Int = 1,
    val pets: Map<PetId, PetState> = PetId.entries.associateWith { PetState(it) },
    val selected: PetId? = null,
    val lastSeenMs: Long? = null,
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
