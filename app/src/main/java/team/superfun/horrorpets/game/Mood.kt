package team.superfun.horrorpets.game

import kotlin.math.roundToInt
import team.superfun.horrorpets.model.PetState

/** Overall mood, 0..100: how fed and how entertained the pet is, plus the transient mood bonus. */
fun mood(p: PetState): Int {
    val raw = Tuning.MOOD_HUNGER_WEIGHT * (Tuning.STAT_MAX - p.hunger) +
        Tuning.MOOD_FUN_WEIGHT * p.funLevel +
        p.moodBonus
    return clampStat(raw).roundToInt()
}
