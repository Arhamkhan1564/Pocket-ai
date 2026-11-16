package com.pocketai.models

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
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

    @Synchronized
    fun list(): List<InstalledModel> {
        if (!registry.exists()) return emptyList()
        require(registry.length() <= 1024 * 1024) { "The model registry is invalid." }
        val json = JSONArray(registry.readText())
        return (0 until json.length()).map { index ->
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
    @Synchronized
    fun importModel(uri: Uri, kind: ModelKind, onProgress: (Long, Long?) -> Unit = { _, _ -> }): InstalledModel {
        val current = list()
        require(current.size < 12) { "Remove an unused model before importing another (maximum 12)." }
        val descriptor = describe(uri)
        val maximum = if (kind == ModelKind.LLM) 4L * 1024 * 1024 * 1024 else 2L * 1024 * 1024 * 1024
        val reserve = 128L * 1024 * 1024
        val available = StatFs(directory.absolutePath).availableBytes - reserve
        require(available > 1024 * 1024) { "Free at least 128 MB of internal storage before importing." }
        descriptor.second?.let { require(it in 64..minOf(maximum, available)) { "The file is too large, incomplete, or exceeds available storage." } }
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, "$id.part")
        val destination = File(directory, "$id.${if (kind == ModelKind.LLM) "gguf" else "bin"}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var lastProgress = 0L
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered(64 * 1024).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Model import cancelled.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        require(copied <= maximum && copied <= available) { "The model exceeds available storage or the supported import size." }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (copied - lastProgress >= 512 * 1024) {
                            onProgress(copied, descriptor.second)
                            lastProgress = copied
                        }
                    }
                    output.flush()
                }
            } ?: throw IOException("The selected storage provider could not open this file.")
            descriptor.second?.let { require(it == copied) { "The file was not copied completely. Try importing it again." } }
            val header = ModelValidator.validateHeader(temporary, kind)
            val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            current.find { it.sha256 == checksum }?.let { throw IllegalArgumentException("This model is already installed as ${it.name}.") }
            require(temporary.renameTo(destination)) { "Could not finalize the model import." }
            val model = InstalledModel(id, descriptor.first, kind, destination.absolutePath, copied,
                checksum, System.currentTimeMillis(), header.architecture, header.metadata + ("format" to header.format))
            writeRegistry(current + model)
            onProgress(copied, descriptor.second ?: copied)
            return model
        } catch (e: Exception) {
            temporary.delete()
            destination.delete()
            throw e
        }
    }

    @Synchronized
    fun remove(id: String) {
        val models = list()
        val model = models.find { it.id == id } ?: return
        val file = fileFor(model)
        require(!file.exists() || file.delete()) { "The model could not be removed." }
        writeRegistry(models.filterNot { it.id == id })
    }

    fun fileFor(model: InstalledModel): File {
        val file = File(model.path).canonicalFile
        require(file.parentFile == directory.canonicalFile && file.isFile) { "The imported model file is unavailable." }
        return file
    }

    fun compatibility(model: InstalledModel): ModelCompatibility {
        val memory = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val supported = Build.SUPPORTED_ABIS.any { it in setOf("arm64-v8a", "x86_64") }
        val messages = mutableListOf<String>()
        if (!supported) messages += "This build requires a 64-bit ARM or x86 device for GGUF and Whisper inference. On a 32-bit phone, saved-knowledge text answers and installed offline TTS remain available."
        if (model.sizeBytes > memory.totalMem * 0.5) messages += "This model is large for the device RAM; loading may fail. Use a smaller quantized model."
        if (model.sizeBytes > memory.availMem * 0.7) messages += "Current free memory is limited. Close other apps before running this model."
        if (Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a" && model.sizeBytes > 512L * 1024 * 1024) {
            messages += "32-bit address space restricts large models. Prefer a model below 512 MB."
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
        temporary.writeText(json.toString())
        require(temporary.renameTo(registry)) { "Could not save the model registry." }
    }
}
