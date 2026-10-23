package team.superfun.horrorpets.ui.training

/** Fast, then easing in to 1 at [u] = 1 (clamped). */
internal fun easeOut(u: Float): Float {
    val rest = 1f - u.coerceIn(0f, 1f)
    return 1f - rest * rest
}

/**
 * How big a thing popping up is [sinceMs] after it began: up from nothing over [durationMs], overshooting a little
 * (a back ease out) before it settles at full size. Nothing before it began.
 */
internal fun popIn(sinceMs: Long, durationMs: Long): Float {
    val u = (sinceMs / durationMs.toFloat()).coerceIn(0f, 1f) - 1f
    return 1f + 2.70158f * u * u * u + 1.70158f * u * u
}
