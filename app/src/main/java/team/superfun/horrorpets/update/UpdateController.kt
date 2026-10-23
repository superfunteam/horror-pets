package team.superfun.horrorpets.update

import android.content.pm.PackageInstaller
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import team.superfun.horrorpets.DEV_VERSION_NAME

/** What the device does for an update: GitHub, the disk and Android's installer. `AndroidUpdateHost` in the app. */
interface UpdateHost {
    /** The body of GitHub's latest-release answer ([LATEST_RELEASE_URL]); throws when there is none to be had. */
    suspend fun latestReleaseJson(): String

    /** Deletes every downloaded or half-downloaded APK but [keep]'s finished one. */
    suspend fun cleanUp(keep: Release?)

    /** Whether [release]'s APK is downloaded and verified already (by an earlier try, maybe in an earlier launch). */
    suspend fun isDownloaded(release: Release): Boolean

    /** Free bytes where downloads go. */
    suspend fun freeBytes(): Long

    /**
     * Downloads [release]'s APK, telling [onProgress] (on any thread) the bytes so far. Throws an [UpdateFailure], or
     * any other exception for a download that broke off. Cancellable; a download that does not finish leaves nothing.
     */
    suspend fun download(release: Release, onProgress: (Long) -> Unit)

    /** Checks the download against [release] and keeps it for installing; or drops it and says what is wrong with it. */
    suspend fun verify(release: Release): UpdateProblem?

    /** Whether this app may install apps now. Android 8+ asks the player to allow it per app; before that, always. */
    fun canInstall(): Boolean

    /**
     * Hands [release]'s downloaded APK to Android's installer. Its verdict comes back later, on the main thread, to
     * [onResult]: a `PackageInstaller.STATUS_...` and, with STATUS_PENDING_USER_ACTION, the confirmation screen to
     * show (an `Intent`; null with any other status). Throws when the installer could not be given it.
     */
    suspend fun install(release: Release, onResult: (status: Int, confirm: Any?) -> Unit)

    /** The controller is gone: forget its callbacks. */
    fun close() {}
}

/** What the update window's buttons do. [UpdateController] does them. */
interface UpdateActions {
    /** UPDATE, or RETRY: download (unless that is done already), verify, install. */
    fun accept()

    /** LATER, Back, or a tap off the window: closes it for the rest of this launch. */
    fun later()

    /** CANCEL: stops the download and goes back to the offer. */
    fun cancel()

    /** The device has no screen for allowing installs from this app: the window says where to find it instead. */
    fun settingsMissing()

    companion object {
        /** Buttons that do nothing. */
        val None: UpdateActions = object : UpdateActions {
            override fun accept() {}

            override fun later() {}

            override fun cancel() {}

            override fun settingsMissing() {}
        }
    }
}

/**
 * The in-app update, as a state machine: once per launch, as the app starts, it asks GitHub for the latest release and,
 * when that is newer than this build, offers it in [step] (which the title shows); on UPDATE it downloads, verifies and
 * installs it through [host]. Any failure of the check itself (offline, rate-limited, a strange answer) offers nothing
 * and says nothing. LATER is for the rest of the launch: an answer that comes in after it (the player left the title
 * first, which closes the window too) offers nothing. See [UpdateStep] for the window's steps.
 *
 * [runningVersion] is this build's versionName, read at each check (a debug build's puppet can fake it). A dev build
 * never checks. [log] gets a line for each check and each failure.
 *
 * Threading: like `GameViewModel`, call everything on the main thread; [scope] null uses `viewModelScope`.
 */
