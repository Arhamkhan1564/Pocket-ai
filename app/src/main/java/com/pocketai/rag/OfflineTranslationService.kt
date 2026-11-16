package com.pocketai.rag

import android.app.ActivityManager
import android.content.Context
import ai.onnxruntime.OrtSession
import com.pocketai.assistant.GroundedEvidence
import com.pocketai.engine.NativeExecution
import com.pocketai.models.OfflineModelAssets
import com.pocketai.models.OfflinePackSpec
import com.pocketai.translation.IndicTokenizer
import com.pocketai.translation.OnnxTranslationKernel
import com.pocketai.translation.PunjabiShahmukhi
import java.io.File
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class OfflineTranslation(
    val text: String, val sourceText: String, val cacheHit: Boolean = false,
    val scriptConverted: Boolean = false, val description: String = "Offline translation · check the original",
)

/** Exact protected literals are copied from the source, never guessed from a translated identifier. */
internal object LiteralProtection {
    data class Protected(val text: String, val literals: Map<String, String>) {
        fun restore(output: String): String {
            if (literals.isEmpty()) return output
            val spans = Regex("\\p{Nd}+").findAll(output).toList()
            val counts = spans.map { ascii(it.value) }.groupingBy { it }.eachCount()
            require(literals.keys.all { counts[it] == 1 }) { "A source identifier did not survive translation exactly once." }
            return buildString {
                var last = 0
                spans.forEach { match ->
                    append(output, last, match.range.first)
                    append(literals[ascii(match.value)] ?: match.value)
                    last = match.range.last + 1
                }
                append(output, last, output.length)
            }
        }
    }
    fun protect(source: String): Protected {
        val pattern = Regex("\\[\\p{Nd}{1,5}\\]|(?<![\\p{L}\\p{Nd}])\\p{L}[\\p{L}\\p{Nd}_./-]*\\p{Nd}(?:[\\p{L}\\p{Nd}_./-]*[\\p{L}\\p{Nd}])?(?![\\p{L}\\p{Nd}])")
        val values = LinkedHashMap<String, String>()
        val normalized = ascii(source)
        var next = 9_876_543_210L
        val masked = pattern.replace(source) { match ->
            while (normalized.contains(next.toString())) next++
            val key = (next++).toString()
            values[key] = match.value
            key
        }
        return Protected(masked, values)
    }
    private fun ascii(value: String) = buildString {
        value.forEach { character -> val digit = Character.digit(character, 10); append(if (digit >= 0) ('0'.code + digit).toChar() else character) }
    }
}

class OfflineTranslationService(context: Context, private val spec: OfflinePackSpec) {
    private val appContext = context.applicationContext
    private val cache = File(appContext.cacheDir, "offline-translations").apply { mkdirs() }

