package team.superfun.horrorpets.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which switches pick the new track up where the old one is ([keepsPlace]), and how the two are brought in step. */
class MusicTracksTest {
    @Test fun onlyTheFightTracksKeepTheirPlace() {
        assertTrue(keepsPlace("fight", "fight_intense"))
        assertTrue("easing back after an intense fight keeps it too", keepsPlace("fight_intense", "fight"))
        assertFalse(keepsPlace("fight", "fight"))
        assertFalse(keepsPlace("fight_intense", "fight_intense"))
        assertFalse(keepsPlace("switcher", "fight"))
        assertFalse(keepsPlace("switcher", "fight_intense"))
        assertFalse(keepsPlace("fight_intense", "jason"))
        assertFalse(keepsPlace(null, "fight_intense"))
        assertFalse(keepsPlace("fight", null))
        assertFalse(keepsPlace(null, null))
    }

    @Test fun theLagIsSignedAndTakesTheShortWayRoundTheLoop() {
        assertEquals("behind", 200L, loopLag(targetMs = 5_000, positionMs = 4_800, lengthMs = 64_000))
        assertEquals("ahead", -200L, loopLag(targetMs = 4_800, positionMs = 5_000, lengthMs = 64_000))
        assertEquals(0L, loopLag(targetMs = 5_000, positionMs = 5_000, lengthMs = 64_000))
        // The target has wrapped past the loop's end and this one has not yet: behind by 200, not ahead by 63.8 s.
        assertEquals(200L, loopLag(targetMs = 100, positionMs = 63_900, lengthMs = 64_000))
        // This one has wrapped and the target has not: ahead by 200.
        assertEquals(-200L, loopLag(targetMs = 63_900, positionMs = 100, lengthMs = 64_000))
        // Half a loop apart is as far as it gets either way.
        assertEquals(32_000L, loopLag(targetMs = 32_000, positionMs = 0, lengthMs = 64_000))
        assertEquals(32_000L - 1, loopLag(targetMs = 0, positionMs = 32_001, lengthMs = 64_000))
        // No length known yet: the plain difference.
        assertEquals(-63_800L, loopLag(targetMs = 100, positionMs = 63_900, lengthMs = 0))
    }

    @Test fun aNudgeMakesUpTheLagInAboutNudgeMsWithinItsClamps() {
        fun assertNudge(speed: Float, durationMs: Long, lagMs: Long) {
            val n = nudgeFor(lagMs)
            assertEquals("speed for $lagMs", speed, n.speed, 1e-6f)
            assertEquals("duration for $lagMs", durationMs, n.durationMs)
            assertEquals("it makes up $lagMs", lagMs.toDouble(), ((n.speed - 1f) * n.durationMs).toDouble(), 0.5)
        }
        assertNudge(1.4f, 400, lagMs = 160) // behind: faster, for NUDGE_MS
        assertNudge(0.6f, 400, lagMs = -160) // ahead: slower
        assertNudge(1f + MAX_NUDGE, 2_000, lagMs = 1_000) // a big gap: no faster than MAX_NUDGE, for longer
        assertNudge(1f - MAX_NUDGE, 2_000, lagMs = -1_000)
        assertNudge(1f + MIN_NUDGE, 200, lagMs = 10) // a small one: at least MIN_NUDGE, for less
        assertNudge(1f - MIN_NUDGE, 200, lagMs = -10)
    }
}
