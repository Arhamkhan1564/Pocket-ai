package com.pocketai.voice

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.CancellationException

/**
 * CPU-only Silero v6.2.3 presence check, matching the official 16 kHz ONNX wrapper:
 * 512 current samples, 64 previous samples and recurrent state [2, 1, 128].
 * The caller verifies the pinned file, holds the shared inference lock and owns runOptions.
 * Close this session before loading an ASR model. This is a speech/noise gate, not ASR.
 */
class SpeechPresenceKernel(
    modelFile: File,
    private val runOptions: OrtSession.RunOptions? = null,
    private val checkCancelled: () -> Unit = {},
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = createSession(modelFile)
    private var closed = false

    @Synchronized
    fun hasSpeech(pcm16k: FloatArray): Boolean {
        val probabilities = probabilities(pcm16k)
        try { return SpeechPresenceDecision.hasSpeech(probabilities, pcm16k.size) }
        finally { probabilities.fill(0f) }
    }

    /** Exposed for reproducible host parity checks; production normally uses hasSpeech. */
    @Synchronized
    fun probabilities(pcm16k: FloatArray): FloatArray {
        check(!closed) { "The speech presence session is closed." }
        SpeechPresenceDecision.validatePcm(pcm16k, ::cancelled)
        val probabilities = FloatArray((pcm16k.size + SpeechPresenceDecision.FRAME_SAMPLES - 1) / SpeechPresenceDecision.FRAME_SAMPLES)
        val frame = FloatArray(CONTEXT_SAMPLES + SpeechPresenceDecision.FRAME_SAMPLES)
        var state = FloatArray(2 * STATE_DIMENSION)
        var success = false
        try {
            for (index in probabilities.indices) {
                cancelled()
                // Preserve the preceding frame's final 64 samples, as in OnnxWrapper._context.
                if (index > 0) frame.copyInto(frame, 0, SpeechPresenceDecision.FRAME_SAMPLES, frame.size)
                java.util.Arrays.fill(frame, CONTEXT_SAMPLES, frame.size, 0f)
                val start = index * SpeechPresenceDecision.FRAME_SAMPLES
                pcm16k.copyInto(frame, CONTEXT_SAMPLES, start, minOf(start + SpeechPresenceDecision.FRAME_SAMPLES, pcm16k.size))
                val next = infer(frame, state)
                probabilities[index] = next.first
                state.fill(0f)
                state = next.second
            }
            cancelled()
            success = true
            return probabilities
        } finally {
            frame.fill(0f)
            state.fill(0f)
            if (!success) probabilities.fill(0f)
        }
    }

    private fun infer(frame: FloatArray, state: FloatArray): Pair<Float, FloatArray> {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(frame), longArrayOf(1, frame.size.toLong())).use { input ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(state), longArrayOf(2, 1, STATE_DIMENSION.toLong())).use { recurrent ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(16_000)), longArrayOf()).use { rate ->
                    cancelled()
                    val result = try {
                        session.run(mapOf("input" to input, "state" to recurrent, "sr" to rate), runOptions)
                    } catch (failure: OrtException) {
                        cancelled()
                        throw failure
                    }
                    result.use {
                        cancelled()
                        val probability = result.get("output").orElse(null) as? OnnxTensor
                            ?: error("The speech presence model returned no probability tensor.")
                        val outputInfo = probability.info as TensorInfo
                        require(outputInfo.type == OnnxJavaType.FLOAT && outputInfo.shape.contentEquals(longArrayOf(1, 1))) {
                            "The speech presence model returned an unsupported probability shape."
                        }
                        val value = probability.floatBuffer.get(0)
                        require(value.isFinite() && value in 0f..1f) { "The speech presence model returned an invalid probability." }
                        val nextState = result.get("stateN").orElse(null) as? OnnxTensor
                            ?: error("The speech presence model returned no recurrent state.")
                        val stateInfo = nextState.info as TensorInfo
                        require(stateInfo.type == OnnxJavaType.FLOAT && stateInfo.shape.contentEquals(longArrayOf(2, 1, STATE_DIMENSION.toLong()))) {
                            "The speech presence model returned an unsupported recurrent state."
                        }
                        val values = nextState.floatBuffer
                        require(values.remaining() == 2 * STATE_DIMENSION) { "The speech presence model returned incomplete state." }
                        val copiedState = FloatArray(values.remaining())
                        try {
                            for (index in copiedState.indices) {
                                copiedState[index] = values.get(index)
                                require(copiedState[index].isFinite()) { "The speech presence model returned invalid recurrent state." }
                            }
                            return value to copiedState
                        } catch (failure: Throwable) {
                            copiedState.fill(0f)
                            throw failure
                        }
                    }
                }
            }
        }
    }

    private fun createSession(modelFile: File): OrtSession {
        cancelled()
        require(modelFile.isFile && modelFile.canRead()) { "The included speech presence model is missing." }
        val created = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setMemoryPatternOptimization(false)
            options.setCPUArenaAllocator(false)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(modelFile.absolutePath, options)
        }
        try {
            cancelled()
            require(created.inputNames == setOf("input", "state", "sr") && created.outputNames == setOf("output", "stateN")) {
                "The included speech presence model has an unsupported interface."
            }
            return created
        } catch (failure: Throwable) { created.close(); throw failure }
    }

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Speech presence check cancelled.")
        checkCancelled()
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; session.close() }
    }

    companion object {
        private const val CONTEXT_SAMPLES = 64
        private const val STATE_DIMENSION = 128
    }
}

/** Boolean equivalent of official timestamp segmentation with defaults and no max-duration split. */
object SpeechPresenceDecision {
    const val FRAME_SAMPLES = 512
    const val MAX_SAMPLES = 60 * 16_000
    private const val MIN_SPEECH_SAMPLES = 4_000 // 250 ms, reference uses strict >.
    private const val MIN_SILENCE_SAMPLES = 1_600 // 100 ms.

    fun validatePcm(pcm: FloatArray, checkCancelled: () -> Unit = {}) {
        require(pcm.size in 1..MAX_SAMPLES) { "Use a nonempty recording of at most 60 seconds." }
        for (index in pcm.indices) {
            if (index % 4096 == 0) checkCancelled()
            require(pcm[index].isFinite() && pcm[index] in -1f..1f) { "The speech recording contains invalid PCM samples." }
        }
    }

    fun hasSpeech(probabilities: FloatArray, sampleCount: Int): Boolean {
        require(sampleCount in 1..MAX_SAMPLES && probabilities.size == (sampleCount + FRAME_SAMPLES - 1) / FRAME_SAMPLES) {
            "The speech presence frame count does not match the recording."
        }
        var start = -1
        var temporaryEnd = -1
        for (index in probabilities.indices) {
            val probability = probabilities[index]
            require(probability.isFinite() && probability in 0f..1f) { "The speech probability is invalid." }
            val value = probability.toDouble() // Match Python's float32 .item() promoted to double.
            val current = index * FRAME_SAMPLES
            if (value >= .5 && temporaryEnd >= 0) temporaryEnd = -1
            if (value >= .5 && start < 0) { start = current; continue }
            if (value < .35 && start >= 0) {
                if (temporaryEnd < 0) temporaryEnd = current
                if (current - temporaryEnd < MIN_SILENCE_SAMPLES) continue
                if (temporaryEnd - start > MIN_SPEECH_SAMPLES) return true
                start = -1; temporaryEnd = -1
            }
        }
        return start >= 0 && sampleCount - start > MIN_SPEECH_SAMPLES
    }
}
