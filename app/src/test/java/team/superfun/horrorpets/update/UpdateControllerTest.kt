@file:OptIn(ExperimentalCoroutinesApi::class)

package team.superfun.horrorpets.update

import android.content.pm.PackageInstaller
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.DEV_VERSION_NAME
import team.superfun.horrorpets.assets.AssetFixtures

/** GitHub's real answer for v0.6.0, and the release it is. */
private val V060_JSON = AssetFixtures.text("release-latest-v0.6.0.json")
private val V060 = parseLatestRelease(V060_JSON)!!

/** A stand-in for the device: GitHub's answer, the disk, the installer. Gates hold a step until a test opens them. */
private class FakeHost : UpdateHost {
    /** GitHub's answer, or null for no connection. */
    var json: String? = V060_JSON
    var fetches = 0
    val cleanUps = mutableListOf<Release?>()

    var free = Long.MAX_VALUE

    /** Releases whose APK is downloaded and verified. */
    val ready = mutableSetOf<Release>()
    var downloads = 0
    var downloadGate: CompletableDeferred<Unit>? = null
    var downloadFailure: Exception? = null
    var verifyGate: CompletableDeferred<Unit>? = null
    var verifyProblem: UpdateProblem? = null

    var canInstall = true
    val installs = mutableListOf<Release>()
    var installFailure: Exception? = null

    /** Where Android's verdict on the last install goes. */
    var verdict: ((Int) -> Unit)? = null

    override suspend fun latestReleaseJson(): String {
        fetches++
        return json ?: throw IOException("offline")
    }

    override suspend fun cleanUp(keep: Release?) {
        cleanUps += keep
    }

    override suspend fun isDownloaded(release: Release) = release in ready

    override fun freeBytes() = free

    override suspend fun download(release: Release, onProgress: (Long) -> Unit) {
        downloads++
        onProgress(release.size / 2)
        downloadGate?.await()
        downloadFailure?.let { throw it }
        onProgress(release.size)
    }

    override suspend fun verify(release: Release): UpdateProblem? {
        verifyGate?.await()
        return verifyProblem.also { if (it == null) ready += release }
    }

    override fun canInstall() = canInstall

    override suspend fun install(release: Release, onResult: (Int) -> Unit) {
        installFailure?.let { throw it }
        installs += release
        verdict = onResult
    }
}

private class Rig(var running: String = "0.5.0") {
    val host = FakeHost()
    val scope = TestScope(StandardTestDispatcher())
    val log = mutableListOf<String>()
    val updates = UpdateController(host, runningVersion = { running }, scope = scope, log = { log += it })

    val step: UpdateStep get() = updates.step.value

    fun settle() = scope.advanceUntilIdle()

    /** Checks at launch and lands on the offer. */
    fun offered(): Rig = apply {
        updates.checkOnce()
        settle()
        assertEquals(UpdateStep.Offer(V060), step)
    }
}

class UpdateControllerTest {
    // ---- the check ----------------------------------------------------------------------------

    @Test
    fun aNewerReleaseIsOfferedAndOnlyItsApkKept() {
        val rig = Rig("0.5.0").offered()
        assertEquals(listOf<Release?>(V060), rig.host.cleanUps)
    }

    @Test
    fun theSameOrAnOlderReleaseIsNoOfferAndClearsOldDownloads() {
        for (running in listOf("0.6.0", "0.7.0")) {
            val rig = Rig(running)
            rig.updates.checkOnce()
            rig.settle()
            assertEquals(running, UpdateStep.None, rig.step)
            assertEquals(running, listOf<Release?>(null), rig.host.cleanUps)
        }
    }

    @Test
    fun aDevBuildNeverChecks() {
        val rig = Rig(DEV_VERSION_NAME)
        rig.updates.checkOnce()
        rig.settle()
        assertEquals(UpdateStep.None, rig.step)
        assertEquals(0, rig.host.fetches)
    }

    @Test
    fun noConnectionIsSilent() {
        val rig = Rig()
        rig.host.json = null
        rig.updates.checkOnce()
        rig.settle()
        assertEquals(UpdateStep.None, rig.step)
        assertEquals("nothing deleted on a guess", emptyList<Release?>(), rig.host.cleanUps)
    }

    @Test
    fun aMalformedOrRateLimitedAnswerIsSilent() {
        for (json in listOf("{", """{"message": "API rate limit exceeded"}""", V060_JSON.replace("v0.6.0", "v0.6"))) {
            val rig = Rig()
            rig.host.json = json
            rig.updates.checkOnce()
            rig.settle()
            assertEquals(json, UpdateStep.None, rig.step)
            assertEquals(json, emptyList<Release?>(), rig.host.cleanUps)
        }
    }

    @Test
    fun theLaunchChecksOnce() {
        val rig = Rig().offered()
        rig.updates.checkOnce()
        rig.settle()
        assertEquals(1, rig.host.fetches)
    }

    // ---- the offer ----------------------------------------------------------------------------

