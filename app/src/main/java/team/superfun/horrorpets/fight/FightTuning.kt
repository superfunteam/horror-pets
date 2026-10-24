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
    // (CHUCKY_STRIKE_MULT on a STRIKE, LEATHERFACE_HEAVY_MULT or JAWS_HEAVY_MULT on a HEAVY), so it applies before the crit.
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
    const val JAWS_HEAVY_MULT = 1.15 // Jaws's HEAVY, his Apex passive (not his strikes, counters or special)

    // Crits: chance = CRIT_BASE + mood / 100 * CRIT_MOOD_SPAN + the pet's passive bonus (FREDDY_CRIT_BONUS).
    const val CRIT_BASE = 0.05
    const val CRIT_MOOD_SPAN = 0.15
    const val FREDDY_CRIT_BONUS = 0.08
    const val CRIT_MULT = 1.75

    // Misses apply to STRIKE and HEAVY only; SPECIAL and counters never miss.
    const val MISS_STRIKE = 0.08
    const val MISS_HEAVY = 0.15
    const val GHOSTFACE_EVADE_BONUS = 0.07 // added to the miss chance of attacks against Ghostface

    // Combos: a hit does COMBO_STEP more damage for each turn in a row its attacker has landed a hit
    // without being hit, counting up to COMBO_MAX_STEPS of them (+45%).
    const val COMBO_STEP = 0.15
    const val COMBO_MAX_STEPS = 3

    // Special effects: a BLEED lasts BLEED_TURNS turns, each costing BLEED_SHARE of max HP (at least 1); a
    // DRAIN heals DRAIN_SHARE of the special's damage; a DAZE spoils the target's next turn.
    const val BLEED_TURNS = 3
    const val BLEED_SHARE = 0.06
    const val DRAIN_SHARE = 0.5

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
    const val CPU_COUNTER_CHANCE = 0.25 // against the player's most-used move
    const val CPU_WEIGHT_STRIKE = 40
    const val CPU_WEIGHT_HEAVY = 30
    const val CPU_WEIGHT_BLOCK = 30

    // Battle rank (wins only): points for HP left (RANK_HP_POINTS at full), for the best combo (RANK_COMBO_POINTS
    // per hit past the first, counting up to RANK_COMBO_MAX hits), for speed (RANK_SPEED_POINTS within
    // RANK_PAR_TURNS turns, RANK_SLOW_TURN fewer for each turn over), RANK_POTION for each potion drunk.
    const val RANK_HP_POINTS = 50
    const val RANK_COMBO_POINTS = 10
    const val RANK_COMBO_MAX = 4
    const val RANK_SPEED_POINTS = 20
    const val RANK_PAR_TURNS = 5
    const val RANK_SLOW_TURN = 4
    const val RANK_POTION = -10

    // Rewards (applied on top of the pet's stats, then clamped)
    const val FIGHT_HUNGER = 15.0 // win or loss, not run
    const val XP_WIN = 40
    const val XP_LOSS = 15
    const val WIN_MOOD_BONUS = 15.0
    const val LOSS_FUN = -5.0
    const val RUN_MOOD_PENALTY = -10.0

    // The fight screen's clock: a hit that lands freezes both fighters for HIT_STOP_STRIKE_MS (a STRIKE) or
    // HIT_STOP_HEAVY_MS (a HEAVY, a crit, a counter or a special), and the blow that empties a bar plays at KO_SLOW
    // speed for KO_SLOW_MS.
    const val HIT_STOP_STRIKE_MS = 70L
    const val HIT_STOP_HEAVY_MS = 110L
    const val KO_SLOW = 0.3f
    const val KO_SLOW_MS = 900L
}
