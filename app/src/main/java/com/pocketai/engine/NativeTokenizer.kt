package com.pocketai.engine

import java.io.File

/**
 * Exact SentencePiece processing of a verified local model.
 * IDs are RAW SPM IDs: no BOS/EOS, language prefix, Marian vocabulary mapping,
 * XLM-R/fairseq offset, or tokenizer-specific post-processing is added here.
 */
class NativeTokenizer(modelFile: File) : AutoCloseable {
    private var handle: Long = 0
    private val metadata: IntArray

    init {
        val file = modelFile.canonicalFile
        require(file.isFile && file.canRead() && file.length() in 1..MAX_MODEL_BYTES) {
            "The SentencePiece model must be a readable local file below 16 MiB."
        }
        NativeTokenizerBridge.requireAvailable()
        handle = NativeTokenizerBridge.openNative(file.absolutePath.toByteArray(Charsets.UTF_8))
        try {
            metadata = NativeTokenizerBridge.infoNative(handle)
            check(metadata.size == 5) { "The tokenizer returned invalid metadata." }
        } catch (failure: Throwable) {
            NativeTokenizerBridge.closeNative(handle)
            handle = 0
            throw failure
        }
    }

    @Synchronized fun encodeIds(text: String): IntArray = NativeTokenizerBridge.encodeIdsNative(live(), utf8(text))
    @Synchronized fun decodeIds(ids: IntArray): String {
        require(ids.size <= MAX_TOKENS) { "Too many raw SentencePiece IDs." }
        return NativeTokenizerBridge.decodeIdsNative(live(), ids).toString(Charsets.UTF_8)
    }
    @Synchronized fun encodePieces(text: String): List<String> =
        NativeTokenizerBridge.encodePiecesNative(live(), utf8(text)).map { it.toString(Charsets.UTF_8) }

    @Synchronized fun decodePieces(pieces: List<String>): String {
        require(pieces.size <= MAX_TOKENS) { "Too many SentencePiece pieces." }
        var bytes = 0
        val encoded = pieces.map { piece ->
            utf8(piece).also {
                bytes += it.size
                require(bytes <= MAX_OUTPUT_BYTES) { "SentencePiece input pieces are too large." }
            }
        }.toTypedArray()
        return NativeTokenizerBridge.decodePiecesNative(live(), encoded).toString(Charsets.UTF_8)
    }

    @Synchronized fun normalize(text: String): String = NativeTokenizerBridge.normalizeNative(live(), utf8(text)).toString(Charsets.UTF_8)
    @Synchronized fun pieceSize(): Int { live(); return metadata[0] }
    @Synchronized fun unknownId(): Int { live(); return metadata[1] }
    @Synchronized fun bosId(): Int { live(); return metadata[2] }
    @Synchronized fun eosId(): Int { live(); return metadata[3] }
    @Synchronized fun padId(): Int { live(); return metadata[4] }

    @Synchronized override fun close() {
        if (handle != 0L) {
            val owned = handle
            handle = 0
            NativeTokenizerBridge.closeNative(owned)
        }
    }

    private fun live(): Long = handle.also { check(it != 0L) { "This tokenizer is closed." } }
    private fun utf8(text: String): ByteArray {
        require(text.length <= MAX_INPUT_BYTES) { "Tokenizer text exceeds 64 KiB." }
        return text.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_INPUT_BYTES) { "Tokenizer UTF-8 text exceeds 64 KiB." } }
    }

    companion object {
        const val MAX_MODEL_BYTES = 16L * 1024 * 1024
        const val MAX_INPUT_BYTES = 64 * 1024
        const val MAX_OUTPUT_BYTES = 1024 * 1024
        const val MAX_TOKENS = 16 * 1024
        fun availabilityMessage(): String? = NativeTokenizerBridge.loadingError?.let { "Native SentencePiece runtime unavailable: ${it.message}" }
    }
}

/** UTF-8 bytes avoid JNI modified-UTF-8 corruption of supplementary Unicode and NUL. */
internal object NativeTokenizerBridge {
    val loadingError: Throwable? = runCatching { System.loadLibrary("pocketai_tokenizer") }.exceptionOrNull()
    fun requireAvailable() { loadingError?.let { throw UnsupportedOperationException("Native SentencePiece runtime is unavailable on this device.", it) } }
    external fun openNative(pathUtf8: ByteArray): Long
    external fun closeNative(handle: Long)
    external fun infoNative(handle: Long): IntArray
    external fun encodeIdsNative(handle: Long, textUtf8: ByteArray): IntArray
    external fun decodeIdsNative(handle: Long, ids: IntArray): ByteArray
    external fun encodePiecesNative(handle: Long, textUtf8: ByteArray): Array<ByteArray>
    external fun decodePiecesNative(handle: Long, piecesUtf8: Array<ByteArray>): ByteArray
    external fun normalizeNative(handle: Long, textUtf8: ByteArray): ByteArray
}
