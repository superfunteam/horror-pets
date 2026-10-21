package team.superfun.horrorpets.assets

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The twelve animations every pet ships. [key] is the manifest key (and the strip file's base name). */
enum class Anim(val key: String) {
    IDLE("idle"),
    RUN_RIGHT("run-right"),
    RUN_LEFT("run-left"),
    WAVE("wave"),
    JUMP("jump"),
    FAIL("fail"),
    WAIT("wait"),
    WORK("work"),
    REVIEW("review"),
    LOOK_A("look-a"),
    LOOK_B("look-b"),
    ATTACK("attack"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        /** The animation whose manifest key is [key], or null if there is none. */
        fun fromKey(key: String): Anim? = byKey[key]
    }
}

/** One horizontal strip: [frames] equally wide cells, shown for [durationsMs] each (one entry per frame). */
@Serializable
data class AnimSpec(
    val file: String,
    val frames: Int,
    val durationsMs: List<Int>,
    val loop: Boolean,
)

/**
 * A pet's `sprites/<pet>/manifest.json`. [cell] is the [width, height] of one frame in pixels;
 * [baselineY] is the row of the feet inside a cell and [anchorX] the column of the body centre, so
 * a pet can be planted on a floor line. [feed] is the key of the animation played while eating.
 */
@Serializable
data class SpriteManifest(
    val pet: String,
    val cell: List<Int>,
    val baselineY: Int,
    val anchorX: Int,
    val feed: String,
    val animations: Map<String, AnimSpec>,
)

private val manifestFormat = Json { ignoreUnknownKeys = true }

/** Throws a `SerializationException` on malformed or incomplete JSON: the manifests ship inside the APK. */
fun parseManifest(json: String): SpriteManifest = manifestFormat.decodeFromString(SpriteManifest.serializer(), json)

/** The spec for [anim]. Throws [IllegalStateException] if the manifest lacks it (a broken asset pack). */
fun SpriteManifest.spec(anim: Anim): AnimSpec =
    animations[anim.key] ?: error("Manifest for '$pet' has no '${anim.key}' animation")

/** The animation played while this pet eats ("review" or "work"). Throws [IllegalStateException] if unknown. */
fun SpriteManifest.feedAnim(): Anim =
    Anim.fromKey(feed) ?: error("Manifest for '$pet' has unknown feed animation '$feed'")
