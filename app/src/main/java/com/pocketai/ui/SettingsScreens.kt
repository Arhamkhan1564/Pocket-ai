package com.pocketai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketai.AppSettings
import com.pocketai.ExternalActivityFlow
import com.pocketai.assistant.LanguageTools
import com.pocketai.data.PocketDatabase
import com.pocketai.hardware.HardwareDiagnostics
import com.pocketai.hardware.HardwareSnapshot
import com.pocketai.models.InstalledModel
import com.pocketai.models.ModelKind
import com.pocketai.models.ModelManager
import com.pocketai.security.AuthManager
import com.pocketai.voice.SpeechController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Resource tools share the app's outer navigation and two primary operating modes. */
@Composable
fun ResourceScreens(
    page: String,
    settings: AppSettings,
    onSettingsChanged: () -> Unit,
    models: ModelManager,
    auth: AuthManager,
    db: PocketDatabase,
    onMessage: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onTestSpeaker: (String) -> Unit,
    onTestMicrophone: () -> Unit,
    speech: SpeechController? = null,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (page) {
            "languages" -> LanguageResources(settings, onSettingsChanged)
            "audio" -> AudioResources(settings, onSettingsChanged, onTestSpeaker, onTestMicrophone, speech)
            "models" -> ModelResources(models, settings, onSettingsChanged, auth, onMessage)
            "diagnostics" -> DiagnosticResources()
            "backup" -> BackupResources(auth, db, onMessage, onSettingsChanged)
            "security" -> SecurityResources(auth, onMessage)
            else -> {
                ResourceTitle("Make it yours", "SETTINGS", "Everything stays on this device. No account or connection is required.")
                ResourceLink("Languages", "Urdu, Punjabi and your third language", onClick = { onNavigate("languages") })
                ResourceLink("Voice & audio", "Offline voices, speech speed and audio tests", onClick = { onNavigate("audio") })
                ResourceLink("Local models", "Import and select a GGUF or Whisper model", onClick = { onNavigate("models") })
                ResourceLink("Device & storage", "Compatibility, memory, battery and audio devices", onClick = { onNavigate("diagnostics") })
                ResourceLink("Appliance session", "Keep a visible assistant session ready for voice use", onClick = { onNavigate("appliance") })
                ResourceLink("Backup & restore", "Move your knowledge using a JSON backup", onClick = { onNavigate("backup") })
                ResourceLink("Administrator", "PIN, recovery and session protection", onClick = { onNavigate("security") })
                ResourcePanel("Private by design", "This APK does not request internet access. Imported models, conversations and saved knowledge remain local. Android system backups are disabled.")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LanguageResources(settings: AppSettings, changed: () -> Unit) {
    ResourceTitle("Your languages", "LANGUAGE", "Original text is preserved. Search normalization never replaces your saved wording.")
    ResourcePanel("Always available for text", "اردو · Urdu\nپنجابی / ਪੰਜਾਬੀ · Punjabi\nText works without downloading a model or voice.")
    Text("Third language", style = MaterialTheme.typography.titleMedium)
    val languages = LanguageTools.optionalLanguages.map { it.tag to it.nativeName }
    languages.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (code, name) ->
                FilterChip(selected = settings.thirdLanguage == code, onClick = {
                    val wasThird = settings.responseLanguage == settings.thirdLanguage
                    settings.thirdLanguage = code
                    if (wasThird) settings.responseLanguage = code
                    changed()
                }, label = { Text(name) })
            }
        }
    }
    HorizontalDivider()
    Text("Punjabi script", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(settings.punjabiScript == "Arab", { settings.punjabiScript = "Arab"; changed() }, label = { Text("شاہ مکھی") })
        FilterChip(settings.punjabiScript == "Guru", { settings.punjabiScript = "Guru"; changed() }, label = { Text("ਗੁਰਮੁਖੀ") })
    }
    Text("Response language", style = MaterialTheme.typography.titleMedium)
    LanguageChoice(settings.responseLanguage, settings.thirdLanguage) { settings.responseLanguage = it; changed() }
    ResourcePanel("Voice support is checked separately", "A language can work for text even when its speech recognition, translation or offline voice is unavailable. Punjabi and Urdu may require a multilingual Whisper model and an installed compatible system voice. Automatic detection cannot reliably distinguish Urdu and Shahmukhi Punjabi; select Punjabi manually.")
}

