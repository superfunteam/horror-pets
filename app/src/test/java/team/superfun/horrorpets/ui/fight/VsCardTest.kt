package team.superfun.horrorpets.ui.fight

import androidx.compose.ui.graphics.Color
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.CHALLENGER_BANNER_FILE
import team.superfun.horrorpets.audio.VOICE_GAP_MS
import team.superfun.horrorpets.assets.vsLogoFile
import team.superfun.horrorpets.assets.vsNameFile
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.fight.VsTimeline.DONE_AT
import team.superfun.horrorpets.ui.fight.VsTimeline.FIGHTER_IN_AT
import team.superfun.horrorpets.ui.fight.VsTimeline.FIGHTER_IN_MS
import team.superfun.horrorpets.ui.fight.VsTimeline.FIGHT_AT
import team.superfun.horrorpets.ui.fight.VsTimeline.FIGHT_SLAM_MS
import team.superfun.horrorpets.ui.fight.VsTimeline.MAX_STEP_MS
import team.superfun.horrorpets.ui.fight.VsTimeline.NAME_SLAM_MS
import team.superfun.horrorpets.ui.fight.VsTimeline.OPPONENT_NAME_AT
import team.superfun.horrorpets.ui.fight.VsTimeline.PANELS_MS
import team.superfun.horrorpets.ui.fight.VsTimeline.PLAYER_NAME_AT
import team.superfun.horrorpets.ui.fight.VsTimeline.VS_AT
import team.superfun.horrorpets.ui.fight.VsTimeline.VS_SLAM_MS

/**
 * The VS card's pure parts: each killer's palette, the speed streaks' motion, the timeline and its run, and who goes
 * where (the VS logo is always the opponent's). The card only draws what these say.
 */
