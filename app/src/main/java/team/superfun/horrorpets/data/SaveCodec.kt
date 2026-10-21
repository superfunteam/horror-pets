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

/** The save file format: [GameState] as JSON. Decoding never throws; an unusable save is reported as null or becomes a fresh game. */
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
     * [decodeOrNull], with a fresh game ([GameState] defaults) in place of a save that cannot be used.
     * Never throws.
     */
    fun decode(json: String?): GameState = decodeOrNull(json) ?: GameState()

    /**
     * Reads a save, or returns null when the whole save is unusable: not JSON, truncated, not an object,
     * a wrongly typed top-level field (`version`, `lastSeenMs`, `pets` that is not an object), or a
     * version newer than this build. No save at all (null or blank) is a first run: [GameState] defaults.
     *
     * Otherwise returns the saved state with every [PetId] present, tolerating what a later build or a
     * damaged entry might leave behind:
     * - a pet key this build does not know (a character added later) is skipped;
     * - a pet entry that cannot be read (wrong type, bad field) is replaced by that pet's defaults, and
     *   so is a missing one; the other pets are kept;
     * - an unknown `selected` pet reads as none, so the player picks again.
     * Every pet is sanitised at this trust boundary:
     * - its `id` is its map key, whether the entry's own `id` is missing or says something else;
     * - level is in 1..[Tuning.MAX_LEVEL] and xp is in 0 until the cost of the next level;
     * - hunger, fun and strength are in 0..100 and moodBonus in -30..30 (NaN becomes the field's default);
     * - sceneIndex is in 0 until [Tuning.SCENE_COUNT]; wins and losses are not negative.
     * A version below 1 reads as 1.
     */
    fun decodeOrNull(json: String?): GameState? {
        if (json.isNullOrBlank()) return GameState()
        return try {
            val root = reader.parseToJsonElement(json) as? JsonObject ?: return null
            val pets = when (val p = root["pets"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> p
                else -> return null
            }
            // Pets and the selection are read on their own below, so neither can sink the rest.
            val rest = reader.decodeFromJsonElement(GameState.serializer(), JsonObject(root - "pets" - "selected"))
            if (rest.version > SUPPORTED_VERSION) return null
            rest.copy(
                version = rest.version.coerceAtLeast(1),
                selected = petIdOrNull(root["selected"]),
                pets = PetId.entries.associateWith { id -> petOrDefault(id, pets[id.name]) },
            )
        } catch (_: Exception) {
            // SerializationException, IllegalArgumentException (bad number): the save as a whole is unusable.
            null
        }
    }

    private fun petIdOrNull(e: JsonElement?): PetId? {
        val name = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return PetId.entries.firstOrNull { it.name == name }
    }

    /**
     * The pet saved under [id]'s key, sanitised, or [id]'s defaults when the entry is missing or
     * unreadable. [PetState.id] has no default, so the key is written into the entry before decoding.
     */
    private fun petOrDefault(id: PetId, e: JsonElement?): PetState {
        if (e !is JsonObject) return PetState(id)
        return try {
            val pet = reader.decodeFromJsonElement(PetState.serializer(), JsonObject(e + ("id" to JsonPrimitive(id.name))))
            sanitize(pet.copy(id = id))
        } catch (_: Exception) {
            PetState(id)
        }
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
