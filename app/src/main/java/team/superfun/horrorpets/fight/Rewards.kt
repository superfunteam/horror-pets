package team.superfun.horrorpets.fight

import team.superfun.horrorpets.game.addXp
import team.superfun.horrorpets.game.clampMoodBonus
import team.superfun.horrorpets.game.clampStat
import team.superfun.horrorpets.model.PetState

/** The pet after a fight, plus the xp it earned and the levels that xp bought. */
data class FightReward(val pet: PetState, val xpGained: Int, val levelsGained: Int)

/**
 * Applies a finished fight to [p]. Every stat is clamped to its range.
 * - Win: hunger +15, mood bonus +15, wins +1, xp +40.
 * - Loss: hunger +15, fun -5, losses +1, xp +15.
 * - Run (the player forfeited): mood bonus -10 and losses +1; no xp and no hunger cost.
 *
 * @throws IllegalArgumentException if the fight has no winner yet.
 */
fun applyFightResult(p: PetState, s: FightState): FightReward {
    val winner = requireNotNull(s.winner) { "The fight is not over" }
    return when {
        s.playerRan -> FightReward(
            pet = p.copy(
                moodBonus = clampMoodBonus(p.moodBonus + FightTuning.RUN_MOOD_PENALTY),
                losses = p.losses + 1,
            ),
            xpGained = 0,
            levelsGained = 0,
        )

        winner == Side.PLAYER -> withXp(
            p.copy(
                hunger = clampStat(p.hunger + FightTuning.FIGHT_HUNGER),
                moodBonus = clampMoodBonus(p.moodBonus + FightTuning.WIN_MOOD_BONUS),
                wins = p.wins + 1,
            ),
            FightTuning.XP_WIN,
        )

        else -> withXp(
            p.copy(
                hunger = clampStat(p.hunger + FightTuning.FIGHT_HUNGER),
                funLevel = clampStat(p.funLevel + FightTuning.LOSS_FUN),
                losses = p.losses + 1,
            ),
            FightTuning.XP_LOSS,
        )
    }
}

private fun withXp(p: PetState, xp: Int): FightReward {
    val r = addXp(p, xp)
    return FightReward(r.pet, xp, r.levelsGained)
}
