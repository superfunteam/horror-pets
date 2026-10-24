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
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetId

/** Where [item]'s picture is under the assets: `sprites/pantry/<id>.webp`, named by its save id. */
fun pantryArt(item: PantryItem): String = "sprites/pantry/${item.id}.webp"

/** The pantry's cutting board, the board its two places sit on. */
const val CUTTING_BOARD_ART = "sprites/pantry/board.webp"

/** The pantry's kitchen: a scene of its own (in a scene catalog entry's fields), shared by every killer. */
const val KITCHEN_SCENE = "scenes/pantry/kitchen.json"

/** Where the VS card's art and the challenger entrance's banner are under the assets. */
private const val VS_ART = "sprites/vs"

/** [pet]'s name in its film's lettering (`sprites/vs/<pet>-name.webp`): 900 px wide, on a transparent background. */
fun vsNameFile(pet: PetId): String = "$VS_ART/${pet.name.lowercase()}-name.webp"

/** [pet]'s VS logo, in its film's style (`sprites/vs/<pet>-vs.webp`): 640 px on its longer side, on transparent. */
fun vsLogoFile(pet: PetId): String = "$VS_ART/${pet.name.lowercase()}-vs.webp"

/** "HERE COMES A NEW CHALLENGER!" (`sprites/vs/challenger.webp`): 1400 x 495, on a transparent background. */
const val CHALLENGER_BANNER_FILE = "$VS_ART/challenger.webp"

