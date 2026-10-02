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

subprojects {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(purgeStaleClassCopies)
    }
}
