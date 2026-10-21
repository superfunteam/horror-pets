package team.superfun.horrorpets.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import team.superfun.horrorpets.model.GameState

/** Where the game is loaded from and saved to. The view model depends on this, not on DataStore. */
interface SaveStore {
    /** The saved game. Throws when the store cannot be read (an I/O error), so the caller can retry. */
    suspend fun load(): GameState

    suspend fun save(s: GameState)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "horror_pets_save",
    // A corrupt preferences file is discarded rather than crashing every read and write.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * [SaveStore] backed by a Preferences DataStore holding the whole save as one JSON string.
 *
 * A save that reads fine but cannot be used at all (corrupt JSON, or one from a newer build) loads as a
 * fresh game, and the first save after that copies the old JSON to a backup key before replacing it,
 * in the same write. Only the most recent such backup is kept.
 */
class SaveRepository internal constructor(private val store: DataStore<Preferences>) : SaveStore {
    constructor(context: Context) : this(context.applicationContext.dataStore)

    /** The unusable save [load] found, until a save has backed it up. */
    @Volatile
    private var unreadable: String? = null

    override suspend fun load(): GameState {
        // A read failure (IOException) reaches the caller: it is not an empty save.
        val json = store.data.first()[SAVE_JSON]
        val game = SaveCodec.decodeOrNull(json)
        if (game != null) return game
        unreadable = json
        return GameState()
    }

    override suspend fun save(s: GameState) {
        val backup = unreadable
        store.edit {
            if (backup != null) it[SAVE_JSON_BACKUP] = backup
            it[SAVE_JSON] = SaveCodec.encode(s)
        }
        unreadable = null
    }

    private companion object {
        val SAVE_JSON = stringPreferencesKey("save_json")
        val SAVE_JSON_BACKUP = stringPreferencesKey("save_json_backup")
    }
}
