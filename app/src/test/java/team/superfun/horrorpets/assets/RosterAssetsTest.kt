package team.superfun.horrorpets.assets

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.PetId

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
                assertTrue("$id ${anim.key}", file("sprites-final/$dir/${manifest.spec(anim).file}").isFile)
            }
            assertTrue("$id feed", runCatching { manifest.feedAnim() }.isSuccess)
        }
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
}
