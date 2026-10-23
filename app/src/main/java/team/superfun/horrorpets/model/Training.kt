package team.superfun.horrorpets.model

import kotlinx.serialization.Serializable

/**
 * The mini games TRAIN opens, in the order the reel shows them. [par] is the score that earns the full reward
 * (rounds for Slash Says and Hide and Seek, draws for Quick Draw, organs for Organ Drop). A game with [needsMate]
 * is played against another killer in the stable, so it is only offered when the stable has one.
 *
 * A pet's best scores are keyed by [name] (see [PetState.trainingBest]), so a game a later build adds can never
 * break an older save.
 */
@Serializable
enum class TrainingGame(val title: String, val needsMate: Boolean, val par: Int) {
    SLASH_SAYS("Slash Says", false, 8),
    QUICK_DRAW("Quick Draw", true, 15),
    HIDE_AND_SEEK("Hide and Seek", true, 8),
    ORGAN_DROP("Organ Drop", false, 30),
}
