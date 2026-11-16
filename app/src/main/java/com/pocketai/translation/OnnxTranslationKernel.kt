package com.pocketai.translation

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException

/** The model's dictionary mapping and official language processor live outside ORT. */
interface TranslationTextCodec {
    fun encode(source: String, targetLanguage: String): LongArray
    fun decode(tokenIds: LongArray, targetLanguage: String): String
}

enum class TranslationStopReason { EOS, LENGTH_LIMIT }

data class TranslationTokenResult(val tokenIds: LongArray, val stopReason: TranslationStopReason) {
    val completed: Boolean get() = stopReason == TranslationStopReason.EOS
}

data class TranslationKernelResult(
    val text: String,
    val tokenIds: LongArray,
    val stopReason: TranslationStopReason,
) {
    val completed: Boolean get() = stopReason == TranslationStopReason.EOS
}

/**
 * CPU inference for the pinned, quantized IndicTrans2 English-to-Indic export.
 *
 * Exactly one heavyweight session is open at a time. Native output tensors
 * survive the session which produced them: the encoder result supplies the
 * first decoder; its cross-attention cache supplies subsequent decoder steps.
 * A result is kept alive until its tensors are no longer used as inputs. Cache
 * tensors never pass through Java arrays. A single direct logits buffer is
 * supplied as a pinned output on every decoder run.
 *
 * The caller owns [codec] and any supplied [runOptions]. In-app callers must use
 * the global native execution lock and connect cancellation to RunOptions.
 */
