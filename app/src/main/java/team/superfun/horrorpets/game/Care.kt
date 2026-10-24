package team.superfun.horrorpets.game

import team.superfun.horrorpets.model.PetState

enum class CareAction { FEED, PLAY, TRAIN }

/**
 * Why a care action or a fight was turned away: [NEEDS_TRAINING] is a new killer's first fight (see
 * `mustTrainBeforeFighting`). A killer too full to eat is the pantry's to turn away (see `Screen.Pantry.tooFull`).
 */
enum class RefuseReason { TOO_HUNGRY, NEEDS_TRAINING }

sealed interface CareResult {
    data class Done(val pet: PetState, val levelsGained: Int) : CareResult
    data class Refused(val reason: RefuseReason) : CareResult
}

/**
 * Applies a care action. Hunger is a need meter (0 = full, 100 = starving), so playing and training raise it. Every
 * stat is clamped to 0..100 and moodBonus to -30..30. A starving pet will not train.
 *
 * FEED is not performed here: it opens the pantry, where what the pet eats decides what it pays (see `feed`). Passing
 * it throws [IllegalArgumentException].
 */
fun perform(action: CareAction, p: PetState): CareResult = when (action) {
    CareAction.FEED -> throw IllegalArgumentException("FEED opens the pantry: see team.superfun.horrorpets.game.pantry.feed")

    CareAction.PLAY -> reward(
        p.copy(
            funLevel = clampStat(p.funLevel + Tuning.PLAY_FUN),
            hunger = clampStat(p.hunger + Tuning.PLAY_HUNGER),
            moodBonus = clampMoodBonus(p.moodBonus + Tuning.PLAY_MOOD_BONUS),
        ),
        Tuning.XP_PLAY,
    )

    CareAction.TRAIN -> {
        if (!canTrain(p)) {
            CareResult.Refused(RefuseReason.TOO_HUNGRY)
        } else {
            reward(
                p.copy(
                    strength = clampStat(p.strength + Tuning.TRAIN_STRENGTH),
                    hunger = clampStat(p.hunger + Tuning.TRAIN_HUNGER),
                    funLevel = clampStat(p.funLevel + Tuning.TRAIN_FUN),
                ),
                Tuning.XP_TRAIN,
            )
        }
    }
}

private fun reward(p: PetState, xp: Int): CareResult.Done {
    val result = addXp(p, xp)
    return CareResult.Done(result.pet, result.levelsGained)
}

/** A starving pet (hunger at or above [Tuning.STARVING_AT]) is too weak to fight. */
fun canFight(p: PetState): Boolean = p.hunger < Tuning.STARVING_AT

/** A starving pet (hunger at or above [Tuning.TRAIN_REFUSE_AT]) will not train; the same test [perform] refuses on. */
fun canTrain(p: PetState): Boolean = p.hunger < Tuning.TRAIN_REFUSE_AT
