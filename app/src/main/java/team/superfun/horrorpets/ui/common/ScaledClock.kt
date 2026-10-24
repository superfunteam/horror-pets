package team.superfun.horrorpets.ui.common

import kotlin.math.floor

/**
 * A clock that runs at a varying speed: each [advance] adds a step of real time scaled by the speed of the moment, so
 * at 0 it holds and at 0.3 it crawls. Fractions of a millisecond carry over, so many short slow steps add up.
 */
internal class ScaledClock {
    private var elapsed = 0.0

    /** Moves on [dtMs] of real time at [scale] speed, and returns the scaled time passed so far, in whole ms. */
    fun advance(dtMs: Long, scale: Float): Long {
        elapsed += dtMs * scale.toDouble()
        return floor(elapsed).toLong()
    }
}
