plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

/**
 * Identifies the exact build that produced a session log: `<commit>[+run<id>]`.
 *
 * Declared here rather than as a top-level function because a top-level function in this script does
 * not inherit the `Project` receiver that `providers` needs.
 */
fun Project.xcertplayBuildId(): String {
    val commit = runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrNull().orEmpty().ifEmpty { "unknown" }
    val runId = runCatching { providers.environmentVariable("GITHUB_RUN_ID").orNull }
        .getOrNull().orEmpty()
    return if (runId.isEmpty()) commit else "$commit+run$runId"
}

/** Commit date of HEAD as `YYYY-MM-DD`, or `unknown` when there is no git checkout to read. */
fun Project.xcertplayCommitDate(): String = runCatching {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%cd", "--date=format:%Y-%m-%d")
    }.standardOutput.asText.get().trim()
}.getOrNull().orEmpty().ifEmpty { "unknown" }

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        // Every build shares versionName and versionCode, so without this a log cannot say which APK
        // produced it. This value is written as the first line of every session log and shown in
        // Settings -> Diagnostics, which is what makes "which build is on the car?" answerable.
        buildConfigField("String", "BUILD_ID", "\"${project.xcertplayBuildId()}\"")
        // Shown in the bottom-right corner of the video surface, so the head unit in the car can be
        // identified on sight. The date is the commit's date - deterministic per commit, which keeps
        // the configuration cache meaningful, and accurate to the day for a CI build.
        buildConfigField("String", "APP_VERSION", "\"${libs.versions.xcertplayVersionName.get()}\"")
        buildConfigField("String", "BUILD_DATE", "\"${project.xcertplayCommitDate()}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
}
