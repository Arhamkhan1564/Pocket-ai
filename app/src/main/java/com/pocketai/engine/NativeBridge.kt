package com.pocketai.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One local inference at a time: ASR and generation never retain models together. */
object NativeExecution {
    private val mutex = Mutex()
    suspend fun <T> runExclusive(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }
    fun cancel() = NativeBridge.cancel()
}

object NativeBridge {
    private val loadingError: Throwable? = runCatching { System.loadLibrary("pocketai") }.exceptionOrNull()

    fun availabilityMessage(): String? = when {
        loadingError != null -> "Local inference is unavailable for this device ABI: ${loadingError.message}"
        !supportsInferenceNative() -> "This 32-bit device supports saved-knowledge answers and installed offline TTS. Local LLM and Whisper inference require a 64-bit device in this build."
        else -> null
    }

    fun ensureAvailable() { availabilityMessage()?.let { throw UnsupportedOperationException(it) } }

    fun generate(modelPath: String, system: String, prompt: String, contextSize: Int, maxTokens: Int, threads: Int): String {
        ensureAvailable()
        return generateNative(modelPath, system.toByteArray(Charsets.UTF_8), prompt.toByteArray(Charsets.UTF_8),
            contextSize, maxTokens, threads).toString(Charsets.UTF_8).trim()
    }

    fun transcribe(modelPath: String, samples: FloatArray, language: String, threads: Int): String {
        ensureAvailable()
        return transcribeNative(modelPath, samples, language, threads).toString(Charsets.UTF_8).trim()
    }

    fun cancel() { if (loadingError == null) cancelNative() }
    private external fun supportsInferenceNative(): Boolean
    private external fun generateNative(modelPath: String, system: ByteArray, prompt: ByteArray, contextSize: Int, maxTokens: Int, threads: Int): ByteArray
    private external fun transcribeNative(modelPath: String, samples: FloatArray, language: String, threads: Int): ByteArray
    private external fun cancelNative()
}
