package team.superfun.horrorpets.fight

/**
 * Every balance number for fights. Lives apart from [team.superfun.horrorpets.game.Tuning], which
 * covers the care and decay model. Signed constants (like `LOSS_FUN`) are deltas to add.
 */
object FightTuning {
    // Hit points: (BASE_HP + HP_PER_LEVEL * level) * lerp(1.0, STARVING_HP_FACTOR, hunger / 100),
    // times the pet's passive HP multiplier (JASON_HP_MULT for Jason), rounded.
    const val BASE_HP = 60
    const val HP_PER_LEVEL = 8
    const val STARVING_HP_FACTOR = 0.7
    const val JASON_HP_MULT = 1.2

    // Damage: (BASE_DAMAGE + strength / STRENGTH_DIVISOR) * variance * move multiplier * crit
    // * the defender's damage-taken multiplier, where variance is uniform in 1 +- DAMAGE_VARIANCE,
    // rounded once at the end, and at least 1. The move multiplier includes the attacker's passive
    // (CHUCKY_STRIKE_MULT on a STRIKE, LEATHERFACE_HEAVY_MULT on a HEAVY), so it applies before the crit.
    const val BASE_DAMAGE = 8.0
    const val STRENGTH_DIVISOR = 8.0
    const val DAMAGE_VARIANCE = 0.15
    const val STRIKE_MULT = 1.0
    const val HEAVY_MULT = 1.8
    const val COUNTER_MULT = 0.5
    const val PINHEAD_COUNTER_MULT = 1.0
    const val SPECIAL_MULT = 2.5
    const val CHUCKY_STRIKE_MULT = 1.15 // Chucky's STRIKE (not his counters, heavies or special)
    const val MICHAEL_DAMAGE_TAKEN_MULT = 0.85 // everything that hits Michael, after crits
    const val LEATHERFACE_HEAVY_MULT = 1.2 // Leatherface's HEAVY (not his strikes, counters or special)

    // Crits: chance = CRIT_BASE + mood / 100 * CRIT_MOOD_SPAN + the pet's passive bonus (FREDDY_CRIT_BONUS).
    const val CRIT_BASE = 0.05
    const val CRIT_MOOD_SPAN = 0.15
    const val FREDDY_CRIT_BONUS = 0.08
    const val CRIT_MULT = 1.75

    // Misses apply to STRIKE and HEAVY only; SPECIAL and counters never miss.
    const val MISS_STRIKE = 0.08
    const val MISS_HEAVY = 0.15
    const val GHOSTFACE_EVADE_BONUS = 0.07 // added to the miss chance of attacks against Ghostface

    // Special meter: METER_PER_HIT per landed hit (counters included), SPECIAL needs METER_MAX.
    const val METER_PER_HIT = 25
    const val METER_MAX = 100

    // Potions: the player's, so many a fight, each healing a random share of max HP between these two
    const val POTIONS_PER_FIGHT = 2
    const val POTION_HEAL_MIN = 0.5
    const val POTION_HEAL_MAX = 1.0

    // Opponent: level is the player's +- OPPONENT_LEVEL_SPREAD (at least 1), strength the player's
    // +- OPPONENT_STRENGTH_SPREAD (clamped), a fixed hunger (for HP) and a fixed mood (for crits).
    const val OPPONENT_LEVEL_SPREAD = 1
    const val OPPONENT_STRENGTH_SPREAD = 10
    const val OPPONENT_HUNGER = 30.0
    const val OPPONENT_MOOD = 50

    // CPU brain
    const val CPU_SPECIAL_CHANCE = 0.7 // with a full meter
    const val CPU_FEINT_CHANCE = 0.25 // a STRIKE, HEAVY or BLOCK shows a false tell, plus the pet's passive bonus
    const val GHOSTFACE_FEINT_BONUS = 0.2
    const val CPU_COUNTER_CHANCE = 0.4 // against the player's most-used move
    const val CPU_WEIGHT_STRIKE = 40
    const val CPU_WEIGHT_HEAVY = 30
    const val CPU_WEIGHT_BLOCK = 30

    // Rewards (applied on top of the pet's stats, then clamped)
    const val FIGHT_HUNGER = 15.0 // win or loss, not run
    const val XP_WIN = 40
    const val XP_LOSS = 15
    const val WIN_MOOD_BONUS = 15.0
    const val LOSS_FUN = -5.0
    const val RUN_MOOD_PENALTY = -10.0
}
