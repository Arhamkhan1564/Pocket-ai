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
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

data class PunjabiCtcResult(val text: String, val frameIds: IntArray, val windowCount: Int)

/**
 * Bounded CPU inference for the pinned wav2vec2 Punjabi CTC export.
 *
 * The caller verifies the model/vocabulary identities, supplies mono 16 kHz PCM
 * in [-1, 1], owns any [runOptions], and holds the application's native-work lock.
 * This class performs no translation, spelling correction, network access or
 * script conversion. Its raw transcript requires user review.
 */
class PunjabiCtcKernel(
    modelFile: File,
    vocabulary: List<String>,
    private val blankId: Int,
    private val wordDelimiter: String = "|",
    private val runOptions: OrtSession.RunOptions? = null,
    private val checkCancelled: () -> Unit = {},
) : Closeable {
    private val labels = vocabulary.toList().also {
        require(it.size in 2..4096 && blankId in it.indices) { "The speech vocabulary or blank ID is invalid." }
        require(it.all { label -> label.isNotEmpty() && label.length <= 128 } && it.distinct().size == it.size) {
            "The speech vocabulary contains empty, duplicate or oversized labels."
        }
        require(wordDelimiter.isNotEmpty() && it.count { label -> label == wordDelimiter } == 1) {
            "The speech vocabulary must contain exactly one word delimiter."
        }
        require(it[blankId] != wordDelimiter) { "The speech blank cannot be the word delimiter." }
    }
    private val environment = OrtEnvironment.getEnvironment()
    private val session = createSession(modelFile)
    private var closed = false

    @Synchronized
    fun transcribe(pcm16k: FloatArray): PunjabiCtcResult {
        check(!closed) { "The speech session is closed." }
        cancelled()
        val audio = PunjabiCtcFrames.normalize(pcm16k, ::cancelled)
        try {
            val windows = PunjabiCtcFrames.windows(audio.size)
            val frameIds = IntArray(PunjabiCtcFrames.frameCount(audio.size))
            var written = 0
            for ((index, window) in windows.withIndex()) {
                cancelled()
                val frames = infer(audio, window)
                for (localFrame in frames.indices) {
                    if (!PunjabiCtcFrames.keepsFrame(windows, index, localFrame)) continue
                    val absoluteFrame = window.start / PunjabiCtcFrames.STRIDE_SAMPLES + localFrame
                    check(absoluteFrame == written) { "The speech windows produced a gap or duplicate frame." }
                    frameIds[written++] = frames[localFrame]
                }
            }
            check(written == frameIds.size) { "The speech windows did not cover the recording." }
            cancelled()
            return PunjabiCtcResult(
                PunjabiCtcFrames.decode(frameIds, labels, blankId, wordDelimiter), frameIds, windows.size,
            )
        } finally {
            audio.fill(0f)
        }
    }

    private fun createSession(modelFile: File): OrtSession {
        cancelled()
        require(modelFile.isFile && modelFile.canRead()) { "The local Punjabi speech model is missing." }
        val created = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setMemoryPatternOptimization(false)
            options.setCPUArenaAllocator(false)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(modelFile.absolutePath, options)
        }
        try {
            cancelled()
            require(created.inputNames == setOf("input_values") && created.outputNames == setOf("logits")) {
                "The local Punjabi speech model has an unsupported interface."
            }
            return created
        } catch (failure: Throwable) {
            created.close()
            throw failure
        }
    }

    private fun infer(audio: FloatArray, window: PunjabiCtcWindow): IntArray {
        val sampleCount = window.endExclusive - window.start
        val expectedFrames = PunjabiCtcFrames.frameCount(sampleCount)
        val inputBuffer = FloatBuffer.wrap(audio, window.start, sampleCount).slice()
        OnnxTensor.createTensor(environment, inputBuffer, longArrayOf(1, sampleCount.toLong())).use { input ->
            cancelled()
            val result = try {
                session.run(mapOf("input_values" to input), runOptions)
            } catch (failure: OrtException) {
                cancelled()
                throw failure
            }
            result.use {
                cancelled()
                val logits = result[0] as? OnnxTensor ?: error("The speech model returned a non-tensor output.")
                val info = logits.info as TensorInfo
                require(info.type == OnnxJavaType.FLOAT &&
                    info.shape.contentEquals(longArrayOf(1, expectedFrames.toLong(), labels.size.toLong()))) {
                    "The speech model returned an unsupported output shape."
                }
                val values = logits.floatBuffer
                require(values.remaining() == expectedFrames * labels.size) { "The speech scores are incomplete." }
                return IntArray(expectedFrames) { frame ->
                    var bestId = -1
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (id in labels.indices) {
                        val score = values.get(frame * labels.size + id)
                        require(score.isFinite()) { "The speech model returned non-finite scores." }
                        if (score > bestScore) { bestScore = score; bestId = id }
                    }
                    check(bestId >= 0) { "The speech model returned no token scores." }
                    bestId
                }
            }
        }
    }

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Speech recognition cancelled.")
        checkCancelled()
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            session.close()
        }
    }
}

