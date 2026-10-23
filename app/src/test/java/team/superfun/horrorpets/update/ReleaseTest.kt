package team.superfun.horrorpets.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.AssetFixtures

/** GitHub's real answer for the v0.6.0 release (fixtures/release-latest-v0.6.0.json). */
private val V060 = AssetFixtures.text("release-latest-v0.6.0.json")

private const val V060_URL = "https://github.com/superfunteam/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk"
private const val V060_SHA = "5759e4b014eb6b062f75a3c8fa8ee362cab5e3e8dfcfdaeb0a8822da1f5d1b71"

private val V060_RELEASE = Release("0.6.0", V060_URL, 101_210_643L, V060_SHA)

/** A latest-release answer of the shape GitHub gives, with one APK asset. */
private fun releaseJson(
    tag: String = "v0.7.0",
    name: String = "horror-pets-0.7.0.apk",
    url: String = "https://github.com/superfunteam/horror-pets/releases/download/v0.7.0/horror-pets-0.7.0.apk",
    size: String = "104857600",
    state: String = "uploaded",
    digest: String? = null,
    prerelease: Boolean = false,
    extraAssets: String = "",
) = """
    {
      "tag_name": "$tag",
      "name": "Virtual Pet Slasher 2 Turbo",
      "draft": false,
      "prerelease": $prerelease,
      "assets": [
        $extraAssets
        {
          "name": "$name",
          "label": null,
          "state": "$state",
          "size": $size,
          ${if (digest != null) "\"digest\": \"$digest\"," else ""}
          "browser_download_url": "$url"
        }
      ],
      "body": "notes"
    }
""".trimIndent()

class ReleaseTest {
    // ---- tags ---------------------------------------------------------------------------------

    @Test
    fun aReleaseTagIsAVPrefixedVersion() {
        assertEquals("0.6.0", versionOfTag("v0.6.0"))
        assertEquals("1.2.3", versionOfTag("v1.2.3"))
        assertEquals("10.99.99", versionOfTag("v10.99.99"))
    }

    @Test
    fun anythingButAStrictTagIsNoRelease() {
        for (bad in listOf("v1.2", "v1", "1.2.3", "v1.2.3-beta", "v1.2.3.4", "V1.2.3", "v 1.2.3", "v1.2.3 ", "vx.y.z", "v1.100.0", "v0.0.0-dev", "", "garbage", "latest")) {
            assertNull(bad, versionOfTag(bad))
        }
    }

    // ---- download URLs ------------------------------------------------------------------------

    @Test
    fun theReleasesOwnDownloadsAreAllowed() {
        assertTrue(isReleaseDownload(V060_URL))
        assertTrue(isReleaseDownload("https://github.com/superfunteam/horror-pets/releases/download/v1.0.0/horror-pets-1.0.0.apk"))
    }

    @Test
    fun anyOtherPlaceIsNot() {
        for (bad in listOf(
            "http://github.com/superfunteam/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk",
            "https://github.com/someone-else/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk",
            "https://github.com/superfunteam/other-repo/releases/download/v0.6.0/horror-pets-0.6.0.apk",
            "https://github.com.evil.example/superfunteam/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk",
            "https://evil.example/superfunteam/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk",
            "https://github.com/superfunteam/horror-pets/releases/download/../../../evil/x.apk",
            "https://github.com/superfunteam/horror-pets/releases/download/v0.6.0/../horror-pets-0.6.0.apk",
            "https://github.com/superfunteam/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk?x=1",
            "https://github.com/superfunteam/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk#x",
            "https://github.com/superfunteam/horror-pets/releases/download/v0.6.0/a/horror-pets-0.6.0.apk",
            "https://github.com/superfunteam/horror-pets/releases/download/",
            "https://github.com/superfunteam/horror-pets/releases/download/v0.6.0/",
            " https://github.com/superfunteam/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk",
            "",
        )) {
            assertFalse(bad, isReleaseDownload(bad))
        }
    }

    // ---- the release JSON ---------------------------------------------------------------------

    @Test
    fun githubsRealAnswerForV060() {
        assertEquals(V060_RELEASE, parseLatestRelease(V060))
    }

    @Test
    fun aShapedAnswerWithoutADigest() {
        assertEquals(
            Release("0.7.0", "https://github.com/superfunteam/horror-pets/releases/download/v0.7.0/horror-pets-0.7.0.apk", 104_857_600L, null),
            parseLatestRelease(releaseJson()),
        )
    }