/**
 * Loads the bundled game art: sprite manifests and strips (`sprites/<pet>/...`), small props such as the player's
 * glove (`sprites/hand/...`), the pantry's items
 * (`sprites/pantry/...`), the VS card's lettering and logos (`sprites/vs/...`) and scene backdrops (`scenes/...`, the
 * pantry's kitchen among them). Bitmaps are decoded as ARGB_8888 without density scaling.
 *
 * The scene catalog must have [Tuning.SCENE_COUNT] scenes per pet (see [scenes]).
 *
 * Caches (the loader is safe to share between the main thread and background loaders). Each cache has
 * its own lock, so a slow scene decode never blocks a sprite strip and vice versa; a decode only blocks
 * other callers of the same cache:
 *  - manifests and the scene catalog are parsed once (under [metaLock]);
 *  - sprite strips live in an LRU of at most [MAX_STRIPS] entries keyed by (pet, anim) (under [stripLock]);
 *  - scene bitmaps live in an LRU of at most [MAX_SCENES], so the one being left can fade out while the
 *    next fades in, and anything older is dropped (a scene is about 10 MB decoded) (under [sceneLock]);
 *  - props are kept once decoded (under [propLock]): the hand's poses, the heads, the cutting board, the potion, the
 *    training reel's art, and the title's cover and logo. Most are under 1.5 MB decoded; the title's cover
 *    (1920 x 1080, about 8 MB) and logo (about 3 MB) are the big ones;
 *  - the VS art (each killer's name lettering and VS logo, and the challenger banner, 1 to 2.8 MB each decoded) lives
 *    in an LRU of at most [MAX_VS_ART] (under [vsLock]): a VS card shows three at once, the entrance two, and all
 *    seventeen kept for good would pin about 24 MB. [prop] hands any `sprites/vs/` path to it too;
 *  - pantry items live in an LRU of at most [MAX_PANTRY_ITEMS] (under [pantryLock]): there are over a hundred, and
 *    a shelf scrolled end to end would otherwise keep every one it passed.
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
    private val propLock = Any()
    private val pantryLock = Any()
    private val vsLock = Any()
    private val manifests = EnumMap<PetId, SpriteManifest>(PetId::class.java)
    private var catalog: Map<PetId, List<SceneSpec>>? = null
    private var kitchenSpec: SceneSpec? = null
    private val strips = lru<StripKey>(MAX_STRIPS) { ready.remove(it) }
    private val sceneBitmaps = lru<String>(MAX_SCENES)
    private val props = HashMap<String, ImageBitmap>()
    private val pantryItems = lru<PantryItem>(MAX_PANTRY_ITEMS) { readyItems.remove(it) }
    private val vsArt = lru<String>(MAX_VS_ART)

    /** The decoded pantry items, readable without a lock (see [readyPantryItem]); kept as [ready] is for strips. */
    private val readyItems = ConcurrentHashMap<PantryItem, ImageBitmap>()

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

    /** The pantry's kitchen, as a scene ([KITCHEN_SCENE]): draw it with `SceneBackdrop` like any other. */
    fun kitchenScene(): SceneSpec = synchronized(metaLock) {
        kitchenSpec ?: parseScene(readText(KITCHEN_SCENE)).also { kitchenSpec = it }
    }

    /** The kitchen's backdrop, cached as the scenes are. */
    fun kitchen(): ImageBitmap = sceneBitmap(kitchenScene())

    /** [item]'s picture ([pantryArt]): its longer side 384 px, on a transparent background. */
    fun pantryItem(item: PantryItem): ImageBitmap = synchronized(pantryLock) {
        pantryItems.getOrPut(item) { decode(pantryArt(item)) }.also { readyItems[item] = it }
    }

    /**
     * [item]'s picture if it is already decoded, or null; never decodes and never waits, so it is safe on the main
     * thread (an item sliding back into view on the shelf, or going onto the board, shows at once).
     */
    fun readyPantryItem(item: PantryItem): ImageBitmap? = readyItems[item]

    /** The cutting board ([CUTTING_BOARD_ART]), 768 px wide and angled a little, on a transparent background. */
    fun cuttingBoard(): ImageBitmap = prop(CUTTING_BOARD_ART)

    /** [pet]'s head (`sprites/<pet>/head.webp`, cut by tools/make_heads.py), for the stable badge and menus. */
    fun head(pet: PetId): ImageBitmap = prop("sprites/${pet.dir}/head.webp")

    /** [pet]'s name lettering for the VS card and the challenger entrance ([vsNameFile]); cached in the VS LRU. */
    fun vsName(pet: PetId): ImageBitmap = vs(vsNameFile(pet))

    /** [pet]'s VS logo ([vsLogoFile]); cached in the VS LRU. */
    fun vsLogo(pet: PetId): ImageBitmap = vs(vsLogoFile(pet))

    /** The challenger entrance's banner ([CHALLENGER_BANNER_FILE]); cached in the VS LRU. */
    fun challengerBanner(): ImageBitmap = vs(CHALLENGER_BANNER_FILE)

    /**
     * A small prop picture at [path] under the assets (`sprites/hand/point.webp`), decoded once and kept. VS art
     * (`sprites/vs/...`) is too big to keep for good: it goes to the VS LRU instead, as [vsName] does.
     */
    fun prop(path: String): ImageBitmap {
        if (path.startsWith("$VS_ART/")) return vs(path)
        return synchronized(propLock) { props.getOrPut(path) { decode(path) } }
    }

    private fun vs(path: String): ImageBitmap = synchronized(vsLock) {
        vsArt.getOrPut(path) { decode(path) }
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
         * on the Java heap. Home keeps at most 8 strips warm (see HomeScreen) and idle variety needs 3
         * more. The pet switcher decodes only the IDLE strips of the pets on stage (the centred one and two
         * either side, at most 5) and the next one beyond each end (prefetched, so an entering pet draws at
         * once; at most 7; see SwitcherScreen), so a scroll through the whole lineup churns a few slots and
         * never the whole cache. A fight warms all 12 (see warmFightStrips).
         */
        const val MAX_STRIPS = 12
        const val MAX_SCENES = 2

        /**
         * A pantry item is at most 384 x 384 px, 0.6 MB decoded, so 24 are under 15 MB: the most a shelf shows at once
         * (under a dozen slots, on a 20:9 phone), the two it decodes ahead either side, the board's two and some to spare.
         */
        const val MAX_PANTRY_ITEMS = 24

        /**
         * The VS card draws three pictures (both names and the opponent's logo); the challenger entrance before it two
         * (the banner and his name, which the card then reuses). Four covers both, about 7 MB decoded at most.
         */
        const val MAX_VS_ART = 4

        /** Access-ordered map that drops its least recently used entry beyond [max], telling [onEvict]. */
        fun <K> lru(max: Int, onEvict: (K) -> Unit = {}): LinkedHashMap<K, ImageBitmap> =
            object : LinkedHashMap<K, ImageBitmap>(max + 2, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, ImageBitmap>): Boolean =
                    (size > max).also { if (it) onEvict(eldest.key) }
            }
    }
}
