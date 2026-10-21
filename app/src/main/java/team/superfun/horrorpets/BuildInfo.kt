package team.superfun.horrorpets

private val VERSION_PREFIX = Regex("""^(\d+)\.(\d+)\.(\d+)""")

/**
 * Android versionCode for a semantic versionName: major * 10000 + minor * 100 + patch.
 * Dev builds (e.g. "0.0.0-dev") and anything unparsable map to 1, since versionCode must be positive.
 *
 * app/build.gradle.kts carries a copy of this logic (build scripts cannot import app code).
 * Keep the two in sync; SmokeTest covers this one.
 */
fun versionCodeOf(name: String): Int {
    val match = VERSION_PREFIX.find(name) ?: return 1
    val (major, minor, patch) = match.destructured.toList().map { it.toInt() }
    require(minor < 100 && patch < 100) { "versionName '$name': minor and patch must each be < 100" }
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}
