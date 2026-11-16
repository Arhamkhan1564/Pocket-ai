package com.pocketai.models

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class OfflinePackFile(val name: String, val assetPath: String, val bytes: Long, val sha256: String)
data class OfflinePackSpec(val id: String, val title: String, val files: List<OfflinePackFile>) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest(files.joinToString("\n") { "${it.name}:${it.bytes}:${it.sha256}" }.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
enum class OfflinePackStatus { NOT_PREPARED, PREPARING, READY, FAILED }
data class OfflinePackState(val status: OfflinePackStatus, val message: String, val copiedBytes: Long = 0, val totalBytes: Long = 0)

/** Copies licensed resources already inside the APK. There is no runtime network path. */
object OfflineModelAssets {
    val embedding = OfflinePackSpec("search", "Multilingual document search", listOf(
        OfflinePackFile("model.onnx", "offline/search/model.onnx", 118_308_185,
            "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193"),
        OfflinePackFile("tokenizer.spm", "offline/search/tokenizer.spm", 5_069_051,
            "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865"),
    ))
    private val mutex = Mutex()
    private val mutableStates = MutableStateFlow<Map<String, OfflinePackState>>(emptyMap())
    val states: StateFlow<Map<String, OfflinePackState>> = mutableStates.asStateFlow()

    fun specs(context: Context): List<OfflinePackSpec> {
        val text = context.applicationContext.assets.open("offline-model-manifest.json").bufferedReader().use { it.readText() }
        require(text.length <= 128 * 1024) { "The offline model manifest is too large." }
        val json = JSONObject(text)
        require(json.getInt("schemaVersion") == 1)
        val packs = json.getJSONArray("packs")
        return (0 until packs.length()).map { index ->
            val pack = packs.getJSONObject(index)
            val files = pack.getJSONArray("files")
            require(files.length() in 1..20)
            OfflinePackSpec(pack.getString("id"), pack.getString("name"), (0 until files.length()).map { fileIndex ->
                val file = files.getJSONObject(fileIndex)
                val asset = file.getString("assetPath")
                require(!asset.startsWith('/') && asset.split('/').none { it in setOf("..", ".") })
                val sha = file.getString("sha256")
                val bytes = file.getLong("sizeBytes")
                require(sha.matches(Regex("[a-f0-9]{64}")) && bytes in 1..2_000_000_000L)
                OfflinePackFile(asset.substringAfterLast('/'), asset, bytes, sha)
            }).also { require(it.files.map { descriptor -> descriptor.name }.distinct().size == it.files.size) }
        }.also { require(it.map { pack -> pack.id }.toSet() == setOf("search", "translation", "english", "speech")) }
    }

    /** Blocking metadata checks; call on IO. Complete markers are written after every file verifies. */
    fun installed(context: Context, spec: OfflinePackSpec): File? = runCatching {
        val directory = directory(context, spec)
        val marker = File(directory, "verified.json")
        if (!marker.isFile || marker.length() > 64 * 1024) return null
        val json = JSONObject(marker.readText())
        if (json.optString("fingerprint") != spec.fingerprint) return null
        val metadata = json.getJSONObject("files")
        if (spec.files.any { descriptor ->
            val file = child(directory, descriptor.name)
            !file.isFile || !file.canRead() || file.length() != descriptor.bytes ||
                metadata.optLong(descriptor.name, -1) != file.lastModified()
        }) return null
        directory
    }.getOrNull()

    suspend fun prepare(context: Context, spec: OfflinePackSpec): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            installed(context, spec)?.let {
                update(spec, OfflinePackStatus.READY, "${spec.title} ready · stored on this phone", spec.totalBytes)
                return@withLock it
            }
            val directory = directory(context, spec).apply { require(mkdirs() || isDirectory) { "Could not prepare offline model storage." } }
            var complete = 0L
            update(spec, OfflinePackStatus.PREPARING, "Preparing ${spec.title.lowercase()} from the APK…", 0)
            try {
                for (descriptor in spec.files) {
                    currentCoroutineContext().ensureActive()
                    val destination = child(directory, descriptor.name)
                    val temporary = child(directory, descriptor.name + ".part")
                    temporary.delete()
                    if (verified(destination, descriptor)) { complete += descriptor.bytes; continue }
                    require(StatFs(directory.absolutePath).availableBytes >= descriptor.bytes + 128L * 1024 * 1024) {
                        "Free more internal storage to prepare ${spec.title}. Your saved documents are unchanged."
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    try {
                        context.applicationContext.assets.open(descriptor.assetPath).use { input ->
                            FileOutputStream(temporary).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    copied += count
                                    require(copied <= descriptor.bytes) { "The included offline model has an unexpected size." }
                                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                                    if (copied % (512 * 1024) < buffer.size) update(spec, OfflinePackStatus.PREPARING,
                                        "Preparing ${spec.title.lowercase()} · ${(complete + copied) * 100 / spec.totalBytes}%", complete + copied)
                                }
                                output.fd.sync()
                            }
                        }
                        require(copied == descriptor.bytes && hex(digest.digest()) == descriptor.sha256) { "The included offline model failed its integrity check." }
                        require(temporary.renameTo(destination)) { "Could not finalize the offline model." }
                        complete += copied
                    } finally { temporary.delete() }
                }
                val metadata = JSONObject()
                spec.files.forEach { metadata.put(it.name, child(directory, it.name).lastModified()) }
                val marker = File(directory, "verified.json.part")
                FileOutputStream(marker).use { output ->
                    output.write(JSONObject().put("fingerprint", spec.fingerprint).put("files", metadata).toString().toByteArray())
                    output.fd.sync()
                }
                require(marker.renameTo(File(directory, "verified.json"))) { "Could not finalize offline setup metadata." }
                update(spec, OfflinePackStatus.READY, "${spec.title} ready · stored on this phone", spec.totalBytes)
                directory
            } catch (cancelled: CancellationException) {
                update(spec, OfflinePackStatus.NOT_PREPARED, "Offline setup paused. It can resume without downloading anything.", complete)
                throw cancelled
            } catch (failure: Exception) {
                update(spec, OfflinePackStatus.FAILED, failure.message ?: "Offline setup could not finish.", complete)
                throw failure
            }
        }
    }

    fun file(directory: File, name: String): File = child(directory, name).also { require(it.isFile && it.canRead()) { "The offline model is unavailable. Run local setup again." } }

    private fun directory(context: Context, spec: OfflinePackSpec): File {
        require(spec.id.matches(Regex("[a-z0-9-]{1,80}")) && spec.files.isNotEmpty())
        return File(context.applicationContext.filesDir, "offline-models/${spec.id}")
    }
    private fun child(directory: File, name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,100}")) && name !in setOf(".", ".."))
        val file = File(directory, name).canonicalFile
        require(file.parentFile == directory.canonicalFile)
        return file
    }
    private fun verified(file: File, descriptor: OfflinePackFile): Boolean {
        if (!file.isFile || file.length() != descriptor.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return hex(digest.digest()) == descriptor.sha256
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun update(spec: OfflinePackSpec, status: OfflinePackStatus, message: String, copied: Long) {
        mutableStates.value = mutableStates.value + (spec.id to OfflinePackState(status, message, copied, spec.totalBytes))
    }
}
