package team.superfun.horrorpets.fight

import kotlin.math.roundToInt
import team.superfun.horrorpets.game.addXp
import team.superfun.horrorpets.game.clampMoodBonus
import team.superfun.horrorpets.game.clampStat
import team.superfun.horrorpets.model.PetState

/**
 * The grade a win earns, from its [RankScore]: the [title] the results screen stamps under the letter, the
 * [bonusXp] it pays on top of the win's, and the [minScore] it takes.
 */
enum class Rank(val title: String, val bonusXp: Int, val minScore: Int) {
    S("SLAUGHTER", 30, 80),
    A("BRUTAL", 20, 60),
    B("MESSY", 10, 35),
    C("SLOPPY", 0, Int.MIN_VALUE),
}

/**
 * How a win scored, part by part, so the results screen can show the sum: [hpPercent] of its HP left is worth
 * [hpPoints] (up to 50), its best combo [comboPoints] (10 a hit past the first, up to 30), its speed [speedPoints]
 * (20 within five turns, 4 fewer a turn after, never below 0), and each potion drunk -10 in [potionPoints].
 */
data class RankScore(val hpPercent: Int, val hpPoints: Int, val comboPoints: Int, val speedPoints: Int, val potionPoints: Int) {
    val total: Int get() = hpPoints + comboPoints + speedPoints + potionPoints
    val rank: Rank get() = Rank.entries.first { total >= it.minScore }
}

/** How the player's win in [s] scores (see [RankScore]); null unless the player won it by fighting (not by a forfeit). */
fun rankScore(s: FightState): RankScore? {
    if (s.winner != Side.PLAYER || s.playerRan || s.cpuRan) return null
    val hpPercent = (100.0 * s.player.hp / s.player.maxHp.coerceAtLeast(1)).roundToInt().coerceIn(0, 100)
    val comboHits = s.stats.bestCombo.coerceAtMost(FightTuning.RANK_COMBO_MAX)
    val overPar = (s.turnsPlayed - FightTuning.RANK_PAR_TURNS).coerceAtLeast(0)
    return RankScore(
        hpPercent = hpPercent,
        hpPoints = (hpPercent * FightTuning.RANK_HP_POINTS / 100.0).roundToInt(),
        comboPoints = FightTuning.RANK_COMBO_POINTS * (comboHits - 1).coerceAtLeast(0),
        speedPoints = (FightTuning.RANK_SPEED_POINTS - FightTuning.RANK_SLOW_TURN * overPar).coerceAtLeast(0),
        potionPoints = FightTuning.RANK_POTION * s.potionsUsed,
    )
}

/**
 * The pet after a fight, plus the xp it earned (a win's rank bonus included) and the levels that xp bought,
 * and how a win was [scored] (null after a loss or a run).
 */
data class FightReward(val pet: PetState, val xpGained: Int, val levelsGained: Int, val scored: RankScore? = null) {
    /** The part of [xpGained] the win's rank paid. */
    val bonusXp: Int get() = scored?.rank?.bonusXp ?: 0
}

/**
 * Applies a finished fight to [p]. Every stat is clamped to its range.
 * - Win: hunger +15, mood bonus +15, wins +1, xp +40 plus its rank's bonus (see [rankScore]).
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

        winner == Side.PLAYER -> {
            val scored = rankScore(s)
            withXp(
                p.copy(
                    hunger = clampStat(p.hunger + FightTuning.FIGHT_HUNGER),
                    moodBonus = clampMoodBonus(p.moodBonus + FightTuning.WIN_MOOD_BONUS),
                    wins = p.wins + 1,
                ),
                FightTuning.XP_WIN + (scored?.rank?.bonusXp ?: 0),
                scored,
            )
        }

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

private fun withXp(p: PetState, xp: Int, scored: RankScore? = null): FightReward {
    val r = addXp(p, xp)
    return FightReward(r.pet, xp, r.levelsGained, scored)
}
