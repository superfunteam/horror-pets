package team.superfun.horrorpets.data

import kotlinx.serialization.json.Json
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

    fun encode(s: GameState): String = format.encodeToString(GameState.serializer(), s)

    /**
     * Returns [GameState] defaults for null, blank, malformed or wrongly typed JSON and for a save
     * from a newer version. Otherwise returns the saved state with every [PetId] present, any
     * missing pet filled with its defaults.
     */
    fun decode(json: String?): GameState {
        if (json.isNullOrBlank()) return GameState()
        val decoded = try {
            format.decodeFromString(GameState.serializer(), json)
        } catch (_: Exception) {
            // SerializationException, IllegalArgumentException (bad enum/number): all mean "unusable save".
            return GameState()
        }
        if (decoded.version > SUPPORTED_VERSION) return GameState()
        return decoded.copy(
            pets = PetId.entries.associateWith { id -> decoded.pets[id]?.copy(id = id) ?: PetState(id) },
        )
    }
}
