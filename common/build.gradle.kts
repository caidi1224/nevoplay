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
