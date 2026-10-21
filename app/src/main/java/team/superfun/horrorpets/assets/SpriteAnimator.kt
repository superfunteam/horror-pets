package team.superfun.horrorpets.assets

/**
 * The frame to show [elapsedMs] after an animation started, or null once a one-shot has finished
 * (the caller then switches to its next animation).
 *
 * - The timeline is [AnimSpec.durationsMs]; a looping animation wraps around its total length.
 * - Negative elapsed time is the first frame.
 * - Frames with a non-positive duration are skipped. A spec with no positive duration at all has no
 *   timeline: a loop sits on frame 0 and a one-shot counts as already finished (null).
 * - The index is clamped to the strip's last frame in case a manifest lists more durations than frames.
 */
fun frameAt(spec: AnimSpec, elapsedMs: Long): Int? {
    if (elapsedMs < 0) return 0
    val durations = spec.durationsMs
    val total = durations.sumOf { it.coerceAtLeast(0).toLong() }
    if (total <= 0L) return if (spec.loop) 0 else null

    val t = if (spec.loop) elapsedMs % total else elapsedMs
    if (t >= total) return null

    var end = 0L
    for ((index, duration) in durations.withIndex()) {
        end += duration.coerceAtLeast(0)
        if (t < end) return index.coerceAtMost((spec.frames - 1).coerceAtLeast(0))
    }
    return null // unreachable: t < total
}
