import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Versioning
// versionName comes from -PversionName=x.y.z (CI release); local builds are 0.0.0-dev.
// versionCode = major * 10000 + minor * 100 + patch, and 1 for dev builds.
// NOTE: build scripts cannot import app code, so this mirrors versionCodeOf() in
// src/main/java/team/superfun/horrorpets/BuildInfo.kt (which SmokeTest covers).
// Keep the two in sync.
// ---------------------------------------------------------------------------
fun versionCodeOf(name: String): Int {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(name) ?: return 1
    val (major, minor, patch) = match.destructured.toList().map { it.toInt() }
    require(minor < 100 && patch < 100) { "versionName '$name': minor and patch must each be < 100" }
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}

val appVersionName = providers.gradleProperty("versionName").orElse("0.0.0-dev").get()

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
// Release signing: only when HP_KEYSTORE_PATH is provided. PR builds never need secrets.
// ---------------------------------------------------------------------------
val keystorePath: String? = providers.environmentVariable("HP_KEYSTORE_PATH").orNull

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
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("HP_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("HP_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("HP_KEY_PASSWORD").orNull
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

    // Already-compressed media must stay stored so ExoPlayer/ImageDecoder can map it directly.
    androidResources {
        noCompress += listOf("ogg", "webp")
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
