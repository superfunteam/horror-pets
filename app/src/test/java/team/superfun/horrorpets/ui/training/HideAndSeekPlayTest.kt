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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.game.training.TrainingKey
import team.superfun.horrorpets.game.training.afterSwap
import team.superfun.horrorpets.game.training.mateAfter

/** Hide and Eek!'s play, on a [GameClock] fed frames by hand. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class HideAndSeekPlayTest {
    /** A game being played: its clock, the sounds it made, its score, and the scores it ended on. */
    private class Rig(seed: Int) {
        val clock = GameClock()
        val sounds = mutableListOf<String>()
        val score = mutableIntStateOf(0)
        val overs = mutableListOf<Int>()
        val play = HideAndSeekPlay(clock, Sink(sounds), score, Random(seed))
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

    /** Frames until [rig]'s game is in [phase] (a round's hide and shuffle take under 6 s). */
    private fun TestScope.until(rig: Rig, phase: SeekPhase) {
        repeat(500) {
            if (rig.play.phase == phase) return
            rig.frame()
            runCurrent()
        }
        throw AssertionError("never came to $phase")
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

    /** [rig]'s remote moves the pointer onto the stone at [stone] (from wherever it is) and checks it. */
    private fun TestScope.check(rig: Rig, stone: Int) {
        while (rig.play.aimed > stone) rig.play.press(TrainingKey.LEFT)
        while (rig.play.aimed < stone) rig.play.press(TrainingKey.RIGHT)
        rig.play.press(TrainingKey.OK)
        runCurrent()
    }

    /** [rig] waits for its pick and checks the mate's stone. */
    private fun TestScope.win(rig: Rig) {
        until(rig, SeekPhase.PICK)
        check(rig, rig.play.hiddenAt)
    }

    @Test
    fun aRightPickWinsTheRoundAndTheNextIsDealt() = runTest {
        val rig = Rig(1)
        start(rig)
        assertEquals(SeekPhase.HIDE, rig.play.phase)
        assertEquals(1, rig.play.round)
        // The mate rises in front of its stone, then ducks out of sight.
        assertNull(rig.play.mateAt)
        advance(rig, 400L)
        assertEquals(rig.play.shuffle.hideAt, rig.play.mateAt)
        assertEquals(Anim.WAVE, rig.play.mate.anim)
        assertTrue("rising", rig.play.mateDown(rig.clock.nowMs) in 0.01f..0.99f)
        advance(rig, 300L) // up
        assertEquals(0f, rig.play.mateDown(rig.clock.nowMs), 1e-3f)
        assertEquals(1f, rig.play.mateAlpha(rig.clock.nowMs), 1e-3f)
        advance(rig, 800L) // ducking
        assertTrue(rig.play.mateDown(rig.clock.nowMs) > 0f)
        assertTrue(rig.play.mateAlpha(rig.clock.nowMs) < 1f)
        until(rig, SeekPhase.SHUFFLE)
        assertNull(rig.play.mateAt)

        // Round 1 shuffles three swaps, then it's the player's pick, the pointer on the middle stone.
        until(rig, SeekPhase.PICK)
        assertEquals(listOf("stone_slide", "stone_slide", "stone_slide"), rig.sounds)
        assertEquals(3, rig.play.swapsDone)
        assertEquals(1, rig.play.aimed)
        rig.sounds.clear()
        check(rig, rig.play.hiddenAt)
        val moves = rig.sounds.count { it == "ui_move" }
        assertEquals(List(moves) { "ui_move" } + "boing", rig.sounds)
        assertEquals(SeekPhase.FOUND, rig.play.phase)
        assertEquals(1, rig.score.intValue)
        // The stone lifts on the mate, who pops up; your killer cheers.
        assertEquals(rig.play.hiddenAt, rig.play.picked)
        assertEquals(rig.play.hiddenAt, rig.play.mateAt)
        assertEquals(Anim.JUMP, rig.play.mate.anim)
        assertEquals(Anim.WAVE, rig.play.pet.anim)
        assertEquals(0f, rig.play.liftOf(rig.play.hiddenAt, rig.clock.nowMs), 1e-3f)
        advance(rig, 300L)
        assertEquals(1f, rig.play.liftOf(rig.play.hiddenAt, rig.clock.nowMs), 1e-3f)
        for (stone in 0..2) if (stone != rig.play.hiddenAt) assertEquals(0f, rig.play.liftOf(stone, rig.clock.nowMs))
        rig.play.pet.onFinished()
        assertEquals(Anim.IDLE, rig.play.pet.anim)

        // The next round 0.8 s after the pick: dealt anew, all stones down, and one more swap.
        advance(rig, 480L)
        assertEquals(1, rig.play.round)
        advance(rig, 40L)
        assertEquals(2, rig.play.round)
        assertEquals(SeekPhase.HIDE, rig.play.phase)
        assertNull(rig.play.picked)
        assertNull(rig.play.mateAt)
        assertEquals(0, rig.play.swapsDone)
        assertEquals(rig.play.shuffle.hideAt, rig.play.hiddenAt)
        assertEquals(4, rig.play.shuffle.swaps.size)
        rig.sounds.clear()
        until(rig, SeekPhase.PICK)
        assertEquals(List(4) { "stone_slide" }, rig.sounds)
        assertEquals(1, rig.play.aimed)
        assertTrue(rig.overs.isEmpty())
    }

    @Test
    fun aWrongPickEndsWithTheRoundsWonOnce() = runTest {
        val rig = Rig(2)
        start(rig)
        win(rig)
        win(rig)
        assertEquals(2, rig.score.intValue)
        until(rig, SeekPhase.PICK)
        assertEquals(3, rig.play.round)
        val right = rig.play.hiddenAt
        val wrong = (right + 1) % 3
        rig.sounds.clear()
        check(rig, wrong)
        assertEquals("refuse", rig.sounds.last())
        assertEquals(SeekPhase.MISSED, rig.play.phase)
        assertEquals(2, rig.score.intValue)
        assertEquals(wrong, rig.play.picked)
        // The picked stone lifts empty; the right one stays down for now, the mate out of sight.
        advance(rig, 300L)
        assertEquals(1f, rig.play.liftOf(wrong, rig.clock.nowMs), 1e-3f)
        assertEquals(0f, rig.play.liftOf(right, rig.clock.nowMs), 1e-3f)
        assertNull(rig.play.mateAt)
        // Half a second after the pick, the right one lifts on the mate, and your killer fails.
        advance(rig, 220L)
        assertEquals(right, rig.play.mateAt)
        assertEquals(Anim.WAVE, rig.play.mate.anim)
        assertEquals(Anim.FAIL, rig.play.pet.anim)
        rig.play.pet.onFinished()
        assertEquals("the fail holds: the game is over", Anim.FAIL, rig.play.pet.anim)
        advance(rig, 300L)
        assertEquals(1f, rig.play.liftOf(right, rig.clock.nowMs), 1e-3f)
        // Over 1.3 s after that, once, with the rounds won.
        advance(rig, 900L)
        assertTrue(rig.overs.isEmpty())
        advance(rig, 120L)
        assertEquals(listOf(2), rig.overs)
        advance(rig, 5_000L)
        assertEquals(listOf(2), rig.overs)
        assertEquals(SeekPhase.MISSED, rig.play.phase)
    }

    @Test
    fun aMissInTheFirstRoundScoresNothing() = runTest {
        val rig = Rig(3)
        start(rig)
        until(rig, SeekPhase.PICK)
        check(rig, (rig.play.hiddenAt + 2) % 3)
        advance(rig, 2_000L)
        assertEquals(listOf(0), rig.overs)
        assertEquals(0, rig.score.intValue)
    }

    @Test
    fun inputOutsideThePickDoesNothing() = runTest {
        val rig = Rig(4)
        rig.play.row = TombRow(cx = 750f, spacing = 375f, footY = 800f, stoneH = 400f)
        start(rig)

        /** Every key and a tap on every stone, none of which may do anything now. */
        fun pokeEverything() {
            for (key in TrainingKey.entries) rig.play.press(key)
            for (stone in 0..2) rig.play.tap(Offset(rig.play.row!!.x(stone), 600f))
            runCurrent()
        }

        // While the mate hides.
        advance(rig, 500L)
        pokeEverything()
        assertEquals(SeekPhase.HIDE, rig.play.phase)
        // While the stones shuffle.
        until(rig, SeekPhase.SHUFFLE)
        advance(rig, 1_000L) // two swaps begun
        pokeEverything()
        assertEquals(SeekPhase.SHUFFLE, rig.play.phase)
        assertEquals(1, rig.play.aimed)
        assertNull(rig.play.picked)
        assertEquals(listOf("stone_slide", "stone_slide"), rig.sounds)
        // Once the mate is found: not a second pick.
        win(rig)
        val sounds = rig.sounds.toList()
        pokeEverything()
        assertEquals(SeekPhase.FOUND, rig.play.phase)
        assertEquals(1, rig.score.intValue)
        assertEquals(sounds, rig.sounds)
        // Once the game is lost, and after it's over.
        until(rig, SeekPhase.PICK)
        check(rig, (rig.play.hiddenAt + 1) % 3)
        val picked = rig.play.picked
        val lost = rig.sounds.toList()
        pokeEverything()
        advance(rig, 2_000L)
        pokeEverything()
        assertEquals(listOf(1), rig.overs)
        assertEquals(picked, rig.play.picked)
        assertEquals(lost, rig.sounds)
        assertEquals(SeekPhase.MISSED, rig.play.phase)
    }

    @Test
    fun theMatesStoneFollowsTheSwaps() = runTest {
        for (seed in 10 until 16) {
            val rig = Rig(seed)
            start(rig)
            repeat(4) { // a few rounds, longer shuffles
                until(rig, SeekPhase.SHUFFLE)
                val shuffle = rig.play.shuffle
                var expected = shuffle.hideAt
                var seen = 0
                while (rig.play.phase == SeekPhase.SHUFFLE) {
                    rig.frame()
                    runCurrent()
                    while (seen < rig.play.swapsDone) expected = afterSwap(expected, shuffle.swaps[seen++])
                    assertEquals("seed $seed after $seen swaps", expected, rig.play.hiddenAt)
                    // One slide a swap, as it starts.
                    val started = rig.play.swapsDone + if (rig.play.swapping != null) 1 else 0
                    assertEquals(started, rig.sounds.count { it == "stone_slide" })
                }
                assertEquals(SeekPhase.PICK, rig.play.phase)
                assertEquals(shuffle.swaps.size, seen)
                assertEquals(mateAfter(shuffle), rig.play.hiddenAt)
                rig.sounds.clear()
                check(rig, rig.play.hiddenAt)
                assertEquals(SeekPhase.FOUND, rig.play.phase)
                rig.sounds.clear()
            }
        }
    }

    @Test
    fun theArrowsMoveThePointerAlongTheRow() = runTest {
        val rig = Rig(5)
        start(rig)
        until(rig, SeekPhase.PICK)
        rig.sounds.clear()
        rig.play.press(TrainingKey.LEFT)
        assertEquals(0, rig.play.aimed)
        rig.play.press(TrainingKey.LEFT) // the end of the row
        assertEquals(0, rig.play.aimed)
        rig.play.press(TrainingKey.UP)
        rig.play.press(TrainingKey.DOWN)
        assertEquals(0, rig.play.aimed)
        assertEquals(listOf("ui_move"), rig.sounds)
        // It glides there, from the middle.
        val from = rig.clock.nowMs
        assertEquals(1f, rig.play.pointerAt(from), 1e-3f)
        advance(rig, 100L)
        assertEquals(0f, rig.play.pointerAt(rig.clock.nowMs), 1e-3f)
        repeat(3) { rig.play.press(TrainingKey.RIGHT) }
        assertEquals(2, rig.play.aimed)
        assertEquals(listOf("ui_move", "ui_move", "ui_move"), rig.sounds)
        assertEquals(SeekPhase.PICK, rig.play.phase)
    }

    @Test
    fun tapsCheckTheStoneUnderTheFinger() = runTest {
        val row = TombRow(cx = 750f, spacing = 375f, footY = 800f, stoneH = 400f)
        val rig = Rig(6)
        rig.play.row = row
        start(rig)
        until(rig, SeekPhase.PICK)
        val right = rig.play.hiddenAt
        // Well above the stones, or off the end of the row: nothing.
        rig.play.tap(Offset(row.x(right), 100f))
        rig.play.tap(Offset(row.x(2) + 200f, 600f))
        runCurrent()
        assertEquals(SeekPhase.PICK, rig.play.phase)
        // Near the mate's stone's edge, still on it.
        rig.play.tap(Offset(row.x(right) + 170f, 700f))
        runCurrent()
        assertEquals(SeekPhase.FOUND, rig.play.phase)
        assertEquals(right, rig.play.picked)
        assertEquals(1, rig.score.intValue)
    }

    @Test
    fun yourKillerWatchesClearOfTheStones() {
        // How far a killer's art reaches from its feet in what the watcher plays (idle, wave, fail), in its cell's
        // heights, as measured on sprites-final's strips: to the right in any of them (Jason's wave, 0.327), to the left
        // standing or failing (Leatherface's fail, 0.267). Chucky's half size is in these already.
        val reachRight = 0.33f
        val reachLeft = 0.27f
        // Screens from 4:3 to a wide phone, the play area between the film's edges.
        val screens = listOf("4:3" to 4f / 3f, "3:2" to 1.5f, "16:10" to 1.6f, "16:9" to 16f / 9f, "20:9" to 20f / 9f)
        for ((name, aspect) in screens) {
            val h = 1080f
            val w = h * aspect * (1f - 2f * FILM_EDGE_FRACTION)
            val row = TombRow.of(w, h, floorY = 0.75f * h)
            val watcher = SeekWatcher.of(w, h)
            val body = watcher.x + reachRight * watcher.height
            val stone = row.x(0) - row.halfWidth
            assertTrue("$name: the body reaches $body, the left stone starts at $stone", body < stone)
            assertTrue("$name: standing inside the frame", watcher.x - reachLeft * watcher.height >= 0f)
            assertTrue("$name: a small onlooker, ${watcher.height}", watcher.height <= 0.34f * h + 1e-3f)
        }
    }

    @Test
    fun theRowFitsThePlayArea() {
        // A TV's frame between the film's edges (3:2), the floor three quarters down.
        val tv = TombRow.of(w = 1500f, h = 1000f, floorY = 750f)
        assertEquals(750f, tv.x(1), 1e-3f)
        assertEquals(375f, tv.spacing, 1e-3f)
        assertEquals(750f, tv.footY, 1e-3f)
        // Narrow enough that the stones shrink a little, to keep a gap between even the widest design.
        assertTrue("${tv.stoneH}", tv.stoneH < 420f && tv.stoneH > 380f)
        assertTrue(tv.stoneH * 393f / 512f < tv.spacing * 0.85f)
        // A phone's wider frame: the full height.
        assertEquals(420f, TombRow.of(w = 1900f, h = 1000f, floorY = 750f).stoneH, 1e-3f)
        // Taps: each stone's column, half way to the next.
        assertEquals(0, tv.stoneAt(Offset(375f + 180f, 600f)))
        assertEquals(1, tv.stoneAt(Offset(375f + 195f, 600f)))
        assertEquals(2, tv.stoneAt(Offset(1125f + 180f, 740f)))
        assertNull(tv.stoneAt(Offset(1125f + 200f, 600f)))
        assertNull(tv.stoneAt(Offset(100f, 600f)))
        assertNull(tv.stoneAt(Offset(750f, 200f)))
        assertNull(tv.stoneAt(Offset(750f, 900f)))
    }
}