    suspend fun translate(source: String, language: String, revision: Long): OfflineTranslation = withContext(Dispatchers.IO) {
        require(source.isNotBlank() && source.length <= 20_000) { "Translate a nonempty saved passage below 20,000 characters." }
        if (language == "en") return@withContext OfflineTranslation(source, source, description = "Original English source")
        require(language in setOf("ur", "pa-Guru", "pa-Arab")) { "The included translator supports English references to Urdu and Punjabi." }
        require(source.count { it in '\u0600'..'\u06FF' || it in '\u0900'..'\u0A7F' } < source.length / 5 + 1) {
            "The included translation pack reads English references. The original remains available for other source languages."
        }
        val key = hash("1.2.0|${spec.fingerprint}|$revision|$language|$source")
        val cached = File(cache, "$key.json")
        synchronized(cacheLock) {
            val cacheAvailable = cleanForRevision(revision)
            if (cacheAvailable && cached.isFile && cached.length() <= 256 * 1024) {
                runCatching { JSONObject(cached.readText()).getString("text") }.getOrNull()?.let { text ->
                    if (checks(source, text, language))
                        return@withContext OfflineTranslation(text, source, true, language == "pa-Arab", description(language))
                }
            }
        }
        val caller = currentCoroutineContext()
        val translated = NativeExecution.runExclusive {
            caller.ensureActive()
            val memory = ActivityManager.MemoryInfo()
            (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            require(!memory.lowMemory && memory.availMem >= 448L * 1024 * 1024) {
                "More free memory is needed for offline translation. Close other apps; the English original remains available."
            }
            val directory = OfflineModelAssets.installed(appContext, spec) ?: error("Prepare offline translation in Offline tools first.")
            val target = if (language == "ur") "ur" else "pa-Guru"
            IndicTokenizer(OfflineModelAssets.file(directory, "model.SRC"), OfflineModelAssets.file(directory, "dict.SRC.json"),
                OfflineModelAssets.file(directory, "dict.TGT.json")).use { codec ->
                OrtSession.RunOptions().use { options ->
                    NativeExecution.installCancellationHook { options.setTerminate(true) }
                    try {
                        val kernel = OnnxTranslationKernel(directory, codec, options) { caller.ensureActive() }
                        val paragraphs = source.split(Regex("\\n\\s*\\n"))
                        var count = 0
                        val text = paragraphs.filter { it.isNotBlank() }.joinToString("\n\n") { paragraph ->
                            val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH).apply { setText(paragraph) }
                            var start = iterator.first()
                            var end = iterator.next()
                            val sentences = ArrayList<String>()
                            while (end != BreakIterator.DONE) {
                                caller.ensureActive()
                                val sentence = paragraph.substring(start, end).trim()
                                if (sentence.isNotBlank()) {
                                    require(++count <= 200) { "Translate a shorter source passage at a time." }
                                    val protected = LiteralProtection.protect(sentence)
                                    val result = kernel.translate(protected.text, target)
                                    require(result.completed) { "The translation reached its output limit. The original remains available." }
                                    sentences += protected.restore(result.text)
                                }
                                start = end; end = iterator.next()
                            }
                            sentences.joinToString(" ")
                        }
                        if (language == "pa-Arab") PunjabiShahmukhi.convert(text) else text
                    } finally { NativeExecution.clearCancellationHook() }
                }
            }
        }
        caller.ensureActive()
        require(checks(source, translated, language)) {
            "The offline translation did not preserve the source checks. Read the intact original."
        }
        synchronized(cacheLock) {
            if (cleanForRevision(revision)) {
              val temporary = File(cache, "$key.part")
              try {
                temporary.writeText(JSONObject().put("text", translated).toString())
                if (!temporary.renameTo(cached)) throw java.io.IOException("Could not cache the local translation.")
                val entries = cache.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.lastModified() }
                var bytes = entries.sumOf { it.length() }
                var remaining = entries.size
                for (entry in entries) {
                    if (remaining <= 200 && bytes <= 32L * 1024 * 1024) break
                    val length = entry.length(); if (entry.delete()) { bytes -= length; remaining-- }
                }
              } catch (_: java.io.IOException) { /* Optional cache failure does not discard a valid translation. */ }
              finally { temporary.delete() }
            }
        }
        OfflineTranslation(translated, source, false, language == "pa-Arab", description(language))
    }

    fun invalidateForRevision(revision: Long) = synchronized(cacheLock) { cleanForRevision(revision) }
    private fun cleanForRevision(revision: Long): Boolean {
      try {
        if (!cache.mkdirs() && !cache.isDirectory) return false
        val marker = File(cache, "revision")
        if (marker.readTextOrNull() != revision.toString()) {
            cache.listFiles().orEmpty().forEach { if (it.isFile) it.delete() }
            marker.writeText(revision.toString())
        }
        return true
      } catch (_: java.io.IOException) { return false }
    }
    private fun checks(source: String, translated: String, language: String): Boolean {
        val citations = Regex("\\[\\d+\\]")
        if (citations.findAll(source).map { it.value }.toList() != citations.findAll(translated).map { it.value }.toList()) return false
        return GroundedEvidence.safeTranslation(source.replace(citations, ""), translated.replace(citations, ""), language, medical = true)
    }
    private fun File.readTextOrNull() = runCatching { if (isFile && length() < 40) readText() else null }.getOrNull()
    private fun description(language: String) = if (language == "pa-Arab")
        "Translated locally to Punjabi, then converted to Shahmukhi · check the English original" else
        "Translated locally from the saved English source · check the original"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object { private val cacheLock = Any() }
}