    @Test
    fun laterClosesItForTheRestOfTheLaunch() {
        val rig = Rig().offered()
        rig.updates.later()
        assertEquals(UpdateStep.None, rig.step)
        rig.updates.checkOnce()
        rig.settle()
        assertEquals(UpdateStep.None, rig.step)
        assertEquals(1, rig.host.fetches)
    }

    @Test
    fun aCheckNowAsksAgainEvenAfterLater() {
        val rig = Rig().offered()
        rig.updates.later()
        rig.updates.checkNow()
        rig.settle()
        assertEquals(UpdateStep.Offer(V060), rig.step)
        assertEquals(2, rig.host.fetches)
    }

    @Test
    fun aCheckNowWithNothingNewerClosesTheOffer() {
        val rig = Rig().offered()
        rig.running = "0.6.0"
        rig.updates.checkNow()
        rig.settle()
        assertEquals(UpdateStep.None, rig.step)
    }

    @Test
    fun aCheckNowLeavesADownloadAlone() {
        val rig = Rig().offered()
        rig.host.downloadGate = CompletableDeferred()
        rig.updates.accept()
        rig.settle()
        rig.updates.checkNow()
        rig.settle()
        assertEquals(UpdateStep.Downloading(V060, V060.size / 2), rig.step)
        assertEquals(1, rig.host.fetches)
    }

    // ---- downloading --------------------------------------------------------------------------

    @Test
    fun updateDownloadsVerifiesAndInstalls() {
        val rig = Rig().offered()
        rig.host.downloadGate = CompletableDeferred()
        rig.host.verifyGate = CompletableDeferred()

        rig.updates.accept()
        assertEquals("the window changes on the press", UpdateStep.Downloading(V060, 0), rig.step)
        rig.settle()
        assertEquals(UpdateStep.Downloading(V060, V060.size / 2), rig.step)

        rig.host.downloadGate!!.complete(Unit)
        rig.settle()
        assertEquals(UpdateStep.Verifying(V060), rig.step)

        rig.host.verifyGate!!.complete(Unit)
        rig.settle()
        assertEquals(UpdateStep.Installing(V060), rig.step)
        assertEquals(listOf(V060), rig.host.installs)
        assertEquals(1, rig.host.downloads)
    }

    @Test
    fun notEnoughSpaceSaysHowMuchIsNeeded() {
        val rig = Rig().offered()
        rig.host.free = 150_000_000L
        rig.updates.accept()
        rig.settle()
        assertEquals(UpdateStep.Failed(V060, UpdateProblem.NoSpace(spaceNeeded(V060.size), 150_000_000L)), rig.step)
        assertEquals(0, rig.host.downloads)
    }

    @Test
    fun aBrokenDownloadCanBeRetried() {
        val rig = Rig().offered()
        rig.host.downloadFailure = IOException("connection reset")
        rig.updates.accept()
        rig.settle()
        assertEquals(UpdateStep.Failed(V060, UpdateProblem.Network), rig.step)

        rig.host.downloadFailure = null
        rig.updates.accept() // RETRY
        rig.settle()
        assertEquals(UpdateStep.Installing(V060), rig.step)
        assertEquals(2, rig.host.downloads)
    }

    @Test
    fun aDownloadThatSaysWhatWentWrongKeepsItsReason() {
        val rig = Rig().offered()
        rig.host.downloadFailure = UpdateFailure(UpdateProblem.BadDownload)
        rig.updates.accept()
        rig.settle()
        assertEquals(UpdateStep.Failed(V060, UpdateProblem.BadDownload), rig.step)
    }

    @Test
    fun aDownloadThatFailsVerificationIsBad() {
        val rig = Rig().offered()
        rig.host.verifyProblem = UpdateProblem.BadDownload
        rig.updates.accept()
        rig.settle()
        assertEquals(UpdateStep.Failed(V060, UpdateProblem.BadDownload), rig.step)
        assertEquals(emptyList<Release>(), rig.host.installs)
    }

    @Test
    fun cancelStopsTheDownloadAndGoesBackToTheOffer() {
        val rig = Rig().offered()
        val gate = CompletableDeferred<Unit>()
        rig.host.downloadGate = gate
        rig.updates.accept()
        rig.settle()
        rig.updates.cancel()
        assertEquals(UpdateStep.Offer(V060), rig.step)
        gate.complete(Unit)
        rig.settle()
        assertEquals("the cancelled download never carries on", UpdateStep.Offer(V060), rig.step)
        assertEquals(emptyList<Release>(), rig.host.installs)
    }

    @Test
    fun cancelWhileVerifyingGoesBackToTheOfferToo() {
        val rig = Rig().offered()
        rig.host.verifyGate = CompletableDeferred()
        rig.updates.accept()
        rig.settle()
        assertEquals(UpdateStep.Verifying(V060), rig.step)
        rig.updates.cancel()
        rig.host.verifyGate!!.complete(Unit)
        rig.settle()
        assertEquals(UpdateStep.Offer(V060), rig.step)
    }

