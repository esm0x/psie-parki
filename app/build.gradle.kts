import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Keystore lives outside the repo; without keystore.properties the release build is
// simply unsigned, so a fresh clone still builds.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.adminstack.psieparki"
    compileSdk = 36

    defaultConfig {
        // Irreversible once published: Play keys the listing, installs and ratings on it.
        applicationId = "com.adminstack.psieparki"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // MapLibre ships native code for four ABIs; an App Bundle hands each phone
        // only its own, so nothing is filtered here.
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystoreProps.getProperty("storeFile") != null) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Store screenshots are rendered by Robolectric only when asked:
                // ./gradlew testDebugUnitTest -Pscreenshots
                it.systemProperty("roborazzi.test.record", project.hasProperty("screenshots").toString())
                it.systemProperty("screenshots.dir", rootProject.file("play/screenshots/raw").absolutePath)
                it.maxHeapSize = "3g"
                // Robolectric downloads its Android jars from Maven Central itself; behind
                // a rate-limited proxy point it at a mirror: -ProbolectricRepo=https://…
                project.findProperty("robolectricRepo")?.let { url ->
                    it.systemProperty("robolectric.dependency.repo.url", url.toString())
                }
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // The map. Vector tiles from OpenFreeMap: no API key, no request quota, and
    // allowed in apps, unlike tile.openstreetmap.org, whose policy forbids them.
    implementation("org.maplibre.gl:android-sdk:13.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.50.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

/**
 * Puts data/parks.json into the APK as the list a fresh install shows before its first
 * download, and the one it keeps showing offline until a download succeeds.
 */
abstract class CopySeedData : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        source.get().asFile.copyTo(File(out, "parks.json"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<CopySeedData>("copy${variant.name.replaceFirstChar { it.uppercase() }}SeedData") {
            source.set(rootProject.layout.projectDirectory.file("data/parks.json"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, CopySeedData::outputDir)
    }
}
