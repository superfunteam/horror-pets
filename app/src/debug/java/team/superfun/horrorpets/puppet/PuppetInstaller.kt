package team.superfun.horrorpets.puppet

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Installs [DebugPuppet] as the app starts. A content provider because the system creates every one the manifest
 * declares before any Activity, and this one is declared in the debug manifest only, so a release build never
 * has it (nor anything else of the puppet's). It serves no data.
 */
class PuppetInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        Puppet.current = DebugPuppet
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
