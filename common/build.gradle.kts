// Imported because `java` resolves to the Java extension inside this script, shadowing the package.
import java.util.Properties

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

/**
 * An optional path handed to the build, from a Gradle property or the gitignored `local.properties`.
 *
 * Only the built-in MFi material is looked up this way, and deliberately so: the certificate and its
 * private key are deployment secrets, so the repository never names a path to them. A build without
 * these properties is exactly the ordinary build and carries no certificate.
 */
fun Project.xcertplayOptionalPath(key: String): String? {
    (findProperty(key) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
    val localProperties = rootProject.file("local.properties")
    if (!localProperties.isFile) return null
    val properties = Properties()
    localProperties.inputStream().use(properties::load)
    return properties.getProperty(key)?.takeIf { it.isNotBlank() }
}

/**
 * Copies the deployment's MFi documents into the APK's assets under the names the runtime reads.
 *
 * A declared task type rather than a `Copy` because adding a generated asset directory goes through
 * `addGeneratedSourceDirectory`, which wires the task's `DirectoryProperty` output — and `Copy` only
 * exposes a `File` destination.
 */
abstract class CopyBundledMfiDocuments : DefaultTask() {
    @get:InputFile
    abstract val certificate: RegularFileProperty

    @get:InputFile
    abstract val privateKey: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyDocuments() {
        val target = outputDirectory.get().asFile.resolve("mfi")
        target.mkdirs()
        certificate.get().asFile.copyTo(target.resolve("mfi.p7b"), overwrite = true)
        privateKey.get().asFile.copyTo(target.resolve("mfi.pk8"), overwrite = true)
    }
}

// Built-in MFi documents, for head units that can read neither the shared Downloads collection nor
// their own data directory. Give both paths and `assets/mfi/mfi.p7b` + `mfi.pk8` end up in the APK;
// give neither and this block does nothing at all.
val bundledMfiCertificate = project.xcertplayOptionalPath("xcertplay.mfi.certificate")
val bundledMfiPrivateKey = project.xcertplayOptionalPath("xcertplay.mfi.privateKey")
if (!bundledMfiCertificate.isNullOrBlank() && !bundledMfiPrivateKey.isNullOrBlank()) {
    val copyBundledMfiDocuments = tasks.register<CopyBundledMfiDocuments>("copyBundledMfiDocuments") {
        description = "Copies this deployment's MFi certificate and key into the APK's assets."
        certificate.set(layout.projectDirectory.file(bundledMfiCertificate))
        privateKey.set(layout.projectDirectory.file(bundledMfiPrivateKey))
        outputDirectory.set(layout.buildDirectory.dir("generated/bundledMfiAssets"))
    }
    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                copyBundledMfiDocuments,
                CopyBundledMfiDocuments::outputDirectory,
            )
        }
    }
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
