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
 * Caches (all guarded by one lock, so the loader is safe to share between the main thread and a
 * background preloader; a load blocks other callers while it decodes):
 *  - manifests and the scene catalog are parsed once;
 *  - sprite strips live in an LRU of at most [MAX_STRIPS] entries keyed by (pet, anim);
 *  - scene bitmaps live in an LRU of at most [MAX_SCENES], so the one being left can fade out while the
 *    next fades in, and anything older is dropped (a scene is about 10 MB decoded).
 * Evicted bitmaps are not recycled: a composable may still be drawing one. The GC reclaims them.
 *
 * A missing or undecodable asset throws (an [java.io.IOException] or [IllegalStateException]): the
 * art ships inside the APK, so that is a broken build, not something to recover from at runtime.
 */
class AssetLoader(context: Context) {
    // The AssetManager, not the Context: this loader must not keep an Activity alive.
    private val assets: AssetManager = context.assets

    private val lock = Any()
    private val manifests = EnumMap<PetId, SpriteManifest>(PetId::class.java)
    private var catalog: Map<PetId, List<SceneSpec>>? = null
    private val strips = lru<StripKey>(MAX_STRIPS)
    private val sceneBitmaps = lru<String>(MAX_SCENES)

    fun manifest(pet: PetId): SpriteManifest = synchronized(lock) {
        manifests.getOrPut(pet) { parseManifest(readText("sprites/${pet.dir}/manifest.json")) }
    }

    /** The strip for [anim]: [AnimSpec.frames] cells of `manifest.cell` side by side. */
    fun strip(pet: PetId, anim: Anim): ImageBitmap = synchronized(lock) {
        val key = StripKey(pet, anim)
        strips.getOrPut(key) { decode("sprites/${pet.dir}/${manifest(pet).spec(anim).file}") }
    }

    fun scenes(): Map<PetId, List<SceneSpec>> = synchronized(lock) {
        catalog ?: parseScenes(readText("scenes/scenes.json")).also { catalog = it }
    }

    fun sceneBitmap(spec: SceneSpec): ImageBitmap = synchronized(lock) {
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
        return bitmap.asImageBitmap()
    }

    private data class StripKey(val pet: PetId, val anim: Anim)

    private val PetId.dir: String get() = name.lowercase()

    private companion object {
        const val MAX_STRIPS = 24
        const val MAX_SCENES = 2

        /** Access-ordered map that drops its least recently used entry beyond [max]. */
        fun <K> lru(max: Int): LinkedHashMap<K, ImageBitmap> =
            object : LinkedHashMap<K, ImageBitmap>(max + 2, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, ImageBitmap>): Boolean = size > max
            }
    }
}
