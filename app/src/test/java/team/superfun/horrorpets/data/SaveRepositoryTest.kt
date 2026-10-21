package team.superfun.horrorpets.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId

/** The on-disk key names are part of the save format: spelled out here, not borrowed from the code. */
private val SAVE = stringPreferencesKey("save_json")
private val BACKUP = stringPreferencesKey("save_json_backup")

/** A Preferences DataStore in memory, whose reads can be made to fail. */
private class MemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val current = MutableStateFlow(initial)
    var failReads = false

    val value: Preferences get() = current.value

    override val data: Flow<Preferences> = flow {
        if (failReads) throw IOException("read failed")
        emitAll(current)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(current.value).also { current.value = it }
}

class SaveRepositoryTest {
    private val state = GameState(selected = PetId.FREDDY, lastSeenMs = 1_760_000_000_000L)

    private fun storeWith(save: String?, backup: String? = null) = MemoryDataStore(
        mutablePreferencesOf().apply {
            if (save != null) this[SAVE] = save
            if (backup != null) this[BACKUP] = backup
        },
    )

    @Test
    fun aCorruptSaveIsBackedUpBeforeTheFirstOverwrite() = runTest {
        val disk = storeWith("{\"version\":1,\"pets\":{tru")
        val repo = SaveRepository(disk)
        assertEquals(GameState(), repo.load())
        assertNull("loading alone writes nothing", disk.value[BACKUP])

        repo.save(state)
        assertEquals("{\"version\":1,\"pets\":{tru", disk.value[BACKUP])
        assertEquals(SaveCodec.encode(state), disk.value[SAVE])

        // Later saves leave the backup alone.
        val later = state.copy(selected = PetId.JASON)
        repo.save(later)
        assertEquals("{\"version\":1,\"pets\":{tru", disk.value[BACKUP])
        assertEquals(SaveCodec.encode(later), disk.value[SAVE])
    }

    @Test
    fun onlyTheMostRecentCorruptSaveIsKept() = runTest {
        val disk = storeWith("garbage #2", backup = "garbage #1")
        val repo = SaveRepository(disk)
        repo.load()
        repo.save(state)
        assertEquals("garbage #2", disk.value[BACKUP])
    }

    @Test
    fun aSaveFromANewerBuildIsBackedUpToo() = runTest {
        val newer = """{"version":2,"selected":"JASON"}"""
        val disk = storeWith(newer)
        val repo = SaveRepository(disk)
        assertEquals(GameState(), repo.load())
        repo.save(state)
        assertEquals(newer, disk.value[BACKUP])
    }

    @Test
    fun aReadableSaveIsNeverBackedUp() = runTest {
        val disk = storeWith(SaveCodec.encode(state), backup = "an older backup")
        val repo = SaveRepository(disk)
        assertEquals(state, repo.load())
        repo.save(state.copy(selected = PetId.PINHEAD))
        assertEquals("an older backup", disk.value[BACKUP])
    }

    @Test
    fun noSaveYetIsNotBackedUp() = runTest {
        val disk = storeWith(null)
        val repo = SaveRepository(disk)
        assertEquals(GameState(), repo.load())
        repo.save(state)
        assertNull(disk.value[BACKUP])
        assertEquals(SaveCodec.encode(state), disk.value[SAVE])
    }

    @Test
    fun aReadFailureIsThrownNotMistakenForAnEmptySave() = runTest {
        val disk = storeWith(SaveCodec.encode(state)).apply { failReads = true }
        val repo = SaveRepository(disk)
        try {
            repo.load()
            fail("expected the read failure to reach the caller")
        } catch (_: IOException) {
            // The caller decides: retry, or play on without saving.
        }
        assertEquals(SaveCodec.encode(state), disk.value[SAVE])
    }
}
