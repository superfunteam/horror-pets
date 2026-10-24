package team.superfun.horrorpets.pvp

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.pvp.nfc.TapMode
import team.superfun.horrorpets.pvp.nfc.TapRadio
import team.superfun.horrorpets.pvp.nfc.TapRadio.Companion.CARD_HOLD_MS
import team.superfun.horrorpets.pvp.nfc.TapRadio.Companion.TURN_MAX_MS
import team.superfun.horrorpets.pvp.nfc.TapRadio.Companion.TURN_MIN_MS

/** When a phone's NFC reads, and when it is a card. */
class TapRadioTest {
    private fun radio(mode: TapMode, seed: Int = 1, now: Long = 0) = TapRadio(Random(seed)).apply {
        resumed = true
        setMode(mode, now)
    }

    /** The radio run from 0 to [untilMs], a millisecond at a time: whether it read, each millisecond. */
    private fun TapRadio.run(untilMs: Long, from: Long = 0): BooleanArray {
        val out = BooleanArray((untilMs - from).toInt())
        var next = from
        for (t in from until untilMs) {
            if (t >= next) next = t + (advance(t) ?: Long.MAX_VALUE / 2)
            out[(t - from).toInt()] = readerOn(t)
        }
        return out
    }

    @Test
    fun offNeverReadsAndListenAlwaysDoes() {
        assertTrue(radio(TapMode.OFF).run(5_000).none { it })
        assertTrue(radio(TapMode.LISTEN).run(5_000).all { it })
        assertNull("nothing timed", radio(TapMode.LISTEN).advance(0))
    }

    @Test
    fun nothingReadsWhileTheGameIsBehind() {
        val r = radio(TapMode.LISTEN)
        r.resumed = false
        assertFalse(r.readerOn(0))
        r.holding = true
        assertFalse(r.readerOn(0))
    }

    @Test
    fun turnsLastBetweenTheirBoundsAndAlternate() {
        val read = radio(TapMode.TAKE_TURNS, seed = 7).run(60_000)
        val runs = mutableListOf<Int>()
        var len = 1
        for (i in 1 until read.size) {
            if (read[i] == read[i - 1]) len++ else {
                runs += len
                len = 1
            }
        }
        val whole = runs.drop(1) // the first run started before the clock did
        assertTrue("many turns: ${whole.size}", whole.size > 60)
        assertTrue("turns too short or long: ${whole.min()}..${whole.max()}", whole.all { it in TURN_MIN_MS..TURN_MAX_MS })
        val share = read.count { it }.toDouble() / read.size
        assertEquals("reads about half the time", 0.5, share, 0.08)
    }

    @Test
    fun twoPhonesTakingTurnsSoonCatchEachOther() {
        // Read on one while the other is a card, for long enough to find it and say hello (a quarter second).
        val catchAfter = mutableListOf<Int>()
        for (seed in 0 until 200) {
            val a = radio(TapMode.TAKE_TURNS, seed = seed).run(20_000)
            val b = radio(TapMode.TAKE_TURNS, seed = 1_000 + seed, now = seed * 37L).run(20_000)
            var streak = 0
            var caught = -1
            for (t in a.indices) {
                streak = if (a[t] != b[t]) streak + 1 else 0
                if (streak >= 250) {
                    caught = t
                    break
                }
            }
            assertTrue("seed $seed never caught", caught >= 0)
            catchAfter += caught
        }
        catchAfter.sort()
        assertTrue("most taps catch within 2 s: ${catchAfter[catchAfter.size * 9 / 10]} ms", catchAfter[catchAfter.size * 9 / 10] < 2_000)
    }

    @Test
    fun aTapUnderWayKeepsReading() {
        val r = radio(TapMode.TAKE_TURNS, seed = 3)
        r.holding = true
        assertTrue(r.run(5_000).all { it })
        r.setMode(TapMode.OFF, 5_000)
        assertTrue("even once the game moves on", r.readerOn(5_000))
        r.holding = false
        assertFalse(r.readerOn(5_000))
    }

    @Test
    fun aPhoneFoundStaysACardUntilReleasedOrForAWhile() {
        val r = radio(TapMode.LISTEN)
        r.holdAsCard(1_000)
        assertFalse(r.readerOn(1_000))
        assertNotNull("wakes when the hold is up", r.advance(1_000))
        assertEquals(CARD_HOLD_MS, r.advance(1_000))
        assertFalse(r.readerOn(1_000 + CARD_HOLD_MS - 1))
        assertTrue(r.readerOn(1_000 + CARD_HOLD_MS))
        r.holdAsCard(10_000)
        r.release()
        assertTrue(r.readerOn(10_000))
    }
}