class UpdateController(
    private val host: UpdateHost,
    private val runningVersion: () -> String,
    scope: CoroutineScope?,
    private val log: (String) -> Unit = {},
) : ViewModel(), UpdateActions {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _step = MutableStateFlow<UpdateStep>(UpdateStep.None)
    val step: StateFlow<UpdateStep> = _step.asStateFlow()

    private var checked = false
    private var checking: Job? = null

    /** LATER (or leaving the title): no offer for the rest of this launch, but for the puppet's [checkNow]. */
    private var dismissed = false

    /** The download, verification or hand-over to the installer under way, if any. */
    private var work: Job? = null

    /** Whether a download, its verification or an install is under way, which a check must leave alone. */
    private val busy: Boolean
        get() = _step.value.let { it is UpdateStep.Downloading || it is UpdateStep.Verifying || it is UpdateStep.Installing }

    // ---- the check ----------------------------------------------------------------------------

    /** The launch's check, as the app starts: the first call checks, any later one does nothing. */
    fun checkOnce() {
        if (checked) return
        checked = true
        check()
    }

    /** Checks again now, even after LATER (the debug puppet's `check_update`); not while an update is under way. */
    fun checkNow() {
        checked = true
        if (busy) {
            log("Update check skipped: an update is under way")
            return
        }
        dismissed = false
        check()
    }

    private fun check() {
        val running = runningVersion()
        if (running == DEV_VERSION_NAME) {
            log("Update check skipped: a dev build never updates")
            return
        }
        checking?.cancel()
        checking = scope.launch {
            val body = try {
                host.latestReleaseJson()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Update check failed: $e")
                return@launch
            }
            val latest = parseLatestRelease(body)
            if (latest == null) {
                log("Update check: no usable release in GitHub's answer")
                return@launch
            }
            val newer = newerRelease(latest, running)
            log("Update check: latest is ${latest.version}, this build $running: ${if (newer != null) "offering it" else "up to date"}")
            if (busy) return@launch
            try {
                host.cleanUp(keep = newer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Could not clear old downloads: $e")
            }
            if (busy) return@launch
            if (dismissed) {
                if (newer != null) log("Update not offered: the player has left the title, or said LATER")
                return@launch
            }
            _step.value = if (newer != null) UpdateStep.Offer(newer) else UpdateStep.None
        }
    }

    // ---- the buttons --------------------------------------------------------------------------

    override fun accept() {
        val release = when (val s = _step.value) {
            is UpdateStep.Offer -> s.release
            is UpdateStep.Failed -> s.release
            else -> return
        }
        _step.value = UpdateStep.Downloading(release, 0)
        val previous = work
        val check = checking
        work = scope.launch {
            // A cancelled download is still deleting its leftovers, and a check may be clearing old ones: let both finish.
            previous?.cancelAndJoin()
            check?.cancelAndJoin()
            if (!host.isDownloaded(release)) {
                if (!download(release)) return@launch
            }
            installOrAsk(release)
        }
    }

    /** Downloads and verifies [release]; false (with the window on the failure) when that did not work out. */
    private suspend fun download(release: Release): Boolean {
        val free = host.freeBytes()
        val needed = spaceNeeded(release.size)
        if (free < needed) {
            _step.value = UpdateStep.Failed(release, UpdateProblem.NoSpace(needed, free))
            return false
        }
        try {
            coroutineScope {
                // The host reports from its own thread. Its reports reach the window through this job's child, on the
                // main thread, so once the job is cancelled none can land: not on the offer, not on the next download.
                val progress = MutableStateFlow(0L)
                val bar = launch { progress.collect { done -> _step.value = UpdateStep.Downloading(release, done) } }
                try {
                    host.download(release) { progress.value = it }
                } finally {
                    bar.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cancel hangs up the connection, and the read waiting on it fails with an IOException, not a
            // cancellation: that is no failure to show, the window has moved on (CANCEL, LATER).
            currentCoroutineContext().ensureActive()
            log("Update download failed: $e")
            _step.value = UpdateStep.Failed(release, (e as? UpdateFailure)?.problem ?: UpdateProblem.Network)
            return false
        }
        _step.value = UpdateStep.Verifying(release)
        val problem = try {
            host.verify(release)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // as above: a cancel can surface as a closed file
            log("Update verification failed: $e")
            UpdateProblem.BadDownload
        }
        if (problem != null) {
            log("Update download rejected: $problem")
            _step.value = UpdateStep.Failed(release, problem)
            return false
        }
        return true
    }

    /** Installs [release]'s verified download, or asks the player to allow installs from this app first. */
    private suspend fun installOrAsk(release: Release) {
        if (!host.canInstall()) {
            _step.value = UpdateStep.NeedsPermission(release)
            return
        }
        _step.value = UpdateStep.Installing(release)
        try {
            host.install(release) { status, confirm -> onInstallResult(release, status, confirm) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // LATER while the APK was being handed over: nothing to show
            log("Could not hand the update to the installer: $e")
            _step.value = UpdateStep.Failed(release, UpdateProblem.Installer)
        }
    }

    override fun later() {
        dismissed = true
        work?.cancel()
        _step.value = UpdateStep.None
    }

    override fun cancel() {
        val release = when (val s = _step.value) {
            is UpdateStep.Downloading -> s.release
            is UpdateStep.Verifying -> s.release
            else -> return
        }
        work?.cancel()
        _step.value = UpdateStep.Offer(release)
    }

    override fun settingsMissing() {
        _step.update { if (it is UpdateStep.NeedsPermission) it.copy(manual = true) else it }
    }

    // ---- coming back --------------------------------------------------------------------------

    /** The app is in front again, maybe back from allowing installs: if it now may install, it does. */
    fun onResume() {
        val waiting = _step.value as? UpdateStep.NeedsPermission ?: return
        if (!host.canInstall()) return
        work?.cancel()
        work = scope.launch { installOrAsk(waiting.release) }
    }

    /**
     * Android's installer's verdict on [release], with its confirmation screen [confirm] when it wants the player to
     * confirm. Once the window has moved on (LATER), it no longer matters: nothing is confirmed, nothing shown.
     */
    private fun onInstallResult(release: Release, status: Int, confirm: Any?) {
        val installing = _step.value as? UpdateStep.Installing
        if (installing?.release != release) return
        when (status) {
            // The game shows the screen (see takeConfirmation); the verdict proper comes after it.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> _step.value =
                if (confirm != null) installing.copy(confirm = confirm) else UpdateStep.Failed(release, UpdateProblem.Installer)
            // Normally Android has replaced this app (and ended its process) before this could arrive.
            PackageInstaller.STATUS_SUCCESS -> _step.value = UpdateStep.None
            else -> {
                log("The installer said no: status $status")
                _step.value = UpdateStep.Failed(release, UpdateProblem.Install(status))
            }
        }
    }

    /**
     * Android's confirmation screen to show now, taken once: the game shows it while it is in front, and only while the
     * window is still installing. Null when there is none.
     */
    fun takeConfirmation(): Any? {
        val installing = _step.value as? UpdateStep.Installing ?: return null
        val confirm = installing.confirm ?: return null
        _step.value = installing.copy(confirm = null)
        return confirm
    }

    /** The confirmation screen from [takeConfirmation] could not be shown. */
    fun confirmationFailed() {
        val installing = _step.value as? UpdateStep.Installing ?: return
        log("Could not show the installer's confirmation")
        _step.value = UpdateStep.Failed(installing.release, UpdateProblem.Installer)
    }

    override fun onCleared() {
        host.close()
    }
}