    @Test
    fun theApkIsTheAssetNamedExactlyForTheTag() {
        val other = """{ "name": "notes.txt", "size": 10, "state": "uploaded", "browser_download_url": "https://github.com/superfunteam/horror-pets/releases/download/v0.7.0/notes.txt" },"""
        assertEquals("0.7.0", parseLatestRelease(releaseJson(extraAssets = other))?.version)
        for (name in listOf("horror-pets-0.6.0.apk", "horror-pets-0.7.0.APK", "Horror-pets-0.7.0.apk", "horror-pets-0.7.0.apk.zip", "horror-pets.apk", "xhorror-pets-0.7.0.apk")) {
            assertNull(name, parseLatestRelease(releaseJson(name = name)))
        }
    }

    @Test
    fun anAssetElsewhereIsNeverOffered() {
        assertNull(parseLatestRelease(releaseJson(url = "https://evil.example/horror-pets-0.7.0.apk")))
        assertNull(parseLatestRelease(releaseJson(url = "http://github.com/superfunteam/horror-pets/releases/download/v0.7.0/horror-pets-0.7.0.apk")))
    }

    @Test
    fun aBadTagIsNoRelease() {
        assertNull(parseLatestRelease(releaseJson(tag = "v0.7")))
        assertNull(parseLatestRelease(releaseJson(tag = "v0.7.0-beta")))
        assertNull(parseLatestRelease(releaseJson(tag = "0.7.0")))
    }

    @Test
    fun anUnfinishedOrEmptyAssetIsNoRelease() {
        assertNull(parseLatestRelease(releaseJson(state = "starter")))
        assertNull(parseLatestRelease(releaseJson(size = "0")))
        assertNull(parseLatestRelease(releaseJson(size = "-5")))
        assertNull(parseLatestRelease(releaseJson(size = "\"big\"")))
    }

    @Test
    fun aPreReleaseIsNoRelease() {
        assertNull(parseLatestRelease(releaseJson(prerelease = true)))
    }

    @Test
    fun aDigestCountsOnlyAsAFullSha256() {
        val sha = "AB".repeat(32)
        assertEquals(sha.lowercase(), parseLatestRelease(releaseJson(digest = "sha256:$sha"))?.sha256)
        for (odd in listOf("md5:abcdef", "sha256:abc", "sha256:${"zz".repeat(32)}", "sha256:${"ab".repeat(33)}", "")) {
            val release = parseLatestRelease(releaseJson(digest = odd))
            assertEquals(odd, "0.7.0", release?.version)
            assertNull(odd, release?.sha256)
        }
    }

    @Test
    fun malformedAnswersAreNoRelease() {
        for (bad in listOf("", "null", "[]", "{", "not json", "{}", """{"tag_name": 7}""", """{"tag_name": "v0.7.0"}""",
            """{"tag_name": "v0.7.0", "assets": "none"}""", """{"message": "API rate limit exceeded", "documentation_url": "https://docs.github.com"}""")) {
            assertNull(bad, parseLatestRelease(bad))
        }
    }

    // ---- newer or not -------------------------------------------------------------------------

    @Test
    fun onlyANewerReleaseIsAnUpdate() {
        assertEquals(V060_RELEASE, newerRelease(V060_RELEASE, "0.5.0"))
        assertEquals(V060_RELEASE, newerRelease(V060_RELEASE, "0.5.99"))
        assertEquals(V060_RELEASE, newerRelease(V060_RELEASE, "0.0.1"))
        assertNull(newerRelease(V060_RELEASE, "0.6.0"))
        assertNull(newerRelease(V060_RELEASE, "0.6.1"))
        assertNull(newerRelease(V060_RELEASE, "1.0.0"))
    }

    @Test
    fun versionsCompareByNumberNotText() {
        val v0100 = V060_RELEASE.copy(version = "0.10.0")
        assertEquals(v0100, newerRelease(v0100, "0.9.0"))
        assertNull(newerRelease(V060_RELEASE.copy(version = "0.9.0"), "0.10.0"))
    }

    @Test
    fun aDevBuildNeverTakesUpdates() {
        assertNull(newerRelease(V060_RELEASE, "0.0.0-dev"))
    }

    @Test
    fun anUnreadableVersionNeverTakesUpdates() {
        assertNull(newerRelease(V060_RELEASE, ""))
        assertNull(newerRelease(V060_RELEASE, "0.5"))
        assertNull(newerRelease(V060_RELEASE.copy(version = "banana"), "0.5.0"))
    }

    @Test
    fun theApkNameMatchesTheReleaseScript() {
        assertEquals("horror-pets-0.6.0.apk", apkNameOf("0.6.0"))
    }
}
