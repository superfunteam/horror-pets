package team.superfun.horrorpets.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import team.superfun.horrorpets.model.GameState
import java.io.IOException

/** Where the game is loaded from and saved to. The view model depends on this, not on DataStore. */
interface SaveStore {
    suspend fun load(): GameState
    suspend fun save(s: GameState)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "horror_pets_save",
    // A corrupt preferences file is discarded rather than crashing every read and write.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val SAVE_JSON = stringPreferencesKey("save_json")

/** [SaveStore] backed by a Preferences DataStore holding the whole save as one JSON string. */
class SaveRepository(context: Context) : SaveStore {
    private val store = context.applicationContext.dataStore

    override suspend fun load(): GameState {
        val prefs = store.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        return SaveCodec.decode(prefs[SAVE_JSON])
    }

    override suspend fun save(s: GameState) {
        store.edit { it[SAVE_JSON] = SaveCodec.encode(s) }
    }
}
