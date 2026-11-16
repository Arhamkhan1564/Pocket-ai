package com.pocketai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketai.AppSettings
import com.pocketai.assistant.LanguageTools
import com.pocketai.engine.NativeBridge
import com.pocketai.hardware.HardwareDiagnostics
import com.pocketai.hardware.HardwareSnapshot
import com.pocketai.voice.AudioDiagnostics
import com.pocketai.voice.MicrophoneMeasurement
import com.pocketai.voice.SpeechController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Optional real hardware checks, embedded in the existing scrollable setup screen. */
@Composable
fun SetupDeviceChecks(settings: AppSettings, speech: SpeechController, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceState by speech.state.collectAsState()
    var snapshot by remember { mutableStateOf<HardwareSnapshot?>(null) }
    var hardwareError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var microphone by remember { mutableStateOf<MicrophoneMeasurement?>(null) }
    var microphoneStatus by remember { mutableStateOf("Not tested. Microphone access is optional for text use.") }
    var toneQueued by remember { mutableStateOf(false) }
    var toneHeard by remember { mutableStateOf(false) }
    var speakerStatus by remember { mutableStateOf("Not tested. Set media volume to a comfortable level.") }

    LaunchedEffect(refresh) {
        try {
            snapshot = withContext(Dispatchers.IO) { HardwareDiagnostics(context).snapshot() }
            hardwareError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { hardwareError = failure.message ?: "Device readings are unavailable." }
    }

    fun testMicrophone() {
        if (busy != null) return
        busy = "microphone"
        microphone = null
        microphoneStatus = "Listening for two seconds. Speak or gently tap near the microphone."
        speech.stopSpeaking()
        scope.launch {
            try {
                val result = AudioDiagnostics.measureMicrophone(context, speech.audioDevices::routeRecorder)
                microphone = result
                microphoneStatus = if (result.soundDetected) {
                    "Audio samples received; sound level detected. This does not verify speech recognition."
                } else {
                    "Audio samples received, but input was silent or very quiet. Check the route, mute controls and microphone."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                microphoneStatus = "Microphone test failed: ${failure.message ?: "capture unavailable"}"
                onMessage(microphoneStatus)
            } finally { busy = null }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) testMicrophone() else {
            microphoneStatus = "Microphone permission was not granted. You can finish setup and use text features."
            onMessage(microphoneStatus)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This device", style = MaterialTheme.typography.titleMedium)
                val current = snapshot
                if (current == null && hardwareError == null) CircularProgressIndicator()
                if (hardwareError != null) Text(hardwareError.orEmpty(), color = MaterialTheme.colorScheme.error)
                current?.let { data ->
                    listOf("Android", "CPU architectures", "Total RAM", "Available RAM", "Internal free", "Microphone feature", "Audio outputs").forEach { key ->
                        Text("$key · ${data.items[key] ?: "Not reported"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { refresh++ }) { Text("Refresh device readings") }
                Text("Text knowledge is ready without model downloads. A small multilingual speech model is included for compatible ARM and x86_64 phones. Translated document answers need a separate language model and enough free memory.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                NativeBridge.availabilityMessage()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Microphone check · optional", style = MaterialTheme.typography.titleMedium)
                Text(microphoneStatus, style = MaterialTheme.typography.bodySmall)
                if (busy == "microphone") LinearProgressIndicator(Modifier.fillMaxWidth())
                microphone?.let { result ->
                    Text("${result.sampleCount} samples · 16 kHz mono · ${String.format(Locale.ROOT, "%.1f", result.rmsDbFs)} dBFS RMS · ${String.format(Locale.ROOT, "%.1f", result.peak * 100)}% peak", style = MaterialTheme.typography.bodySmall)
                    Text("Samples were discarded. Background noise can also produce a level; no recognition-quality claim is made.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = busy == null && !voiceState.recording && !voiceState.transcribing, onClick = {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) testMicrophone()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Test microphone for 2 seconds") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Speaker check · optional", style = MaterialTheme.typography.titleMedium)
                Text(speakerStatus, style = MaterialTheme.typography.bodySmall,
                    color = if (toneHeard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(enabled = busy == null, onClick = {
                    busy = "speaker"
                    toneQueued = false
                    toneHeard = false
                    speakerStatus = "Requesting a short tone on Android's current media output…"
                    speech.stopSpeaking()
                    scope.launch {
                        try {
                            toneQueued = AudioDiagnostics.queueSpeakerTone()
                            speakerStatus = if (toneQueued) "Tone was queued. Confirm below only if you heard it; queuing alone does not pass this test."
                                else "Android did not accept the tone. Check the current audio output and media volume."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            speakerStatus = "Speaker test failed: ${failure.message ?: "audio unavailable"}"
                            onMessage(speakerStatus)
                        } finally { busy = null }
                    }
                }) { Text("Play a short test tone") }
                if (toneQueued && !toneHeard) Column {
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { toneHeard = true; speakerStatus = "Confirmed by you: the test tone was audible on the current output." }) { Text("I heard the tone") }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { toneQueued = false; speakerStatus = "Tone not heard. Check media volume and Android's output route, then retry." }) { Text("I did not hear it") }
                }
                Text("A tone tests the current media route, not an installed language voice or speech pronunciation.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Offline voices · checked separately", style = MaterialTheme.typography.titleMedium)
                if (!voiceState.ttsReady) Text("Android TTS is unavailable or still starting. Text remains available.", style = MaterialTheme.typography.bodySmall)
                LanguageTools.configured(settings.thirdLanguage).forEach { language ->
                    val tag = LanguageTools.speechTag(language.tag, settings.punjabiScript)
                    Text("${language.name}: ${speech.offlineVoiceStatus(tag)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Recognition needs a compatible multilingual Whisper model and generated answers need a compatible GGUF. Voice availability does not establish recognition support. All audio checks are optional; you can finish with text only.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
