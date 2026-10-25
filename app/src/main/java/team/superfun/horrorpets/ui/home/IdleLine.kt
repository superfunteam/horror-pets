package team.superfun.horrorpets.ui.home

import kotlin.random.Random

// When Home's killer says something of its own (one of its idle lines, see `killerLines`), kept free of Compose so it
// can be tested: every IDLE_LINE_MIN_MS to IDLE_LINE_MAX_MS of quiet time, drawn afresh each time, and only while
// nothing else is going on.

/** The shortest wait for the next idle line. */
const val IDLE_LINE_MIN_MS = 25_000L

/** The longest wait for the next idle line. */
const val IDLE_LINE_MAX_MS = 45_000L

/** When the next idle line is due, drawn from [rng]: [IDLE_LINE_MIN_MS] to [IDLE_LINE_MAX_MS] after [nowMs]. */
fun nextIdleLineAt(nowMs: Long, rng: Random): Long = nowMs + rng.nextLong(IDLE_LINE_MIN_MS, IDLE_LINE_MAX_MS + 1)

/**
 * Whether the killer says its idle line now: once its time ([nextAtMs]) has come, and only when it is [quiet] (no gag,
 * walk, bubble or sleep). A line whose time comes while it is busy is not said late: the caller draws the next time.
 */
fun idleLineDue(nowMs: Long, nextAtMs: Long, quiet: Boolean): Boolean = quiet && nowMs >= nextAtMs
