package team.superfun.horrorpets.ui.training

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine

/** The most game time one frame may add, so a hitch (a GC pause, a slow frame) can never skip past a call. */
private const val MAX_FRAME_MS = 100L

private const val NANOS_PER_MS = 1_000_000L

/** No frame seen since the clock was made or last paused, so the next frame has nothing to measure from. */
private const val NO_FRAME = Long.MIN_VALUE

/**
 * Training's game time: the real time of the frames shown while the game is not paused, so a game paused while the
 * app is away (or under its "GET READY" beat) picks up exactly where it stopped, with nothing having timed out.
 *
 * The frame clock drives it ([tick], once a frame: see [rememberGameClock]); [nowMs] is the time so far, as snapshot
 * state, so a draw or a layout that reads it follows it. The first frame after a pause adds nothing (the gap since
 * the last frame before it is not game time), and no frame adds more than [MAX_FRAME_MS]. Whether it is [paused] is
 * snapshot state too, for a game that must know when it stops and starts again.
 *
 * Main thread only, like the frame clock that drives it.
 */
class GameClock {
    private val now = mutableLongStateOf(0L)

    /** The game time so far, in ms: 0 until the second frame after the clock starts. */
    val nowMs: Long get() = now.longValue

    /**
     * Whether the clock is paused: from a frame that said so ([tick]) until one that says it isn't. While training
     * holds a game (the app away, then its "GET READY" beat) or shows its results card, this is true: a game holding
     * input that may never be let go (a key whose coming up went to another app) lets go of it as this changes.
     */
    var paused: Boolean by mutableStateOf(false)
        private set

    private var elapsedNanos = 0L
    private var lastFrameNanos = NO_FRAME
    private val waiters = mutableListOf<Waiter>()

    /**
     * One frame, shown at [frameNanos] (the frame clock's time): advances the game time by the time since the last
     * frame, unless [paused] or there was no last frame to measure from. A paused frame forgets the last frame, so the
     * first one after the pause only starts the count again.
     */
    fun tick(frameNanos: Long, paused: Boolean) {
        if (paused != this.paused) this.paused = paused
        if (paused) {
            lastFrameNanos = NO_FRAME
            return
        }
        val last = lastFrameNanos
        lastFrameNanos = frameNanos
        if (last == NO_FRAME) return
        // Nanoseconds add up exactly, so 60 Hz frames of 16.67 ms make a second, not sixty times 16 ms.
        elapsedNanos += (frameNanos - last).coerceIn(0L, MAX_FRAME_MS * NANOS_PER_MS)
        val ms = elapsedNanos / NANOS_PER_MS
        if (ms == now.longValue) return
        now.longValue = ms
        wake(ms)
    }

    /** Suspends until [ms] of game time have passed (at once when [ms] is not positive). Cancellable. */
    suspend fun waitMs(ms: Long) {
        if (ms <= 0L) return
        val until = nowMs + ms
        suspendCancellableCoroutine { continuation ->
            val waiter = Waiter(until, continuation)
            waiters += waiter
            continuation.invokeOnCancellation { waiters -= waiter }
        }
    }

    /** Suspends until the game time is [ms] (at once when it already is). Cancellable. */
    suspend fun waitUntil(ms: Long) = waitMs(ms - nowMs)

    /** Resumes every wait that is over at [ms]. */
    private fun wake(ms: Long) {
        if (waiters.isEmpty()) return
        val due = waiters.filter { it.untilMs <= ms }
        if (due.isEmpty()) return
        waiters -= due.toSet()
        for (waiter in due) waiter.continuation.resume(Unit)
    }

    private class Waiter(val untilMs: Long, val continuation: CancellableContinuation<Unit>)
}

/**
 * A [GameClock] that the frame clock ticks, frozen while [paused]. While paused it stops asking for frames
 * altogether, so a game waiting on its results card or on the app coming back costs nothing.
 */
@Composable
fun rememberGameClock(paused: Boolean): GameClock {
    val clock = remember { GameClock() }
    val pausedNow = rememberUpdatedState(paused)
    LaunchedEffect(clock) {
        while (true) {
            var paused = false
            withFrameNanos {
                paused = pausedNow.value
                clock.tick(it, paused)
            }
            // That frame told the clock it was paused, so the first frame after the pause adds nothing.
            if (paused) snapshotFlow { pausedNow.value }.first { !it }
        }
    }
    return clock
}
