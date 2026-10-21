package team.superfun.horrorpets.assets

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.PetId

/**
 * Loads the bundled game art: sprite manifests and strips (`sprites/<pet>/...`) and scene backdrops
 * (`scenes/...`). Bitmaps are decoded as ARGB_8888 without density scaling.
 *
 * The scene catalog must have [Tuning.SCENE_COUNT] scenes per pet (see [scenes]).
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
    private val strips = lru<StripKey>(MAX_STRIPS) { ready.remove(it) }
    private val sceneBitmaps = lru<String>(MAX_SCENES)

    /**
     * The decoded strips, readable without a lock (see [readyStrip]). Written only under [stripLock] by
     * [strip] and by the strip LRU's eviction, so it always holds exactly the cached strips.
     */
    private val ready = ConcurrentHashMap<StripKey, ImageBitmap>()

    fun manifest(pet: PetId): SpriteManifest = synchronized(metaLock) {
        manifests.getOrPut(pet) { parseManifest(readText("sprites/${pet.dir}/manifest.json")) }
    }

    /** The strip for [anim]: [AnimSpec.frames] cells of `manifest.cell` side by side. */
    fun strip(pet: PetId, anim: Anim): ImageBitmap = synchronized(stripLock) {
        val key = StripKey(pet, anim)
        strips.getOrPut(key) { decode("sprites/${pet.dir}/${manifest(pet).spec(anim).file}") }.also { ready[key] = it }
    }

    /**
     * The strip for [anim] if it is already decoded, or null; never decodes and never waits for a decode in
     * progress, so it is safe on the main thread. A sprite uses it to show its first frame at once (a pet
     * sliding into the pet switcher whose strip was prefetched). It does not count as a use for the LRU.
     */
    fun readyStrip(pet: PetId, anim: Anim): ImageBitmap? = ready[StripKey(pet, anim)]

    /**
     * The scene catalog. Throws [IllegalStateException] unless every pet has exactly
     * [Tuning.SCENE_COUNT] scenes: the view model wraps scene indexes with that constant while the
     * screens index this list, so a catalog of any other size would put the two out of step.
     */
    fun scenes(): Map<PetId, List<SceneSpec>> = synchronized(metaLock) {
        catalog ?: parseScenes(readText("scenes/scenes.json")).also { parsed ->
            val wrong = parsed.filterValues { it.size != Tuning.SCENE_COUNT }.mapValues { it.value.size }
            check(wrong.isEmpty()) { "Scene catalog must have ${Tuning.SCENE_COUNT} scenes per pet, has $wrong" }
            catalog = parsed
        }
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
        /**
         * At worst every slot holds an 8-frame strip: 384 x 416 px x 8 frames x 4 bytes = 5.1 MB, so
         * 12 slots are about 61 MB decoded. Sized for 2 GB TV sticks, where bitmaps on API 22-25 live
         * on the Java heap. Home keeps 8 strips warm (see HomeScreen) and idle variety needs 3 more. The
         * pet switcher decodes only the IDLE strips of the pets on stage (the centred one and two either
         * side, at most 5) and the next one beyond each end (prefetched, so an entering pet draws at once;
         * at most 7; see SwitcherScreen), so a scroll through the whole lineup churns a few slots and never
         * the whole cache. A fight warms all 12 (see warmFightStrips).
         */
        const val MAX_STRIPS = 12
        const val MAX_SCENES = 2

        /** Access-ordered map that drops its least recently used entry beyond [max], telling [onEvict]. */
        fun <K> lru(max: Int, onEvict: (K) -> Unit = {}): LinkedHashMap<K, ImageBitmap> =
            object : LinkedHashMap<K, ImageBitmap>(max + 2, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, ImageBitmap>): Boolean =
                    (size > max).also { if (it) onEvict(eldest.key) }
            }
    }
}
