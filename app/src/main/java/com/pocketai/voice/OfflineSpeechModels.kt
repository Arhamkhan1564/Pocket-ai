package com.pocketai.voice

import android.content.Context
import com.pocketai.AppSettings
import com.pocketai.engine.NativeBridge
import com.pocketai.models.ModelKind
import com.pocketai.models.ModelManager
import com.pocketai.models.ModelValidator
import com.pocketai.models.OfflineModelAssets
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class SpeechBackend { WHISPER, PUNJABI_CTC }

/** Immutable resource selection captured before the microphone starts. */
data class SpeechRecognitionPlan(
    val modelFile: File,
    val backend: SpeechBackend,
    val languageTag: String,
    val vocabularyFile: File? = null,
    val modelBytes: Long = modelFile.length(),
    val modelModified: Long = modelFile.lastModified(),
    val vocabularyBytes: Long? = vocabularyFile?.length(),
    val vocabularyModified: Long? = vocabularyFile?.lastModified(),
    val speechPresenceFile: File? = null,
    val speechPresenceBytes: Long? = speechPresenceFile?.length(),
    val speechPresenceModified: Long? = speechPresenceFile?.lastModified(),
) {
    val primaryLanguage: String get() = languageTag.substringBefore('-').lowercase(Locale.ROOT)

    fun validateFiles() {
        require(modelFile.isFile && modelFile.canRead() && modelFile.length() == modelBytes &&
            modelFile.lastModified() == modelModified && modelBytes > 0) {
            "The selected speech resource changed or was removed. Start a new recording after local setup."
        }
        if (backend == SpeechBackend.WHISPER) {
            require(modelBytes <= 180L * 1024 * 1024) { "Use a Whisper tiny or base model below 180 MiB." }
            require(!modelFile.name.contains(".en", true) || primaryLanguage == "en") {
                "This English-only speech model cannot recognize $languageTag. Select the included recognizers or a multilingual Whisper model."
            }
            speechPresenceFile?.let { vad ->
                require(vad.isFile && vad.canRead() && vad.length() == speechPresenceBytes &&
                    vad.lastModified() == speechPresenceModified && vad.length() > 0) {
                    "The offline speech detector changed or was removed. Run local setup again."
                }
            }
        } else {
            require(primaryLanguage == "pa") { "The Punjabi speech resource only recognizes Punjabi." }
            val vocab = vocabularyFile
            require(vocab != null && vocab.isFile && vocab.canRead() && vocab.length() == vocabularyBytes &&
                vocab.lastModified() == vocabularyModified && vocab.length() in 1..64 * 1024) {
                "The Punjabi speech vocabulary changed or was removed. Run local setup again."
            }
        }
    }
}

/** Only copies verified APK assets. An explicit custom selection or disabled state always wins. */
object OfflineSpeechModels {
    suspend fun prepare(
        context: Context, models: ModelManager, settings: AppSettings, languageTag: String,
    ): SpeechRecognitionPlan = withContext(Dispatchers.IO) {
        NativeBridge.ensureAvailable()
        val selected = models.list().firstOrNull { it.id == settings.selectedWhisperId && it.kind == ModelKind.WHISPER }
            ?: error("Voice input is disabled or its selected model is unavailable. Choose the included recognizers in Resources → Local models.")
        val language = languageTag.substringBefore('-').lowercase(Locale.ROOT)
        require(language.matches(Regex("[a-z]{2,3}|auto"))) { "Choose a speech language before recording." }
        val included = selected.metadata["bundled"] == "true"
        var plan = if (included && language in setOf("ur", "pa")) {
            val id = if (language == "ur") "voice-ur" else "voice-pa"
            val spec = OfflineModelAssets.specs(context).single { it.id == id }
            val directory = OfflineModelAssets.prepare(context, spec)
            if (language == "ur") SpeechRecognitionPlan(
                OfflineModelAssets.file(directory, "ggml-urdu-base-q5_1.bin"), SpeechBackend.WHISPER, languageTag,
            ) else SpeechRecognitionPlan(
                OfflineModelAssets.file(directory, "model.onnx"), SpeechBackend.PUNJABI_CTC, languageTag,
                OfflineModelAssets.file(directory, "vocab.json"),
            )
        } else SpeechRecognitionPlan(models.fileFor(selected), SpeechBackend.WHISPER, languageTag)
        plan.validateFiles()
        if (plan.backend == SpeechBackend.WHISPER) {
            val header = ModelValidator.validateHeader(plan.modelFile, ModelKind.WHISPER)
            require(header.metadata["multilingual"] != "false" || language == "en") {
                "This English-only speech model cannot recognize $languageTag. Select the included recognizers or a multilingual Whisper model."
            }
            val spec = OfflineModelAssets.specs(context).single { it.id == "speech-vad" }
            val directory = OfflineModelAssets.prepare(context, spec)
            val vad = OfflineModelAssets.file(directory, "silero-vad.onnx")
            plan = plan.copy(speechPresenceFile = vad, speechPresenceBytes = vad.length(), speechPresenceModified = vad.lastModified())
        }
        plan.validateFiles()
        plan
    }

    /** Vocabulary IDs are explicit in the pinned file; JSON iteration order is irrelevant. */
    internal fun punjabiVocabulary(file: File): List<String> {
        require(file.length() in 1..64 * 1024) { "The Punjabi speech vocabulary is invalid." }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        require(json.length() == 65) { "The Punjabi speech vocabulary must contain 65 tokens." }
        val labels = arrayOfNulls<String>(65)
        for (label in json.keys()) {
            val rawId = json.get(label)
            require(rawId is Int || rawId is Long) { "The Punjabi speech vocabulary IDs must be integers." }
            val id = (rawId as Number).toLong().also { require(it in 0L..64L) }.toInt()
            require(id in labels.indices && labels[id] == null && label.isNotEmpty() && label.length <= 40) {
                "The Punjabi speech vocabulary contains invalid IDs."
            }
            labels[id] = label
        }
        require(labels.all { it != null } && labels[1] == "<pad>" && labels.count { it == "|" } == 1) {
            "The Punjabi speech vocabulary has an unsupported blank or word delimiter."
        }
        return labels.map { requireNotNull(it) }
    }
}
