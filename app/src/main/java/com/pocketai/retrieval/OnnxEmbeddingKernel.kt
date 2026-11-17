package com.pocketai.retrieval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/** Raw SentencePiece IDs; the E5 fairseq offset is applied here, not by the codec. */
interface EmbeddingTokenizer {
    fun encode(text: String): IntArray
    val unknownId: Int
}

data class EmbeddedWindow(val startOffset: Int, val endOffset: Int, val vector: FloatArray)

/** A passage can remain readable/searchable even when its text exceeds this model's bounded input plan. */
class UnsupportedEmbeddingTextException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)

/** The same CPU tensor path is usable in Android and the host parity harness. */
class OnnxEmbeddingKernel(
    modelFile: File,
    private val tokenizer: EmbeddingTokenizer,
    private val runOptions: OrtSession.RunOptions? = null,
    private val checkCancelled: () -> Unit = {},
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        options.setMemoryPatternOptimization(false)
        options.setCPUArenaAllocator(false)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
        environment.createSession(modelFile.absolutePath, options)
    }

    fun query(text: String): FloatArray {
        require(text.isNotBlank() && text.length <= 4000) { "Enter a question under 4,000 characters." }
        return embed("query: $text")
    }

    fun document(text: String): List<EmbeddedWindow> {
        val windows = try {
            EmbeddingWindows.split(text) { tokenizer.encode("passage: $it").size }
        } catch (failure: IllegalArgumentException) {
            throw UnsupportedEmbeddingTextException(failure.message ?: "Split this passage into shorter notes for semantic indexing.", failure)
        }
        return windows.map { window ->
            checkCancelled()
            EmbeddedWindow(window.startOffset, window.endOffset, embed("passage: ${window.text}"))
        }
    }

    private fun embed(text: String): FloatArray {
        checkCancelled()
        val raw = tokenizer.encode(text)
        val ids = LongArray(minOf(raw.size, CONTENT_TOKENS) + 2)
        ids[0] = 0 // XLM-R/fairseq BOS, not SentencePiece's BOS.
        for (index in 0 until ids.size - 2) ids[index + 1] =
            if (raw[index] == tokenizer.unknownId) 3 else (raw[index] + 1).toLong()
        ids[ids.lastIndex] = 2
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { input ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { mask ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape).use { types ->
                    val feed = mapOf("input_ids" to input, "attention_mask" to mask, "token_type_ids" to types)
                    session.run(feed, runOptions).use { result ->
                        checkCancelled()
                        val output = result[0] as? OnnxTensor ?: error("The embedding model returned an invalid tensor.")
                        val expected = ids.size * DIMENSIONS
                        val values = output.floatBuffer
                        require(values.remaining() == expected) { "The embedding model has an unsupported output shape." }
                        val vector = FloatArray(DIMENSIONS)
                        for (token in ids.indices) for (dimension in 0 until DIMENSIONS) {
                            vector[dimension] += values.get(token * DIMENSIONS + dimension) / ids.size
                        }
                        var squared = 0.0
                        vector.forEach { require(it.isFinite()) { "The embedding model returned non-finite values." }; squared += it.toDouble() * it }
                        require(squared > 1e-12) { "The embedding model returned an empty vector." }
                        val length = sqrt(squared).toFloat()
                        for (index in vector.indices) vector[index] /= length
                        return vector
                    }
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val DIMENSIONS = 384
        const val CONTENT_TOKENS = 254
        const val MODEL_SHA256 = "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193"
        const val TOKENIZER_SHA256 = "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865"
    }
}

data class EmbeddingWindow(val startOffset: Int, val endOffset: Int, val text: String)

/** Exact source offsets survive token limits; long records are indexed in overlapping windows. */
object EmbeddingWindows {
    fun split(text: String, tokenCount: (String) -> Int): List<EmbeddingWindow> {
        require(text.isNotBlank() && text.length <= 20_000) {
            "Semantic indexing needs nonempty passages of at most 20,000 characters. Split this note into shorter passages; its original and keyword search remain available."
        }
        val windows = ArrayList<EmbeddingWindow>()
        var start = 0
        while (start < text.length) {
            var end = boundaryBefore(text, minOf(start + 900, text.length), start + 1)
            while (tokenCount(text.substring(start, end)) > OnnxEmbeddingKernel.CONTENT_TOKENS && end - start > 1) {
                val smaller = start + ((end - start) * .75).toInt().coerceAtLeast(1)
                end = boundaryBefore(text, smaller, start + 1)
            }
            require(tokenCount(text.substring(start, end)) <= OnnxEmbeddingKernel.CONTENT_TOKENS) {
                "A source character exceeds the embedding model's token limit."
            }
            windows += EmbeddingWindow(start, end, text.substring(start, end))
            require(windows.size <= 256) { "This record produces too many semantic windows. Split it into shorter records." }
            if (end == text.length) break
            var next = maxOf(start + 1, end - 100)
            while (next < end && !text[next].isWhitespace()) next++
            if (next >= end) next = end
            if (next in 1 until text.length && Character.isLowSurrogate(text[next]) && Character.isHighSurrogate(text[next - 1])) next++
            start = next
            while (start < text.length && text[start].isWhitespace()) start++
        }
        return windows
    }

    private fun boundaryBefore(text: String, preferred: Int, minimum: Int): Int {
        var end = preferred.coerceAtMost(text.length)
        if (end == text.length) return end
        val floor = maxOf(minimum, end - 100)
        var word = end
        while (word > floor && !text[word - 1].isWhitespace()) word--
        if (word > floor) end = word
        if (end > minimum && end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        return end.coerceAtLeast(minimum)
    }
}
