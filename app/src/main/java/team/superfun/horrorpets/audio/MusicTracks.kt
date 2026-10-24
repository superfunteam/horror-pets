package team.superfun.horrorpets.audio

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Whether switching the music from [from] to [to] picks the new track up where the old one is, rather than from its
 * start: the fight music hardening into `fight_intense`, or easing back into `fight` after an intense fight. The two
 * share tempo, bars and length, so the beat carries on through the crossfade. Every other switch starts the new
 * track over.
 */
fun keepsPlace(from: String?, to: String?): Boolean =
    (from == "fight" && to == "fight_intense") || (from == "fight_intense" && to == "fight")

/** How long a nudge (see [nudgeFor]) aims to take. */
internal const val NUDGE_MS = 400f

/** The least a nudge moves the speed off 1. */
internal const val MIN_NUDGE = 0.05f

/** The most a nudge moves the speed off 1. */
internal const val MAX_NUDGE = 0.5f

/**
 * How many ms a player at [positionMs] is behind one at [targetMs] in the same loop of [lengthMs] (negative: ahead),
 * the shorter way round, so two either side of the loop's end are a few ms apart, not most of a loop. A [lengthMs] not
 * known yet (0 or less) gives the plain difference.
 */
internal fun loopLag(targetMs: Long, positionMs: Long, lengthMs: Long): Long {
    val gap = targetMs - positionMs
    if (lengthMs <= 0) return gap
    val wrapped = gap.mod(lengthMs)
    return if (wrapped > lengthMs / 2) wrapped - lengthMs else wrapped
}

/** A playback [speed] (1 is normal) to hold for [durationMs]. */
internal data class Nudge(val speed: Float, val durationMs: Long)

/**
 * The nudge that makes up [lagMs] (behind: faster; ahead, a negative lag: slower) in about [NUDGE_MS]: the speed moves
 * off 1 by between [MIN_NUDGE] and [MAX_NUDGE], and is held for just as long as that takes, so a small gap takes less
 * than [NUDGE_MS] and a big one more.
 */
internal fun nudgeFor(lagMs: Long): Nudge {
    val off = (abs(lagMs) / NUDGE_MS).coerceIn(MIN_NUDGE, MAX_NUDGE)
    return Nudge(speed = if (lagMs > 0) 1f + off else 1f - off, durationMs = (abs(lagMs) / off).roundToLong())
}
