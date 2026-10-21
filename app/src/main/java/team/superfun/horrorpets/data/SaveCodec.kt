package team.superfun.horrorpets.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.clampMoodBonus
import team.superfun.horrorpets.game.clampStat
import team.superfun.horrorpets.game.xpToNext
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

/** The save file format: [GameState] as JSON. Decoding never throws; any unusable save becomes a fresh game. */
object SaveCodec {
    /** Newest save version this build understands; a higher one was written by a newer build. */
    private const val SUPPORTED_VERSION = 1

    private val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Reads `NaN` / `Infinity` instead of rejecting the whole save, so [sanitize] can repair the field.
     * Encoding stays strict: the app never writes a non-finite number.
     */
    private val reader = Json(format) { allowSpecialFloatingPointValues = true }

    fun encode(s: GameState): String = format.encodeToString(GameState.serializer(), s)

    /**
     * Returns [GameState] defaults for null, blank, truncated, malformed or wrongly typed JSON and for
     * a save from a newer version. Otherwise returns the saved state with every [PetId] present (any
     * missing pet filled with its defaults) and every pet sanitised at this trust boundary:
     * - its `id` is its map key, whether the entry's own `id` is missing or says something else;
     * - level is in 1..[Tuning.MAX_LEVEL] and xp is in 0 until the cost of the next level;
     * - hunger, fun and strength are in 0..100 and moodBonus in -30..30 (NaN becomes the field's default);
     * - sceneIndex is in 0 until [Tuning.SCENE_COUNT]; wins and losses are not negative.
     * A version below 1 reads as 1.
     */
    fun decode(json: String?): GameState {
        if (json.isNullOrBlank()) return GameState()
        val decoded = try {
            val root = reader.parseToJsonElement(json) as? JsonObject ?: return GameState()
            reader.decodeFromJsonElement(GameState.serializer(), withPetIdsFromKeys(root))
        } catch (_: Exception) {
            // SerializationException, IllegalArgumentException (bad enum/number): all mean "unusable save".
            return GameState()
        }
        if (decoded.version > SUPPORTED_VERSION) return GameState()
        return decoded.copy(
            version = decoded.version.coerceAtLeast(1),
            pets = PetId.entries.associateWith { id ->
                decoded.pets[id]?.let { sanitize(it.copy(id = id)) } ?: PetState(id)
            },
        )
    }

    /**
     * [PetState.id] has no default, so an entry without one would fail the whole decode. The map key
     * already names the pet, so write it into every pet object first. Non-object values are left alone
     * and still fail the decode.
     */
    private fun withPetIdsFromKeys(root: JsonObject): JsonObject {
        val pets = root["pets"] as? JsonObject ?: return root
        val withIds: Map<String, JsonElement> = pets.mapValues { (key, pet) ->
            if (pet is JsonObject) JsonObject(pet + ("id" to JsonPrimitive(key))) else pet
        }
        return JsonObject(root + ("pets" to JsonObject(withIds)))
    }

    private fun sanitize(p: PetState): PetState {
        val fresh = PetState(p.id)
        val level = p.level.coerceIn(1, Tuning.MAX_LEVEL)
        return p.copy(
            hunger = clampOrDefault(fresh.hunger, p.hunger, ::clampStat),
            funLevel = clampOrDefault(fresh.funLevel, p.funLevel, ::clampStat),
            strength = clampOrDefault(fresh.strength, p.strength, ::clampStat),
            level = level,
            xp = p.xp.coerceIn(0, xpToNext(level) - 1),
            moodBonus = clampOrDefault(fresh.moodBonus, p.moodBonus, ::clampMoodBonus),
            wins = p.wins.coerceAtLeast(0),
            losses = p.losses.coerceAtLeast(0),
            sceneIndex = p.sceneIndex.coerceIn(0, Tuning.SCENE_COUNT - 1),
        )
    }

    /** NaN has no sensible clamp, so it falls back to [default]; +/-Infinity clamp to the bounds. */
    private fun clampOrDefault(default: Double, v: Double, clamp: (Double) -> Double): Double =
        if (v.isNaN()) default else clamp(v)
}
