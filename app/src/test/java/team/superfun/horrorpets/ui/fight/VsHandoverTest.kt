package team.superfun.horrorpets.ui.fight

import java.io.File
import javax.sound.sampled.AudioSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.audio.SoundSink
import team.superfun.horrorpets.audio.VOICE_GAP_MS
import team.superfun.horrorpets.audio.VoiceQueue
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.fight.VsTimeline.DONE_AT
import team.superfun.horrorpets.ui.fight.VsTimeline.VS_AT
import team.superfun.horrorpets.ui.fight.VsTimeline.VS_SLAM_MS

/**
 * The hand-over from the challenger's entrance (or anything else still talking) to the VS card: the card's announcer,
 * "<player> versus <opponent>", starts as the card opens, so "versus" lands with VS and "fight!" before the fight.
 *
 * The screens run as they do on the device (the entrance's [EntranceRun] and the card's [VsRun], stepped a frame at a
 * time), into a fake [SoundSink] that says the real lines for their real lengths through the same [VoiceQueue] as
 * `GameAudio`, with the same gap between lines.
 */
class VsHandoverTest {
    private val root: File = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "audio-final").isDirectory }
        ?: error("audio-final not found above ${System.getProperty("user.dir")}")

    private fun lineMs(name: String): Long {
        val format = AudioSystem.getAudioFileFormat(File(root, "audio-final/voice/$name.wav"))
        return (format.frameLength * 1000L / format.format.frameRate).toLong()
    }

    /** An announcer on a clock: says each line for its length, then the gap, then the next. */
    private inner class Announcer : SoundSink {
        val queue = VoiceQueue()
        var now = 0L
            private set

        /** Every line it started, with when. */
        val said = mutableListOf<Pair<String, Long>>()
        private var endsAt = Long.MAX_VALUE

        override fun sfx(name: String) = Unit

        override fun voice(names: List<String>) {
            queue.add(names)
            pump()
        }

        override fun skipVoiceTo(lines: List<String>) {
            if (queue.skipTo(lines)) endsAt = Long.MAX_VALUE
            pump()
        }

        fun advance(ms: Long) {
            val until = now + ms
            while (endsAt <= until) {
                now = endsAt
                endsAt = Long.MAX_VALUE
                queue.finished()
                pump()
            }
            now = until
        }

        private fun pump() {
            if (queue.sounding != null) return
            val name = queue.next ?: return
            queue.start()
            said += name to now
            endsAt = now + lineMs(name) + VOICE_GAP_MS
        }

        fun startOf(name: String, after: Long = 0L): Long = said.first { it.first == name && it.second >= after }.second
    }

    private val frame = 16L

    /** The entrance against [pet], skipped by OK at [skipAt] (or played out when null). Returns when it handed over. */
    private fun entrance(sink: Announcer, pet: PetId, skipAt: Long?): Long {
        val run = EntranceRun(entranceSounds(pet))
        var elapsed = 0L
        while (!run.over) {
            for (cue in run.frame(elapsed)) cue.voice?.let { sink.voice(listOf(it)) }
            if (run.over) break
            if (skipAt != null && run.t >= skipAt) run.skip()
            sink.advance(frame)
            elapsed = frame
        }
        return sink.now
    }

    /**
     * The VS card, from the moment it opens: the view model's lines queued as it does (as the router drains them), then
     * the card's frames until it hands over to the fight, its own calls going to [sink] unless [claims] is off. Then the
     * announcer is left to finish over the fight. Returns when the card handed over.
     */
    private fun card(sink: Announcer, player: PetId, opponent: PetId, claims: Boolean = true): Long {
        val lineup = vsLineup(player, opponent)
        sink.voice(lineup.lines)
        val run = VsRun()
        var elapsed = 0L
        while (!run.over) {
            for (cue in run.frame(elapsed)) {
                when (cue) {
                    VsCue.CLAIM_ANNOUNCER -> if (claims) sink.skipVoiceTo(lineup.lines)
                    VsCue.FIGHT_CALL -> sink.voice(listOf("fight"))
                    VsCue.DONE -> Unit
                }
            }
            if (run.over) break
            sink.advance(frame)
            elapsed = frame
        }
        val handedOver = sink.now
        sink.advance(5_000L)
        return handedOver
    }

    private fun assertOnTime(sink: Announcer, opened: Long, player: PetId) {
        val name = player.name.lowercase()
        assertEquals("\"$name\" as the card opens", opened, sink.startOf(name, opened))
        val versus = sink.startOf("versus", opened) - opened
        assertTrue("versus at $versus lands with VS", kotlin.math.abs(versus - (VS_AT + VS_SLAM_MS)) <= 250L)
    }

    @Test
    fun skippingTheEntranceMidLineNoLongerMakesTheCardLate() {
        val sink = Announcer()
        val opened = entrance(sink, PetId.JAWS, skipAt = 1_000L) // "Here comes a new challenger!" is being said
        assertEquals("new_challenger", sink.queue.sounding)
        card(sink, PetId.JASON, PetId.JAWS)
        assertOnTime(sink, opened, PetId.JASON)
        val fight = sink.startOf("fight", opened) - opened
        assertTrue("fight! at $fight, before the fight", fight <= DONE_AT)
        assertEquals("the entrance's name line was never said", 0, sink.said.count { it.first == "jaws" && it.second < opened })
    }

    @Test
    fun withoutTheClaimTheCardsLinesWaitedBehindTheEntrance() {
        // Task 4's finding, kept as a control: this is what the claim fixes.
        val sink = Announcer()
        val opened = entrance(sink, PetId.JAWS, skipAt = 1_000L)
        card(sink, PetId.JASON, PetId.JAWS, claims = false)
        val late = sink.startOf("jason", opened) - opened
        assertTrue("jason started $late ms late", late >= 1_000L)
        assertTrue("so fight! came after the card", sink.startOf("fight", opened) - opened > DONE_AT)
    }

    @Test
    fun theWholeEntranceThenTheCardIsOnTimeToo() {
        // His name line is still being said as the card opens: the card cuts its tail.
        val sink = Announcer()
        val opened = entrance(sink, PetId.JAWS, skipAt = null)
        card(sink, PetId.FREDDY, PetId.JAWS)
        assertOnTime(sink, opened, PetId.FREDDY)
    }

    @Test
    fun fightIsCalledByTheTimeTheFightIsUpForEveryPair() {
        // "<player> versus <opponent>" runs 3.4 to 3.85 s with the gaps, so for the longest names "fight!" starts just
        // after the card hands over (as on the card this one replaced); never more than a moment.
        for (player in PetId.entries) {
            for (opponent in PetId.entries) {
                val sink = Announcer()
                val done = card(sink, player, opponent)
                val fight = sink.startOf("fight")
                assertTrue("$player vs $opponent: fight! at $fight, the fight at $done", fight <= done + 200L)
            }
        }
    }

    @Test
    fun anOrdinaryCardIsUntouchedByItsOwnClaim() {
        for (player in PetId.entries) {
            val opponent = if (player == PetId.JASON) PetId.FREDDY else PetId.JASON
            val sink = Announcer()
            card(sink, player, opponent)
            assertOnTime(sink, 0L, player)
            val lines = vsLineup(player, opponent).lines + "fight"
            assertEquals("$player: each line once, whole, in order", lines, sink.said.map { it.first })
            var at = 0L
            for ((name, start) in sink.said.dropLast(1)) {
                assertEquals("$name was said in full", at, start)
                at = start + lineMs(name) + VOICE_GAP_MS
            }
        }
    }
}
