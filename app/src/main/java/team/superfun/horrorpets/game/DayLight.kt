package team.superfun.horrorpets.game

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.min

/** The light over Home at an hour of the local day: the scene is tinted for all but [DAY]. */
enum class DayLight { DAWN, DAY, DUSK, NIGHT }

/** The light at [hour] (0..23) of the local day: dawn from 05:00, day from 07:00, dusk from 18:00, night from 21:00. */
fun dayLightAt(hour: Int): DayLight = when (hour) {
    in 5..6 -> DayLight.DAWN
    in 7..17 -> DayLight.DAY
    in 18..20 -> DayLight.DUSK
    else -> DayLight.NIGHT
}

/** Whether the killers sleep through [hour] (0..23) of the local day: from 23:00 until 06:00. */
fun isSleepHour(hour: Int): Boolean = hour >= 23 || hour < 6

/** The hour of the day (0..23) that [nowMs] is on a clock in [zone]. */
fun localHour(nowMs: Long, zone: TimeZone): Int =
    Calendar.getInstance(zone).apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)

/**
 * How many hours of `[fromMs, toMs)` fall in sleep hours (see [isSleepHour]) on a clock in [zone]; 0.0 when [toMs]
 * is not after [fromMs].
 *
 * The stretch is walked one local hour at a time, the partial first and last hours counting by the millisecond. Each
 * step runs on the epoch-ms axis to the next whole local hour, so a clock change (an hour that is gone, or one that
 * happens twice) counts for exactly the real time it took: the result is never negative and never more than the
 * stretch is long.
 *
 * It costs one step per local hour of the stretch, so callers bound it: [applyElapsed], the only one, caps the
 * stretch at [Tuning.MAX_ELAPSED_MS] (30 days, some 720 steps).
 */
fun sleepHoursBetween(fromMs: Long, toMs: Long, zone: TimeZone): Double {
    if (toMs <= fromMs) return 0.0
    val clock = Calendar.getInstance(zone)
    var sleptMs = 0L
    var t = fromMs
    while (t < toMs) {
        clock.timeInMillis = t
        // Read how far into its local hour t is rather than setting the minute to 0: during a fall-back, 01:00 is
        // two moments, and setting a field could land on the other one, behind t.
        val intoHourMs = clock.get(Calendar.MINUTE) * Tuning.MINUTE_MS +
            clock.get(Calendar.SECOND) * 1_000L +
            clock.get(Calendar.MILLISECOND)
        val next = min(t + (Tuning.HOUR_MS - intoHourMs), toMs)
        if (isSleepHour(clock.get(Calendar.HOUR_OF_DAY))) sleptMs += next - t
        t = next
    }
    return sleptMs.toDouble() / Tuning.HOUR_MS
}
