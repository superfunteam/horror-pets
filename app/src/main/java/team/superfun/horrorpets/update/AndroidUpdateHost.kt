package team.superfun.horrorpets.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Update checks, downloads and installs are logged under this tag: `adb logcat -s Updates`. */
const val UPDATES_TAG = "Updates"

/** The release check's connect and read timeouts. */
private const val CHECK_TIMEOUT_MS = 8_000

/** The download's: a stalled read gives up after this long. */
private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 8_000
private const val DOWNLOAD_READ_TIMEOUT_MS = 20_000

/** GitHub's latest-release answer is a few KB; anything this big is not one. */
private const val MAX_RELEASE_JSON_BYTES = 1024 * 1024

/**
 * [UpdateHost] on a device: GitHub over `HttpURLConnection`, downloads in `cacheDir/updates/` (app-specific storage,
 * which needs no permission and is cleared with the app's cache), and the install through a `PackageInstaller`
 * session, which works from Android 5 on without a FileProvider. Android's verdict reaches [InstallStatusReceiver].
 *
 * A download is written to `<apk>.part` and only renamed to the APK's own name once [verify] has passed it, so a file
 * by that name is always a whole, checked release (kept for a RETRY, or a later launch, until a check finds nothing
 * newer).
 */
class AndroidUpdateHost(context: Context, private val userAgent: String) : UpdateHost {
    private val context = context.applicationContext
    private val dir: File get() = File(context.cacheDir, "updates")

    private fun apkFile(release: Release) = File(dir, apkNameOf(release.version))

    private fun partFile(release: Release) = File(dir, apkNameOf(release.version) + ".part")

    private fun open(url: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            useCaches = false
            instanceFollowRedirects = true // github.com hands release downloads on to its download host (https to https)
            setRequestProperty("User-Agent", userAgent)
        }

