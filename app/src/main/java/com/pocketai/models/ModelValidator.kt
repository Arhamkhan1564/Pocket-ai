package com.pocketai.models

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class ModelKind { LLM, WHISPER }

data class ModelHeader(
    val format: String,
    val architecture: String?,
    val metadata: Map<String, String>
)

/** Parses bounded headers. The inference runtime performs the final tensor compatibility check. */
object ModelValidator {
    private const val MAX_HEADER = 64L * 1024 * 1024

    fun validateHeader(file: File, kind: ModelKind): ModelHeader {
        require(file.isFile && file.length() >= 64) { "The selected file is empty or incomplete." }
        RandomAccessFile(file, "r").use { input ->
            return when (kind) {
                ModelKind.LLM -> parseGguf(input)
                ModelKind.WHISPER -> parseWhisper(input)
            }
        }
    }

    private fun parseGguf(f: RandomAccessFile): ModelHeader {
        require(readU32(f) == 0x46554747L) { "Choose a GGUF language model, not a ZIP or other file." }
        val version = readU32(f)
        require(version == 2L || version == 3L) { "This GGUF version is not supported (version $version)." }
        val tensors = readCount(f, 200_000)
        val entries = readCount(f, 100_000)
        require(tensors > 0) { "The GGUF file contains no model tensors." }
        val metadata = linkedMapOf("gguf.version" to version.toString(), "tensor_count" to tensors.toString())
        var architecture: String? = null
        var alignment = 32L
        repeat(entries.toInt()) {
            val key = readString(f, 4096)
            val type = readU32(f).toInt()
            val retain = key == "general.architecture" || key == "general.name" ||
                key == "general.file_type" || key == "general.quantization_version" ||
                key.endsWith(".context_length") || key == "general.alignment"
            val value = readValue(f, type, retain)
            if (retain && value != null) metadata[key] = value.take(512)
            if (key == "general.architecture") architecture = value
            if (key == "general.alignment") alignment = value?.toLongOrNull() ?: 32L
            require(f.filePointer <= MAX_HEADER) { "The GGUF metadata header is too large." }
        }
        require(!architecture.isNullOrBlank()) { "GGUF architecture metadata is missing." }
        require(alignment in 1..4096 && (alignment and (alignment - 1)) == 0L) { "Invalid GGUF tensor alignment." }
        var largestOffset = 0L
        var parameterCount = 0L
        repeat(tensors.toInt()) {
            readString(f, 16_384)
            val dimensions = readU32(f)
            require(dimensions in 1..4) { "Invalid GGUF tensor dimensions." }
            var parameters = 1L
            repeat(dimensions.toInt()) {
                val dimension = readCount(f, 1_000_000_000)
                require(dimension > 0 && parameters <= Long.MAX_VALUE / dimension) { "Invalid GGUF tensor size." }
                parameters *= dimension
            }
            readU32(f) // Quantization type is validated by llama.cpp when loaded.
            val offset = readCount(f, f.length())
            require(offset % alignment == 0L) { "Invalid GGUF tensor offset." }
            largestOffset = maxOf(largestOffset, offset)
            require(parameterCount <= Long.MAX_VALUE - parameters) { "Invalid GGUF parameter count." }
            parameterCount += parameters
            require(f.filePointer <= MAX_HEADER) { "The GGUF tensor header is too large." }
        }
        val dataOffset = ((f.filePointer + alignment - 1) / alignment) * alignment
        require(dataOffset < f.length() && largestOffset < f.length() - dataOffset) {
            "The GGUF tensor data is missing or truncated."
        }
        metadata["parameter_count"] = parameterCount.toString()
        return ModelHeader("GGUF v$version", architecture, metadata)
    }

