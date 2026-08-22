import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    /**
     * Release signing, if this machine has a key for it.
     *
     * `keystore.properties` is local and gitignored; it names a keystore kept
     * outside the repository so it cannot be committed by accident. Without it
     * the build still works — it falls back to the shared development key that
     * ships in Light's SDK, which is right for side-loading your own builds and
     * wrong for anything published, since everyone building a tool has it.
     */
    val releaseKeystore = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val canSignRelease = releaseKeystore.getProperty("storeFile")
        ?.let { File(it).exists() } == true

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
        if (canSignRelease) {
            create("release") {
                storeFile = File(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    // So the About screen can read the version out of lighttool.toml rather
    // than repeating it in a string that drifts — which is how it came to claim
    // 1.0.0 through an entire rename.
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // The dev key only as a fallback, so a contributor without a
            // keystore can still produce a build — theirs simply won't be one
            // that can update an installed copy.
            signingConfig = signingConfigs.getByName(
                if (canSignRelease) "release" else "lightsdkDev",
            )
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))

    // Exact Material icons matching the original app (album, music-note,
    // record-voice-over, graphic-eq, …) with per-use tint control.
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.material:material-icons-extended")

    // Networking (Subsonic API over ktor + okhttp engine)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)

    // Local library cache
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Background library sync
    implementation(libs.androidx.work.runtime)

    // Media3 Player constants for repeat mode (playback itself goes through the SDK)
    implementation(libs.androidx.media3.common)

    ksp(libs.androidx.room.compiler)
    testImplementation(libs.kotlin.test)
}

/**
 * Room schemas, exported and committed, so a schema change ships as an
 * auto-migration rather than a wipe. The SDK's database helper can only fall
 * back to dropping every table — a tool has no way to hand it migrations — but
 * auto-migrations need no handing over: the generated database carries them,
 * and Room uses one whenever the two versions' schemas are here to diff.
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