    override suspend fun latestReleaseJson(): String = withContext(Dispatchers.IO) {
        val conn = open(LATEST_RELEASE_URL, CHECK_TIMEOUT_MS, CHECK_TIMEOUT_MS)
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            conn.inputStream.use { readCapped(it, MAX_RELEASE_JSON_BYTES) }.decodeToString()
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun cleanUp(keep: Release?) {
        withContext(Dispatchers.IO) {
            val kept = keep?.let(::apkFile)
            dir.listFiles()?.forEach { if (it != kept) it.delete() }
        }
    }

    override suspend fun isDownloaded(release: Release): Boolean = withContext(Dispatchers.IO) {
        apkFile(release).let { it.isFile && it.length() == release.size }
    }

    /**
     * From Android 8, what the system could make free for this app: free space plus other apps' caches, which
     * [download] has it clear as needed. Before that, plain free space.
     */
    @SuppressLint("UsableSpace") // the fallback, before Android 8 or if the system will not say
    override fun freeBytes(): Long {
        val cache = context.cacheDir
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val storage = context.getSystemService(StorageManager::class.java)
                return storage.getAllocatableBytes(storage.getUuidForPath(cache))
            } catch (e: IOException) {
                Log.w(UPDATES_TAG, "No allocatable bytes for the cache; using its free space", e)
            }
        }
        return cache.usableSpace
    }

    /** Android 8+: has the system clear other apps' caches until this app has [bytes] free (see [freeBytes]). */
    private fun makeRoom(bytes: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val storage = context.getSystemService(StorageManager::class.java)
            storage.allocateBytes(storage.getUuidForPath(context.cacheDir), bytes)
        } catch (e: IOException) {
            // Not enough after all: the download or the install will say so.
            Log.w(UPDATES_TAG, "Could not make room for the update", e)
        }
    }

    override suspend fun download(release: Release, onProgress: (Long) -> Unit) {
        withContext(Dispatchers.IO) {
            // Starts clean: no older release's APK, no half of an earlier try.
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            makeRoom(spaceNeeded(release.size))
            val part = partFile(release)
            val conn = open(release.url, DOWNLOAD_CONNECT_TIMEOUT_MS, DOWNLOAD_READ_TIMEOUT_MS)
            // A cancel hangs up, so a read waiting on a stalled connection gives up at once instead of at its timeout.
            val hangUp = launch {
                try {
                    awaitCancellation()
                } finally {
                    conn.disconnect()
                }
            }
            try {
                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
                // Content-Length as a header: HttpURLConnection.contentLengthLong is Android 7+.
                val length = conn.getHeaderField("Content-Length")?.toLongOrNull()
                if (length != null && length != release.size) throw UpdateFailure(UpdateProblem.BadDownload)
                conn.inputStream.use { input ->
                    part.outputStream().use { output -> copyDownload(input, output, release.size, onProgress, active = { isActive }) }
                }
            } catch (e: Throwable) {
                part.delete()
                throw e
            } finally {
                hangUp.cancel()
                conn.disconnect()
            }
        }
    }

    override suspend fun verify(release: Release): UpdateProblem? = withContext(Dispatchers.IO) {
        val part = partFile(release)
        val size = part.length()
        val sha256 = if (release.sha256 != null && size == release.size) part.inputStream().use(::sha256Hex) else null
        ensureActive()
        var problem = downloadProblem(release, size, sha256)
        if (problem == null && !isOurs(part, release)) problem = UpdateProblem.BadDownload
        // Only a download that passed gets the APK's own name (see isDownloaded).
        if (problem == null && !part.renameTo(apkFile(release))) problem = UpdateProblem.BadDownload
        if (problem != null) part.delete()
        problem
    }

    /** Whether the APK in [file] says it is this app at [release]'s version (or Android cannot read it at all). */
    private fun isOurs(file: File, release: Release): Boolean {
        val pm = context.packageManager
        val info: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, 0)
        }
        return isReleaseApk(info?.packageName, info?.let(PackageInfoCompat::getLongVersionCode), context.packageName, release)
    }

    override fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override suspend fun install(release: Release, onResult: (status: Int) -> Unit) {
        val sessionId = withContext(Dispatchers.IO) { commitSession(release, onResult) }
        Log.i(UPDATES_TAG, "Handed ${release.version} to the installer (session $sessionId)")
    }

    /** Writes [release]'s APK into a new installer session and commits it; returns the session's id. */
    private suspend fun commitSession(release: Release, onResult: (status: Int) -> Unit): Int = coroutineScope {
        val apk = apkFile(release)
        val installer = context.packageManager.packageInstaller
        // An earlier try's session that never got anywhere only takes space.
        for (old in installer.mySessions) runCatching { installer.abandonSession(old.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, COPY_BUFFER_BYTES) }
                    session.fsync(out)
                }
                ensureActive()
                InstallStatusReceiver.onResult = onResult
                session.commit(statusSender(sessionId))
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        sessionId
    }

    /** Where Android reports the session's progress: [InstallStatusReceiver], explicitly, so the intent can be mutable. */
    private fun statusSender(sessionId: Int): IntentSender {
        val intent = Intent(context, InstallStatusReceiver::class.java)
        // The installer fills in the status extras, so from Android 12 the intent has to be declared mutable.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender
    }

    override fun close() {
        InstallStatusReceiver.onResult = null
    }
}

/**
 * Gets Android's installer's word on an update's session. STATUS_PENDING_USER_ACTION means it wants the player to
 * confirm: this opens its confirmation screen (the game is in front, so it may). Every status then goes on to the
 * waiting [onResult]. Not exported: only the session's own PendingIntent reaches it.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        var status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i(UPDATES_TAG, "Installer status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            status = try {
                if (confirm == null) throw ActivityNotFoundException("no confirmation intent")
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                status
            } catch (e: RuntimeException) { // ActivityNotFoundException, or a SecurityException from an odd build
                Log.w(UPDATES_TAG, "Could not open the installer's confirmation", e)
                PackageInstaller.STATUS_FAILURE
            }
        }
        onResult?.invoke(status)
    }

    companion object {
        /** Who is waiting for the verdict (set as a session is committed); called on the main thread. */
        @Volatile
        var onResult: ((Int) -> Unit)? = null
    }
}

/**
 * Opens Android's "install unknown apps" setting for this app (Android 8+), for the player to allow updates. False
 * when there is none to open, as on some TV builds, or before Android 8, which has no such setting per app.
 */
fun openInstallSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
