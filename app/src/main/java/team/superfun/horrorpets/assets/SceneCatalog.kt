package team.superfun.horrorpets.assets

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import team.superfun.horrorpets.model.PetId

/**
 * One backdrop from `scenes/scenes.json`. [file] is relative to `scenes/` (e.g. `jason/dock.webp`).
 * [floorY] is where the pet's feet go, as a fraction of the FULL image height. [tint] / [accent] are
 * `#RRGGBB` strings; [tintStrength] and [shadowOpacity] are 0..1.
 */
@Serializable
data class SceneSpec(
    val id: String,
    val title: String,
    val file: String,
    val floorY: Float,
    val tint: String,
    val tintStrength: Float,
    val shadowOpacity: Float,
    val accent: String,
)

private val sceneFormat = Json { ignoreUnknownKeys = true }
private val catalogSerializer = MapSerializer(String.serializer(), ListSerializer(SceneSpec.serializer()))

/**
 * Parses the scene catalog. Keys name a [PetId] case-insensitively; keys that name no pet are
 * ignored. Throws a `SerializationException` on malformed JSON and [IllegalArgumentException] unless
 * every pet has at least one scene (the catalog ships inside the APK, so that is a broken build).
 */
fun parseScenes(json: String): Map<PetId, List<SceneSpec>> {
    val raw = sceneFormat.decodeFromString(catalogSerializer, json)
    val byPet = LinkedHashMap<PetId, List<SceneSpec>>()
    for ((key, scenes) in raw) {
        val pet = try {
            PetId.valueOf(key.uppercase())
        } catch (_: IllegalArgumentException) {
            continue
        }
        byPet[pet] = scenes
    }
    val missing = PetId.entries.filter { byPet[it].isNullOrEmpty() }
    require(missing.isEmpty()) { "Scene catalog has no scenes for $missing" }
    return byPet
}
