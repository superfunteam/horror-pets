package team.superfun.horrorpets.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.superfun.horrorpets.DEV_VERSION_NAME
import team.superfun.horrorpets.versionCodeOf

/**
 * GitHub's newest release of the game (drafts and pre-releases never count as the latest). The landing page
 * (site/main.js) reads the same.
 */
const val LATEST_RELEASE_URL = "https://api.github.com/repos/superfunteam/horror-pets/releases/latest"

/** Where every release's APK is downloaded from. A download anywhere else is never offered. */
const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/superfunteam/horror-pets/releases/download/"

/**
 * A release's APK, as the update check found it: the [version] it installs ("0.6.0"), where to download it ([url]),
 * how many bytes it is ([size]) and, when GitHub gave one, its SHA-256 ([sha256], lowercase hex).
 */
data class Release(val version: String, val url: String, val size: Long, val sha256: String? = null)

/** The asset tools/release.sh uploads for [version]. */
fun apkNameOf(version: String): String = "horror-pets-$version.apk"

/**
 * The versionName a release tag is for: `v0.6.0` is 0.6.0. Anything but a strict `vMAJOR.MINOR.PATCH` that
 * [versionCodeOf] takes (no suffix, minor and patch below 100) is null.
 */
fun versionOfTag(tag: String): String? {
    if (!tag.startsWith("v")) return null
    val name = tag.substring(1)
    if (name == DEV_VERSION_NAME) return null
    return name.takeIf { runCatching { versionCodeOf(it) }.isSuccess }
}

/** A release's tag and APK name after [RELEASE_DOWNLOAD_PREFIX]: two plain path parts, nothing more. */
private val DOWNLOAD_PATH = Regex("""[A-Za-z0-9._-]+/[A-Za-z0-9._-]+""")

/**
 * Whether [url] is one of this repo's release downloads: under [RELEASE_DOWNLOAD_PREFIX] (https, github.com, this
 * repo) with just a tag and a file name after it, no `.` or `..` parts, query or fragment.
 */
fun isReleaseDownload(url: String): Boolean {
    if (!url.startsWith(RELEASE_DOWNLOAD_PREFIX)) return false
    val path = url.substring(RELEASE_DOWNLOAD_PREFIX.length)
    return DOWNLOAD_PATH.matches(path) && path.split('/').none { it == "." || it == ".." }
}

/** GitHub's `"sha256:<64 hex>"` asset digest. */
private val SHA256_DIGEST = Regex("""sha256:([0-9a-fA-F]{64})""")

@Serializable
private data class WireRelease(
    @SerialName("tag_name") val tag: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<WireAsset> = emptyList(),
)

@Serializable
private data class WireAsset(
    val name: String? = null,
    val size: Long? = null,
    val state: String? = null,
    val digest: String? = null,
    @SerialName("browser_download_url") val url: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * The release in GitHub's latest-release answer [body], or null when it holds none this app could install: not JSON
 * of that shape (an error, a rate limit), a draft or pre-release, a tag that is not a strict `vMAJOR.MINOR.PATCH`, or
 * no finished asset named exactly [apkNameOf] its version at a [isReleaseDownload] URL. Whether it is newer is
 * [newerRelease]'s call.
 */
fun parseLatestRelease(body: String): Release? {
    val wire = try {
        json.decodeFromString<WireRelease>(body)
    } catch (e: IllegalArgumentException) { // SerializationException is one
        return null
    }
    if (wire.draft || wire.prerelease) return null
    val version = wire.tag?.let(::versionOfTag) ?: return null
    val asset = wire.assets.firstOrNull { it.name == apkNameOf(version) } ?: return null
    if (asset.state != null && asset.state != "uploaded") return null
    val url = asset.url?.takeIf(::isReleaseDownload) ?: return null
    val size = asset.size?.takeIf { it > 0 } ?: return null
    val sha256 = asset.digest?.let { SHA256_DIGEST.matchEntire(it) }?.groupValues?.get(1)?.lowercase()
    return Release(version, url, size, sha256)
}

/**
 * [release] when it is newer than the running build, [runningVersion]; null when it is the same or older. A dev
 * build ([DEV_VERSION_NAME]), or a versionName that is not a release's, never takes updates.
 */
fun newerRelease(release: Release, runningVersion: String): Release? {
    if (runningVersion == DEV_VERSION_NAME) return null
    val running = runCatching { versionCodeOf(runningVersion) }.getOrNull() ?: return null
    val offered = runCatching { versionCodeOf(release.version) }.getOrNull() ?: return null
    return release.takeIf { offered > running }
}
