package team.superfun.horrorpets.data

/** Source of "now" in epoch milliseconds. Injected so game logic and tests never read the wall clock directly. */
fun interface Clock {
    fun nowMs(): Long
}

/** The production clock: real wall-clock time. */
val WallClock = Clock { System.currentTimeMillis() }

/**
 * Debug time travel: reports [base] time shifted by [offsetMs]. A positive offset is the future
 * (pets decay while "away"), a negative one the past. The offset can be changed at any time.
 */
class OffsetClock(private val base: Clock, @Volatile var offsetMs: Long = 0) : Clock {
    override fun nowMs(): Long = base.nowMs() + offsetMs
}
