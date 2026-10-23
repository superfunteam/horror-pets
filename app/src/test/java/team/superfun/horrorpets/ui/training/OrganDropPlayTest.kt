package team.superfun.horrorpets.ui.training

import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.game.training.CATCH_HALF
import team.superfun.horrorpets.game.training.DROP_RANGE
import team.superfun.horrorpets.game.training.Drop
import team.superfun.horrorpets.game.training.FIRST_LAND_MS
import team.superfun.horrorpets.game.training.ORGAN_KINDS
import team.superfun.horrorpets.game.training.RUN_SPEED
import team.superfun.horrorpets.game.training.START_X
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.game.training.nextDrop

/** Organ Drop's play, on a [GameClock] fed frames by hand, in a play area [W] x [H]. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class OrganDropPlayTest {
    /** A game being played: its clock, the sounds it made, its score, and the scores it ended on. */
    private class Rig(val seed: Int) {
        val clock = GameClock()
        val sounds = mutableListOf<String>()
        val score = mutableIntStateOf(0)
        val overs = mutableListOf<Int>()
        val play = OrganDropPlay(clock, Sink(sounds), score, Random(seed))
        val lane = DropLane.of(W, H, floorY = 480f, bodyHeight = 276f)
        private var frameNanos = 5_000_000_000L

        init {
            play.lane = lane
            clock.tick(frameNanos, paused = false) // the first frame only starts the count
        }

        /** One frame [ms] after the last (at most 100: a longer one is capped). */
        fun frame(ms: Long = 16L) {
            frameNanos += ms * 1_000_000L
            clock.tick(frameNanos, paused = false)
        }

        /** The app goes away: a paused frame, and the game lets go as it sees the clock stop. */
        fun away() {
            frameNanos += 16_000_000L
            clock.tick(frameNanos, paused = true)
            assertTrue(clock.paused)
            play.letGo()
        }

        /** Back three seconds later: the first frame only starts the count again, and the game lets go once more. */
        fun back() {
            frameNanos += 3_000_000_000L
            clock.tick(frameNanos, paused = false)
            assertFalse(clock.paused)
            play.letGo()
        }

        val x: Float get() = play.killerX(clock.nowMs)
        val anim: Anim get() = play.petAnim(clock.nowMs)
    }

    private class Sink(val names: MutableList<String>) : SoundSink {
        override fun sfx(name: String) {
            names += name
        }

        override fun voice(names: List<String>) = Unit
    }

    /** Starts [rig]'s game (cancelled as the test ends: a game still going runs on forever). */
    private fun TestScope.start(rig: Rig) {
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { rig.play.playOut { rig.overs += it } }
        runCurrent()
    }

    /** Frames on for exactly [ms] of game time, [check]ing after each. */
    private fun TestScope.advance(rig: Rig, ms: Long, check: () -> Unit = {}) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            rig.frame(step)
            runCurrent()
            check()
            left -= step
        }
    }

    /** Frames on until [done] (within [limitMs] of game time). */
    private fun TestScope.until(rig: Rig, limitMs: Long = 10_000L, check: () -> Unit = {}, done: () -> Boolean) {
        val until = rig.clock.nowMs + limitMs
        while (!done()) {
            if (rig.clock.nowMs > until) throw AssertionError("not within $limitMs ms")
            rig.frame()
            runCurrent()
            check()
        }
    }

    /** A finger on the next organ to land, held there until it does: caught. */
    private fun TestScope.catchNext(rig: Rig, check: () -> Unit = {}) {
        val next = rig.play.landingNext!!
        val before = rig.play.caught
        rig.play.touch(next.drop.x * W)
        until(rig, check = check) { next.caughtAtMs != Long.MAX_VALUE || rig.play.missed != null }
        assertEquals("caught at its landing", next.landAtMs, next.caughtAtMs)
        assertEquals(before + 1, rig.play.caught)
    }

    /** The first [n] drops [rig]'s game deals, the same way. */
    private fun scheduleOf(rig: Rig, n: Int): List<Drop> {
        val rng = Random(rig.seed)
        val drops = ArrayList<Drop>(n)
        for (i in 0 until n) drops += nextDrop(drops.lastOrNull(), i, rng)
        return drops
    }

    @Test
    fun catchesCountAndKeepTheScore() = runTest {
        val rig = Rig(1)
        start(rig)
        assertEquals(DropPhase.PLAY, rig.play.phase)
        assertEquals(START_X, rig.x, 0f)
        assertEquals(Anim.IDLE, rig.anim)
        assertEquals(rig.play.startMs + FIRST_LAND_MS, rig.play.landingNext!!.landAtMs)

        // Every organ in the air is dealt and drawn, none showing late; more of them as it goes.
        val schedule = scheduleOf(rig, 60)
        var mostInAir = 0
        val allShown = {
            val now = rig.clock.nowMs
            val inAir = schedule.indices.filter { i ->
                val landAt = rig.play.startMs + schedule[i].landMs
                landAt - schedule[i].fallMs <= now && now < landAt
            }
            val dealt = rig.play.falling.map { it.index }
            assertTrue("at $now: $inAir in the air, $dealt dealt", dealt.containsAll(inAir))
            for (i in inAir) assertEquals(schedule[i], rig.play.falling.first { it.index == i }.drop)
            if (rig.play.caught >= 25) mostInAir = maxOf(mostInAir, inAir.size)
        }
        repeat(32) { catchNext(rig, allShown) }
        assertEquals(3, mostInAir)

        assertEquals(32, rig.play.caught)
        assertEquals(32, rig.score.intValue)
        assertEquals(List(32) { "squish" }, rig.sounds)
        assertEquals(DropPhase.PLAY, rig.play.phase)
        assertTrue(rig.overs.isEmpty())
        // The last catch's "+1" floats up over where the pet was; the old ones are gone.
        val plus = rig.play.pluses.last()
        assertTrue(rig.clock.nowMs - plus.atMs in 0L..16L)
        assertEquals(rig.x, plus.x, 1e-4f)
        assertTrue(rig.play.pluses.size <= 2)
        // Organs caught go once popped in: only a handful are ever kept.
        assertTrue(rig.play.falling.size <= 6)
    }

    @Test
    fun aMissEndsItOnceWithTheCatchesSoFar() = runTest {
        val rig = Rig(2)
        start(rig)
        repeat(3) { catchNext(rig) }
        val next = rig.play.landingNext!!
        // Just before it lands, the pet runs off the other way, still running as it gets past.
        advance(rig, next.landAtMs - 120L - rig.clock.nowMs)
        val from = rig.x
        val away = if (next.drop.x > from) DROP_RANGE.start else DROP_RANGE.endInclusive
        assertTrue("room to run on past the landing", abs(away - from) > 0.2f)
        rig.play.touch(away * W)
        until(rig) { rig.play.phase != DropPhase.PLAY }
        assertEquals(DropPhase.MISSED, rig.play.phase)
        assertSame(next, rig.play.missed)
        assertEquals(next.landAtMs, rig.play.endAtMs)
        assertNull(rig.play.landingNext)
        assertEquals(listOf("squish", "squish", "squish"), rig.sounds)
        assertEquals(3, rig.score.intValue)
        // It stopped where it was as the organ got past, standing.
        val stoppedAt = from + sign(away - from) * RUN_SPEED * 0.12f
        assertEquals(stoppedAt, rig.x, 1e-4f)
        assertEquals(Anim.IDLE, rig.anim)

        // The organ falls on to the floor, as far again as the lane has it: then the splat and the pet's FAIL.
        val splatAt = next.landAtMs + (next.drop.fallMs * rig.lane.belowShare).roundToLong()
        assertEquals(splatAt, rig.play.splatAtMs)
        advance(rig, 16L)
        assertTrue(rig.play.fallOf(next, rig.clock.nowMs) > 1f)
        assertEquals(DropPhase.MISSED, rig.play.phase)
        until(rig) { rig.play.phase == DropPhase.OVER }
        assertTrue(rig.clock.nowMs - splatAt in 0L..16L)
        assertEquals(listOf("squish", "squish", "squish", "splat"), rig.sounds)
        assertEquals(Anim.FAIL, rig.anim)
        assertEquals(stoppedAt, rig.x, 0f)

        // Over 1.3 s after the splat, once, with the catches.
        advance(rig, splatAt + 1_250L - rig.clock.nowMs)
        assertTrue(rig.overs.isEmpty())
        advance(rig, 100L)
        assertEquals(listOf(3), rig.overs)
        advance(rig, 3_000L)
        assertEquals(listOf(3), rig.overs)
        assertEquals(4, rig.sounds.size)
        assertEquals(3, rig.score.intValue)
    }

    @Test
    fun inputAfterTheEndDoesNothingAndTheOrgansInTheAirStop() = runTest {
        val rig = Rig(3)
        start(rig)
        repeat(12) { catchNext(rig) }
        val next = rig.play.landingNext!!
        rig.play.touch(if (next.drop.x > 0.5f) 0f else W)
        until(rig) { rig.play.missed != null }
        val end = rig.play.endAtMs
        val stoppedAt = rig.x
        // Others were on their way down as it got past: they stay where they were then.
        val others = rig.play.falling.filter { it !== next && it.caughtAtMs == Long.MAX_VALUE && it.appearAtMs < end }
        assertTrue(others.isNotEmpty())
        val heldAt = others.map { rig.play.fallOf(it, end) }
        assertTrue(heldAt.all { it > 0f && it < 1f })

        for (key in TrainingKey.entries) {
            rig.play.key(key, down = true)
            rig.play.key(key, down = false)
        }
        rig.play.key(TrainingKey.LEFT, down = true)
        rig.play.touch(0f)
        rig.play.touch(W)
        advance(rig, 500L)
        rig.play.lift()
        rig.play.letGo()
        until(rig) { rig.overs.isNotEmpty() }
        advance(rig, 2_000L)
        assertEquals(stoppedAt, rig.x, 0f)
        assertEquals(heldAt, others.map { rig.play.fallOf(it, rig.clock.nowMs) })
        assertEquals(12, rig.play.caught)
        assertEquals(listOf(12), rig.overs)
        assertEquals(List(12) { "squish" } + "splat", rig.sounds)
    }

    @Test
    fun theArrowsRunThePetWhileHeld() = runTest {
        val rig = Rig(4)
        start(rig)
        val eps = 1e-4f
        rig.play.key(TrainingKey.RIGHT, down = true)
        advance(rig, 200L)
        assertEquals(START_X + RUN_SPEED * 0.2f, rig.x, eps)
        assertEquals(Anim.RUN_RIGHT, rig.anim)
        // The held key's repeats change nothing: on at the same speed.
        repeat(3) {
            advance(rig, 32L)
            rig.play.key(TrainingKey.RIGHT, down = true)
        }
        assertEquals(START_X + RUN_SPEED * 0.296f, rig.x, eps)
        // Let go: it stops there.
        rig.play.key(TrainingKey.RIGHT, down = false)
        val stopped = rig.x
        advance(rig, 100L)
        assertEquals(stopped, rig.x, 0f)
        assertEquals(Anim.IDLE, rig.anim)
        // Left, then Right pressed over it (the latest wins), then Right let go (back to Left, still held).
        rig.play.key(TrainingKey.LEFT, down = true)
        advance(rig, 100L)
        assertEquals(stopped - RUN_SPEED * 0.1f, rig.x, eps)
        assertEquals(Anim.RUN_LEFT, rig.anim)
        rig.play.key(TrainingKey.RIGHT, down = true)
        advance(rig, 50L)
        assertEquals(stopped - RUN_SPEED * 0.05f, rig.x, eps)
        assertEquals(Anim.RUN_RIGHT, rig.anim)
        rig.play.key(TrainingKey.RIGHT, down = false)
        assertEquals(Anim.RUN_LEFT, rig.anim)
        // Up, Down and OK don't steer.
        rig.play.key(TrainingKey.OK, down = true)
        rig.play.key(TrainingKey.UP, down = true)
        assertEquals(Anim.RUN_LEFT, rig.anim)
        // Held into the range's end, it stands there.
        until(rig, limitMs = 900L) { rig.x <= DROP_RANGE.start }
        assertEquals(DROP_RANGE.start, rig.x, 0f)
        advance(rig, 32L)
        assertEquals(DROP_RANGE.start, rig.x, 0f)
        assertEquals(Anim.IDLE, rig.anim)
        assertTrue("all inside the first organ's fall", rig.clock.nowMs < rig.play.startMs + FIRST_LAND_MS)
    }

    @Test
    fun aFingerRunsThePetToItAndStopsThere() = runTest {
        val rig = Rig(5)
        start(rig)
        val eps = 1e-4f
        rig.play.touch(0.8f * W)
        advance(rig, 100L)
        assertEquals(START_X + RUN_SPEED * 0.1f, rig.x, eps)
        assertEquals(Anim.RUN_RIGHT, rig.anim)
        // There at 0.33 s, and no farther: standing under the finger.
        advance(rig, 300L)
        assertEquals(0.8f, rig.x, 0f)
        assertEquals(Anim.IDLE, rig.anim)
        // A finger resting a few px off doesn't set it running...
        rig.play.touch(0.8f * W + 5f)
        advance(rig, 50L)
        assertEquals(0.8f, rig.x, 0f)
        assertEquals(Anim.IDLE, rig.anim)
        // ...but moved farther it does, and back the other way it turns.
        rig.play.touch(0.83f * W)
        advance(rig, 16L)
        assertEquals(Anim.RUN_RIGHT, rig.anim)
        advance(rig, 32L)
        assertEquals(0.83f, rig.x, eps)
        rig.play.touch(0.5f * W)
        advance(rig, 100L)
        assertEquals(0.83f - RUN_SPEED * 0.1f, rig.x, eps)
        assertEquals(Anim.RUN_LEFT, rig.anim)
        // Lifted: it stops where it is.
        rig.play.lift()
        val stopped = rig.x
        advance(rig, 100L)
        assertEquals(stopped, rig.x, 0f)
        assertEquals(Anim.IDLE, rig.anim)
        // A finger past the range's end runs it to the end, no farther.
        rig.play.touch(0f)
        until(rig, limitMs = 900L) { rig.anim == Anim.IDLE }
        assertEquals(DROP_RANGE.start, rig.x, 0f)
        // The finger wins over an arrow held while it is down; lifted, the arrow runs it.
        rig.play.key(TrainingKey.RIGHT, down = true)
        advance(rig, 50L)
        assertEquals(DROP_RANGE.start, rig.x, 0f)
        rig.play.lift()
        advance(rig, 50L)
        assertEquals(DROP_RANGE.start + RUN_SPEED * 0.05f, rig.x, eps)
        assertTrue("all inside the first organ's fall", rig.clock.nowMs < rig.play.startMs + FIRST_LAND_MS)
    }

    @Test
    fun whatIsHeldIsLetGoAsTheGameStopsAndStartsAgain() = runTest {
        val rig = Rig(6)
        start(rig)
        rig.play.key(TrainingKey.RIGHT, down = true)
        advance(rig, 100L)
        val there = rig.x
        assertEquals(Anim.RUN_RIGHT, rig.anim)
        // The app goes away with Right held; its key-up goes elsewhere and never comes.
        rig.away()
        assertEquals(there, rig.x, 0f)
        assertEquals(Anim.IDLE, rig.anim)
        rig.back()
        advance(rig, 200L)
        assertEquals("standing where it was", there, rig.x, 0f)
        assertEquals(Anim.IDLE, rig.anim)
        // Right still held after all: its next repeat runs it again.
        rig.play.key(TrainingKey.RIGHT, down = true)
        advance(rig, 50L)
        assertEquals(there + RUN_SPEED * 0.05f, rig.x, 1e-4f)
        rig.play.key(TrainingKey.RIGHT, down = false)
        // A finger down as the app goes away is let go of too.
        rig.play.touch(0.1f * W)
        advance(rig, 50L)
        assertEquals(Anim.RUN_LEFT, rig.anim)
        rig.away()
        val held = rig.x
        rig.back()
        advance(rig, 200L)
        assertEquals(held, rig.x, 0f)
        assertEquals(Anim.IDLE, rig.anim)
        // Nothing landed while away: the first organ comes down 1.6 s into the game's own time, not the wall's.
        assertEquals(0, rig.play.caught)
        assertNotNull(rig.play.landingNext)
        assertEquals(rig.play.startMs + FIRST_LAND_MS, rig.play.landingNext!!.landAtMs)
        assertEquals(DropPhase.PLAY, rig.play.phase)
    }

    @Test
    fun eachOrganIsJudgedWhereThePetWasAsItLanded() = runTest {
        // A game whose first organ comes down with room to run past it to the right.
        // (Not one that comes down where the pet starts: it would already be standing there.)
        val rig = (10 until 60).map { Rig(it) }.first {
            val x = it.play.landingNext!!.drop.x
            x in 0.15f..0.75f && abs(x - 0.02f - START_X) > 0.02f
        }
        start(rig)
        val first = rig.play.landingNext!!
        // Stood just left of where it lands, then running right so as to be 0.07 past it as it lands: inside the
        // window then, outside it a frame later.
        rig.play.touch((first.drop.x - 0.02f) * W)
        until(rig) { rig.anim == Anim.IDLE }
        rig.play.lift()
        advance(rig, first.landAtMs - 100L - rig.clock.nowMs)
        rig.play.key(TrainingKey.RIGHT, down = true)
        advance(rig, 96L)
        // A frame carries the clock past the landing; Right is let go before the game's own wait wakes.
        rig.frame(16L)
        assertTrue("past the window now", abs(rig.x - first.drop.x) > CATCH_HALF)
        rig.play.key(TrainingKey.RIGHT, down = false)
        assertEquals("caught where the pet was at the landing", first.landAtMs, first.caughtAtMs)
        runCurrent()
        assertEquals(1, rig.play.caught)
        assertEquals(listOf("squish"), rig.sounds)
        assertEquals(DropPhase.PLAY, rig.play.phase)
    }

    @Test
    fun theLaneFitsThePlayArea() {
        assertEquals(ORGAN_KINDS, ORGAN_ART.size)
        // 3:2, the floor at 0.78: organs 0.11 of the height, caught at the chest of a pet 0.46 tall.
        val lane = DropLane.of(w = 1500f, h = 1000f, floorY = 780f, bodyHeight = 460f)
        assertEquals(110f, lane.organH, 1e-3f)
        assertEquals(550f, lane.catchY, 1e-3f)
        assertEquals(lane.topY, lane.y(0f), 0f)
        assertEquals(lane.catchY, lane.y(1f), 1e-3f)
        assertTrue("shows out of sight", lane.topY + lane.organH / 2f <= 0f)
        assertTrue("comes down on the floor", lane.restY > lane.catchY && lane.restY < lane.floorY)
        assertEquals(lane.restY, lane.y(1f + lane.belowShare), 1e-2f)
        // Fingers across the play area, kept to the range.
        assertEquals(0.5f, lane.xOf(750f), 1e-6f)
        assertEquals(DROP_RANGE.start, lane.xOf(0f), 0f)
        assertEquals(DROP_RANGE.endInclusive, lane.xOf(1500f), 0f)
        // A half-size pet (Chucky) catches lower down, at its own chest.
        val small = DropLane.of(w = 1500f, h = 1000f, floorY = 780f, bodyHeight = 230f)
        assertEquals(665f, small.catchY, 1e-3f)
    }

    private companion object {
        const val W = 1_000f
        const val H = 600f
    }
}