    @Test
    fun anApkAlreadyDownloadedIsNotDownloadedAgain() {
        val rig = Rig().offered()
        rig.host.ready += V060
        rig.host.free = 0 // no space check either: the space is already taken
        rig.updates.accept()
        rig.settle()
        assertEquals(UpdateStep.Installing(V060), rig.step)
        assertEquals(0, rig.host.downloads)
    }

    // ---- permission to install ----------------------------------------------------------------

    @Test
    fun withoutPermissionItAsksAndInstallsOnceAllowed() {
        val rig = Rig().offered()
        rig.host.canInstall = false
        rig.updates.accept()
        rig.settle()
        assertEquals(UpdateStep.NeedsPermission(V060, manual = false), rig.step)

        rig.updates.onResume() // back without allowing it
        rig.settle()
        assertEquals(UpdateStep.NeedsPermission(V060, manual = false), rig.step)

        rig.host.canInstall = true
        rig.updates.onResume()
        rig.settle()
        assertEquals(UpdateStep.Installing(V060), rig.step)
        assertEquals(listOf(V060), rig.host.installs)
        assertEquals("downloaded once", 1, rig.host.downloads)
    }

    @Test
    fun noSettingsScreenFallsBackToInstructions() {
        val rig = Rig().offered()
        rig.host.canInstall = false
        rig.updates.accept()
        rig.settle()
        rig.updates.settingsMissing()
        assertEquals(UpdateStep.NeedsPermission(V060, manual = true), rig.step)
        rig.host.canInstall = true
        rig.updates.onResume()
        rig.settle()
        assertEquals(UpdateStep.Installing(V060), rig.step)
    }

    @Test
    fun aResumeOutsideThePermissionStepDoesNothing() {
        val rig = Rig().offered()
        rig.updates.onResume()
        rig.settle()
        assertEquals(UpdateStep.Offer(V060), rig.step)
        assertEquals(emptyList<Release>(), rig.host.installs)
    }

    // ---- installing ---------------------------------------------------------------------------

    @Test
    fun androidsConfirmationKeepsTheWindowInstalling() {
        val rig = Rig().offered()
        rig.updates.accept()
        rig.settle()
        rig.host.verdict!!(PackageInstaller.STATUS_PENDING_USER_ACTION)
        assertEquals(UpdateStep.Installing(V060), rig.step)
    }

    @Test
    fun aRefusedInstallSaysWhyAndRetryInstallsTheSameDownload() {
        val rig = Rig().offered()
        rig.updates.accept()
        rig.settle()
        rig.host.verdict!!(PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(UpdateStep.Failed(V060, UpdateProblem.Install(PackageInstaller.STATUS_FAILURE_ABORTED)), rig.step)

        rig.updates.accept() // RETRY
        rig.settle()
        assertEquals(UpdateStep.Installing(V060), rig.step)
        assertEquals(listOf(V060, V060), rig.host.installs)
        assertEquals(1, rig.host.downloads)
    }

    @Test
    fun aSuccessClosesTheWindow() {
        val rig = Rig().offered()
        rig.updates.accept()
        rig.settle()
        rig.host.verdict!!(PackageInstaller.STATUS_SUCCESS)
        assertEquals(UpdateStep.None, rig.step)
    }

    @Test
    fun aVerdictAfterTheWindowClosedIsIgnored() {
        val rig = Rig().offered()
        rig.updates.accept()
        rig.settle()
        rig.updates.later()
        rig.host.verdict!!(PackageInstaller.STATUS_FAILURE)
        assertEquals(UpdateStep.None, rig.step)
    }

    @Test
    fun anInstallerThatCannotBeReachedIsAFailure() {
        val rig = Rig().offered()
        rig.host.installFailure = IOException("session")
        rig.updates.accept()
        rig.settle()
        assertEquals(UpdateStep.Failed(V060, UpdateProblem.Installer), rig.step)
    }

    // ---- presses that do not apply ------------------------------------------------------------

    @Test
    fun pressesThatDoNotApplyAreIgnored() {
        val rig = Rig()
        rig.updates.accept()
        rig.updates.cancel()
        rig.updates.later()
        rig.updates.settingsMissing()
        rig.settle()
        assertEquals(UpdateStep.None, rig.step)

        rig.offered()
        rig.updates.cancel()
        rig.updates.settingsMissing()
        assertEquals(UpdateStep.Offer(V060), rig.step)
    }

    @Test
    fun aMashedUpdateDownloadsOnce() {
        val rig = Rig().offered()
        rig.updates.accept()
        rig.updates.accept()
        rig.settle()
        assertEquals(1, rig.host.downloads)
        assertEquals(listOf(V060), rig.host.installs)
    }

    @Test
    fun theCheckIsLogged() {
        val rig = Rig().offered()
        assertTrue(rig.log.joinToString("\n"), rig.log.any { "0.6.0" in it && "0.5.0" in it })
    }
}
