package team.superfun.horrorpets.assets

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.Food
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.ui.fight.Moment
import team.superfun.horrorpets.ui.home.PLAY_HAND_ART
import team.superfun.horrorpets.ui.home.PLAY_VOICE_LINES

/**
 * Adding a killer is an enum entry, a roster entry and its asset folders. This checks the folders: every
 * pet has its sprite strips and manifest, its scenes, its music, its special's sound and its announcer
 * lines, all under the names the game derives from `PetId.name.lowercase()`.
 */
class RosterAssetsTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "sprites-final").isDirectory }
        ?: error("sprites-final not found above ${System.getProperty("user.dir")}")

    private fun file(path: String) = File(root, path)

    @Test
    fun everyPetHasItsSprites() {
        for (id in PetId.entries) {
            val dir = id.name.lowercase()
            val manifest = parseManifest(file("sprites-final/$dir/manifest.json").readText())
            assertEquals("$id", dir, manifest.pet)
            for (anim in Anim.entries) {
                // An optional animation (the dance) need not be there, but one the manifest lists must be.
                if (anim.optional && !manifest.has(anim)) continue
                assertTrue("$id ${anim.key}", file("sprites-final/$dir/${manifest.spec(anim).file}").isFile)
            }
            assertTrue("$id feed", runCatching { manifest.feedAnim() }.isSuccess)
        }
    }

    @Test
    fun leatherfaceHasHisDance() {
        val manifest = parseManifest(file("sprites-final/leatherface/manifest.json").readText())
        assertTrue(manifest.has(Anim.DANCE))
        assertTrue(file("sprites-final/leatherface/${manifest.spec(Anim.DANCE).file}").isFile)
    }

    @Test
    fun everyPetHasItsScenes() {
        val catalog = parseScenes(file("scenes-final/scenes.json").readText())
        for (id in PetId.entries) {
            val scenes = catalog.getValue(id)
            assertEquals("$id", Tuning.SCENE_COUNT, scenes.size)
            for (s in scenes) assertTrue("$id ${s.file}", file("scenes-final/${s.file}").isFile)
        }
    }

    @Test
    fun everySpecialsHitIsWhereTheRosterSaysItIs() {
        // The fight times each special's sound so its hit (the clip's strongest attack) lands with the blow.
        for (id in PetId.entries) {
            val measured = strongestAttackMs(file("audio-final/sfx/special_${id.name.lowercase()}.wav").readBytes())
            assertTrue("$id: roster ${id.spec.specialHitMs} ms, clip $measured ms", abs(measured - id.spec.specialHitMs) <= 20L)
        }
        assertEquals(420L, PetId.MICHAEL.spec.specialHitMs)
        // special_leatherface opens at full level: its first 10 ms window (nothing before it) rises highest, so
        // its hit is at 0 ms; the rev at about 320 ms rises far less over the already-loud pull before it.
        assertEquals(0L, PetId.LEATHERFACE.spec.specialHitMs)
    }

    /**
     * Where a 16-bit PCM WAV hits hardest: the 10 ms window whose peak rises most above the loudest of the 50 ms
     * before it. A whoosh building up to a stab scores low; the stab itself scores high.
     */
    private fun strongestAttackMs(wav: ByteArray): Long {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        check(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF" && String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
        var pos = 12
        var channels = 0
        var rate = 0
        while (pos + 8 <= wav.size) {
            val tag = String(wav, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (tag == "fmt ") {
                check(buf.getShort(body).toInt() == 1 && buf.getShort(body + 14).toInt() == 16) { "16-bit PCM only" }
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
            } else if (tag == "data") {
                val frames = minOf(size, wav.size - body) / (2 * channels)
                val window = rate / 100
                val peaks = IntArray((frames + window - 1) / window)
                for (f in 0 until frames) {
                    for (c in 0 until channels) {
                        val v = abs(buf.getShort(body + (f * channels + c) * 2).toInt())
                        if (v > peaks[f / window]) peaks[f / window] = v
                    }
                }
                val best = peaks.indices.maxBy { k -> peaks[k] - ((maxOf(0, k - 5) until k).maxOfOrNull { peaks[it] } ?: 0) }
                return best * 10L
            }
            pos = body + size + (size and 1)
        }
        error("no data chunk")
    }

    @Test
    fun everyPetHasItsSounds() {
        for (id in PetId.entries) {
            val name = id.name.lowercase()
            for (path in listOf(
                "audio-final/music/$name.ogg",
                "audio-final/sfx/special_$name.wav",
                "audio-final/voice/$name.wav",
                "audio-final/voice/${name}_never_dies.wav",
            )) {
                assertTrue(path, file(path).isFile)
            }
        }
    }

    @Test
    fun everyFightCalloutHasItsLine() {
        for (line in Moment.entries.flatMap { it.lines }.distinct()) {
            val path = "audio-final/voice/$line.wav"
            assertTrue(path, file(path).isFile)
        }
    }

    @Test
    fun everyFoodHasItsArtAndSounds() {
        for (food in Food.entries) {
            val path = "sprites-final/food/${food.file}"
            assertTrue(path, file(path).isFile)
        }
        for (sfx in listOf("food_drop", "bite")) assertTrue(sfx, file("audio-final/sfx/$sfx.wav").isFile)
    }

    @Test
    fun thePotionHasItsArtAndSound() {
        assertTrue(file("sprites-final/fight/potion.webp").isFile)
        assertTrue(file("audio-final/sfx/potion.wav").isFile)
    }

    @Test
    fun thePlayGloveHasItsArtVoiceAndSounds() {
        for (path in PLAY_HAND_ART) assertTrue(path, file(path.replaceFirst("sprites/", "sprites-final/")).isFile)
        for (line in PLAY_VOICE_LINES) assertTrue(line, file("audio-final/voice/$line.wav").isFile)
        for (sfx in listOf("hand_whoosh", "boop", "tickle", "jingle", "boing")) {
            assertTrue(sfx, file("audio-final/sfx/$sfx.wav").isFile)
        }
    }
}
