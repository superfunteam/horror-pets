package team.superfun.horrorpets.assets

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The animations a pet can ship. [key] is the manifest key (and the strip file's base name). The twelve
 * [required] ones are in every manifest; an [optional] one ([DANCE], Leatherface's chainsaw dance; [THRASH] and
 * [BARRELS], Jaws's) only in some, so a caller checks [SpriteManifest.has] before asking for it.
 */
enum class Anim(val key: String, val optional: Boolean = false) {
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

    /** A one-shot dance, played for PLAY on Home and for a danced special (see `SpecialStyle.danced`). */
    DANCE("dance", optional = true),

    /** Jaws's one-shot ultra thrash: chomps wrapped in swooshes and bite marks. */
    THRASH("thrash", optional = true),

    /** Jaws's one-shot barrel breach: three yellow barrels pop up out of his back and sink again. */
    BARRELS("barrels", optional = true),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        /** The animations every manifest has. */
        val required: List<Anim> = entries.filterNot { it.optional }

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
 * [displayScale] is how tall the pet stands next to the others (1.0, or 0.5 for Chucky, who is half
 * as tall): its sprite is drawn that much smaller, feet on the same spot. Absent means 1.0; use [bodyScale].
 */
@Serializable
data class SpriteManifest(
    val pet: String,
    val cell: List<Int>,
    val baselineY: Int,
    val anchorX: Int,
    val feed: String,
    val animations: Map<String, AnimSpec>,
    val displayScale: Float = 1f,
)

/** [SpriteManifest.displayScale], with anything that is not a positive, finite number read as full size (1). */
val SpriteManifest.bodyScale: Float
    get() = displayScale.takeIf { it.isFinite() && it > 0f } ?: 1f

private val manifestFormat = Json { ignoreUnknownKeys = true }

/** Throws a `SerializationException` on malformed or incomplete JSON: the manifests ship inside the APK. */
fun parseManifest(json: String): SpriteManifest = manifestFormat.decodeFromString(SpriteManifest.serializer(), json)

/** Whether this pet ships [anim]: always true for the [Anim.required] ones in a sound asset pack. */
fun SpriteManifest.has(anim: Anim): Boolean = anim.key in animations

/**
 * The spec for [anim]. Throws [IllegalStateException] if the manifest lacks it: a broken asset pack for a
 * required animation, a caller that did not check [has] first for an optional one.
 */
fun SpriteManifest.spec(anim: Anim): AnimSpec =
    animations[anim.key] ?: error("Manifest for '$pet' has no '${anim.key}' animation")

/** The animation played while this pet eats ("review" or "work"). Throws [IllegalStateException] if unknown. */
fun SpriteManifest.feedAnim(): Anim =
    Anim.fromKey(feed) ?: error("Manifest for '$pet' has unknown feed animation '$feed'")
