package team.superfun.horrorpets.ui.training

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.game.training.DrawCall
import team.superfun.horrorpets.game.training.DrawOutcome
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.game.training.drawWindowMs
import team.superfun.horrorpets.game.training.key

/** Quick Draw's play, on a [GameClock] fed frames by hand. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class QuickDrawPlayTest {
    /** A game being played: its clock, the sounds it made, its score, and the streaks it ended on. */
    private class Rig(seed: Int) {
        val clock = GameClock()
        val sounds = mutableListOf<String>()
        val score = mutableIntStateOf(0)
        val overs = mutableListOf<Int>()
        val play = QuickDrawPlay(clock, Sink(sounds), score, Random(seed))
        private var frameNanos = 5_000_000_000L

        init {
            clock.tick(frameNanos, paused = false) // the first frame only starts the count
        }

        /** One frame [ms] after the last (at most 100: a longer one is capped). */
        fun frame(ms: Long = 16L) {
            frameNanos += ms * 1_000_000L
            clock.tick(frameNanos, paused = false)
        }
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

    /** Frames until [rig]'s next call lights (the longest wait is 2.2 s). */
    private fun TestScope.untilCall(rig: Rig) {
        repeat(200) {
            if (rig.play.phase == DrawPhase.CALL) return
            rig.frame()
            runCurrent()
        }
        throw AssertionError("no call came")
    }

    /** Frames on for [ms] of game time. */
    private fun TestScope.advance(rig: Rig, ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            rig.frame(step)
            runCurrent()
            left -= step
        }
    }

    /** [rig] answers the call lit now with its own key. */
    private fun TestScope.hit(rig: Rig) {
        untilCall(rig)
        rig.play.press(rig.play.call!!.key())
        runCurrent()
    }

    @Test
    fun aDrawScoresAndTheNextCallComes() = runTest {
        val rig = Rig(1)
        start(rig)
        assertEquals(DrawPhase.READY, rig.play.phase)
        assertNull(rig.play.call)
        untilCall(rig)
        val called = rig.play.call!!
        assertEquals(1_500L, rig.play.windowMs)
        rig.play.press(called.key())
        runCurrent()
        assertEquals(1, rig.play.streak)
        assertEquals(1, rig.score.intValue)
        assertEquals(listOf("hit"), rig.sounds)
        assertEquals(Anim.ATTACK, rig.play.pet.anim)
        assertEquals(Anim.FAIL, rig.play.mate.anim)
        // The ring goes dark for the next call, which has less time.
        assertEquals(DrawPhase.READY, rig.play.phase)
        assertNull(rig.play.call)
        assertEquals(called, rig.play.matched)
        assertEquals(drawWindowMs(1), rig.play.windowMs)
        untilCall(rig)
        assertNotNull(rig.play.call)
        assertTrue(rig.overs.isEmpty())
        // The swing and the flinch end back at idle.
        rig.play.pet.onFinished()
        rig.play.mate.onFinished()
        assertEquals(Anim.IDLE, rig.play.pet.anim)
        assertEquals(Anim.IDLE, rig.play.mate.anim)
    }

    @Test
    fun everyFifthDrawIsACrit() = runTest {
        val rig = Rig(2)
        start(rig)
        repeat(10) { hit(rig) }
        assertEquals(listOf("hit", "hit", "hit", "hit", "crit", "hit", "hit", "hit", "hit", "crit"), rig.sounds)
        assertEquals(10, rig.score.intValue)
        assertEquals(drawWindowMs(10), rig.play.windowMs)
        assertTrue(rig.overs.isEmpty())
    }

    @Test
    fun pressingBeforeTheCallIsTooSoon() = runTest {
        val rig = Rig(3)
        start(rig)
        advance(rig, 160L) // well inside the shortest wait
        rig.play.press(TrainingKey.OK)
        runCurrent()
        assertEquals(DrawPhase.OVER, rig.play.phase)
        assertEquals(DrawOutcome.EARLY, rig.play.outcome)
        assertEquals(DrawCall.CENTER, rig.play.pressed)
        assertNull("nothing was called", rig.play.call)
        assertEquals(listOf("refuse"), rig.sounds)
        assertEquals(Anim.ATTACK, rig.play.mate.anim)
        assertEquals(Anim.FAIL, rig.play.pet.anim)
        // The flinch holds: the game is over.
        rig.play.pet.onFinished()
        assertEquals(Anim.FAIL, rig.play.pet.anim)
        // Over 1.3 s after the press, once, with no draws.
        advance(rig, 1_250L)
        assertTrue(rig.overs.isEmpty())
        advance(rig, 100L)
        assertEquals(listOf(0), rig.overs)
        // Nothing more: no call comes, and presses do nothing.
        advance(rig, 3_000L)
        rig.play.press(TrainingKey.UP)
        runCurrent()
        assertEquals(DrawPhase.OVER, rig.play.phase)
        assertEquals(listOf(0), rig.overs)
        assertEquals(listOf("refuse"), rig.sounds)
    }

    @Test
    fun theWrongPartLoses() = runTest {
        val rig = Rig(4)
        start(rig)
        hit(rig)
        hit(rig)
        untilCall(rig)
        val called = rig.play.call!!
        val wrong = DrawCall.entries.first { it != called }
        rig.play.press(wrong.key())
        runCurrent()
        assertEquals(DrawOutcome.WRONG, rig.play.outcome)
        assertEquals(wrong, rig.play.pressed)
        assertEquals("still lit, to show what it was", called, rig.play.call)
        assertEquals(listOf("hit", "hit", "refuse"), rig.sounds)
        assertEquals(2, rig.score.intValue)
        advance(rig, 1_400L)
        assertEquals(listOf(2), rig.overs)
    }

    @Test
    fun tooSlowLoses() = runTest {
        val rig = Rig(5)
        start(rig)
        hit(rig)
        untilCall(rig)
        val window = rig.play.windowMs
        advance(rig, window + 20L)
        assertEquals(DrawPhase.OVER, rig.play.phase)
        assertEquals(DrawOutcome.LATE, rig.play.outcome)
        assertNull(rig.play.pressed)
        assertEquals(listOf("hit", "refuse"), rig.sounds)
        // Too late now, even with the right part.
        rig.play.press(rig.play.call!!.key())
        runCurrent()
        assertEquals(DrawOutcome.LATE, rig.play.outcome)
        advance(rig, 1_400L)
        assertEquals(listOf(1), rig.overs)
    }

    @Test
    fun aPressAtTheWindowsLastMomentStillDraws() = runTest {
        val rig = Rig(6)
        start(rig)
        untilCall(rig)
        // On to exactly the window's end, the last moment a press counts.
        advance(rig, rig.play.calledAtMs + rig.play.windowMs - rig.clock.nowMs)
        assertEquals(rig.play.calledAtMs + rig.play.windowMs, rig.clock.nowMs)
        assertEquals(DrawPhase.CALL, rig.play.phase)
        rig.play.press(rig.play.call!!.key())
        runCurrent()
        assertEquals(1, rig.play.streak)
        assertEquals(DrawPhase.READY, rig.play.phase)
    }

    @Test
    fun tapsAnswerWithThePartUnderTheFinger() = runTest {
        val rig = Rig(7)
        rig.play.ring = DrawRingSpot(cx = 500f, cy = 300f, outer = 100f)
        start(rig)
        untilCall(rig)
        val called = rig.play.call!!
        // A tap well outside the ring does nothing at all.
        rig.play.tap(Offset(900f, 300f))
        runCurrent()
        assertEquals(DrawPhase.CALL, rig.play.phase)
        // On the called part.
        val at = when (called) {
            DrawCall.UP -> Offset(500f, 230f)
            DrawCall.DOWN -> Offset(500f, 370f)
            DrawCall.LEFT -> Offset(430f, 300f)
            DrawCall.RIGHT -> Offset(570f, 300f)
            DrawCall.CENTER -> Offset(505f, 295f)
        }
        rig.play.tap(at)
        runCurrent()
        assertEquals(1, rig.play.streak)
        // A tap on the ring while it waits is too soon.
        rig.play.tap(Offset(500f, 300f))
        runCurrent()
        assertEquals(DrawOutcome.EARLY, rig.play.outcome)
        assertEquals(DrawCall.CENTER, rig.play.pressed)
    }

    @Test
    fun theRingKeepsToItsPlace() {
        // 16:9, the floor three quarters down: in the middle, its centre 0.37 of the height above the floor.
        val wide = DrawRingSpot.of(w = 1500f, h = 1000f, floorY = 750f)
        assertEquals(750f, wide.cx, 1e-3f)
        assertEquals(380f, wide.cy, 1e-3f)
        assertEquals(187.5f, wide.outer, 1e-3f) // an eighth of the width, under 0.19 of the height
        // A phone's wide frame: 0.19 of the height.
        assertEquals(190f, DrawRingSpot.of(w = 1900f, h = 1000f, floorY = 750f).outer, 1e-3f)
        // A floor high up the frame: the time ring stays on screen.
        val high = DrawRingSpot.of(w = 1500f, h = 1000f, floorY = 400f)
        assertTrue("top at ${high.cy - high.reach}", high.cy - high.reach > 0f)
    }
}
