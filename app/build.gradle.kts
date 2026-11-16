import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localSigning = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.isFile) file.inputStream().use(::load)
}

// Keep licensed model weights outside the source checkout and Git/source ZIPs.
// A distribution build must contain the verified speech model; never silently
// produce an APK whose advertised bundled voice input is missing.
val bundledModelAssetsDir = rootProject.file(
    providers.gradleProperty("pocketAiModelAssetsDir")
        .orElse(providers.environmentVariable("POCKETAI_MODEL_ASSETS_DIR"))
        .getOrElse("../model-assets")
)
val bundledSpeechModel = bundledModelAssetsDir.resolve("models/ggml-tiny-q5_1.bin")
val bundledSpeechSha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
if (!bundledSpeechModel.isFile || bundledSpeechModel.length() != 32_152_673L) {
    throw GradleException(
        "The bundled multilingual Whisper tiny-q5_1 model is missing or incomplete. " +
            "Run python3 scripts/fetch-bundled-speech.py before Gradle sync/build. " +
            "Use POCKETAI_MODEL_ASSETS_DIR or -PpocketAiModelAssetsDir to override the external assets directory."
    )
}
val actualSpeechSha256 = MessageDigest.getInstance("SHA-256").apply {
    bundledSpeechModel.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            update(buffer, 0, count)
        }
    }
}.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
if (actualSpeechSha256 != bundledSpeechSha256) {
    throw GradleException("The bundled speech model failed SHA-256 verification. Run python3 scripts/fetch-bundled-speech.py again.")
}

android {
    namespace = "com.pocketai"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.pocketai"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_ARM_NEON=TRUE")
            }
        }
    }

    signingConfigs {
        create("localRelease") {
            if (localSigning.isNotEmpty()) {
                storeFile = rootProject.file(localSigning.getProperty("storeFile"))
                storePassword = localSigning.getProperty("storePassword")
                keyAlias = localSigning.getProperty("keyAlias")
                keyPassword = localSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(bundledModelAssetsDir)
    androidResources { noCompress += "bin" }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