data class PunjabiCtcWindow(val start: Int, val endExclusive: Int)

/** Frame planning and raw CTC decoding have no Android or JSON dependencies. */
object PunjabiCtcFrames {
    const val SAMPLE_RATE = 16_000
    const val MAX_SAMPLES = 60 * SAMPLE_RATE
    const val WINDOW_SAMPLES = 12 * SAMPLE_RATE
    const val OVERLAP_SAMPLES = 12_800
    const val STRIDE_SAMPLES = 320
    const val RECEPTIVE_FIELD_SAMPLES = 400
    private const val STEP_SAMPLES = WINDOW_SAMPLES - OVERLAP_SAMPLES

    fun frameCount(sampleCount: Int): Int {
        require(sampleCount >= RECEPTIVE_FIELD_SAMPLES) { "The speech recording is too short." }
        return (sampleCount - RECEPTIVE_FIELD_SAMPLES) / STRIDE_SAMPLES + 1
    }

    fun windows(sampleCount: Int): List<PunjabiCtcWindow> {
        require(sampleCount in RECEPTIVE_FIELD_SAMPLES..MAX_SAMPLES) {
            "Use a nonempty speech recording of at most 60 seconds."
        }
        val result = ArrayList<PunjabiCtcWindow>()
        var start = 0
        while (true) {
            val end = minOf(start + WINDOW_SAMPLES, sampleCount)
            result += PunjabiCtcWindow(start, end)
            if (end == sampleCount) return result
            start += STEP_SAMPLES
        }
    }

    fun keepsFrame(windows: List<PunjabiCtcWindow>, windowIndex: Int, localFrame: Int): Boolean {
        val window = windows[windowIndex]
        require(localFrame in 0 until frameCount(window.endExclusive - window.start))
        // Doubled sample centers avoid rounding the RF400 center at sample199.5.
        val center2 = 2 * window.start + RECEPTIVE_FIELD_SAMPLES - 1 + 2 * STRIDE_SAMPLES * localFrame
        val lower2 = if (windowIndex == 0) 0 else windows[windowIndex - 1].endExclusive + window.start
        val upper2 = if (windowIndex == windows.lastIndex) 2 * window.endExclusive
            else window.endExclusive + windows[windowIndex + 1].start
        return center2 >= lower2 && center2 < upper2
    }

    /** Full-utterance Wav2Vec2 normalization with Double statistics and Float output, before slicing. */
    fun normalize(pcm: FloatArray, checkCancelled: () -> Unit = {}): FloatArray {
        require(pcm.size in RECEPTIVE_FIELD_SAMPLES..MAX_SAMPLES) { "Use a speech recording of at most 60 seconds." }
        var sum = 0.0
        for (index in pcm.indices) {
            if (index % 4096 == 0) checkCancelled()
            val sample = pcm[index]
            require(sample.isFinite() && sample in -1f..1f) { "The speech recording contains invalid PCM samples." }
            sum += sample
        }
        val mean = sum / pcm.size
        var squared = 0.0
        for (index in pcm.indices) {
            if (index % 4096 == 0) checkCancelled()
            val difference = pcm[index] - mean
            squared += difference * difference
        }
        val scale = sqrt(squared / pcm.size + 1e-7)
        val normalized = FloatArray(pcm.size)
        try {
            for (index in pcm.indices) {
                if (index % 4096 == 0) checkCancelled()
                normalized[index] = ((pcm[index] - mean) / scale).toFloat()
            }
            return normalized
        } catch (failure: Throwable) {
            normalized.fill(0f)
            throw failure
        }
    }

    /** Collapse globally after merging frame IDs; a blank separates repeated letters. */
    fun decode(frameIds: IntArray, vocabulary: List<String>, blankId: Int, wordDelimiter: String): String {
        require(blankId in vocabulary.indices && wordDelimiter.isNotEmpty()) { "The speech vocabulary is invalid." }
        val result = StringBuilder()
        var previous = -1
        for (id in frameIds) {
            require(id in vocabulary.indices) { "The speech model returned an unknown token ID." }
            if (id != previous && id != blankId) {
                val label = vocabulary[id]
                result.append(if (label == wordDelimiter) " " else label)
            }
            previous = id
        }
        return result.toString().trim()
    }
}
