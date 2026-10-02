// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Deletes the "<name> <n>.class" copies left behind in `build/intermediates` before every module
 * build.
 *
 * Kotlin/Gradle incremental compilation moves a class aside under a numbered name instead of
 * deleting it, and a build that fails half way leaves those copies in place. The next build then
 * sees two files claiming the same class and dies in the bundling/dex step:
 *
 *   File .../CarPlayBackgroundSession 2.class already exists, it cannot be overwritten by
 *   SerializableChange(...)                 (bundleLibRuntimeToDirDebug)
 *   D8: Type com.shilapi.xcertplay... is defined multiple times   (mergeLibDex)
 *
 * The documented cure - `clean`, or `:common:clean :mobile:clean` - throws away every incremental
 * output to get rid of a handful of stale files, and it has to be remembered each time. Deleting
 * exactly those leftovers removes the failure and keeps the rest of the incremental state, so this
 * runs before every module's `preBuild`.
 *
 * Ant's `?` matches exactly one character, hence the two patterns for one- and two-digit copies.
 */
val purgeStaleClassCopies by tasks.registering(Delete::class) {
    description = "Deletes stale '<name> <n>.class' copies from build/intermediates"
    group = "build"
    subprojects.forEach { module ->
        delete(
            module.fileTree(module.layout.buildDirectory.dir("intermediates")) {
                include("**/* ?.class", "**/* ??.class")
            },
        )
    }
}

/**
 * Fixed entry points for local verification.
 *
 * Unit tests, lint for every module, then the debug APK of one app:
 *
 *   ./gradlew clean verifyAutomotive   # -> automotive/build/outputs/apk/debug/automotive-debug.apk
 *   ./gradlew verifyMobile             # -> mobile/build/outputs/apk/debug/mobile-debug.apk
 *
 * Name `clean` first on the command line when a from-scratch build is wanted - that is the only
 * ordering Gradle guarantees, and it is deliberate that `clean` is not a dependency here: `clean`
 * and the build tasks write the same directories, and a task-level `mustRunAfter` on a subset of
 * them loses the race against the resource and signing tasks it does not name. Leftovers that make
 * an *incremental* build fail are handled instead by `purgeStaleClassCopies` below, which runs
 * inside each module before its own toolchain starts.
 *
 * A debug APK built in this working copy carries the MFi documents from `cert/` when that directory
 * exists (see common/build.gradle.kts), which is what the head unit needs to authenticate.
 */
listOf("verifyAutomotive" to "automotive", "verifyMobile" to "mobile").forEach { (name, app) ->
    tasks.register(name) {
        group = "verification"
        description = "Clean, unit tests, lint and the $app debug APK (with cert/ documents if present)."
        dependsOn(
            ":shared:clean",
            ":common:clean",
            ":mobile:clean",
            ":automotive:clean",
            ":shared:testDebugUnitTest",
            ":common:lintDebug",
            ":mobile:lintDebug",
            ":automotive:lintDebug",
            ":$app:assembleDebug",
        )
    }
}

subprojects {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(purgeStaleClassCopies)
    }
}