    private fun parseWhisper(f: RandomAccessFile): ModelHeader {
        require(readU32(f) == 0x67676d6cL) { "Choose a whisper.cpp GGML .bin model." }
        val vocabulary = readU32(f)
        val audioContext = readU32(f)
        val audioState = readU32(f)
        val audioHeads = readU32(f)
        val audioLayers = readU32(f)
        val textContext = readU32(f)
        val textState = readU32(f)
        val textHeads = readU32(f)
        val textLayers = readU32(f)
        val mels = readU32(f)
        val fileType = readU32(f)
        require(vocabulary in 50_000..60_000 && audioContext in 1..6000 && textContext in 1..4096 &&
            audioState in 64..4096 && audioState == textState && audioHeads in 1..64 && textHeads in 1..64 &&
            audioLayers in 1..64 && textLayers in 1..64 && mels in listOf(80L, 128L) && fileType <= 100_000) {
            "This is not a recognized whisper.cpp model header."
        }
        require(f.length() >= 1024 * 1024) { "The speech model appears incomplete (less than 1 MB)." }
        val variant = when (audioLayers) {
            4L -> "tiny"
            6L -> "base"
            12L -> "small"
            24L -> "medium"
            32L -> "large"
            else -> "custom"
        }
        return ModelHeader("Whisper GGML", "whisper", mapOf(
            "variant" to variant,
            "multilingual" to (vocabulary >= 51_865).toString(),
            "audio_context" to audioContext.toString(),
            "file_type" to fileType.toString()
        ))
    }

    private fun readValue(f: RandomAccessFile, type: Int, retain: Boolean, depth: Int = 0): String? {
        require(depth <= 1) { "Nested GGUF arrays are not supported." }
        val result: String? = when (type) {
            0 -> f.readUnsignedByte().toString()
            1 -> f.readByte().toString()
            2 -> java.lang.Short.reverseBytes(f.readShort()).toInt().and(65535).toString()
            3 -> java.lang.Short.reverseBytes(f.readShort()).toString()
            4 -> readU32(f).toString()
            5 -> Integer.reverseBytes(f.readInt()).toString()
            6 -> Float.fromBits(Integer.reverseBytes(f.readInt())).toString()
            7 -> f.readUnsignedByte().also { require(it <= 1) { "Invalid GGUF boolean." } }.toString()
            8 -> if (retain) readString(f, 1_048_576) else { skipString(f); null }
            9 -> {
                val elementType = readU32(f).toInt()
                require(elementType in 0..12 && elementType != 9) { "Invalid GGUF array type." }
                val count = readCount(f, 2_000_000)
                val width = when (elementType) { 0, 1, 7 -> 1L; 2, 3 -> 2L; 4, 5, 6 -> 4L; 10, 11, 12 -> 8L; else -> 0L }
                if (width > 0) skip(f, count * width)
                else repeat(count.toInt()) { readValue(f, elementType, false, depth + 1) }
                null
            }
            10, 11 -> java.lang.Long.reverseBytes(f.readLong()).toString()
            12 -> Double.fromBits(java.lang.Long.reverseBytes(f.readLong())).toString()
            else -> error("Unsupported GGUF metadata type: $type")
        }
        require(f.filePointer <= minOf(MAX_HEADER, f.length())) { "The model header is truncated or too large." }
        return if (retain) result else null
    }

    private fun readU32(f: RandomAccessFile): Long = Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
    private fun readCount(f: RandomAccessFile, maximum: Long): Long {
        val n = java.lang.Long.reverseBytes(f.readLong())
        require(n in 0..maximum) { "Invalid or oversized model header value." }
        return n
    }
    private fun readString(f: RandomAccessFile, maximum: Long): String {
        val length = readCount(f, maximum)
        require(length <= f.length() - f.filePointer && f.filePointer + length <= MAX_HEADER) { "Truncated model string." }
        val bytes = ByteArray(length.toInt())
        f.readFully(bytes)
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun skipString(f: RandomAccessFile) { skip(f, readCount(f, MAX_HEADER)) }
    private fun skip(f: RandomAccessFile, length: Long) {
        require(length >= 0 && length <= f.length() - f.filePointer && f.filePointer + length <= MAX_HEADER) {
            "The model header is truncated or too large."
        }
        f.seek(f.filePointer + length)
    }
}
