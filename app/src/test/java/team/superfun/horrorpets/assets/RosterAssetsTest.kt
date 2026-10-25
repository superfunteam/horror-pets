package team.superfun.horrorpets.assets

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.Attacks
import team.superfun.horrorpets.model.ItemKind
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.spec
import team.superfun.horrorpets.ui.fight.Moment
import team.superfun.horrorpets.ui.home.PLAY_HAND_ART
import team.superfun.horrorpets.ui.home.PLAY_VOICE_LINES
import team.superfun.horrorpets.ui.home.SNORE_SFX
import team.superfun.horrorpets.ui.training.ORGAN_ART
import team.superfun.horrorpets.ui.training.TOMBSTONE_ART
import team.superfun.horrorpets.ui.training.TRAINING_SFX
import team.superfun.horrorpets.ui.training.tone

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
                // An optional animation (the dance, the thrash, the barrels) need not be there, but one the manifest
                // lists must be.
                if (anim.optional && !manifest.has(anim)) continue
                assertTrue("$id ${anim.key}", file("sprites-final/$dir/${manifest.spec(anim).file}").isFile)
            }
            assertTrue("$id feed", runCatching { manifest.feedAnim() }.isSuccess)
            assertTrue("$id head", file("sprites-final/$dir/head.webp").isFile)
            // An attack that names its own animation (Jaws's Barrel Breach and Ultra Thrash) finds it in the manifest.
            for (attack in Attacks.filter { it.pet == id && it.anim != null }) {
                val anim = Anim.fromKey(attack.anim!!)
                assertTrue("${attack.name}: ${attack.anim} is an animation", anim != null)
                assertTrue("${attack.name}: $dir ships ${attack.anim}", manifest.has(anim!!))
            }
        }
        // Jaws ships both of his extra moves.
        val jaws = parseManifest(file("sprites-final/jaws/manifest.json").readText())
        assertTrue(jaws.has(Anim.THRASH) && jaws.has(Anim.BARRELS))
    }

    @Test
    fun jawsWaitsAndWorksOnHisIdleFramesWithoutCopiesOfThem() {
        // His wait and work are his idle frames with their own timing: they point at idle's strip, so it ships once.
        val jaws = parseManifest(file("sprites-final/jaws/manifest.json").readText())
        val idle = jaws.spec(Anim.IDLE)
        for (anim in listOf(Anim.WAIT, Anim.WORK)) {
            val spec = jaws.spec(anim)
            assertEquals("$anim", idle.file, spec.file)
            assertEquals("$anim", idle.frames, spec.frames)
            assertFalse("no copy of idle for $anim", file("sprites-final/jaws/${anim.key}.png").exists())
        }
        assertEquals(listOf(160, 160, 160, 160, 160, 160), jaws.spec(Anim.WAIT).durationsMs)
        assertTrue(jaws.spec(Anim.WAIT).loop)
        assertFalse(jaws.spec(Anim.WORK).loop)
    }

    @Test
    fun animationsThatShareAStripShareItsFrameCount() {
        for (id in PetId.entries) {
            val manifest = parseManifest(file("sprites-final/${id.name.lowercase()}/manifest.json").readText())
            for ((strip, specs) in manifest.animations.values.groupBy { it.file }) {
                assertEquals("$id $strip", 1, specs.map { it.frames }.toSet().size)
            }
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
        // special_jaws: the tank's hiss and whoosh, then the explosion at 780 ms (the blow).
        assertEquals(780L, PetId.JAWS.spec.specialHitMs)
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
    fun theSoundsAFeedPlaysAreShipped() {
        // The kitchen plays the meal's toss and bites, the reaction's happy or gross beat (or the favorite's fanfare)
        // and a level-up; the old snack drop's art is gone.
        for (sfx in listOf("food_drop", "bite", "eat_happy", "eat_gross", "favorite_fanfare", "levelup")) {
            assertTrue(sfx, file("audio-final/sfx/$sfx.wav").isFile)
        }
        val food = file("sprites-final/food").listFiles().orEmpty().filter { it.extension == "webp" }
        assertTrue("the snack drop's art is retired: $food", food.isEmpty())
    }

    @Test
    fun theSoundsAPantryCookQueuesAreShipped() {
        // The view model plays cook_<way to cook> for a cook (the screen the poof, as it shows it), the squelch for one
        // it refuses, and the buzz for a killer too full to eat.
        val ways = PantryItem.entries.filter { it.kind == ItemKind.METHOD }.map { "cook_${it.id}" }
        for (sfx in ways + listOf("pantry_poof", "pantry_squelch", "refuse")) {
            assertTrue(sfx, file("audio-final/sfx/$sfx.wav").isFile)
        }
    }

    @Test
    fun everyPantryItemHasItsArt() {
        // The spec's "every item has art": each item's picture, where the loader reads it, under its save id.
        for (item in PantryItem.entries) {
            val path = pantryArt(item).replaceFirst("sprites/", "sprites-final/")
            assertEquals("$item", "sprites-final/pantry/${item.id}.webp", path)
            assertTrue(path, file(path).isFile)
        }
        // Nothing else in the folder but the cutting board: a renamed id would leave its old picture behind.
        val shipped = file("sprites-final/pantry").list()!!.filter { it.endsWith(".webp") }.toSet()
        assertEquals(PantryItem.entries.map { "${it.id}.webp" }.toSet() + "board.webp", shipped)
        assertTrue(file(CUTTING_BOARD_ART.replaceFirst("sprites/", "sprites-final/")).isFile)
    }

    @Test
    fun theKitchenIsAScene() {
        // Read the way the loader reads it, so it crops, tints and places its floor like any other scene.
        val kitchen = parseScene(file(KITCHEN_SCENE.replaceFirst("scenes/", "scenes-final/")).readText())
        assertTrue(kitchen.file, file("scenes-final/${kitchen.file}").isFile)
        assertEquals(0.7531f, kitchen.floorY, 1e-6f)
        // The pantry's own key sounds: an item on or off the board, and the focus moving.
        for (sfx in listOf("pantry_pick", "ui_move")) assertTrue(sfx, file("audio-final/sfx/$sfx.wav").isFile)
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

    @Test
    fun everyKillerHasItsWeaponSounds() {
        // A hit in a fight sounds like the attacker's weapon: `hit_<pet>` for a strike or a counter, `heavy_<pet>` for a heavy.
        for (id in PetId.entries) {
            val pet = id.name.lowercase()
            for (sfx in listOf("hit_$pet", "heavy_$pet")) assertTrue(sfx, file("audio-final/sfx/$sfx.wav").isFile)
        }
    }

    @Test
    fun theSnoreIsShipped() {
        // A sleeping killer snores on Home (HomeScreen plays it every 4 s): an sfx, loaded like any other by its name.
        assertTrue(file("audio-final/sfx/$SNORE_SFX.wav").isFile)
    }

    @Test
    fun everyTrainingAssetIsThere() {
        for (path in TOMBSTONE_ART + ORGAN_ART) {
            assertTrue(path, file(path.replaceFirst("sprites/", "sprites-final/")).isFile)
        }
        assertEquals(15, TRAINING_SFX.size)
        for (sfx in TRAINING_SFX) {
            val wav = file("audio-final/sfx/$sfx.wav")
            assertTrue(sfx, wav.isFile)
            assertEquals("$sfx: channels, rate, bits", Triple(1, 44_100, 16), wavFormat(wav.readBytes()))
        }
        assertEquals("tone_1", tone(0))
        assertEquals("tone_8", tone(7))
    }

    /** A 16-bit PCM WAV's (channels, sample rate, bits per sample), from its fmt chunk. */
    private fun wavFormat(wav: ByteArray): Triple<Int, Int, Int> {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        check(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF" && String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
        var pos = 12
        while (pos + 8 <= wav.size) {
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (String(wav, pos, 4, Charsets.US_ASCII) == "fmt ") {
                check(buf.getShort(body).toInt() == 1) { "PCM only" }
                return Triple(buf.getShort(body + 2).toInt(), buf.getInt(body + 4), buf.getShort(body + 14).toInt())
            }
            pos = body + size + (size and 1)
        }
        error("no fmt chunk")
    }
}