class OnnxTranslationKernel(
    modelDirectory: File,
    private val codec: TranslationTextCodec,
    private val runOptions: OrtSession.RunOptions? = null,
    private val checkCancelled: () -> Unit = {},
) {
    private val directory = modelDirectory.canonicalFile

    @Synchronized
    fun translate(source: String, targetLanguage: String, maxOutputTokens: Int = 128): TranslationKernelResult {
        require(source.isNotBlank() && source.length <= MAX_SOURCE_CHARACTERS) {
            "Translate one nonempty source segment under $MAX_SOURCE_CHARACTERS characters."
        }
        cancelled()
        val generated = generate(codec.encode(source, targetLanguage), maxOutputTokens)
        cancelled()
        return TranslationKernelResult(
            codec.decode(generated.tokenIds, targetLanguage), generated.tokenIds, generated.stopReason,
        )
    }

    /** Raw-token entry point also permits exact Java/Python parity checks. */
    @Synchronized
    fun generate(inputIds: LongArray, maxOutputTokens: Int = 128): TranslationTokenResult {
        require(inputIds.size in 3..MAX_SOURCE_TOKENS) {
            "This source segment exceeds the translation model's $MAX_SOURCE_TOKENS-token limit. Split it into shorter sentences."
        }
        require(inputIds.all { it in 0 until ENCODER_VOCABULARY.toLong() } && inputIds.last() == EOS) {
            "The translation tokenizer returned unsupported source IDs or omitted EOS."
        }
        require(maxOutputTokens in 1..MAX_OUTPUT_TOKENS) { "Output limit must be within 1..$MAX_OUTPUT_TOKENS tokens." }
        val modelFiles = GRAPH_NAMES.associateWith { name ->
            File(directory, name).also { require(it.isFile && it.canRead()) { "Translation graph is missing: $name" } }
        }
        require(File(directory, SHARED_DECODER_DATA).isFile) { "Shared local translation weights are missing." }
        cancelled()
        val environment = OrtEnvironment.getEnvironment()
        val ownedRunOptions = if (runOptions == null) OrtSession.RunOptions() else null
        val options = runOptions ?: ownedRunOptions!!
        var encoderResult: OrtSession.Result? = null
        var firstResult: OrtSession.Result? = null
        var latestResult: OrtSession.Result? = null
        try {
            val sourceShape = longArrayOf(1, inputIds.size.toLong())
            directLongTensor(environment, LongArray(inputIds.size) { 1 }, sourceShape).use { mask ->
                directLongTensor(environment, inputIds, sourceShape).use { source ->
                    session(environment, modelFiles.getValue(ENCODER)).use { encoder ->
                        requireNames(encoder.inputNames, setOf("input_ids", "attention_mask"), ENCODER)
                        requireNames(encoder.outputNames, setOf("last_hidden_state"), ENCODER)
                        encoderResult = run(encoder, mapOf("input_ids" to source, "attention_mask" to mask),
                            setOf("last_hidden_state"), emptyMap(), options)
                        tensor(encoderResult!!, "last_hidden_state", longArrayOf(1, inputIds.size.toLong(), HIDDEN_SIZE))
                    }
                }
                val tokenBuffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer()
                tokenBuffer.put(0, EOS) // The export's decoder start ID equals EOS.
                val logitsBuffer = ByteBuffer.allocateDirect(DECODER_VOCABULARY * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                OnnxTensor.createTensor(environment, tokenBuffer, longArrayOf(1, 1)).use { token ->
                    OnnxTensor.createTensor(environment, logitsBuffer, longArrayOf(1, 1, DECODER_VOCABULARY.toLong())).use { logits ->
                        val pinned = mapOf("logits" to logits)
                        session(environment, modelFiles.getValue(FIRST_DECODER)).use { decoder ->
                            requireNames(decoder.inputNames,
                                setOf("input_ids", "encoder_hidden_states", "encoder_attention_mask"), FIRST_DECODER)
                            requireNames(decoder.outputNames, ALL_PRESENT + "logits", FIRST_DECODER)
                            firstResult = run(decoder, mapOf(
                                "input_ids" to token,
                                "encoder_attention_mask" to mask,
                                "encoder_hidden_states" to value(encoderResult!!, "last_hidden_state"),
                            ), ALL_PRESENT, pinned, options)
                            validateCaches(firstResult!!, inputIds.size, 1, includeEncoder = true)
                        }
                        encoderResult!!.close()
                        encoderResult = null
                        val generated = LongArray(maxOutputTokens)
                        generated[0] = argmax(logitsBuffer)
                        var size = 1
                        if (generated[0] == EOS) return TranslationTokenResult(generated.copyOf(size), TranslationStopReason.EOS)
                        if (size == maxOutputTokens) return TranslationTokenResult(generated, TranslationStopReason.LENGTH_LIMIT)
                        val firstCache = values(firstResult!!)
                        var decoderCache = firstCache
                        session(environment, modelFiles.getValue(PAST_DECODER)).use { decoder ->
                            requireNames(decoder.inputNames, ALL_PAST + setOf("input_ids", "encoder_attention_mask"), PAST_DECODER)
                            requireNames(decoder.outputNames, ALL_PRESENT + "logits", PAST_DECODER)
                            while (size < maxOutputTokens) {
                                cancelled()
                                tokenBuffer.put(0, generated[size - 1])
                                val feed = LinkedHashMap<String, OnnxTensor>(ALL_PAST.size + 2)
                                feed["input_ids"] = token
                                feed["encoder_attention_mask"] = mask
                                for (name in ENCODER_PRESENT) feed[name.replace("present.", "past_key_values.")] = firstCache.getValue(name)
                                for (name in DECODER_PRESENT) feed[name.replace("present.", "past_key_values.")] = decoderCache.getValue(name)
                                // The encoder KV is invariant; keep the first result instead of
                                // requesting/copying it again from every past-decoder result.
                                val next = run(decoder, feed, DECODER_PRESENT, pinned, options)
                                val previous = latestResult
                                latestResult = next
                                try {
                                    validateCaches(next, inputIds.size, size + 1, includeEncoder = false)
                                    decoderCache = values(next)
                                } finally {
                                    previous?.close()
                                }
                                generated[size++] = argmax(logitsBuffer)
                                if (generated[size - 1] == EOS) {
                                    return TranslationTokenResult(generated.copyOf(size), TranslationStopReason.EOS)
                                }
                            }
                        }
                        return TranslationTokenResult(generated, TranslationStopReason.LENGTH_LIMIT)
                    }
                }
            }
        } finally {
            latestResult?.close()
            firstResult?.close()
            encoderResult?.close()
            ownedRunOptions?.close()
        }
    }

    private fun session(environment: OrtEnvironment, graph: File): OrtSession {
        cancelled()
        val created = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setMemoryPatternOptimization(false)
            options.setCPUArenaAllocator(false)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(graph.absolutePath, options)
        }
        try {
            cancelled()
            return created
        } catch (failure: Throwable) {
            created.close()
            throw failure
        }
    }

    private fun run(
        session: OrtSession,
        inputs: Map<String, OnnxTensor>,
        outputs: Set<String>,
        pinned: Map<String, OnnxTensor>,
        options: OrtSession.RunOptions,
    ): OrtSession.Result {
        cancelled()
        val result = try {
            session.run(inputs, outputs, pinned, options)
        } catch (failure: OrtException) {
            cancelled() // Translate a cancelled native run back to caller cancellation.
            throw failure
        }
        try {
            cancelled()
            return result
        } catch (failure: Throwable) {
            result.close()
            throw failure
        }
    }

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Translation cancelled.")
        checkCancelled()
    }

    private fun validateCaches(result: OrtSession.Result, sourceTokens: Int, decoderTokens: Int, includeEncoder: Boolean) {
        for (name in DECODER_PRESENT) tensor(result, name, longArrayOf(1, HEADS, decoderTokens.toLong(), HEAD_WIDTH))
        if (includeEncoder) for (name in ENCODER_PRESENT) tensor(result, name, longArrayOf(1, HEADS, sourceTokens.toLong(), HEAD_WIDTH))
    }

    private fun tensor(result: OrtSession.Result, name: String, shape: LongArray): OnnxTensor {
        val tensor = value(result, name)
        val info = tensor.info as TensorInfo
        require(info.type == OnnxJavaType.FLOAT && info.shape.contentEquals(shape)) {
            "The translation model returned an unsupported tensor shape: $name."
        }
        return tensor
    }

    private fun value(result: OrtSession.Result, name: String): OnnxTensor =
        result.firstOrNull { it.key == name }?.value as? OnnxTensor
            ?: error("The translation model omitted tensor $name.")

    private fun values(result: OrtSession.Result): Map<String, OnnxTensor> =
        result.associate { it.key to (it.value as? OnnxTensor ?: error("The translation model returned a non-tensor output.")) }

    private fun requireNames(actual: Set<String>, expected: Set<String>, graph: String) {
        require(actual == expected) { "The translation graph has an unsupported interface: $graph." }
    }

    private fun argmax(logits: FloatBuffer): Long {
        var best = -1
        var maximum = Float.NEGATIVE_INFINITY
        for (index in 0 until DECODER_VOCABULARY) {
            if (index == PAD.toInt()) continue
            val value = logits.get(index)
            require(!value.isNaN() && value != Float.POSITIVE_INFINITY) { "The translation model returned invalid logits." }
            if (value > maximum) { maximum = value; best = index }
        }
        check(best >= 0) { "The translation model returned no finite token scores." }
        return best.toLong()
    }

    private fun directLongTensor(environment: OrtEnvironment, values: LongArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(values.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
        buffer.put(values).rewind()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    companion object {
        const val MAX_SOURCE_TOKENS = 256
        const val MAX_OUTPUT_TOKENS = 256
        const val MAX_SOURCE_CHARACTERS = 4000
        private const val ENCODER_VOCABULARY = 32322
        private const val DECODER_VOCABULARY = 122672
        private const val LAYERS = 18
        private const val HEADS = 8L
        private const val HEAD_WIDTH = 64L
        private const val HIDDEN_SIZE = 512L
        private const val EOS = 2L
        private const val PAD = 1L
        private const val ENCODER = "encoder_model.onnx"
        private const val FIRST_DECODER = "decoder_model.onnx"
        private const val PAST_DECODER = "decoder_with_past_model.onnx"
        private const val SHARED_DECODER_DATA = "decoder_shared.onnx.data"
        private val GRAPH_NAMES = setOf(ENCODER, FIRST_DECODER, PAST_DECODER)
        private fun cacheNames(kind: String): Set<String> = (0 until LAYERS).flatMap { layer ->
            listOf("present.$layer.$kind.key", "present.$layer.$kind.value")
        }.toSet()
        private val ENCODER_PRESENT = cacheNames("encoder")
        private val DECODER_PRESENT = cacheNames("decoder")
        private val ALL_PRESENT = ENCODER_PRESENT + DECODER_PRESENT
        private val ALL_PAST = ALL_PRESENT.map { it.replace("present.", "past_key_values.") }.toSet()
    }
}
