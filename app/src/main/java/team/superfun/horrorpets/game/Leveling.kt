package team.superfun.horrorpets.game

import team.superfun.horrorpets.model.PetState

data class XpResult(val pet: PetState, val levelsGained: Int)

/** XP needed to go from [level] to [level] + 1: 100 for 1 to 2, 200 for 2 to 3, and so on. */
fun xpToNext(level: Int): Int = Tuning.XP_PER_LEVEL * level

/** Adds [amount] xp, carrying any overflow into as many level-ups as it pays for. Non-positive amounts change nothing. */
fun addXp(p: PetState, amount: Int): XpResult {
    if (amount <= 0) return XpResult(p, 0)
    var level = p.level
    var xp = p.xp.toLong() + amount
    var gained = 0
    while (xp >= xpToNext(level)) {
        xp -= xpToNext(level)
        level++
        gained++
    }
    return XpResult(p.copy(level = level, xp = xp.toInt()), gained)
}
