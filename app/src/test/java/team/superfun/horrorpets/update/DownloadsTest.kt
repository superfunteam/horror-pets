package team.superfun.horrorpets.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private val RELEASE = Release(
    "0.6.0",
    "https://github.com/superfunteam/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk",
    101_210_643L,
    // sha256 of "abc"
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
)

private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

class DownloadsTest {
    // ---- copying ------------------------------------------------------------------------------

    @Test
    fun copiesEveryByteAndReportsTheTotalLast() {
        val data = bytes(3 * PROGRESS_STEP_BYTES + 123)
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val copied = copyDownload(ByteArrayInputStream(data), out, limit = data.size.toLong(), onProgress = { progress += it })
        assertEquals(data.size.toLong(), copied)
        assertArrayEquals(data, out.toByteArray())
        assertEquals(data.size.toLong(), progress.last())
        assertEquals("progress only grows", progress.sorted(), progress)
        assertTrue("reported now and then, not per read: $progress", progress.size in 2..5)
    }

    @Test
    fun aShortDownloadCopiesWhatCame() {
        val out = ByteArrayOutputStream()
        assertEquals(10L, copyDownload(ByteArrayInputStream(bytes(10)), out, limit = 100, onProgress = {}))
    }

    @Test
    fun moreThanTheReleaseSizeIsABadDownload() {
        val out = ByteArrayOutputStream()
        val e = assertThrows(UpdateFailure::class.java) {
            copyDownload(ByteArrayInputStream(bytes(PROGRESS_STEP_BYTES * 2)), out, limit = 1000, onProgress = {})
        }
        assertEquals(UpdateProblem.BadDownload, e.problem)
        assertTrue("stops at the limit, never writes far past it", out.size() <= 1000 + COPY_BUFFER_BYTES)
    }

    @Test
    fun aCancelStopsTheCopy() {
        var reads = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return len
            }
        }
        assertThrows(CancellationException::class.java) {
            copyDownload(endless, ByteArrayOutputStream(), limit = Long.MAX_VALUE, onProgress = {}, active = { reads < 5 })
        }
        assertEquals(5, reads)
    }

    @Test
    fun sha256IsLowercaseHex() {
        assertEquals(RELEASE.sha256, sha256Hex(ByteArrayInputStream("abc".toByteArray())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun readCappedReadsSmallAnswersAndRefusesHugeOnes() {
        assertArrayEquals(bytes(500), readCapped(ByteArrayInputStream(bytes(500)), 500))
        assertThrows(IOException::class.java) { readCapped(ByteArrayInputStream(bytes(501)), 500) }
    }

    // ---- verifying ----------------------------------------------------------------------------

    @Test
    fun theRightSizeAndHashIsTheRelease() {
        assertNull(downloadProblem(RELEASE, RELEASE.size, RELEASE.sha256))
    }

    @Test
    fun aDownloadOfAnotherSizeIsBad() {
        assertEquals(UpdateProblem.BadDownload, downloadProblem(RELEASE, RELEASE.size - 1, RELEASE.sha256))
        assertEquals(UpdateProblem.BadDownload, downloadProblem(RELEASE, RELEASE.size + 1, RELEASE.sha256))
        assertEquals(UpdateProblem.BadDownload, downloadProblem(RELEASE, 0, RELEASE.sha256))
    }

    @Test
    fun aDownloadWithAnotherHashIsBad() {
        assertEquals(UpdateProblem.BadDownload, downloadProblem(RELEASE, RELEASE.size, "00".repeat(32)))
        assertEquals(UpdateProblem.BadDownload, downloadProblem(RELEASE, RELEASE.size, null))
    }

    @Test
    fun withoutADigestTheSizeIsEnough() {
        val noDigest = RELEASE.copy(sha256 = null)
        assertNull(downloadProblem(noDigest, noDigest.size, null))
        assertNull(downloadProblem(noDigest, noDigest.size, "00".repeat(32)))
        assertEquals(UpdateProblem.BadDownload, downloadProblem(noDigest, noDigest.size - 1, null))
    }

    @Test
    fun theApkMustBeThisGameAtTheReleasesVersion() {
        val us = "team.superfun.horrorpets"
        assertTrue(isReleaseApk("team.superfun.horrorpets", 600L, us, RELEASE))
        assertTrue("unreadable: the installer judges it", isReleaseApk(null, null, us, RELEASE))
        assertEquals(false, isReleaseApk("com.example.other", 600L, us, RELEASE))
        assertEquals(false, isReleaseApk(us, 500L, us, RELEASE))
    }

    // ---- space and sizes ----------------------------------------------------------------------

    @Test
    fun anUpdateNeedsRoomForTheDownloadAndTheInstallersCopy() {
        assertEquals(2 * RELEASE.size + SPACE_MARGIN_BYTES, spaceNeeded(RELEASE.size))
    }

    @Test
    fun megabytesRoundLikeTheLandingPage() {
        // site/main.js: Math.round(size / 1048576)
        assertEquals(97L, megabytes(101_210_643L))
        assertEquals(0L, megabytes(0))
        assertEquals(1L, megabytes(524_288))
        assertEquals(0L, megabytes(524_287))
        assertEquals(100L, megabytes(100L * 1_048_576))
    }
}