class VsCardTest {
    private val root: File = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "audio-final").isDirectory }
        ?: error("audio-final not found above ${System.getProperty("user.dir")}")

    // ---- palettes ----

    /** WCAG relative luminance of an opaque sRGB colour, 0 (black) to 1 (white). */
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> if (la > lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun everyKillerHasAPaletteOfTheirOwn() {
        val palettes = PetId.entries.associateWith(::vsPalette)
        assertEquals("one per killer, the secret one too", PetId.entries.size, palettes.size)
        assertEquals("no two alike", PetId.entries.size, palettes.values.toSet().size)
        for ((pet, p) in palettes) {
            for (c in listOf(p.deep, p.mid, p.hot, p.streak)) assertEquals("$pet is opaque", 1f, c.alpha)
        }
    }

    @Test
    fun eachHalfIsHottestBehindItsFighter() {
        for (pet in PetId.entries) {
            val p = vsPalette(pet)
            assertTrue("$pet: deep is darker than mid", luminance(p.deep) < luminance(p.mid))
            assertTrue("$pet: hot is the brightest", luminance(p.hot) > luminance(p.mid))
            assertTrue("$pet: hot glows against deep", contrast(p.hot, p.deep) >= 4.0)
        }
    }

    @Test
    fun theStreaksShowUpOnTheirHalf() {
        for (pet in PetId.entries) {
            val p = vsPalette(pet)
            assertTrue("$pet's streaks against its mid: ${contrast(p.streak, p.mid)}", contrast(p.streak, p.mid) >= 1.5)
        }
    }

    // ---- the streaks ----

    @Test
    fun thereAreManyLongStreaksAndEachRacesAcrossFast() {
        assertTrue(VsStreaks.COUNT >= 20)
        assertTrue("fast: under a second across", VsStreaks.SLOWEST_MS <= 1_000L)
        for (i in 0 until VsStreaks.COUNT) {
            assertTrue("streak $i", VsStreaks.crossMs(i) in VsStreaks.FASTEST_MS..VsStreaks.SLOWEST_MS)
            assertTrue("streak $i is long", VsStreaks.length(i) in 0.25f..1f)
            assertTrue("streak $i has a lane", VsStreaks.lane(i) in 0f..1f)
        }
        assertTrue("not all at one speed", (0 until VsStreaks.COUNT).map(VsStreaks::crossMs).toSet().size > VsStreaks.COUNT / 2)
    }

    @Test
    fun aStreakMovesOutwardEveryFrameAndWrapsAround() {
        for (i in 0 until VsStreaks.COUNT) {
            for (t in 0L..6_000L step 13L) {
                val p = VsStreaks.progress(i, t)
                assertTrue("streak $i at $t: $p", p >= 0f && p < 1f)
                val step = (VsStreaks.progress(i, t + 16L) - p).mod(1f)
                val expected = 16f / VsStreaks.crossMs(i)
                assertEquals("streak $i moves at its own pace at $t", expected, step, 1e-3f)
            }
        }
    }

    @Test
    fun theStreaksAreSpreadAcrossTheHalf() {
        // Every fifth of the half has a lane, and at the start they are not bunched up.
        val lanes = (0 until VsStreaks.COUNT).map { (VsStreaks.lane(it) * 5).toInt().coerceAtMost(4) }.toSet()
        assertEquals(setOf(0, 1, 2, 3, 4), lanes)
        val starts = (0 until VsStreaks.COUNT).map { (VsStreaks.progress(it, 0L) * 4).toInt() }.toSet()
        assertEquals(setOf(0, 1, 2, 3), starts)
    }

    @Test
    fun theBurstTurnsSlowly() {
        assertEquals(0f, burstTurnDeg(0L), 0f)
        assertTrue(burstTurnDeg(1_000L) > 0f)
        assertTrue("a slow turn over the whole card", burstTurnDeg(DONE_AT) < 45f)
    }

    // ---- the timeline ----

    @Test
    fun itKeepsTodaysTimeline() {
        // Never slower than the card it replaces (the spec's anti-goal), and every beat where it was.
        assertEquals(380L, PANELS_MS)
        assertEquals(120L, FIGHTER_IN_AT)
        assertEquals(520L, FIGHTER_IN_MS)
        assertEquals(520L, PLAYER_NAME_AT)
        assertEquals(820L, OPPONENT_NAME_AT)
        assertEquals(170L, NAME_SLAM_MS)
        assertEquals(1_150L, VS_AT)
        assertEquals(210L, VS_SLAM_MS)
        assertEquals(3_050L, FIGHT_AT)
        assertEquals(180L, FIGHT_SLAM_MS)
        assertEquals(3_800L, DONE_AT)
        assertEquals(100L, MAX_STEP_MS)
    }

    @Test
    fun thePanelsSweepInAndTheFightersSlideIn() {
        assertEquals(0f, VsTimeline.sweep(0L), 0f)
        assertEquals(1f, VsTimeline.sweep(PANELS_MS), 1e-6f)
        assertEquals(1f, VsTimeline.sweep(DONE_AT), 1e-6f)
        assertEquals(0f, VsTimeline.fighterIn(FIGHTER_IN_AT), 0f)
        assertEquals(1f, VsTimeline.fighterIn(FIGHTER_IN_AT + FIGHTER_IN_MS), 1e-6f)
        var last = -1f
        for (t in 0L..DONE_AT step 10L) {
            val s = VsTimeline.sweep(t)
            assertTrue(s >= last)
            last = s
        }
    }

    @Test
    fun eachSlamFallsToItsSizeAndStays() {
        assertEquals(2.2f, VsTimeline.slamScale(0L, NAME_SLAM_MS, 2.2f), 1e-6f)
        assertEquals(1f, VsTimeline.slamScale(NAME_SLAM_MS, NAME_SLAM_MS, 2.2f), 1e-6f)
        assertEquals(1f, VsTimeline.slamScale(5_000L, NAME_SLAM_MS, 2.2f), 1e-6f)
        var last = Float.MAX_VALUE
        for (t in 0L..VS_SLAM_MS step 5L) {
            val s = VsTimeline.slamScale(t, VS_SLAM_MS, 3.2f)
            assertTrue("falling at $t", s <= last)
            last = s
        }
    }

    @Test
    fun theNamesLandThenVsWithAJoltAndAFlash() {
        assertTrue(PLAYER_NAME_AT + NAME_SLAM_MS <= OPPONENT_NAME_AT + NAME_SLAM_MS)
        assertTrue(OPPONENT_NAME_AT + NAME_SLAM_MS < VS_AT)
        val landed = VS_AT + VS_SLAM_MS
        assertTrue("VS jolts", VsTimeline.shakeAt(landed) >= 0.9f)
        assertTrue("and flashes", VsTimeline.flashAt(landed) > 0.2f)
        assertTrue("FIGHT! flashes too", VsTimeline.flashAt(FIGHT_AT + FIGHT_SLAM_MS) > 0.2f)
        assertEquals("still before anything lands", 0f, VsTimeline.shakeAt(PLAYER_NAME_AT), 0f)
        assertEquals("still at the end", 0f, VsTimeline.shakeAt(DONE_AT), 0f)
        assertEquals(0f, VsTimeline.flashAt(DONE_AT), 0f)
    }

    /** How long the bundled announcer line [name] plays, in ms. */
    private fun lineMs(name: String): Long {
        val format = AudioSystem.getAudioFileFormat(File(root, "audio-final/voice/$name.wav"))
        return (format.frameLength * 1000L / format.format.frameRate).toLong()
    }

    @Test
    fun vsLandsWithTheAnnouncersVersusForEveryKiller() {
        // The card claims the announcer on its first frame, so "<player>" starts at 0 and "versus" after it and
        // GameAudio's gap between lines. VS lands within a beat of that, whoever the player is.
        val landed = VS_AT + VS_SLAM_MS
        for (pet in PetId.entries) {
            val versus = lineMs(pet.name.lowercase()) + VOICE_GAP_MS
            assertTrue("$pet: versus at $versus, VS lands at $landed", kotlin.math.abs(versus - landed) <= 250L)
        }
    }

    // ---- one run of the card ----

    /** Steps a run [steps] ms apart (cycling) until it is over: every cue it gave, with the time it gave it at. */
    private fun playThrough(run: VsRun, steps: List<Long>): List<Pair<VsCue, Long>> {
        val heard = run.frame(0).map { it to run.t }.toMutableList()
        var i = 0
        while (!run.over) {
            heard += run.frame(steps[i++ % steps.size]).map { it to run.t }
            check(i < 10_000) { "the card never ended" }
        }
        return heard
    }

    @Test
    fun itClaimsTheAnnouncerAtOnceCallsFightOnceAndEndsAtAnyFrameRate() {
        for (steps in listOf(listOf(16L), listOf(33L), listOf(5L, 40L, 17L, 90L), listOf(500L))) {
            val run = VsRun()
            val cues = playThrough(run, steps)
            assertEquals("$steps", listOf(VsCue.CLAIM_ANNOUNCER, VsCue.FIGHT_CALL, VsCue.DONE), cues.map { it.first })
            assertEquals("claimed on the first frame", 0L, cues[0].second)
            assertTrue("fight at FIGHT_AT, $steps", cues[1].second in FIGHT_AT until FIGHT_AT + MAX_STEP_MS)
            assertEquals("done at DONE_AT, $steps", DONE_AT, cues[2].second)
            assertEquals("it stops at the end", DONE_AT, run.t)
            assertTrue(run.over)
        }
    }

    @Test
    fun aStallSlowsTheCardInsteadOfSkippingIt() {
        val run = VsRun()
        run.frame(0)
        run.frame(2_000L)
        assertEquals(MAX_STEP_MS, run.t)
        assertFalse(run.over)
    }

    @Test
    fun okOrBackEndsItAndNothingMoreHappens() {
        val run = VsRun()
        run.frame(0)
        run.frame(50L)
        assertTrue("OK or Back ends it", run.end())
        assertFalse("only once", run.end())
        assertTrue(run.over)
        val t = run.t
        assertEquals(emptyList<VsCue>(), run.frame(16L))
        assertEquals(emptyList<VsCue>(), run.frame(5_000L))
        assertEquals("the streaks stand still once it is over", t, run.t)
    }

    // ---- who goes where ----

    @Test
    fun theVsLogoIsAlwaysTheOpponents() {
        for (player in PetId.entries) {
            for (opponent in PetId.entries) {
                // player == opponent happens in PvP, when both players bring the same killer.
                val lineup = vsLineup(player, opponent)
                assertEquals(player, lineup.left)
                assertEquals(opponent, lineup.right)
                assertEquals("$player vs $opponent", opponent, lineup.logo)
                assertEquals(listOf(player.name.lowercase(), "versus", opponent.name.lowercase()), lineup.lines)
            }
        }
    }

    @Test
    fun onlyJawsIsMirroredAndOnlyOnTheLeft() {
        // He faces left in his art: on the player's side he turns to face his opponent; on the right he already does.
        assertTrue(vsLineup(PetId.JAWS, PetId.JASON).mirrorLeft)
        assertFalse(vsLineup(PetId.JASON, PetId.JAWS).mirrorRight)
        for (a in PetId.entries) {
            for (b in PetId.entries) {
                val lineup = vsLineup(a, b)
                assertEquals("$a vs $b", a == PetId.JAWS, lineup.mirrorLeft)
                assertFalse("$a vs $b", lineup.mirrorRight)
            }
        }
    }

    @Test
    fun theArtIsShippedWhereTheLoaderLooks() {
        for (pet in PetId.entries) {
            val dir = pet.name.lowercase()
            assertEquals("sprites/vs/$dir-name.webp", vsNameFile(pet))
            assertEquals("sprites/vs/$dir-vs.webp", vsLogoFile(pet))
            assertEquals("the entrance's name lettering is the card's", vsNameArt(pet), vsNameFile(pet))
            for (path in listOf(vsNameFile(pet), vsLogoFile(pet))) {
                assertTrue(path, File(root, path.replaceFirst("sprites/", "sprites-final/")).isFile)
            }
        }
        assertEquals(CHALLENGER_BANNER_ART, CHALLENGER_BANNER_FILE)
        assertTrue(File(root, CHALLENGER_BANNER_FILE.replaceFirst("sprites/", "sprites-final/")).isFile)
    }
}
