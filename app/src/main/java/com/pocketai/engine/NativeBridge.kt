package com.pocketai.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One local inference at a time: ASR and generation never retain models together. */
object NativeExecution {
    private val mutex = Mutex()
    private val cancellationLock = Any()
    private var cancellationHook: (() -> Unit)? = null
    suspend fun <T> runExclusive(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { block() }
            finally { clearCancellationHook() }
        }
    }
    /** Register only inside runExclusive, and clear before disposing the native run options. */
    fun installCancellationHook(hook: () -> Unit) = synchronized(cancellationLock) { cancellationHook = hook }
    fun clearCancellationHook() = synchronized(cancellationLock) { cancellationHook = null }
    fun cancel() {
        NativeBridge.cancel()
        synchronized(cancellationLock) { cancellationHook?.invoke() }
    }
}

object NativeBridge {
    private val loadingError: Throwable? = runCatching { System.loadLibrary("pocketai") }.exceptionOrNull()

    fun availabilityMessage(): String? = when {
        loadingError != null -> "Local inference is unavailable for this device ABI: ${loadingError.message}"
        !supportsInferenceNative() -> "This processor does not support the included inference runtime. ARMv7 needs NEON support. Saved-document text search and installed offline TTS remain available."
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
