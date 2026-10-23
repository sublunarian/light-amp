pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // The LP3 keyboard, which the SDK pulls from JitPack; no credentials needed.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("light-sdk/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "light-amp"

// ---------------------------------------------------------------------------
// The Light SDK is the pristine `light-sdk` submodule; every Amp change to it
// lives as a patch file in `light-sdk-patch/` (its README says what each one
// is). A clean submodule gets the full set applied here and KEEPS it applied,
// so builds and the IDE always see the patched sources. A dirty submodule is
// left alone — patched is its normal state, and it is also what work in
// progress on a spike looks like. `scripts/sdk-patches.sh` has
// apply / revert / regen / check helpers.
// ---------------------------------------------------------------------------
val sdkDir = file("light-sdk")
if (!File(sdkDir, "sdk").exists()) {
    error("The light-sdk submodule is empty. Run: git submodule update --init")
}

fun sdkGit(vararg args: String): Int = ProcessBuilder("git", *args)
    .directory(sdkDir)
    .redirectErrorStream(true)
    .start()
    .waitFor()

val sdkIsPristine = ProcessBuilder("git", "status", "--porcelain")
    .directory(sdkDir)
    .start()
    .inputStream.bufferedReader().readText().isBlank()

if (sdkIsPristine) {
    val patches = file("light-sdk-patch")
        .listFiles { f: File -> f.isFile && f.extension == "patch" }
        ?.sortedBy { it.name }
        .orEmpty()
    // Check the whole set before touching anything, so a drifted submodule
    // fails loudly instead of being left half-patched.
    for (patch in patches) {
        check(sdkGit("apply", "--check", patch.absolutePath) == 0) {
            "light-sdk-patch/${patch.name} no longer applies to the light-sdk " +
                "submodule — it has moved past what the patch expects. " +
                "Regenerate the patch against the current submodule " +
                "(scripts/sdk-patches.sh) before building."
        }
    }
    for (patch in patches) {
        check(sdkGit("apply", patch.absolutePath) == 0) {
            "Applying light-sdk-patch/${patch.name} failed after its dry run passed."
        }
    }
    if (patches.isNotEmpty()) {
        println("Applied ${patches.size} Amp patches to the light-sdk submodule.")
    }
}

includeBuild("light-sdk/plugin")

include(":lint-rules")
project(":lint-rules").projectDir = file("light-sdk/lint-rules")

include(":sdk:shared")
project(":sdk").projectDir = file("light-sdk/sdk")
project(":sdk:shared").projectDir = file("light-sdk/sdk/shared")

include(":sdk:ui")
project(":sdk:ui").projectDir = file("light-sdk/sdk/ui")

include(":sdk:client")
project(":sdk:client").projectDir = file("light-sdk/sdk/client")

include(":sdk:server")
project(":sdk:server").projectDir = file("light-sdk/sdk/server")

include(":sdk:emulator")
project(":sdk:emulator").projectDir = file("light-sdk/sdk/emulator")

include(":tool")
