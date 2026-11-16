package com.pocketai.models

import android.content.Context
import com.pocketai.AppSettings
import com.pocketai.engine.NativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BundledSpeechStatus { NOT_STARTED, INSTALLING, READY, UNSUPPORTED, FAILED, REMOVED }

data class BundledSpeechState(
    val status: BundledSpeechStatus = BundledSpeechStatus.NOT_STARTED,
    val message: String = "Included offline speech model has not been prepared yet.",
    val modelId: String? = null,
    val bytesCopied: Long = 0L,
    val totalBytes: Long = BundledSpeechModel.SIZE_BYTES
)

/** A pinned multilingual speech model shipped with the APK; no downloads are performed here. */
object BundledSpeechModel {
    const val ASSET_PATH = "models/ggml-tiny-q5_1.bin"
    const val DISPLAY_NAME = "Whisper tiny multilingual · included"
    const val SIZE_BYTES = 32_152_673L
    const val SHA256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
    const val SOURCE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny-q5_1.bin"
    private const val PREFERENCES = "pocket_bundled_speech"
    private const val REMOVED_KEY = "removed_sha256"
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(BundledSpeechState())
    val state: StateFlow<BundledSpeechState> = mutableState.asStateFlow()

    /** Call at app startup. force is reserved for the explicit Restore included model action. */
    suspend fun ensureInstalled(
        context: Context,
        models: ModelManager,
        settings: AppSettings,
        force: Boolean = false
    ): InstalledModel? = withContext(Dispatchers.IO) {
        mutex.withLock {
            NativeBridge.availabilityMessage()?.let { message ->
                mutableState.value = BundledSpeechState(BundledSpeechStatus.UNSUPPORTED, message)
                return@withLock null
            }
            val appContext = context.applicationContext
            val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (!force && preferences.getString(REMOVED_KEY, null) == SHA256) {
                mutableState.value = BundledSpeechState(BundledSpeechStatus.REMOVED,
                    "The included speech model was removed. Restore it in Resources → Local models when needed.")
                return@withLock null
            }
            mutableState.value = BundledSpeechState(BundledSpeechStatus.INSTALLING,
                "Preparing included multilingual speech · 30.7 MiB · stays on this phone")
            try {
                val installed = models.installVerifiedBundledModel(DISPLAY_NAME, SIZE_BYTES, SHA256,
                    onProgress = { bytes, _ ->
                        mutableState.value = BundledSpeechState(BundledSpeechStatus.INSTALLING,
                            "Preparing included multilingual speech · ${bytes * 100 / SIZE_BYTES}%", bytesCopied = bytes)
                    }, source = { appContext.assets.open(ASSET_PATH) })
                // Wait for any concurrent import before inspecting selection. Commit on Main,
                // where model-selection buttons run, so a newer user choice always wins.
                val availableModels = models.list()
                val selectedId = settings.selectedWhisperId
                val selected = availableModels.firstOrNull { it.id == selectedId && it.kind == ModelKind.WHISPER }
                val validSelection = selected != null && runCatching {
                    ModelValidator.validateHeader(models.fileFor(selected), ModelKind.WHISPER)
                }.isSuccess
                withContext(Dispatchers.Main.immediate) {
                    if (!validSelection && settings.selectedWhisperId == selectedId) {
                        settings.selectedWhisperId = installed.id
                    }
                }
                if (force) preferences.edit().remove(REMOVED_KEY).apply()
                mutableState.value = BundledSpeechState(BundledSpeechStatus.READY,
                    "Included multilingual speech ready · 30.7 MiB. Urdu and Punjabi recognition quality depends on the recording and phone.",
                    installed.id, SIZE_BYTES)
                installed
            } catch (cancelled: CancellationException) {
                mutableState.value = BundledSpeechState(BundledSpeechStatus.NOT_STARTED, "Speech setup paused. It will resume when the app opens again.")
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = BundledSpeechState(BundledSpeechStatus.FAILED,
                    "Included speech model could not be prepared: ${failure.message ?: "resource unavailable"}. Open Local models to retry or import a compatible model.")
                null
            }
        }
    }

    internal fun markRemoved(context: Context, checksum: String) {
        if (checksum != SHA256) return
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(REMOVED_KEY, SHA256).apply()
        mutableState.value = BundledSpeechState(BundledSpeechStatus.REMOVED,
            "The private copy of the included speech model was removed. Its APK resource remains available for Restore.")
    }
}