@Composable
fun LanguageChoice(selected: String, third: String, onChange: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ur", "pa", third).distinct().forEach { code ->
            FilterChip(selected == code, { onChange(code) }, label = { Text(languageLabel(code)) })
        }
    }
}

fun languageLabel(code: String): String = when (code) {
    "ur" -> "اردو"
    "pa" -> "ਪੰਜਾਬੀ / پنجابی"
    else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.titlecase(Locale.ENGLISH) }
}

@Composable
private fun AudioResources(settings: AppSettings, changed: () -> Unit, testSpeaker: (String) -> Unit, testMicrophone: () -> Unit, speech: SpeechController?) {
    ResourceTitle("A voice of its own", "VOICE & AUDIO", "Start with the phone's microphone and speaker. Connected audio devices are listed in device diagnostics.")
    Card {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speak answers", style = MaterialTheme.typography.titleMedium)
                Text("Use an available offline system voice", style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.speechEnabled, { settings.speechEnabled = it; changed() })
        }
    }
    Text("Speech speed · ${String.format(Locale.ROOT, "%.1f", settings.speechRate)}×", style = MaterialTheme.typography.titleMedium)
    Slider(value = settings.speechRate, onValueChange = { settings.speechRate = it; changed() }, valueRange = 0.5f..1.4f)
    Text("Voice volume · ${(settings.voiceVolume * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
    Slider(value = settings.voiceVolume, onValueChange = { settings.voiceVolume = it; changed() }, valueRange = 0f..1f)
    Text("Test language", style = MaterialTheme.typography.titleMedium)
    LanguageChoice(settings.responseLanguage, settings.thirdLanguage) { settings.responseLanguage = it; changed() }
    if (speech != null) {
        val voiceState by speech.state.collectAsState()
        val devices by speech.audioDevices.devices.collectAsState()
        var inputId by remember { mutableStateOf<Int?>(null) }
        ResourcePanel(if (voiceState.ttsReady) "Offline voice availability" else "Speech engine starting", listOf("ur", "pa", settings.thirdLanguage).distinct().joinToString("\n") { code ->
            val tag = if (code == "pa") "pa-${settings.punjabiScript}" else code
            "${languageLabel(code)}: ${speech.offlineVoiceStatus(tag)}"
        })
        Text("Microphone route", style = MaterialTheme.typography.titleMedium)
        FilterChip(inputId == null, { speech.audioDevices.selectInput(null); inputId = null }, label = { Text("Built-in / Android default") })
        devices.filter { it.input }.forEach { device ->
            FilterChip(inputId == device.id, { speech.audioDevices.selectInput(device.id); inputId = device.id }, label = { Text("${device.label} · ${device.kind}") })
        }
        Text("Audio output follows Android's current media route: ${speech.audioDevices.outputSummary()}", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = { testSpeaker(settings.responseLanguage) }, modifier = Modifier.fillMaxWidth()) { Text("Test offline speaker") }
    OutlinedButton(onClick = testMicrophone, modifier = Modifier.fillMaxWidth()) { Text("Test microphone in conversation") }
    ResourcePanel("Tap to talk", "Recording starts only after you tap the microphone and grant permission. Tap stop to transcribe with an imported Whisper model. Audio is held temporarily in memory and is not saved. Wake-word and unattended background listening are not enabled.")
    ResourcePanel("If you hear no speech", "An offline voice must be installed for the selected language. Pocket AI refuses voices that require a network connection. Text responses remain available. Your Android media volume also affects playback.")
}

@Composable
private fun ModelResources(manager: ModelManager, settings: AppSettings, changed: () -> Unit, auth: AuthManager, message: (String) -> Unit) {
    ResourceTitle("Intelligence, locally", "MODEL LIBRARY", "No large model is bundled. Import files from your phone or USB storage using Android's file picker.")
    var refresh by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf<List<InstalledModel>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf("Preparing import…") }
    var kind by remember { mutableStateOf(ModelKind.LLM) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) {
        runCatching { withContext(Dispatchers.IO) { manager.list() } }
            .onSuccess { installed = it }
            .onFailure { message(it.message ?: "Could not read the model library.") }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        ExternalActivityFlow.active = false
        if (uri != null) scope.launch {
            busy = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    auth.requireAuthorized()
                    manager.importModel(uri, kind) { done, total ->
                        importProgress = if (total != null && total > 0) "Importing ${formatBytes(done)} / ${formatBytes(total)}" else "Importing ${formatBytes(done)}"
                    }
                }
            }
            result.onSuccess { model ->
                if (model.kind == ModelKind.LLM) settings.selectedLlmId = model.id else settings.selectedWhisperId = model.id
                changed()
                message("${model.name} imported. Runtime compatibility is checked when loading.")
                refresh++
            }.onFailure { message(it.message ?: "Model import failed.") }
            busy = false
        }
    }
    ResourcePanel("For a 2 GB phone", "Start with a small quantized GGUF and a multilingual Whisper tiny/base model. Speech and text inference run sequentially. Memory estimates are conservative; actual compatibility and speed require a device benchmark.")
    Text("Context window", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(256, 512, 1024).forEach { tokens ->
            FilterChip(settings.contextSize == tokens, { settings.contextSize = tokens; changed() }, label = { Text("$tokens tokens") })
        }
    }
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(importProgress, style = MaterialTheme.typography.bodySmall)
    }
    AdminGate(auth, message) {
        Button(onClick = {
            kind = ModelKind.LLM
            launchExternal(message) { importer.launch(arrayOf("*/*")) }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Import language model · GGUF") }
        OutlinedButton(onClick = {
            kind = ModelKind.WHISPER
            launchExternal(message) { importer.launch(arrayOf("*/*")) }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Import speech model · Whisper") }
        if (installed.isEmpty()) ResourcePanel("No models installed", "Saved knowledge search already works. A GGUF enables generated answers; a Whisper model enables local speech recognition. Installing a model does not automatically provide translation or a voice.")
        installed.forEach { model ->
            val selected = if (model.kind == ModelKind.LLM) settings.selectedLlmId == model.id else settings.selectedWhisperId == model.id
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Text("${model.kind.name} · ${formatBytes(model.sizeBytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("SHA-256 ${model.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall)
                    Text("Imported ${DateFormat.getDateInstance().format(Date(model.importedAt))}", style = MaterialTheme.typography.bodySmall)
                    var compatibility by remember(model.id) { mutableStateOf("Checking compatibility…") }
                    LaunchedEffect(model.id) { compatibility = runCatching { withContext(Dispatchers.IO) { manager.compatibility(model).let { result ->
                        (if (result.canAttempt) "Runtime load can be attempted.\n" else "This device is not compatible.\n") + result.messages.joinToString("\n")
                    } } }.getOrElse { it.message ?: "Compatibility could not be checked." } }
                    Text(compatibility, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (model.kind == ModelKind.LLM) settings.selectedLlmId = if (selected) null else model.id
                            else settings.selectedWhisperId = if (selected) null else model.id
                            changed()
                        }, enabled = !busy) { Text(if (selected) "Selected · disable" else "Use model") }
                        TextButton(onClick = {
                            scope.launch {
                                busy = true
                                runCatching { withContext(Dispatchers.IO) { auth.requireAuthorized(); manager.remove(model.id) } }
                                    .onSuccess {
                                        if (settings.selectedLlmId == model.id) settings.selectedLlmId = null
                                        if (settings.selectedWhisperId == model.id) settings.selectedWhisperId = null
                                        changed(); refresh++; message("Model removed.")
                                    }.onFailure { message(it.message ?: "Could not remove model.") }
                                busy = false
                            }
                        }, enabled = !busy) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticResources() {
    ResourceTitle("Know your device", "DIAGNOSTICS", "These readings describe this phone. A 2 GB device should be benchmarked before choosing a model.")
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<HardwareSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        runCatching { withContext(Dispatchers.IO) { HardwareDiagnostics(context).snapshot() } }
            .onSuccess { snapshot = it; error = null }.onFailure { error = it.message }
    }
    OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text("Refresh device readings") }
    if (snapshot == null && error == null) CircularProgressIndicator()
    if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
    snapshot?.let { data ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                data.items.forEach { (label, value) ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
    ResourcePanel("What these checks mean", "Microphone hardware availability does not confirm permission or recognition quality. Voice testing requires a microphone permission and speech model. External storage visibility depends on Android and the attached USB device. Import models and backups through the system picker.")
}

@Composable
private fun BackupResources(auth: AuthManager, db: PocketDatabase, message: (String) -> Unit, onRestored: () -> Unit) {
    ResourceTitle("Knowledge, kept safe", "BACKUP & RESTORE", "Export verified knowledge, categories and recent conversations to a file you control.")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        ExternalActivityFlow.active = false
        if (uri != null) scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) {
                val json = db.exportBackup()
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open backup destination.")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            } }.onSuccess { message("Backup saved successfully.") }.onFailure { message(it.message ?: "Backup failed.") }
            busy = false
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        ExternalActivityFlow.active = false
        if (uri != null) scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) {
                auth.requireAuthorized()
                val stream = context.contentResolver.openInputStream(uri) ?: error("Could not open backup.")
                stream.use { input ->
                    val bytes = input.readBytesBounded(16 * 1024 * 1024)
                    bytes.toString(Charsets.UTF_8)
                }
            } }.onSuccess { pendingRestore = it }.onFailure { message(it.message ?: "Could not read backup.") }
            busy = false
        }
    }
    ResourcePanel("Keep backups private", "Backup files contain personal knowledge and conversation text. Store them in a trusted location. PIN credentials, settings, model files, correction history and training history are not included. Models can be re-imported separately. Portable backups have a 16 MB safety limit.")
    AdminGate(auth, message) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = {
            launchExternal(message) { exporter.launch("PocketAI-backup-${java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())}.json") }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export knowledge backup") }
        OutlinedButton(onClick = {
            launchExternal(message) { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
    }
    if (pendingRestore != null) AlertDialog(
        onDismissRequest = { if (!busy) pendingRestore = null },
        title = { Text("Restore knowledge?") },
        text = { Text("The backup will be validated before the database is changed. Restore replaces the knowledge and conversation data in this installation. Export a current backup first if you want to keep it.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val json = pendingRestore ?: return@TextButton
            scope.launch {
                busy = true
                runCatching { withContext(Dispatchers.IO) { db.restoreBackup(json) } }
                    .onSuccess { message("Restored $it knowledge records."); pendingRestore = null; onRestored() }
                    .onFailure { message(it.message ?: "Restore failed. Existing data was not changed.") }
                busy = false
            }
        }) { Text("Restore") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { pendingRestore = null }) { Text("Cancel") } },
    )
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer)
        if (size < 0) break
        require(output.size() + size <= limit) { "Backup is larger than the 16 MB import limit." }
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}

/** Preserve the current short administrator session while Android's own file picker is visible. */
private inline fun launchExternal(message: (String) -> Unit, launch: () -> Unit) {
    ExternalActivityFlow.active = true
    try {
        launch()
    } catch (failure: Exception) {
        ExternalActivityFlow.active = false
        message(failure.message ?: "Android could not open the file picker.")
    }
}

@Composable
private fun SecurityResources(auth: AuthManager, message: (String) -> Unit) {
    ResourceTitle("An owner's space", "ADMINISTRATOR", "Only an authorized administrator can change verified knowledge or restore a backup.")
    var refresh by remember { mutableIntStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("PIN protection · ${if (auth.isUnlocked && refresh >= 0) "unlocked" else "locked"}", color = MaterialTheme.colorScheme.primary)
    AdminGate(auth, message) {
        ResourcePanel("Sessions expire automatically", "Administrator access lasts for ten minutes of inactivity. The app returns to User Mode and locks administration when it leaves the foreground.")
        PinField(pin, { pin = it }, "New PIN · 6–12 digits")
        PinField(confirm, { confirm = it }, "Confirm new PIN")
        Button(onClick = {
            if (pin != confirm) { message("The PINs do not match."); return@Button }
            scope.launch {
                busy = true
                runCatching { withContext(Dispatchers.IO) { auth.changePin(pin) } }
                    .onSuccess { recovery = it; pin = ""; confirm = ""; refresh++ }
                    .onFailure { message(it.message ?: "Could not change PIN.") }
                busy = false
            }
        }, enabled = !busy && pin.length in 6..12, modifier = Modifier.fillMaxWidth()) { Text("Change administrator PIN") }
        OutlinedButton(onClick = { auth.lock(); refresh++; message("Administrator access locked.") }, modifier = Modifier.fillMaxWidth()) { Text("Lock administrator access") }
    }
    RecoveryCodeDialog(recovery) { recovery = null }
}

@Composable
fun AdminGate(auth: AuthManager, message: (String) -> Unit, content: @Composable () -> Unit) {
    var unlocked by remember { mutableStateOf(auth.isUnlocked) }
    var pin by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var recovering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var freshRecovery by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // The mutator itself rechecks session authorization, including expiry while a screen stays open.
    if (unlocked && auth.isUnlocked) content() else {
        ResourcePanel("Administrator access", "Unlock with your administrator PIN to manage these resources.")
        if (recovering) {
            OutlinedTextField(recoveryCode, { recoveryCode = it }, label = { Text("Recovery code") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
            PinField(newPin, { newPin = it }, "New PIN · 6–12 digits")
            Button(onClick = {
                scope.launch {
                    busy = true
                    runCatching { withContext(Dispatchers.IO) { auth.recover(recoveryCode, newPin) } }
                        .onSuccess { freshRecovery = it; unlocked = true; recoveryCode = ""; newPin = ""; recovering = false }
                        .onFailure { message(it.message ?: "Recovery failed.") }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Recover administrator access") }
            TextButton(onClick = { recovering = false }) { Text("Use PIN instead") }
        } else {
            PinField(pin, { pin = it }, "Administrator PIN")
            Button(onClick = {
                scope.launch {
                    busy = true
                    runCatching { withContext(Dispatchers.IO) { auth.unlock(pin) } }
                        .onSuccess { success ->
                            if (success) { unlocked = true; pin = "" } else message("Incorrect PIN. Try again.")
                        }.onFailure { message(it.message ?: "Could not unlock.") }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Checking…" else "Unlock") }
            TextButton(onClick = { recovering = true }) { Text("Use recovery code") }
        }
    }
    RecoveryCodeDialog(freshRecovery) { freshRecovery = null }
}

@Composable
fun RecoveryCodeDialog(code: String?, onDismiss: () -> Unit) {
    if (code != null) AlertDialog(
        onDismissRequest = {},
        title = { Text("Keep your recovery code") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Write this down and keep it somewhere private. It can reset a forgotten administrator PIN. Previous recovery codes no longer work.")
            SelectionContainer { Text(code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("I have saved it") } },
    )
}

@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = { raw -> onChange(raw.mapNotNull { char ->
            Character.digit(char, 10).takeIf { it >= 0 }?.let { ('0'.code + it).toChar() }
        }.take(12).joinToString("")) }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

@Composable
private fun ResourceTitle(title: String, eyebrow: String, subtitle: String) {
    Text(eyebrow, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ResourceLink(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun ResourcePanel(title: String, detail: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}
