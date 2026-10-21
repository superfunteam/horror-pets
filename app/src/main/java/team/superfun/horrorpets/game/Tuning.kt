package team.superfun.horrorpets.game

/** Every balance number for the game model. Care and fight constants are added alongside as they land. */
object Tuning {
    // Time
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS

    // Stat bounds
    const val STAT_MIN = 0.0
    const val STAT_MAX = 100.0
    const val MOOD_BONUS_MAX = 30.0

    // Real-time decay (per hour of wall-clock time away)
    const val HUNGER_PER_HOUR = 4.0
    const val FUN_PER_HOUR = 3.0
    const val STRENGTH_PER_HOUR = 1.0 / 6
    const val MOOD_BONUS_DECAY_PER_HOUR = 5.0

    // Fake death: the pet "dies" if left alone this long, and elapsed time is capped.
    const val FAKE_DEATH_AFTER_MS = 7 * DAY_MS
    const val MAX_ELAPSED_MS = 30 * DAY_MS
    const val FAKE_DEATH_HUNGER = 90.0
    const val FAKE_DEATH_FUN = 10.0
    const val FAKE_DEATH_STRENGTH_FACTOR = 0.8

    // Mood = HUNGER_WEIGHT * (100 - hunger) + FUN_WEIGHT * fun + moodBonus
    const val MOOD_HUNGER_WEIGHT = 0.4
    const val MOOD_FUN_WEIGHT = 0.6

    // Status thresholds
    const val STARVING_AT = 85.0 // hunger >=
    const val HUNGRY_AT = 60.0 // hunger >=
    const val BORED_AT = 30.0 // fun <=
    const val GRUMPY_BELOW = 30 // mood <

    // "Welcome back" toast is only worth showing after this long away.
    const val TOAST_MIN_AWAY_MS = 10 * MINUTE_MS

    // Clock sanity: 2024-01-01T00:00:00Z. A device clock earlier than this has not synced yet
    // (typical right after a TV boots), so it is treated as unknown: no decay, no lastSeen write.
    const val MIN_VALID_EPOCH_MS = 1_704_067_200_000L

    // Leveling: going from level L to L+1 costs XP_PER_LEVEL * L xp.
    const val XP_PER_LEVEL = 100

    // Care: feed (refused when hunger is already below FEED_REFUSE_BELOW)
    const val FEED_HUNGER = -35.0
    const val FEED_REFUSE_BELOW = 10.0

    // Care: play
    const val PLAY_FUN = 25.0
    const val PLAY_HUNGER = 8.0
    const val PLAY_MOOD_BONUS = 5.0

    // Care: train (refused when hunger is at or above TRAIN_REFUSE_AT)
    const val TRAIN_STRENGTH = 6.0
    const val TRAIN_HUNGER = 12.0
    const val TRAIN_FUN = -5.0
    const val TRAIN_REFUSE_AT = 85.0

    // XP awarded per care action
    const val XP_TRAIN = 10
    const val XP_PLAY = 5
    const val XP_FEED = 0
}

internal fun clampStat(v: Double): Double = v.coerceIn(Tuning.STAT_MIN, Tuning.STAT_MAX)

internal fun clampMoodBonus(v: Double): Double = v.coerceIn(-Tuning.MOOD_BONUS_MAX, Tuning.MOOD_BONUS_MAX)
