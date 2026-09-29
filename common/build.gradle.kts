plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        // Changan Qiyuan OpenSDK credentials. They are read from the environment at build time (a
        // GitHub Actions secret in CI) and injected into the manifest as meta-data, which is the
        // only place the SDK looks for them. Without them the placeholders stay empty, the SDK
        // fails to initialise, and the app treats that as "vehicle signals unavailable" - so a
        // build from a plain checkout behaves exactly as it did before.
        manifestPlaceholders["caDevClientId"] =
            providers.environmentVariable("CA_DEV_CLIENT_ID").orElse("").get()
        manifestPlaceholders["caDevClientSecret"] =
            providers.environmentVariable("CA_DEV_CLIENT_SECRET").orElse("").get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":shared"))
    // Changan Qiyuan vehicle-signal SDK, vendored as a local copy because it is not published to any
    // repository. Only the optional vehicle-signal probe uses it.
    implementation(files("libs/opensdk-client-1.0.0.0.aar"))
    // The SDK's own JSON helpers reference Gson without bundling it.
    implementation(libs.gson)
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
