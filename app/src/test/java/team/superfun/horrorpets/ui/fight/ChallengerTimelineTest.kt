package team.superfun.horrorpets.ui.fight

import java.io.File
import javax.sound.sampled.AudioSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.CHALLENGER_BANNER_FILE
import team.superfun.horrorpets.assets.parseManifest
import team.superfun.horrorpets.assets.vsNameFile
import team.superfun.horrorpets.audio.VOICE_GAP_MS
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.fight.Entrance.BANNER_AT
import team.superfun.horrorpets.ui.fight.Entrance.BURST_AT
import team.superfun.horrorpets.ui.fight.Entrance.END_AT
import team.superfun.horrorpets.ui.fight.Entrance.GUARD_MS
import team.superfun.horrorpets.ui.fight.Entrance.LUNGE_MS
import team.superfun.horrorpets.ui.fight.Entrance.MAX_STEP_MS
import team.superfun.horrorpets.ui.fight.Entrance.NAME_AT
import team.superfun.horrorpets.ui.fight.Entrance.SETTLE_MS
import team.superfun.horrorpets.ui.fight.Entrance.SLAM_MS

/**
 * The challenger's entrance, as a timeline: what it shows and plays when, how it ends, and that it stands still once
 * it has. The screen only draws what these say.
 */
class ChallengerTimelineTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "audio-final").isDirectory }
        ?: error("audio-final not found above ${System.getProperty("user.dir")}")

    /** How long the bundled announcer line or effect [path] (under audio-final) plays, in ms. */
    private fun lengthMs(path: String): Long {
        val format = AudioSystem.getAudioFileFormat(File(root, "audio-final/$path"))
        return (format.frameLength * 1000L / format.format.frameRate).toLong()
    }

    /** How long the VS card may take, once the entrance ends, to open and claim the announcer: a frame or two. */
    private val cardOpensMs = 100L

    /** Steps a run frame by frame, [steps] ms apart (cycling), until it is over: every sound it played, in order. */
    private fun playThrough(run: EntranceRun, steps: List<Long>): List<EntranceSound> {
        val heard = run.frame(0).toMutableList()
        var i = 0
        while (!run.over) {
            heard += run.frame(steps[i++ % steps.size])
            check(i < 10_000) { "the entrance never ended" }
        }
        return heard
    }

    @Test
    fun theHitComesFirstAndTheWaterBurstsOnItsSecondNote() {
        val sounds = entranceSounds(PetId.JAWS)
        assertEquals(EntranceSound(0, sfx = "challenger_hit"), sounds.first())
        // tools/synth_audio.py puts the bass hit's second note 360 ms in.
        assertEquals(360L, BURST_AT)
        assertEquals("black until the burst: no Jaws yet", 0f, Entrance.lungeScale(BURST_AT - 1))
        assertTrue(Entrance.lungeScale(BURST_AT) > 0f)
        assertTrue("the burst flashes", Entrance.flashAt(BURST_AT) > 0.5f)
        assertTrue("and jolts", Entrance.shakeAt(BURST_AT) >= 0.9f)
    }

    @Test
    fun heComesAtTheCameraFromSmallToBig() {
        assertTrue("small, far off", Entrance.lungeScale(BURST_AT) < 0.2f)
        var last = 0f
        for (t in BURST_AT..BURST_AT + LUNGE_MS step 10) {
            val s = Entrance.lungeScale(t)
            assertTrue("growing at $t: $s after $last", s > last)
            last = s
        }
        assertTrue("past full size as he reaches the camera", last > 1f)
        assertEquals("then settled at full size", 1f, Entrance.lungeScale(BURST_AT + LUNGE_MS + SETTLE_MS), 1e-6f)
        assertEquals(1f, Entrance.lungeScale(END_AT), 1e-6f)
        // Jaws shut as he comes, wide open by the time he is on the camera.
        assertEquals(Entrance.APPROACH_FRAME, Entrance.lungeFrame(BURST_AT))
        assertEquals(Entrance.BITE_FRAME, Entrance.lungeFrame(BURST_AT + LUNGE_MS))
        assertEquals(Entrance.BITE_FRAME, Entrance.lungeFrame(END_AT))
        assertTrue("he snaps them at the camera before his name", (BANNER_AT..NAME_AT).any { Entrance.lungeFrame(it) == Entrance.APPROACH_FRAME })
    }

    @Test
    fun theBannerSlamsInWithTheAnnouncerThenHisName() {
        assertEquals(
            listOf(
                EntranceSound(0, sfx = "challenger_hit"),
                EntranceSound(BANNER_AT, voice = "new_challenger"),
                EntranceSound(NAME_AT, voice = "jaws"),
            ),
            entranceSounds(PetId.JAWS),
        )
        assertTrue("he is nearly at the camera when the banner comes", BANNER_AT >= BURST_AT + LUNGE_MS * 3 / 4)
        assertTrue("the banner lands with an impact", Entrance.flashAt(BANNER_AT + SLAM_MS) > 0.3f)
        assertTrue(Entrance.slamScale(0) > 2f)
        assertEquals(1f, Entrance.slamScale(SLAM_MS), 1e-6f)

        // The announcer reads the banner, then says his name as it lands: the name waits for the line to end.
        val line = lengthMs("voice/new_challenger.wav")
        val lands = NAME_AT + SLAM_MS
        assertTrue("his name lands at $lands, as the line ends at ${BANNER_AT + line}", lands in BANNER_AT + line - 200..BANNER_AT + line + 300)
        assertTrue(Entrance.flashAt(lands) > 0.2f)
    }

    /**
     * Where on the timeline [pet]'s entrance's last announcer line ends, from the bundled clips: each line starts when
     * it falls due or, if the one before is still being said, [VOICE_GAP_MS] after that one ends.
     */
    private fun lastLineEndsAt(pet: PetId): Long {
        var free = 0L
        var end = 0L
        for (sound in entranceSounds(pet)) {
            val line = sound.voice ?: continue
            val start = maxOf(sound.at, free)
            end = start + lengthMs("voice/$line.wav")
            free = end + VOICE_GAP_MS
        }
        return end
    }

    @Test
    fun itRunsAboutFourSecondsAndHoldsTheNameABeat() {
        assertTrue("$END_AT", END_AT in 3_000L..5_000L)
        assertTrue("the name holds a beat before the VS card", END_AT >= NAME_AT + SLAM_MS + 500)
        assertTrue("the hit has rung out", END_AT >= lengthMs("sfx/challenger_hit.wav"))
    }

    @Test
    fun itEndsOnlyOnceTheAnnouncerHasSaidHisName() {
        // The VS card that follows cuts whatever the announcer is still saying as it opens, so his name, the entrance's
        // last word, must be over by the end, with a moment to spare for the card's first frame. Measured on the clips,
        // so a line recorded again longer cannot cut it short.
        for (pet in PetId.entries.filter { it.secret }) {
            val said = lastLineEndsAt(pet)
            assertTrue("$pet: the entrance ends at $END_AT, his name at $said", END_AT >= said + cardOpensMs)
        }
    }

    @Test
    fun theSplashOffHisNameHasFallenBeforeTheLastPictureHolds() {
        // Its droplets are drawn on the timeline: still in the air at the end, they would hang there.
        assertTrue(END_AT >= NAME_AT + SLAM_MS + Entrance.NAME_SPLASH_MS)
    }

    @Test
    fun eachSoundPlaysOnceInOrderAtAnyFrameRate() {
        val expected = entranceSounds(PetId.JAWS)
        for (steps in listOf(listOf(16L), listOf(33L), listOf(0L, 7L, 16L, 250L, 1L, 49L), listOf(MAX_STEP_MS * 5))) {
            val run = EntranceRun(expected)
            assertEquals("$steps", expected, playThrough(run, steps))
            assertEquals(END_AT, run.t)
        }
    }

    @Test
    fun aStallSlowsTheTimelineInsteadOfSkippingIt() {
        val run = EntranceRun(entranceSounds(PetId.JAWS))
        run.frame(0)
        run.frame(5_000)
        assertEquals(MAX_STEP_MS, run.t)
        assertFalse(run.over)
    }

    @Test
    fun okIsIgnoredForTheGuardThenSkipsToTheEnd() {
        val run = EntranceRun(entranceSounds(PetId.JAWS))
        assertEquals(listOf(EntranceSound(0, sfx = "challenger_hit")), run.frame(0))
        assertFalse("not even on the first frame", run.skip())
        while (run.t + 16 < GUARD_MS) run.frame(16)
        assertFalse("a FIGHT press mashed twice must not skip it", run.skip())
        assertFalse(run.over)
        run.frame(16)
        assertTrue(run.t >= GUARD_MS)
        assertTrue(run.skip())
        assertTrue(run.over)
        assertEquals("on its last picture", END_AT, run.t)

        // Over: no frame moves it or plays anything, and another OK changes nothing.
        assertTrue(run.frame(16).isEmpty())
        assertEquals(END_AT, run.t)
        assertFalse(run.skip())
    }

    @Test
    fun aSkipLeavesTheLaterLinesUnsaid() {
        val run = EntranceRun(entranceSounds(PetId.JAWS))
        val heard = run.frame(0).toMutableList()
        while (run.t < 500) heard += run.frame(20)
        assertTrue(run.skip())
        heard += run.frame(20)
        assertEquals(listOf(EntranceSound(0, sfx = "challenger_hit")), heard)
    }

    @Test
    fun nothingMovesOnceItEnds() {
        val later = END_AT + 10_000
        assertEquals(0f, Entrance.shakeAt(END_AT))
        assertEquals(0f, Entrance.flashAt(END_AT))
        for (f in listOf(Entrance::shakeAt, Entrance::flashAt, Entrance::lungeScale, Entrance::lungeTravel, Entrance::hover)) {
            assertEquals(f(END_AT), f(later))
        }
        assertEquals(Entrance.lungeFrame(END_AT), Entrance.lungeFrame(later))
        assertEquals(Entrance.slamScale(END_AT - BANNER_AT), Entrance.slamScale(later - BANNER_AT))
    }

    @Test
    fun theArtAndSoundsAreShipped() {
        assertTrue(File(root, "sprites-final/vs/challenger.webp").isFile)
        assertEquals("sprites/vs/challenger.webp", CHALLENGER_BANNER_FILE)
        assertTrue(File(root, "audio-final/sfx/challenger_hit.wav").isFile)
        assertTrue(File(root, "audio-final/voice/new_challenger.wav").isFile)
        for (pet in PetId.entries.filter { it.secret }) {
            val dir = pet.name.lowercase()
            assertEquals("sprites/vs/$dir-name.webp", vsNameFile(pet))
            assertTrue("$pet name", File(root, "sprites-final/vs/$dir-name.webp").isFile)
            assertTrue("$pet line", File(root, "audio-final/voice/$dir.wav").isFile)
            val attack = parseManifest(File(root, "sprites-final/$dir/manifest.json").readText()).animations.getValue("attack")
            assertTrue("$pet's attack strip has the frames the lunge shows", attack.frames > maxOf(Entrance.APPROACH_FRAME, Entrance.BITE_FRAME))
        }
    }
}
