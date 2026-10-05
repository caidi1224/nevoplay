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
fun Project.nevoPlayBuildId(): String {
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
fun Project.nevoPlayCommitDate(): String = runCatching {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%cd", "--date=format:%Y-%m-%d")
    }.standardOutput.asText.get().trim()
}.getOrNull().orEmpty().ifEmpty { "unknown" }

/**
 * An optional path handed to the build, from a Gradle property, the gitignored `local.properties`, or
 * - for exactly the two documents below - the gitignored `cert/` directory of this working copy.
 *
 * The certificate and its private key are deployment secrets, so the repository never names a path to
 * them and never carries them: `cert/` is excluded locally. It is a *fallback* only, and only when
 * both conventional file names are present, because forgetting the two `-P` arguments produced an APK
 * that looks complete but cannot authenticate on the head unit - a failure that shows up in the car,
 * not in the build.
 */
fun Project.nevoPlayOptionalPath(key: String): String? {
    (findProperty(key) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        val properties = Properties()
        localProperties.inputStream().use(properties::load)
        properties.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

/** The conventional local documents, used when nothing above named them. */
val localMfiDocuments: Pair<String, String>? = run {
    val certificate = rootProject.file("cert/certificate.p7b")
    val privateKey = rootProject.file("cert/identity.pk8")
    if (certificate.isFile && privateKey.isFile) {
        logger.lifecycle("bundling the MFi documents found in cert/ (gitignored)")
        // Absolute: the caller resolves these against its own project directory.
        certificate.absolutePath to privateKey.absolutePath
    } else {
        null
    }
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
val bundledMfiCertificate = project.nevoPlayOptionalPath("nevoPlay.mfi.certificate")
    ?: localMfiDocuments?.first
val bundledMfiPrivateKey = project.nevoPlayOptionalPath("nevoPlay.mfi.privateKey")
    ?: localMfiDocuments?.second
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
    namespace = "com.edd1e.nevoplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        // Every build shares versionName and versionCode, so without this a log cannot say which APK
        // produced it. This value is written as the first line of every session log and shown in
        // Settings -> Diagnostics, which is what makes "which build is on the car?" answerable.
        buildConfigField("String", "BUILD_ID", "\"${project.nevoPlayBuildId()}\"")
        // Shown in the bottom-right corner of the video surface, so the head unit in the car can be
        // identified on sight. The date is the commit's date - deterministic per commit, which keeps
        // the configuration cache meaningful, and accurate to the day for a CI build.
        buildConfigField("String", "APP_VERSION", "\"${libs.versions.nevoPlayVersionName.get()}\"")
        buildConfigField("String", "BUILD_DATE", "\"${project.nevoPlayCommitDate()}\"")
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
