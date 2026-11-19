package com.pocketai.models

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.pocketai.engine.NativeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class InstalledModel(
    val id: String,
    val name: String,
    val kind: ModelKind,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val importedAt: Long,
    val architecture: String?,
    val metadata: Map<String, String>
)

data class ModelCompatibility(val canAttempt: Boolean, val messages: List<String>, val totalRamBytes: Long)

enum class ModelProfile(val contextSize: Int, val maxTokens: Int, val threads: Int) {
    LOW_MEMORY(512, 128, 2), BALANCED(1024, 256, 4)
}

/** Imports files into app-private storage. This class does no downloads or network requests. */
class ModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "models").apply { mkdirs() }
    private val registry = File(directory, "registry.json")

    fun list(): List<InstalledModel> = synchronized(registryLock) {
        if (!registry.exists()) return@synchronized emptyList()
        require(registry.length() <= 1024 * 1024) { "The model registry is invalid." }
        val json = JSONArray(registry.readText())
        (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            val id = item.getString("id")
            require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid model registry ID." }
            val kind = ModelKind.valueOf(item.getString("kind"))
            val metadata = item.optJSONObject("metadata") ?: JSONObject()
            InstalledModel(id, item.getString("name"), kind,
                File(directory, "$id.${if (kind == ModelKind.LLM) "gguf" else "bin"}").absolutePath,
                item.getLong("sizeBytes"), item.getString("sha256"), item.getLong("importedAt"),
                item.optString("architecture").ifBlank { null },
                metadata.keys().asSequence().associateWith { metadata.getString(it) })
        }.filter { File(it.path).isFile }
    }

    /** Blocking I/O; call on Dispatchers.IO. Progress is emitted at most every 512 KiB. */
    fun importModel(uri: Uri, kind: ModelKind, onProgress: (Long, Long?) -> Unit = { _, _ -> }): InstalledModel = synchronized(registryLock) {
        val current = list()
        require(current.size < 12) { "Remove an unused model before importing another (maximum 12)." }
        val descriptor = describe(uri)
        install(current, descriptor.first, kind, descriptor.second, null, emptyMap(), null, onProgress) {
            appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("The selected storage provider could not open this file.")
        }
    }

    /** App-bundled resource only. A known digest is mandatory; existing private copies are checked. */
    internal fun installVerifiedBundledModel(
        name: String,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
        source: () -> InputStream
    ): InstalledModel = synchronized(registryLock) {
        require(expectedSha256.matches(Regex("[a-f0-9]{64}"))) { "A trusted speech-model checksum is required." }
        val current = list()
        val existing = current.firstOrNull { it.kind == ModelKind.WHISPER && it.sha256 == expectedSha256 && it.metadata["bundled"] == "true" }
        if (existing != null) {
            val file = fileFor(existing)
            if (file.length() == expectedSize && sha256(file) == expectedSha256) {
                ModelValidator.validateHeader(file, ModelKind.WHISPER)
                onProgress(expectedSize, expectedSize)
                return@synchronized existing
            }
        }
        require(existing != null || current.size < 12) { "Remove an unused model before restoring the included speech model (maximum 12)." }
        // A stable destination lets an interrupted first install overwrite its own orphan
        // instead of leaving another full model copy after each process interruption.
        val destinationId = existing?.id ?: UUID.nameUUIDFromBytes(expectedSha256.toByteArray(Charsets.US_ASCII)).toString()
        install(current.filterNot { it.id == existing?.id }, name, ModelKind.WHISPER, expectedSize,
            expectedSha256, mapOf("bundled" to "true", "license" to "MIT", "source" to BundledSpeechModel.SOURCE_URL),
            destinationId, onProgress, source)
    }

    private fun install(
        current: List<InstalledModel>,
        name: String,
        kind: ModelKind,
        expectedSize: Long?,
        expectedSha256: String?,
        extraMetadata: Map<String, String>,
        replacementId: String?,
        onProgress: (Long, Long?) -> Unit,
        source: () -> InputStream
    ): InstalledModel {
        val maximum = if (kind == ModelKind.LLM) 4L * 1024 * 1024 * 1024 else 2L * 1024 * 1024 * 1024
        val reserve = 128L * 1024 * 1024
        val available = StatFs(directory.absolutePath).availableBytes - reserve
        require(available > 1024 * 1024) { "Free at least 128 MB of internal storage before importing." }
        expectedSize?.let { require(it in 64..minOf(maximum, available)) { "The file is too large, incomplete, or exceeds available storage." } }
        val id = replacementId ?: UUID.randomUUID().toString()
        val temporary = File(directory, "$id.part")
        val destination = File(directory, "$id.${if (kind == ModelKind.LLM) "gguf" else "bin"}")
        var finalized = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var lastProgress = 0L
            source().use { input ->
                FileOutputStream(temporary).use { rawOutput ->
                    val output = rawOutput.buffered(64 * 1024)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Model import cancelled.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        require(copied <= maximum && copied <= available) { "The model exceeds available storage or the supported import size." }
                        require(expectedSize == null || copied <= expectedSize) { "The speech-model resource is larger than expected." }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (copied - lastProgress >= 512 * 1024) {
                            onProgress(copied, expectedSize)
                            lastProgress = copied
                        }
                    }
                    output.flush()
                    rawOutput.fd.sync()
                }
            }
            expectedSize?.let { require(it == copied) { "The file was not copied completely. Try importing it again." } }
            val header = ModelValidator.validateHeader(temporary, kind)
            val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(expectedSha256 == null || checksum == expectedSha256) { "The included speech-model checksum did not match. Install a verified Pocket AI APK or import your own model." }
            val bundled = extraMetadata["bundled"] == "true"
            current.find { it.sha256 == checksum && (kind != ModelKind.WHISPER || it.kind != ModelKind.WHISPER ||
                (it.metadata["bundled"] == "true") == bundled) }
                ?.let { throw IllegalArgumentException("This model is already installed as ${it.name}.") }
            require(temporary.renameTo(destination)) { "Could not finalize the model import." }
            val model = InstalledModel(id, name, kind, destination.absolutePath, copied,
                checksum, System.currentTimeMillis(), header.architecture, header.metadata + ("format" to header.format) + extraMetadata)
            writeRegistry(current + model)
            finalized = true
            onProgress(copied, expectedSize ?: copied)
            return model
        } catch (e: Exception) {
            temporary.delete()
            // Preserve a previous private copy if verification failed before replacement.
            if (!finalized && replacementId == null) destination.delete()
            throw e
        }
    }

    fun remove(id: String): Unit = synchronized(registryLock) {
        val models = list()
        val model = models.find { it.id == id } ?: return@synchronized
        val file = fileFor(model)
        require(!file.exists() || file.delete()) { "The model could not be removed." }
        writeRegistry(models.filterNot { it.id == id })
        if (model.kind == ModelKind.WHISPER && model.metadata["bundled"] == "true") BundledSpeechModel.markRemoved(appContext, model.sha256)
    }

    fun fileFor(model: InstalledModel): File {
        val file = File(model.path).canonicalFile
        require(file.parentFile == directory.canonicalFile && file.isFile) { "The imported model file is unavailable." }
        return file
    }

    fun compatibility(model: InstalledModel): ModelCompatibility {
        val memory = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val nativeIssue = NativeBridge.availabilityMessage()
        val supported = nativeIssue == null
        val messages = mutableListOf<String>()
        if (nativeIssue != null) messages += nativeIssue
        if (model.sizeBytes > memory.totalMem * 0.5) messages += "This model is large for the device RAM; loading may fail. Use a smaller quantized model."
        if (model.sizeBytes > memory.availMem * 0.7) messages += "Current free memory is limited. Close other apps before running this model."
        if (!android.os.Process.is64Bit()) {
            messages += "This app process is 32-bit. Use Whisper tiny and a small quantized language model; available RAM and address space can still prevent loading."
            if (model.sizeBytes > 512L * 1024 * 1024) {
                messages += "This model exceeds 512 MiB. A smaller model is strongly recommended for 32-bit inference."
            }
        }
        if (model.kind == ModelKind.WHISPER && model.metadata["multilingual"] == "false") {
            messages += "This is an English-only speech model; Urdu and Punjabi need a multilingual Whisper model."
        }
        messages += "Header validated. The native runtime verifies tensors when opened; language quality and speed need testing on this phone."
        return ModelCompatibility(supported, messages, memory.totalMem)
    }

    fun totalSizeBytes(): Long = list().sumOf { it.sizeBytes }

    private fun describe(uri: Uri): Pair<String, Long?> {
        var name = "Imported model"
        var size: Long? = null
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n).replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(120)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it > 0 }
            }
        }
        return name to size
    }

    private fun writeRegistry(models: List<InstalledModel>) {
        val json = JSONArray()
        models.forEach { m ->
            json.put(JSONObject().put("id", m.id).put("name", m.name).put("kind", m.kind.name)
                .put("sizeBytes", m.sizeBytes).put("sha256", m.sha256).put("importedAt", m.importedAt)
                .put("architecture", m.architecture ?: "").put("metadata", JSONObject(m.metadata)))
        }
        val temporary = File(directory, "registry.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        require(temporary.renameTo(registry)) { "Could not save the model registry." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Speech-model verification cancelled.")
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object { private val registryLock = Any() }
}
