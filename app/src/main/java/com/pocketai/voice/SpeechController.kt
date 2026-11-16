package com.pocketai.voice

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.pocketai.engine.NativeBridge
import com.pocketai.engine.NativeExecution
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class VoiceState(
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val speaking: Boolean = false,
    val ttsReady: Boolean = false,
    val secondsRecorded: Int = 0,
    val message: String = "Speech engine starting",
)

/** Bounded PCM recording + local Whisper inference and verified offline Android TTS voices. */
class SpeechController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    val audioDevices = AudioDeviceManager(appContext)
    private val mutableState = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()
    private val lock = Any()
    private var session: RecordingSession? = null
    private var closed = false
    @Volatile private var ttsReady = false
    private var currentUtterance: String? = null
    var speechRate: Float = 1f
        set(value) { field = value.coerceIn(0.5f, 1.75f) }
    var volume: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f) }

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (!closed) {
                ttsReady = status == TextToSpeech.SUCCESS
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == currentUtterance) update { it.copy(speaking = true, message = "Speaking offline") }
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == currentUtterance) update { it.copy(speaking = false, message = "Ready") }
                    }
                    @Deprecated("Android callback")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == currentUtterance) update { it.copy(speaking = false, message = "The installed voice could not speak this text. Text remains available.") }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                })
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (Build.VERSION.SDK_INT >= 26) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                update { it.copy(ttsReady = ttsReady, message = if (ttsReady) "Speech engine ready; language voices checked before playback" else "Android speech engine unavailable; text responses work") }
            }
        }
    }

    val isRecording: Boolean get() = session?.stopping?.get() == false
    /** Retains a bounded recording after auto-stop so the next tap can transcribe it. */
    val hasPendingRecording: Boolean get() = synchronized(lock) { session != null }

    /** Permission must be granted by a visible Activity before an explicit microphone session starts. */
    fun startRecording() {
        check(!closed) { "Speech controller is closed" }
        check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required. Allow it in Android settings and try again."
        }
        stopSpeaking()
        synchronized(lock) {
            check(session == null) { "Finish or cancel the current recording first." }
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "This microphone does not support 16 kHz mono recording." }
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                error("Android could not initialize the microphone. It may be in use by another app.")
            }
            val recording = RecordingSession(recorder)
            try {
                audioDevices.routeRecorder(recorder)
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone could not start." }
                session = recording
                update { it.copy(recording = true, transcribing = false, secondsRecorded = 0, message = "Listening · maximum 60 seconds · tap again to transcribe") }
                recording.thread = Thread({ readAudio(recording) }, "PocketAI-Microphone").also { it.start() }
            } catch (failure: Throwable) {
                recorder.release()
                throw failure
            }
        }
    }

    suspend fun finishTranscription(modelFile: File?, languageTag: String): String = withContext(Dispatchers.IO) {
        val recording = synchronized(lock) { session ?: error("Record some speech first.") }
        var pcm: FloatArray? = null
        try {
            stopSession(recording)
            synchronized(lock) { if (session === recording) session = null }
            recording.failure?.let { error(it) }
            require(recording.count >= SAMPLE_RATE / 3) { "The recording was too short. Speak for at least a second and try again." }
            require(modelFile != null && modelFile.isFile) {
                "Import a multilingual Whisper tiny or base model in Models before using voice input. Recording was discarded."
            }
            require(modelFile.length() in 1..MAX_SPEECH_MODEL_BYTES) {
                "Use a Whisper tiny or base model below 180 MiB on this low-memory device."
            }
            val language = languageTag.substringBefore('-').lowercase(Locale.ROOT).ifBlank { "auto" }
            require(!modelFile.name.contains(".en", ignoreCase = true) || language == "en" || language == "auto") {
                "This English-only speech model cannot recognize $languageTag. Import a multilingual Whisper model."
            }
            val samples = speechSamples(recording.samples, recording.count)
            pcm = samples
            update { it.copy(recording = false, transcribing = true, message = "Recognizing speech locally · this may take time") }
            val result = NativeExecution.runExclusive {
                val memory = ActivityManager.MemoryInfo()
                (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
                val estimatedBytes = maxOf(modelFile.length() * 4L + 128L * 1024 * 1024, 256L * 1024 * 1024)
                check(!memory.lowMemory && estimatedBytes <= memory.availMem * 3L / 4L) {
                    "Not enough free memory for this speech model. Close other apps or import multilingual Whisper tiny. Required safety budget: ${estimatedBytes / (1024 * 1024)} MiB; available: ${memory.availMem / (1024 * 1024)} MiB."
                }
                NativeBridge.transcribe(modelFile.absolutePath, samples, language, minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors() - 1)))
            }.trim()
            require(result.isNotBlank()) { "No speech was recognized. Try a quieter room and speak clearly." }
            update { it.copy(transcribing = false, message = "Speech recognized offline") }
            result
        } catch (failure: Throwable) {
            update { it.copy(transcribing = false, message = failure.message ?: "Offline recognition failed") }
            throw failure
        } finally {
            runCatching { stopSession(recording) }
            synchronized(lock) { if (session === recording) session = null }
            pcm?.fill(0f)
            recording.samples.fill(0)
            update { it.copy(recording = false, transcribing = false) }
        }
    }

    fun cancelTranscription() { NativeExecution.cancel() }

    fun cancelRecording() {
        val recording = synchronized(lock) { session.also { session = null } } ?: return
        stopSession(recording)
        recording.samples.fill(0)
        update { it.copy(recording = false, transcribing = false, secondsRecorded = 0, message = "Recording discarded") }
    }

    /** null means queued successfully. A diagnostic string means text-only output remains available. */
    fun speak(text: String, languageTag: String): String? {
        if (text.isBlank()) return "There is no answer to repeat yet."
        if (!ttsReady) return report("Android speech engine is unavailable or still starting. Text responses remain available.")
        val engine = tts ?: return report("Android speech engine is unavailable.")
        val voice = chooseVoice(languageTag) ?: return report("No installed offline voice for $languageTag. Install a compatible voice in Android's text-to-speech settings. Text responses remain available.")
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) {
            return report("This answer is too long for the installed speech engine. Read or copy the text instead.")
        }
        return try {
            if (engine.setLanguage(voice.locale) < TextToSpeech.LANG_AVAILABLE) {
                return report("Android reports missing speech data for ${voice.locale.toLanguageTag()}. Install an offline voice in Android settings.")
            }
            if (engine.setVoice(voice) != TextToSpeech.SUCCESS) return report("Android could not select the offline voice ${voice.name}.")
            engine.setSpeechRate(speechRate)
            val utterance = UUID.randomUUID().toString()
            currentUtterance = utterance
            val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, parameters, utterance) == TextToSpeech.SUCCESS) {
                update { it.copy(speaking = true, message = "Speaking with installed offline voice ${voice.name}") }
                null
            } else report("Android could not play this answer. Text responses remain available.")
        } catch (failure: Exception) { report("Offline speech failed: ${failure.message ?: "voice unavailable"}") }
    }

    fun offlineVoiceStatus(languageTag: String): String {
        if (!ttsReady) return "Speech engine unavailable or starting"
        val voice = chooseVoice(languageTag) ?: return "No installed offline voice for $languageTag"
        return "Offline voice: ${voice.name}; pronunciation requires a device listening test"
    }

    fun hasOfflineVoice(languageTag: String): Boolean = ttsReady && chooseVoice(languageTag) != null

    fun installedOfflineVoices(): List<String> = try {
        if (!ttsReady) emptyList() else tts?.voices.orEmpty().filter { installedOffline(it) }
            .map { "${it.locale.toLanguageTag()} · ${it.name}" }.sorted()
    } catch (_: Exception) { emptyList() }

    fun stopSpeaking() {
        currentUtterance = null
        tts?.stop()
        update { it.copy(speaking = false) }
    }

    override fun close() {
        closed = true
        if (mutableState.value.transcribing) NativeExecution.cancel()
        cancelRecording()
        stopSpeaking()
        tts?.shutdown()
        tts = null
        ttsReady = false
        audioDevices.close()
    }

    private fun chooseVoice(languageTag: String): Voice? {
        val locale = Locale.forLanguageTag(languageTag)
        return try {
            tts?.voices.orEmpty().filter { voice ->
                installedOffline(voice) && voice.locale.language == locale.language &&
                    (locale.script.isBlank() || voice.locale.script == locale.script)
            }.maxByOrNull { voice ->
                (if (voice.locale.country == locale.country) 4 else 0) + (if (voice.locale.script == locale.script) 2 else 0) + voice.quality / 100
            }
        } catch (_: Exception) { null }
    }

    private fun installedOffline(voice: Voice): Boolean = !voice.isNetworkConnectionRequired &&
        !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

    private fun readAudio(recording: RecordingSession) {
        val chunk = ShortArray(1600)
        try {
            while (!recording.stopping.get() && recording.count < recording.samples.size) {
                val read = recording.recorder.read(chunk, 0, minOf(chunk.size, recording.samples.size - recording.count), AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    chunk.copyInto(recording.samples, recording.count, 0, read)
                    recording.count += read
                    val seconds = recording.count / SAMPLE_RATE
                    if (mutableState.value.secondsRecorded != seconds) update { it.copy(secondsRecorded = seconds) }
                } else if (!recording.stopping.get()) {
                    recording.failure = "Microphone recording failed (Android code $read)."
                    break
                }
            }
        } catch (failure: Exception) {
            if (!recording.stopping.get()) recording.failure = failure.message ?: "Microphone recording failed"
        } finally {
            chunk.fill(0)
            recording.stopping.set(true)
            try { recording.recorder.stop() } catch (_: Exception) { }
            update { it.copy(recording = false, message = recording.failure ?: "Recording ready · tap microphone to transcribe") }
        }
    }

    private fun stopSession(recording: RecordingSession) {
        recording.stopping.set(true)
        try { recording.recorder.stop() } catch (_: Exception) { }
        try { recording.thread?.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (recording.released.compareAndSet(false, true)) recording.recorder.release()
    }

    private fun report(message: String): String { update { it.copy(message = message, speaking = false) }; return message }
    private fun update(change: (VoiceState) -> VoiceState) { synchronized(mutableState) { mutableState.value = change(mutableState.value) } }

    private class RecordingSession(val recorder: AudioRecord) {
        val samples = ShortArray(SAMPLE_RATE * MAX_RECORDING_SECONDS)
        val stopping = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        @Volatile var count = 0
        @Volatile var failure: String? = null
        var thread: Thread? = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_RECORDING_SECONDS = 60
        const val MAX_SPEECH_MODEL_BYTES = 180L * 1024 * 1024

        /** Lightweight energy gate: removes distant silence, never truncates gaps within speech. */
        internal fun speechSamples(samples: ShortArray, count: Int): FloatArray {
            require(count in 1..samples.size) { "The microphone returned no audio." }
            val frame = SAMPLE_RATE / 10
            var first = -1
            var last = -1
            var offset = 0
            while (offset < count) {
                val end = minOf(offset + frame, count)
                var energy = 0.0
                for (index in offset until end) {
                    val value = samples[index].toDouble() / 32768.0
                    energy += value * value
                }
                if (energy / maxOf(1, end - offset) > 0.000004) {
                    if (first == -1) first = offset
                    last = end
                }
                offset = end
            }
            require(first >= 0) { "No audible speech was detected. Check microphone access and speak closer to the phone." }
            val start = maxOf(0, first - SAMPLE_RATE / 5)
            val end = minOf(count, last + SAMPLE_RATE / 5)
            return FloatArray(end - start) { samples[start + it] / 32768f }
        }
    }
}
