import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Versioning
// versionName comes from -PversionName=x.y.z (release builds); local builds are 0.0.0-dev.
// versionCode = major * 10000 + minor * 100 + patch, and 1 for the dev sentinel.
// Anything else (a 'v' prefix, a suffix, two components) fails the build instead of guessing.
// NOTE: build scripts cannot import app code, so this mirrors versionCodeOf() in
// src/main/java/team/superfun/horrorpets/BuildInfo.kt (which BuildInfoTest covers).
// Keep the two in sync.
// ---------------------------------------------------------------------------
val devVersionName = "0.0.0-dev"
val releaseVersionRegex = Regex("""(\d+)\.(\d+)\.(\d+)""")
val maxMajorVersion = 209_999 // Android caps versionCode at 2_100_000_000.

fun versionCodeOf(name: String): Int {
    if (name == devVersionName) return 1
    val match = requireNotNull(releaseVersionRegex.matchEntire(name)) {
        "versionName '$name' must be MAJOR.MINOR.PATCH (for example 1.2.3, with no 'v' prefix or suffix), " +
            "or '$devVersionName' for dev builds"
    }
    // Digit runs too long for an Int count as out of range rather than blowing up with a parse error.
    val (major, minor, patch) = match.destructured.toList().map { it.toIntOrNull() ?: Int.MAX_VALUE }
    require(major <= maxMajorVersion) { "versionName '$name': major must be <= $maxMajorVersion" }
    require(minor < 100 && patch < 100) { "versionName '$name': minor and patch must each be < 100" }
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}

val appVersionName = providers.gradleProperty("versionName").orElse(devVersionName).get()

// ---------------------------------------------------------------------------
// Game assets: sprites-final/, scenes-final/ and audio-final/ live at the repo root and are
// mirrored into the APK's assets/ as sprites/, scenes/ and audio/.
// ---------------------------------------------------------------------------
abstract class SyncGameAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spritesDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scenesDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val audioDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun sync() {
        // sync{} (not copy{}) so files removed from the source folders disappear from the output.
        fileSystem.sync {
            into(outputDir)
            from(spritesDir) { into("sprites") }
            from(scenesDir) { into("scenes") }
            from(audioDir) {
                into("audio")
                exclude("CREDITS.md")
            }
            exclude("**/.DS_Store")
        }
    }
}

val syncGameAssets = tasks.register<SyncGameAssets>("syncGameAssets") {
    description = "Copies sprites-final, scenes-final and audio-final into the APK assets layout."
    group = "build"
    spritesDir.set(rootProject.layout.projectDirectory.dir("sprites-final"))
    scenesDir.set(rootProject.layout.projectDirectory.dir("scenes-final"))
    audioDir.set(rootProject.layout.projectDirectory.dir("audio-final"))
    outputDir.set(layout.buildDirectory.dir("generated/gameAssets"))
}

// ---------------------------------------------------------------------------
// Release signing: only when HP_KEYSTORE_PATH is set (and not blank). Builds without it, such as PR
// and tag verification builds in CI, produce an unsigned release APK and never need secrets.
// tools/release.sh exports all four variables for the one Gradle run that makes the signed APK.
// ---------------------------------------------------------------------------
fun optionalEnv(name: String): String? =
    providers.environmentVariable(name).filter { it.isNotBlank() }.orNull

val keystorePath: String? = optionalEnv("HP_KEYSTORE_PATH")

fun requiredSigningEnv(name: String): String = optionalEnv(name)
    ?: throw GradleException(
        "HP_KEYSTORE_PATH is set, but $name is missing or blank. Release signing needs " +
            "HP_KEYSTORE_PASSWORD, HP_KEY_ALIAS and HP_KEY_PASSWORD as well (tools/release.sh sets all four).",
    )

android {
    namespace = "team.superfun.horrorpets"
    compileSdk = 36

    defaultConfig {
        applicationId = "team.superfun.horrorpets"
        minSdk = 22
        targetSdk = 36
        versionName = appVersionName
        versionCode = versionCodeOf(appVersionName)
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath).also {
                    if (!it.isFile) throw GradleException("HP_KEYSTORE_PATH does not point to a file: $it")
                }
                storePassword = requiredSigningEnv("HP_KEYSTORE_PASSWORD")
                keyAlias = requiredSigningEnv("HP_KEY_ALIAS")
                keyPassword = requiredSigningEnv("HP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Unsigned when no keystore is configured.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Media must stay stored so ExoPlayer/ImageDecoder can map it directly and SoundPool can openFd() it.
    androidResources {
        noCompress += listOf("ogg", "wav", "webp")
    }
}

kotlin {
    jvmToolchain(17)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncGameAssets, SyncGameAssets::outputDir)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
