package team.superfun.horrorpets.game

import team.superfun.horrorpets.model.PetState

enum class CareAction { FEED, PLAY, TRAIN }

enum class RefuseReason { TOO_FULL, TOO_HUNGRY }

sealed interface CareResult {
    data class Done(val pet: PetState, val levelsGained: Int) : CareResult
    data class Refused(val reason: RefuseReason) : CareResult
}

/**
 * Applies a care action. Hunger is a need meter (0 = full, 100 = starving), so feeding lowers it
 * while playing and training raise it. Every stat is clamped to 0..100 and moodBonus to -30..30.
 * A pet that is already full will not eat, and a starving pet will not train.
 */
fun perform(action: CareAction, p: PetState): CareResult = when (action) {
    CareAction.FEED -> {
        if (p.hunger < Tuning.FEED_REFUSE_BELOW) {
            CareResult.Refused(RefuseReason.TOO_FULL)
        } else {
            reward(p.copy(hunger = clampStat(p.hunger + Tuning.FEED_HUNGER)), Tuning.XP_FEED)
        }
    }

    CareAction.PLAY -> reward(
        p.copy(
            funLevel = clampStat(p.funLevel + Tuning.PLAY_FUN),
            hunger = clampStat(p.hunger + Tuning.PLAY_HUNGER),
            moodBonus = clampMoodBonus(p.moodBonus + Tuning.PLAY_MOOD_BONUS),
        ),
        Tuning.XP_PLAY,
    )

    CareAction.TRAIN -> {
        if (p.hunger >= Tuning.TRAIN_REFUSE_AT) {
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
