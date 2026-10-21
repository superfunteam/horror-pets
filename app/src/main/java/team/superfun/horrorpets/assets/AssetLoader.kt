package team.superfun.horrorpets.assets

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.EnumMap
import team.superfun.horrorpets.model.PetId

/**
 * Loads the bundled game art: sprite manifests and strips (`sprites/<pet>/...`) and scene backdrops
 * (`scenes/...`). Bitmaps are decoded as ARGB_8888 without density scaling.
 *
 * Caches (the loader is safe to share between the main thread and background loaders). Each cache has
 * its own lock, so a slow scene decode never blocks a sprite strip and vice versa; a decode only blocks
 * other callers of the same cache:
 *  - manifests and the scene catalog are parsed once (under [metaLock]);
 *  - sprite strips live in an LRU of at most [MAX_STRIPS] entries keyed by (pet, anim) (under [stripLock]);
 *  - scene bitmaps live in an LRU of at most [MAX_SCENES], so the one being left can fade out while the
 *    next fades in, and anything older is dropped (a scene is about 10 MB decoded) (under [sceneLock]).
 * Lock order: [stripLock] may take [metaLock] (a strip needs its manifest), never the other way round.
 * Evicted bitmaps are not recycled: a composable may still be drawing one. The GC reclaims them.
 *
 * A missing or undecodable asset throws (an [java.io.IOException] or [IllegalStateException]): the
 * art ships inside the APK, so that is a broken build, not something to recover from at runtime.
 */
class AssetLoader(context: Context) {
    // The AssetManager, not the Context: this loader must not keep an Activity alive.
    private val assets: AssetManager = context.assets

    private val metaLock = Any()
    private val stripLock = Any()
    private val sceneLock = Any()
    private val manifests = EnumMap<PetId, SpriteManifest>(PetId::class.java)
    private var catalog: Map<PetId, List<SceneSpec>>? = null
    private val strips = lru<StripKey>(MAX_STRIPS)
    private val sceneBitmaps = lru<String>(MAX_SCENES)

    fun manifest(pet: PetId): SpriteManifest = synchronized(metaLock) {
        manifests.getOrPut(pet) { parseManifest(readText("sprites/${pet.dir}/manifest.json")) }
    }

    /** The strip for [anim]: [AnimSpec.frames] cells of `manifest.cell` side by side. */
    fun strip(pet: PetId, anim: Anim): ImageBitmap = synchronized(stripLock) {
        val key = StripKey(pet, anim)
        strips.getOrPut(key) { decode("sprites/${pet.dir}/${manifest(pet).spec(anim).file}") }
    }

    fun scenes(): Map<PetId, List<SceneSpec>> = synchronized(metaLock) {
        catalog ?: parseScenes(readText("scenes/scenes.json")).also { catalog = it }
    }

    fun sceneBitmap(spec: SceneSpec): ImageBitmap = synchronized(sceneLock) {
        sceneBitmaps.getOrPut(spec.file) { decode("scenes/${spec.file}") }
    }

    private fun readText(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    private fun decode(path: String): ImageBitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Could not decode asset '$path'")
        // Art is drawn smaller than its source on 720p and CRT outputs; mipmaps keep that downscale smooth.
        bitmap.setHasMipMap(true)
        return bitmap.asImageBitmap()
    }

    private data class StripKey(val pet: PetId, val anim: Anim)

    private val PetId.dir: String get() = name.lowercase()

    private companion object {
        /** About 55 MB of decoded strips at worst: sized for 2 GB TV sticks. */
        const val MAX_STRIPS = 16
        const val MAX_SCENES = 2

        /** Access-ordered map that drops its least recently used entry beyond [max]. */
        fun <K> lru(max: Int): LinkedHashMap<K, ImageBitmap> =
            object : LinkedHashMap<K, ImageBitmap>(max + 2, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, ImageBitmap>): Boolean = size > max
            }
    }
}
