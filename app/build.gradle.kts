import java.util.Properties
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import groovy.json.JsonSlurper

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
// A distribution build must contain every pinned baseline resource. A partial
// checkout must never silently produce an APK missing its offline capabilities.
val bundledModelAssetsDir = rootProject.file(
    providers.gradleProperty("pocketAiModelAssetsDir")
        .orElse(providers.environmentVariable("POCKETAI_MODEL_ASSETS_DIR"))
        .getOrElse("../model-assets")
)
val offlineFetchInstructions = "Run python3 scripts/fetch-offline-models.py before Gradle sync/build. " +
    "Use POCKETAI_MODEL_ASSETS_DIR or -PpocketAiModelAssetsDir to override the external assets directory."
val offlineManifest = JsonSlurper().parse(rootProject.file("app/src/main/assets/offline-model-manifest.json")) as Map<*, *>
if (offlineManifest["schemaVersion"] != 1 || offlineManifest["bundleVersion"] != "1.2.0") {
    throw GradleException("Unsupported baseline offline model manifest.")
}
val offlinePacks = offlineManifest["packs"] as? List<*> ?: throw GradleException("Offline model packs are missing.")
val offlinePackIds = HashSet<String>()
val offlineModelFiles = linkedMapOf<String, Pair<Long, String>>()
fun safeAssetPath(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') &&
    path.matches(Regex("[A-Za-z0-9_./-]+")) && path.split('/').none { it == "." || it == ".." || it.isEmpty() }
offlinePacks.forEach { rawPack ->
    val pack = rawPack as? Map<*, *> ?: throw GradleException("Invalid offline model pack.")
    val id = pack["id"] as? String ?: throw GradleException("Offline model pack has no ID.")
    if (!offlinePackIds.add(id)) throw GradleException("Duplicate offline model pack: $id")
    val license = pack["licenseAsset"] as? String ?: throw GradleException("Offline model pack has no license asset: $id")
    if (!safeAssetPath(license) || !rootProject.file("app/src/main/assets/$license").isFile) {
        throw GradleException("Offline model license is missing: $id")
    }
    var packBytes = 0L
    (pack["files"] as? List<*> ?: throw GradleException("Offline model pack has no files: $id")).forEach { rawFile ->
        val file = rawFile as? Map<*, *> ?: throw GradleException("Invalid offline model file: $id")
        val path = file["assetPath"] as? String ?: throw GradleException("Offline model file has no asset path: $id")
        val bytes = (file["sizeBytes"] as? Number)?.toLong() ?: 0L
        val digest = file["sha256"] as? String ?: ""
        if (!safeAssetPath(path) || !(path.startsWith("offline/") || path.startsWith("models/")) ||
            bytes !in 1..2_000_000_000L || !digest.matches(Regex("[0-9a-f]{64}")) || offlineModelFiles.containsKey(path)) {
            throw GradleException("Invalid or duplicate pinned offline resource: $path")
        }
        offlineModelFiles[path] = bytes to digest
        packBytes += bytes
    }
    if (packBytes != (pack["totalSizeBytes"] as? Number)?.toLong()) {
        throw GradleException("Offline model pack size total differs from its manifest: $id")
    }
}
if (offlinePackIds != setOf("search", "translation", "english", "speech") ||
    offlineModelFiles.values.sumOf { it.first } != (offlineManifest["totalSizeBytes"] as? Number)?.toLong()) {
    throw GradleException("The baseline offline model manifest is incomplete or has an inconsistent total.")
}
offlineModelFiles.forEach { (assetPath, expected) ->
    val file = bundledModelAssetsDir.resolve(assetPath)
    if (!file.isFile || file.length() != expected.first) {
        throw GradleException("Bundled offline resource is missing or incomplete: $assetPath. $offlineFetchInstructions")
    }
    if (fileSha256(file) != expected.second) {
        throw GradleException("Bundled offline resource failed SHA-256 validation: $assetPath. $offlineFetchInstructions")
    }
}
val actualOfflineAssets = bundledModelAssetsDir.walkTopDown().filter { it.isFile }
    .map { it.relativeTo(bundledModelAssetsDir).invariantSeparatorsPath }.toSet()
if (actualOfflineAssets != offlineModelFiles.keys) {
    throw GradleException("The external model-assets directory contains files not listed in the pinned manifest; use a clean assets directory. $offlineFetchInstructions")
}

// The stock 1.20 JNI wrapper has 4-KiB ELF alignment. Keep API 23 support by
// reproducibly rebuilding that small official wrapper; later official AARs
// require API 24. Preparation inputs, core libraries and Java classes are pinned.
val ortAndroidAar = rootProject.file(
    providers.gradleProperty("pocketAiOrtAndroidAar")
        .orElse(providers.environmentVariable("POCKETAI_ORT_ANDROID_AAR"))
        .getOrElse("../runtime-assets/onnxruntime-android-1.20.0-pocketai16k.aar")
)
val ortBuildRecord = ortAndroidAar.resolveSibling(ortAndroidAar.nameWithoutExtension + ".json")
val ortPreparationInstructions = "Run python3 scripts/prepare-onnxruntime-android.py with Android NDK 27.2.12479018 before Gradle sync/build."
if (!ortAndroidAar.isFile || !ortBuildRecord.isFile) {
    throw GradleException("The prepared API-23/16-KiB ONNX Runtime dependency is missing. $ortPreparationInstructions")
}
fun fileSha256(file: java.io.File): String = MessageDigest.getInstance("SHA-256").apply {
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            update(buffer, 0, count)
        }
    }
}.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
val ortMetadata = JsonSlurper().parse(ortBuildRecord) as Map<*, *>
if (ortMetadata["source_commit"] != "c4fb724e810bb496165b9015c77f402727392933" ||
    ortMetadata["inputs_manifest_sha256"] != fileSha256(rootProject.file("scripts/onnxruntime-android-inputs.json")) ||
    ortMetadata["artifact_sha256"] != fileSha256(ortAndroidAar)) {
    throw GradleException("Prepared ONNX Runtime provenance/checksum validation failed. $ortPreparationInstructions")
}
ZipFile(ortAndroidAar).use { archive ->
    val expectedAbis = mapOf("armeabi-v7a" to (1 to 40), "arm64-v8a" to (2 to 183), "x86_64" to (2 to 62))
    val expectedEntries = expectedAbis.keys.flatMap { abi ->
        listOf("jni/$abi/libonnxruntime.so", "jni/$abi/libonnxruntime4j_jni.so")
    }.toSet()
    val actualEntries = archive.entries().asSequence().map { it.name }
        .filter { it.startsWith("jni/") && it.endsWith(".so") }.toSet()
    if (actualEntries != expectedEntries) {
        throw GradleException("Prepared ONNX Runtime must contain exactly the three supported Android ABIs. $ortPreparationInstructions")
    }
    expectedEntries.forEach { name ->
        val bytes = archive.getInputStream(archive.getEntry(name)).use { it.readBytes() }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val abi = name.split('/')[1]
        val (expectedClass, expectedMachine) = expectedAbis.getValue(abi)
        if (bytes.size < 64 || data.getInt(0) != 0x464c457f || bytes[4].toInt() != expectedClass ||
            bytes[5].toInt() != 1 || (data.getShort(18).toInt() and 0xffff) != expectedMachine) {
            throw GradleException("Prepared ONNX Runtime ELF ABI validation failed for $name.")
        }
        val offset = if (expectedClass == 1) data.getInt(28).toLong() and 0xffffffffL else data.getLong(32)
        val entrySize = data.getShort(if (expectedClass == 1) 42 else 54).toInt() and 0xffff
        val count = data.getShort(if (expectedClass == 1) 44 else 56).toInt() and 0xffff
        var loadSegments = 0
        repeat(count) { index ->
            val position = offset + index.toLong() * entrySize
            if (position < 0 || position + entrySize > bytes.size) {
                throw GradleException("Prepared ONNX Runtime has an invalid ELF program table: $name")
            }
            if (data.getInt(position.toInt()) == 1) {
                loadSegments++
                val alignment = if (expectedClass == 1) data.getInt(position.toInt() + 28).toLong() and 0xffffffffL
                    else data.getLong(position.toInt() + 48)
                if (alignment < 16_384L) {
                    throw GradleException("Prepared ONNX Runtime ELF must use 16-KiB load alignment: $name")
                }
            }
        }
        if (loadSegments == 0) throw GradleException("Prepared ONNX Runtime ELF has no load segments: $name")
    }
}

android {
    namespace = "com.pocketai"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.pocketai"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                targets += listOf("pocketai", "pocketai_tokenizer")
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(bundledModelAssetsDir)
    androidResources { noCompress += listOf("bin", "onnx", "data", "gguf", "spm", "SRC", "TGT") }
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
    implementation(files(ortAndroidAar))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
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
