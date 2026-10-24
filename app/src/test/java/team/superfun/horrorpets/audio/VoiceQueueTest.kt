package team.superfun.horrorpets.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The announcer's queue as [GameAudio] keeps it: lines said one after another, and [VoiceQueue.skipTo], which a screen
 * whose own lines must land on time (the VS card) calls as it opens, to cut whatever an earlier screen left talking.
 */
class VoiceQueueTest {
    private val vs = listOf("jason", "versus", "jaws")

    /** A queue saying [sounding] (null: silent) with [queued] waiting behind it. */
    private fun queue(sounding: String?, vararg queued: String) = VoiceQueue().apply {
        if (sounding != null) {
            add(listOf(sounding))
            assertEquals(sounding, start())
        }
        add(queued.toList())
    }

    @Test
    fun linesAreSaidInTheOrderTheyCame() {
        val q = VoiceQueue()
        assertNull(q.sounding)
        assertNull(q.next)
        q.add(listOf("jason", "versus"))
        q.add(listOf("freddy"))
        assertEquals("jason", q.start())
        assertEquals("jason", q.sounding)
        assertEquals(listOf("versus", "freddy"), q.queued)
        q.finished()
        assertNull(q.sounding)
        assertEquals("versus", q.start())
        q.finished()
        q.dropNext()
        assertTrue(q.idle)
    }

    @Test
    fun theVsLinesAlreadyUnderWayAreLeftAlone() {
        // The usual VS card: its lines were queued as it opened and the first is being said.
        val q = queue("jason", "versus", "jaws")
        assertFalse("nothing is cut", q.skipTo(vs))
        assertEquals("jason", q.sounding)
        assertEquals(listOf("versus", "jaws"), q.queued)

        // Further on, and with "fight!" already queued behind them.
        val later = queue("versus", "jaws", "fight")
        assertFalse(later.skipTo(vs))
        assertEquals(listOf("jaws", "fight"), later.queued)
    }

    @Test
    fun aLineLeftOverFromTheEntranceIsCutAndTheVsLinesGoNext() {
        // The entrance was skipped while the announcer read "Here comes a new challenger!".
        val q = queue("new_challenger", "jason", "versus", "jaws")
        assertTrue("the leftover line is cut", q.skipTo(vs))
        assertNull(q.sounding)
        assertEquals(vs, q.queued)
    }

    @Test
    fun leftoversQueuedAheadOfTheVsLinesAreDropped() {
        // Skipped after his name was queued too: both of the entrance's lines go.
        val q = queue("new_challenger", "jaws", "jason", "versus", "jaws")
        assertTrue(q.skipTo(vs))
        assertEquals(vs, q.queued)
    }

    @Test
    fun beforeTheVsLinesArriveEverythingIsDropped() {
        // The card asks first and the view model's lines come after: nothing may stand in their way.
        val q = queue("new_challenger", "jaws")
        assertTrue(q.skipTo(vs))
        assertTrue(q.idle)
        q.add(vs)
        assertEquals("they start at once", "jason", q.start())
    }

    @Test
    fun hisNameLeftOverFromTheEntranceIsNotTakenForTheEndOfTheVsLines() {
        // The entrance ended on "JAWS!!!" and the card claimed the announcer before the view model's lines came. That
        // "jaws" is the entrance's, not the card's last line: the card's lines cannot be that far on as it opens.
        val q = queue("jaws")
        assertTrue("his name is cut", q.skipTo(vs))
        assertTrue(q.idle)
        q.add(vs)
        assertEquals("the VS lines start at once", "jason", q.start())

        // The same in a mirror match: a lone "jason" sounding is not the card's second "jason".
        val mirror = listOf("jason", "versus", "jason")
        val alone = queue("jason")
        assertTrue(alone.skipTo(mirror))
        assertTrue(alone.idle)
    }

    @Test
    fun aQuietAnnouncerHasNothingToCut() {
        val q = VoiceQueue()
        assertFalse(q.skipTo(vs))
        assertTrue(q.idle)

        // Still loading: queued, not yet sounding. Nothing changes.
        val loading = queue(null, "jason", "versus", "jaws")
        assertFalse(loading.skipTo(vs))
        assertEquals(vs, loading.queued)
    }

    @Test
    fun aCardThatOpensAgainSaysItsNewLinesNotTheOldOnes() {
        // Back, then the same opponent again while the first card's lines are still being said.
        val q = queue("jason", "versus", "jaws", "jason", "versus", "jaws")
        assertTrue(q.skipTo(vs))
        assertEquals(vs, q.queued)
    }

    @Test
    fun aMirrorMatchKeepsBothNames() {
        // PvP: both players brought Jason.
        val mirror = listOf("jason", "versus", "jason")
        val q = queue("jason", "versus", "jason")
        assertFalse(q.skipTo(mirror))
        assertEquals(listOf("versus", "jason"), q.queued)

        val waiting = queue("new_challenger", "jason", "versus", "jason")
        assertTrue(waiting.skipTo(mirror))
        assertEquals(mirror, waiting.queued)
    }

    @Test
    fun skippingToNothingChangesNothing() {
        val q = queue("gotcha", "jason")
        assertFalse(q.skipTo(emptyList()))
        assertEquals("gotcha", q.sounding)
        assertEquals(listOf("jason"), q.queued)
    }

    @Test
    fun clearSilencesItAll() {
        val q = queue("jason", "versus")
        q.clear()
        assertNull(q.sounding)
        assertTrue(q.idle)
    }
}
