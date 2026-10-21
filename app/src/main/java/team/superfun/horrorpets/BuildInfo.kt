package team.superfun.horrorpets

/** versionName of local and PR builds (the Gradle default when no -PversionName is given). */
const val DEV_VERSION_NAME = "0.0.0-dev"

private val RELEASE_VERSION = Regex("""(\d+)\.(\d+)\.(\d+)""")

// Android caps versionCode at 2_100_000_000; major * 10_000 + 9_999 must stay under that.
private const val MAX_MAJOR = 209_999

/**
 * Android versionCode for a versionName: major * 10000 + minor * 100 + patch.
 *
 * Only two shapes are accepted: the dev sentinel [DEV_VERSION_NAME], which maps to 1, and a strict
 * `MAJOR.MINOR.PATCH` (no `v` prefix, no suffix). Anything else throws, so a typo in a release tag can
 * never silently ship with the wrong versionCode. Minor and patch must each be below 100.
 *
 * app/build.gradle.kts carries a copy of this logic (build scripts cannot import app code).
 * Keep the two in sync; BuildInfoTest covers this one.
 */
fun versionCodeOf(name: String): Int {
    if (name == DEV_VERSION_NAME) return 1
    val match = requireNotNull(RELEASE_VERSION.matchEntire(name)) {
        "versionName '$name' must be MAJOR.MINOR.PATCH (for example 1.2.3, with no 'v' prefix or suffix), " +
            "or '$DEV_VERSION_NAME' for dev builds"
    }
    // Digit runs too long for an Int count as out of range rather than blowing up with a parse error.
    val (major, minor, patch) = match.destructured.toList().map { it.toIntOrNull() ?: Int.MAX_VALUE }
    require(major <= MAX_MAJOR) { "versionName '$name': major must be <= $MAX_MAJOR" }
    require(minor < 100 && patch < 100) { "versionName '$name': minor and patch must each be < 100" }
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}
