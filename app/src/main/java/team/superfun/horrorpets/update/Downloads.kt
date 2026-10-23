package team.superfun.horrorpets.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import team.superfun.horrorpets.versionCodeOf

/** Bytes per read while downloading or hashing. */
const val COPY_BUFFER_BYTES = 64 * 1024

/** A download reports its progress about this often (bytes), so the window's bar moves without a flood of updates. */
const val PROGRESS_STEP_BYTES = 512 * 1024

/** Room kept free beyond what an update itself takes, for the install's own files. */
const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024

/**
 * The free space an update of [size] bytes needs before its download starts: the APK itself, plus the copy of it
 * Android's installer stages while it installs (in the same storage), plus [SPACE_MARGIN_BYTES].
 */
fun spaceNeeded(size: Long): Long = 2 * size + SPACE_MARGIN_BYTES

/** [bytes] in whole megabytes (MiB), rounded as the landing page rounds the APK's size. */
fun megabytes(bytes: Long): Long = (bytes + 512 * 1024) / (1024 * 1024)

/**
 * Copies a download from [input] to [output] and returns how many bytes it was. [onProgress] gets the bytes so far
 * every [PROGRESS_STEP_BYTES] or so, and the total at the end. More than [limit] bytes is an [UpdateFailure]
 * (BadDownload), so a wrong answer can never fill the disk. Between reads it stops with a [CancellationException]
 * once [active] says no.
 */
fun copyDownload(input: InputStream, output: OutputStream, limit: Long, onProgress: (Long) -> Unit, active: () -> Boolean = { true }): Long {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    var reported = 0L
    while (true) {
        if (!active()) throw CancellationException("download cancelled")
        val n = input.read(buffer)
        if (n < 0) break
        if (total + n > limit) throw UpdateFailure(UpdateProblem.BadDownload)
        output.write(buffer, 0, n)
        total += n
        if (total - reported >= PROGRESS_STEP_BYTES) {
            reported = total
            onProgress(total)
        }
    }
    if (total != reported) onProgress(total)
    return total
}

/**
 * The SHA-256 of everything in [input], as lowercase hex. Between reads it stops with a [CancellationException] once
 * [active] says no.
 */
fun sha256Hex(input: InputStream, active: () -> Boolean = { true }): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    while (true) {
        if (!active()) throw CancellationException("hash cancelled")
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** All of [input], which must be at most [max] bytes (an [IOException] if it is more). For small answers, never APKs. */
fun readCapped(input: InputStream, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (out.size() + n > max) throw IOException("more than $max bytes")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * What is wrong with a download of [size] bytes hashing to [sha256] (null when not hashed) for [release], or null when
 * it is the release's APK: exactly its size and, when GitHub gave a hash for it, that hash.
 */
fun downloadProblem(release: Release, size: Long, sha256: String?): UpdateProblem? = when {
    size != release.size -> UpdateProblem.BadDownload
    release.sha256 != null && sha256 != release.sha256 -> UpdateProblem.BadDownload
    else -> null
}

/**
 * Whether an APK that says it is [packageName] at [versionCode] is [release] of this app, [ourPackage]. An APK Android
 * cannot read (both null) passes: its installer has the last word on that.
 */
fun isReleaseApk(packageName: String?, versionCode: Long?, ourPackage: String, release: Release): Boolean {
    if (packageName == null && versionCode == null) return true
    return packageName == ourPackage && versionCode == versionCodeOf(release.version).toLong()
}
