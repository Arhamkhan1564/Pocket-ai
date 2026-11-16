@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pocketai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketai.AppSettings
import com.pocketai.ExternalActivityFlow
import com.pocketai.assistant.AssistantService
import com.pocketai.assistant.LanguageTools
import com.pocketai.appliance.ApplianceSession
import com.pocketai.data.*
import com.pocketai.engine.LocalAiEngine
import com.pocketai.engine.NativeBridge
import com.pocketai.models.BundledSpeechModel
import com.pocketai.models.BundledSpeechStatus
import com.pocketai.models.InstalledModel
import com.pocketai.models.ModelKind
import com.pocketai.models.ModelManager
import com.pocketai.models.ModelValidator
import com.pocketai.rag.OfflineRagServices
import com.pocketai.rag.OfflineTranslation
import com.pocketai.security.AuthManager
import com.pocketai.voice.SpeechController
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@Composable
fun PocketApp() = PocketTheme {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val auth = remember { AuthManager(context) }
    val db = remember { PocketDatabase(context, auth) }
    val models = remember { ModelManager(context) }
    val offlineRag = remember { OfflineRagServices(context, db, settings, models) }
    val engine = remember { LocalAiEngine(context) }
    val speech = remember { SpeechController(context) }
    val assistant = remember { AssistantService(db, models, engine, settings, offlineRag = offlineRag) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by remember { mutableStateOf("chat") }
    var training by remember { mutableStateOf(false) }
    var setupComplete by remember { mutableStateOf(settings.setupComplete) }
    var revision by remember { mutableIntStateOf(0) }
    var unlocked by remember { mutableStateOf(auth.isUnlocked) }
    var authTarget by remember { mutableStateOf<String?>(null) }
    var composer by remember { mutableStateOf("") }
    var teachingVoice by remember { mutableStateOf("") }
    var recordingLanguage by remember { mutableStateOf(settings.responseLanguage) }
    var editRecord by remember { mutableStateOf<KnowledgeRecord?>(null) }
    var conversations by remember { mutableStateOf(emptyList<ConversationRecord>()) }
    var knowledgeCount by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var answerJob by remember { mutableStateOf<Job?>(null) }
    var lastEngine by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var speechSetupIssue by remember { mutableStateOf<String?>(null) }
    var readySpeechModel by remember { mutableStateOf<InstalledModel?>(null) }
    var sourceToRead by remember { mutableStateOf<Long?>(null) }
    var documentToRead by remember { mutableStateOf<DocumentSummary?>(null) }
    var librarySection by remember { mutableStateOf("documents") }
    val voice by speech.state.collectAsState()
    val offlineState by offlineRag.state.collectAsState()
    val semanticIndex by offlineRag.indexState.collectAsState()
    val bundledSpeech by BundledSpeechModel.state.collectAsState()
    val appliance by ApplianceSession.state.collectAsState()
    val message: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text.take(600)) }; Unit }
    val localModelStatus = when {
        offlineState.preparing -> offlineState.message
        semanticIndex.paused -> "Reference indexing is paused. Originals remain readable; resume indexing in Offline tools."
        semanticIndex.running -> "Indexing references · ${semanticIndex.indexedRecords}/${semanticIndex.totalRecords}. Cross-language search may still be incomplete."
        semanticIndex.error != null -> "Reference indexing could not finish. Originals remain readable; retry in Offline tools."
        offlineState.searchReady && (semanticIndex.indexedRecords < semanticIndex.totalRecords || semanticIndex.indexedRecords < knowledgeCount) -> "Reference indexing is still needed. Check Offline tools; originals remain readable."
        offlineState.searchReady && offlineState.translationReady -> "Offline search and reference translation ready. Open a source to check its original."
        offlineState.searchReady -> "Offline search ready. Translation setup: ${offlineState.error ?: offlineState.message}"
        else -> "Original references remain readable. ${offlineState.error ?: offlineState.message}"
    }
    fun speak(text: String, language: String = settings.responseLanguage, script: String? = null) {
        if (appliance.running) { message("Stop the appliance session before using manual speaker controls."); return }
        speech.speechRate = settings.speechRate
        speech.volume = settings.voiceVolume
        val actualScript = script ?: LanguageTools.detectScript(text, language, settings.punjabiScript).second
        speech.speak(text, LanguageTools.speechTag(language, actualScript))?.let(message)
    }
    fun navigate(target: String) { page = target }
    fun guardedNavigate(target: String) {
        if (auth.isUnlocked) { unlocked = true; page = target; if (target == "teach") training = true }
        else authTarget = target
    }
    fun beginRecording() {
        if (busy) return
        if (appliance.running) { message("Stop the appliance session before using tap-to-talk."); return }
        if (readySpeechModel == null) {
            speechSetupIssue = "Check your speech model before using the microphone. Open model setup, or tap the microphone again after setup is ready. No audio has been recorded."
            return
        }
        runCatching { speech.startRecording() }.onFailure { message(it.message ?: "Microphone unavailable") }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording() else message("Microphone access was not granted. Text input is available.")
    }
    fun toggleMicrophone(language: String = settings.responseLanguage) {
        if (busy) return
        if (speech.hasPendingRecording) {
            busy = true; busyLabel = "Recognizing speech locally…"
            answerJob = scope.launch {
                try {
                    val model = readySpeechModel ?: error("The speech model is no longer selected. Check model setup and try again.")
                    val recognized = speech.finishTranscription(models.fileFor(model), recordingLanguage)
                    if (training && page == "teach") teachingVoice = recognized else { page = "chat"; composer = recognized }
                    message("Speech recognized. Review the text before ${if (training) "saving" else "sending"}.")
                } catch (e: CancellationException) { speech.cancelRecording(); throw e }
                catch (e: Exception) { speech.cancelRecording(); message(e.message ?: "Transcription failed") }
                finally { busy = false; answerJob = null }
            }
        } else {
            if (busy || appliance.running) {
                if (appliance.running) message("Stop the appliance session before using tap-to-talk.")
                return
            }
            busy = true
            busyLabel = "Checking offline speech setup…"
            answerJob = scope.launch {
                try {
                    val model = withContext(Dispatchers.IO) {
                        NativeBridge.availabilityMessage()?.let { error(it) }
                        val selected = models.list().firstOrNull { it.id == settings.selectedWhisperId && it.kind == ModelKind.WHISPER }
                            ?: error(if (bundledSpeech.status == BundledSpeechStatus.INSTALLING) {
                                "The included offline speech model is being prepared. Wait for setup to finish, then try again. You can keep using text."
                            } else {
                                "Included offline speech is not ready. Open Offline tools to prepare or restore it from this APK, then try the microphone. Text input works now."
                            })
                        val file = models.fileFor(selected)
                        require(file.isFile && file.canRead() && file.length() in 64..(180L * 1024 * 1024)) {
                            "The selected speech resource is missing, unreadable or too large. Prepare the included speech again in Offline tools. Optional custom speech files must fit the 180 MiB limit."
                        }
                        val header = ModelValidator.validateHeader(file, ModelKind.WHISPER)
                        require(header.metadata["multilingual"] == "true" || language.substringBefore('-') == "en") {
                            "The selected speech model is English-only. In model setup, select the included multilingual Whisper model for ${LanguageTools.name(language)}. English-only .en models cannot recognize Urdu or Punjabi."
                        }
                        selected
                    }
                    readySpeechModel = model
                    recordingLanguage = language
                    busy = false
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    readySpeechModel = null
                    speechSetupIssue = (failure.message ?: "Offline speech is not ready. Open model setup to check the selected model.") + "\n\nNo audio has been recorded."
                } finally { busy = false; answerJob = null }
            }
        }
    }
    fun submit() {
        val question = composer.trim()
        if (question.isBlank() || busy) return
        if (appliance.running) { message("Stop the appliance session before starting a text conversation."); return }
        if (question.length > 4000) { message("Keep a question below 4,000 characters."); return }
        if (SearchText.normalize(question) in setOf("stop speaking", "stop", "خاموش", "بولنا بند کریں", "بولنا بند کرو", "ਬੋਲਣਾ ਬੰਦ ਕਰੋ")) {
            speech.stopSpeaking(); composer = ""; return
        }
        speech.stopSpeaking()
        composer = ""; busy = true; busyLabel = "Searching local knowledge…"
        answerJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { db.addConversation("user", question, settings.responseLanguage) }
                conversations = withContext(Dispatchers.IO) { db.conversations(80) }
                val answer = withContext(Dispatchers.IO) { assistant.answer(question) }
                withContext(Dispatchers.IO) { db.addConversation("assistant", answer.text, settings.responseLanguage, answer.storedSources, sourceIds = answer.sourceIds) }
                conversations = withContext(Dispatchers.IO) { db.conversations(80) }
                lastEngine = answer.engine
                if (settings.speechEnabled) speak(answer.text)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(e.message ?: "The request failed. Your saved knowledge is unchanged.") }
            finally { busy = false; answerJob = null; revision++ }
        }
    }
    LaunchedEffect(Unit) {
        try {
            offlineRag.prepare()
            offlineRag.startIndexing()
            revision++
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message(failure.message ?: "Offline setup could not finish. Open Offline tools to retry; originals remain readable.") }
    }
    LaunchedEffect(revision, offlineState.searchReady) { if (offlineState.searchReady) offlineRag.startIndexing() }
    LaunchedEffect(revision, setupComplete) {
        try {
            val refreshed = withContext(Dispatchers.IO) {
                val history = db.conversations(80)
                val count = db.countKnowledge()
                history to count
            }
            conversations = refreshed.first
            knowledgeCount = refreshed.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message(failure.message ?: "Local data could not be refreshed.") }
        unlocked = auth.isUnlocked
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !ExternalActivityFlow.active) {
                auth.lock(); unlocked = false; training = false
                authTarget = null
                if (page in setOf("teach", "security", "backup", "history")) page = "chat"
                speech.cancelRecording(); speech.stopSpeaking()
                if (answerJob?.isActive == true) { engine.cancel(); answerJob?.cancel() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); speech.close(); if (answerJob?.isActive == true) engine.cancel(); offlineRag.close(); db.close() }
    }
    BackHandler(page != "chat") { if (page == "teach") { training = false; auth.lock(); unlocked = false }; page = "chat" }

    if (!setupComplete) {
        SetupScreen(settings, auth, speech, offlineRag, onComplete = { settings.setupComplete = true; setupComplete = true; auth.lock(); revision++ }, onMessage = message)
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PocketMark(40)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("pocket ai", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp)
                                Spacer(Modifier.width(9.dp))
                                Box(Modifier.size(5.dp).background(PocketAccent, CircleShape))
                                Text(" OFFLINE", fontSize = 8.sp, color = PocketAccent)
                            }
                            Text("YOUR KNOWLEDGE. YOUR DEVICE.", fontSize = 8.sp, color = PocketMuted, letterSpacing = .8.sp, maxLines = 1)
                        }
                        IconButton(onClick = { page = "settings" }) { Icon(Icons.Outlined.Settings, "Settings") }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !training, onClick = { training = false; auth.lock(); unlocked = false; page = "chat" }, label = { Text("User Mode") }, leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(16.dp)) })
                        FilterChip(selected = training, onClick = { guardedNavigate("teach") }, label = { Text("Training Mode") }, leadingIcon = { Icon(if (unlocked) Icons.Outlined.School else Icons.Outlined.Lock, null, Modifier.size(16.dp)) })
                    }
                    if (appliance.running) TextButton(onClick = { page = "appliance" }, modifier = Modifier.padding(horizontal = 12.dp)) { Icon(Icons.Outlined.Mic, null, Modifier.size(16.dp)); Text("  Appliance session active · view controls") }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    NavigationBarItem(selected = page == "chat" || page == "teach", onClick = { page = if (training && auth.isUnlocked) "teach" else "chat" }, icon = { Icon(if (training) Icons.Outlined.School else Icons.Outlined.GraphicEq, null) }, label = { Text(if (training) "Teach" else "Assistant") })
                    NavigationBarItem(selected = page == "memory", onClick = { page = "memory" }, icon = { Icon(Icons.Outlined.AutoStories, null) }, label = { Text("Library") })
                    NavigationBarItem(selected = page in setOf("settings", "languages", "audio", "models", "diagnostics", "backup", "security", "history", "appliance"), onClick = { page = "settings" }, icon = { Icon(Icons.Outlined.Tune, null) }, label = { Text("Resources") })
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when (page) {
                    "chat" -> ChatScreen(
                        conversations, knowledgeCount, composer, { composer = it }, ::submit, { toggleMicrophone() },
                        voice.recording || speech.hasPendingRecording, voice.secondsRecorded, busy, busyLabel, lastEngine,
                        onStop = { engine.cancel(); answerJob?.cancel(); speech.cancelRecording(); speech.stopSpeaking(); busy = false },
                        onRepeat = { conversations.lastOrNull { it.role == "assistant" }?.let { speak(it.content, it.language) } ?: message("Ask a question first.") },
                        onClear = { confirmClear = true }, onTeach = { guardedNavigate("teach") },
                        onModels = { page = "models" }, settings = settings, onLanguage = { settings.responseLanguage = it; revision++ },
                        voiceStatus = if (voice.recording || speech.hasPendingRecording || voice.transcribing || voice.speaking) voice.message else "",
                        localModelStatus = localModelStatus, db = db, sourceRevision = revision,
                        onReadSource = { sourceToRead = it }
                    )
                    "teach" -> if (training && auth.isUnlocked) TrainingScreen(
                        db, settings, editRecord, teachingVoice,
                        onVoiceConsumed = { teachingVoice = "" }, onMicrophone = ::toggleMicrophone, recording = voice.recording || speech.hasPendingRecording,
                        processing = busy, processingLabel = busyLabel,
                        onMessage = message, onSaved = { document -> editRecord = null; librarySection = if (document) "documents" else "knowledge"; revision++; page = "memory"; if (settings.speechEnabled) speak("Information saved successfully.", "en") },
                        onHistory = { page = "history" }, onCancelEdit = { editRecord = null; revision++ },
                        localModelStatus = localModelStatus, onModels = { page = "models" }
                    ) else Text("Unlock Training Mode to manage knowledge.", Modifier.padding(24.dp))
                    "memory" -> LibraryScreen(db, revision, auth.isUnlocked, section = librarySection, onSectionChange = { librarySection = it },
                        onEdit = { item -> editRecord = item; guardedNavigate("teach") },
                        onDelete = { item ->
                            scope.launch {
                                try { withContext(Dispatchers.IO) { db.deleteKnowledge(item.id) }; revision++; message("Knowledge deleted.") }
                                catch (e: Exception) { message(e.message ?: "Unlock Training Mode first.") }
                            }
                        }, onTeach = { guardedNavigate("teach") }, onSpeak = { speak(it.content, it.language, it.script) }, onMessage = message,
                        onReadDocument = { documentToRead = it }, onReadSource = { sourceToRead = it },
                        onUnlockEditing = { guardedNavigate("memory") }, onChanged = { revision++ }
                    )
                    "history" -> HistoryScreen(db, revision)
                    "appliance" -> ApplianceScreen(onMessage = message, onModels = { page = "models" }, onAudio = { page = "audio" }, onStart = {
                        if (answerJob?.isActive == true) { engine.cancel(); answerJob?.cancel(); answerJob = null; busy = false }
                        speech.cancelRecording(); speech.stopSpeaking(); training = false; auth.lock(); unlocked = false
                        runCatching { ApplianceSession.start(context) }.onFailure { message(it.message ?: "The session could not start.") }
                    })
                    else -> ResourceScreens(
                        page = page, settings = settings, onSettingsChanged = { revision++ }, models = models, auth = auth, db = db, offlineRag = offlineRag,
                        onMessage = message, onNavigate = { target ->
                            if (target in setOf("backup", "security") && !auth.isUnlocked) guardedNavigate(target) else navigate(target)
                        }, onTestSpeaker = { language -> speak(when(language) { "ur" -> "پاکٹ اے آئی کی آواز کا امتحان۔"; "pa" -> if (settings.punjabiScript == "Guru") "ਪਾਕੇਟ ਏ ਆਈ ਦੀ ਆਵਾਜ਼ ਦਾ ਟੈਸਟ।" else "پاکٹ اے آئی دی آواز دا امتحان۔"; else -> "Pocket AI speaker test. Your audio stays on this device." }, language) },
                        onTestMicrophone = { page = "chat"; toggleMicrophone() }, speech = speech
                    )
                }
            }
        }
    }
    authTarget?.let { target ->
        UnlockDialog(auth, onDismiss = { authTarget = null }, onUnlocked = {
            authTarget = null; unlocked = true; page = target; if (target in setOf("teach", "memory")) training = true
        })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear conversation?") },
        text = { Text("This deletes the conversation history. Saved knowledge stays in your library.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { withContext(Dispatchers.IO) { db.clearConversations() }; revision++ } }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    speechSetupIssue?.let { issue ->
        AlertDialog(
            onDismissRequest = { speechSetupIssue = null },
            title = { Text("Set up offline voice") },
            text = { Text(issue) },
            confirmButton = { TextButton(onClick = { speechSetupIssue = null; page = "models" }) { Text("Open model setup") } },
            dismissButton = { TextButton(onClick = { speechSetupIssue = null }) { Text("Use text for now") } },
        )
    }
    sourceToRead?.let { sourceId ->
        SourcePassageViewer(db, sourceId, revision, settings, translationReady = offlineState.translationReady,
            translateSource = offlineRag::translateSource, onOpenTools = { sourceToRead = null; page = "models" }, onDismiss = { sourceToRead = null }, onReadDocument = { document ->
            sourceToRead = null; documentToRead = document
        })
    }
    documentToRead?.let { document -> DocumentReader(db, document, revision, onDismiss = { documentToRead = null }) }
}

@Composable
fun PocketMark(size: Int = 64) {
    Box(Modifier.size(size.dp).background(PocketAccent, RoundedCornerShape((size * .28f).dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.GraphicEq, null, Modifier.size((size * .58f).dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun PageHeading(eyebrow: String, title: String, subtitle: String? = null) {
    Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = PocketAccent, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(7.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    if (subtitle != null) { Spacer(Modifier.height(7.dp)); Text(subtitle, color = PocketMuted, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ChatScreen(
    conversations: List<ConversationRecord>, knowledgeCount: Int, composer: String, onComposer: (String) -> Unit,
    onSubmit: () -> Unit, onMicrophone: () -> Unit, recording: Boolean, seconds: Int, busy: Boolean, busyLabel: String,
    engineLabel: String, onStop: () -> Unit, onRepeat: () -> Unit, onClear: () -> Unit, onTeach: () -> Unit,
    onModels: () -> Unit, settings: AppSettings, onLanguage: (String) -> Unit, voiceStatus: String,
    localModelStatus: String, db: PocketDatabase, sourceRevision: Int, onReadSource: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(conversations.size, busy) { if (conversations.isNotEmpty()) listState.animateScrollToItem(conversations.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            LanguageTools.configured(settings.thirdLanguage).forEach { option ->
                FilterChip(selected = settings.responseLanguage == option.tag, onClick = { onLanguage(option.tag) }, enabled = !recording && !busy, label = { Text(if (option.tag == "pa") { if (settings.punjabiScript == "Guru") "ਪੰਜਾਬੀ" else "پنجابی" } else option.nativeName, maxLines = 1) }, modifier = Modifier.padding(end = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            if (conversations.isNotEmpty()) IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.DeleteSweep, "Clear conversation", Modifier.size(20.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ANSWERS · ${LanguageTools.name(settings.responseLanguage).uppercase()}", color = PocketAccent, style = MaterialTheme.typography.labelSmall)
                Text(localModelStatus, color = PocketMuted, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            TextButton(onClick = onModels, modifier = Modifier.padding(start = 6.dp)) { Text("Offline tools") }
        }
        if (conversations.isEmpty()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxHeight < 280.dp) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PocketMark(42)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text("Your references, within reach.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("$knowledgeCount saved passages · all on your device", color = PocketMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TextButton(onClick = onTeach, modifier = Modifier.padding(top = 4.dp)) { Icon(Icons.Outlined.UploadFile, null, Modifier.size(16.dp)); Text(" Import a reference") }
                }
            } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(22.dp))
                PocketMark(78)
                Spacer(Modifier.height(24.dp))
                Text("Your references.\nAnswers you can check.", textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text("Import an English reference, ask a question,\nthen open the original source. Everything stays on this phone.", textAlign = TextAlign.Center, color = PocketMuted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(28.dp))
                Card(onClick = onTeach, colors = CardDefaults.cardColors(containerColor = PocketSurface), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoStories, null, tint = PocketAccent)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text("Import a reference", fontWeight = FontWeight.Medium); Text("TXT, Word or text PDF · $knowledgeCount saved passages", style = MaterialTheme.typography.bodySmall, color = PocketMuted) }
                        Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                SuggestionChip(onClick = onModels, label = { Text("Check offline tools") }, icon = { Icon(Icons.Outlined.Memory, null, Modifier.size(18.dp)) })
                Spacer(Modifier.height(8.dp))
                Text("Original references remain readable offline.\nLanguage tools and spoken voices are checked separately.\nOpen any answer's source to check names, numbers and context.", color = PocketMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
            }
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(conversations, key = { it.id }) { item ->
                val isUser = item.role == "user"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    Text(if (isUser) "YOU" else "POCKET AI", color = if (isUser) PocketMuted else PocketAccent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
                    Surface(color = if (isUser) MaterialTheme.colorScheme.surfaceVariant else PocketSurface, shape = RoundedCornerShape(18.dp)) {
                        SelectionContainer { Text(item.content, Modifier.padding(16.dp), textAlign = if (LanguageTools.isRtl(item.content)) TextAlign.Right else TextAlign.Start, lineHeight = 25.sp) }
                    }
                    if (item.sources.isNotBlank()) {
                        val details = if (item.sources.startsWith("@engine=")) {
                            val kind = item.sources.substringAfter("@engine=").substringBefore('\n')
                            val references = item.sources.substringAfter('\n', "")
                            if (references.isBlank()) kind else "Saved sources  $references\n$kind"
                        } else "Saved sources  ${item.sources}"
                        Text(details, style = MaterialTheme.typography.labelSmall, color = PocketAccent, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (!isUser && item.sourceIds.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            item.sourceIds.distinct().take(20).forEachIndexed { index, id ->
                                SourceCitationChip(db, id, index + 1, sourceRevision) { onReadSource(id) }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (busy) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp); Text(busyLabel, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelMedium) }
            if (voiceStatus.isNotBlank()) Text(voiceStatus, color = PocketAccent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
            if (!busy && engineLabel.isNotBlank()) Text(engineLabel, color = PocketMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = onMicrophone, enabled = !busy, modifier = Modifier.size(50.dp)) { Icon(if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic, if (recording) "Finish recording" else "Record voice") }
                OutlinedTextField(value = composer, onValueChange = onComposer, modifier = Modifier.weight(1f), placeholder = { Text(if (recording) "Listening… ${seconds}s" else "Ask Pocket AI…") }, shape = RoundedCornerShape(24.dp), maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                    trailingIcon = { IconButton(onClick = onSubmit, enabled = composer.isNotBlank() && !busy && !recording) { Icon(Icons.Outlined.ArrowUpward, "Send message", tint = if (composer.isNotBlank()) PocketAccent else PocketMuted) } })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRepeat, enabled = conversations.any { it.role == "assistant" }) { Icon(Icons.Outlined.VolumeUp, null, Modifier.size(15.dp)); Text(" Repeat", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onStop) { Icon(Icons.Outlined.StopCircle, null, Modifier.size(15.dp)); Text(" Stop", style = MaterialTheme.typography.labelSmall) }
                Text("Private by design", color = PocketMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun SetupScreen(settings: AppSettings, auth: AuthManager, speech: SpeechController, offlineRag: OfflineRagServices, onComplete: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val tools by offlineRag.state.collectAsState()
    val existingAdministrator = remember { auth.isConfigured }
    var step by remember { mutableIntStateOf(if (existingAdministrator) 2 else 0) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var third by remember { mutableStateOf(settings.thirdLanguage) }
    var script by remember { mutableStateOf(settings.punjabiScript) }
    var error by remember { mutableStateOf("") }
    var showRecovery by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { PocketMark(46); Text("  pocket ai", fontWeight = FontWeight.Bold, fontSize = 24.sp) }
            Spacer(Modifier.height(42.dp))
            PageHeading("WELCOME · ${step + 1} / 4", when(step) { 0 -> "An assistant that\nstays with you."; 1 -> "Make it your own."; 2 -> "Protect what you teach."; else -> "Prepare this phone." }, when(step) { 0 -> "Your personal, offline knowledge companion. Built for the phone you already have."; 1 -> "English, Urdu and Punjabi are always available for text. Choose an extra language and Punjabi script."; 2 -> "Only an authorized owner can change permanent knowledge. Your PIN and recovery code stay on this device."; else -> "Included tools prepare from this APK. Check language readiness and optionally test the microphone and speaker. Originals remain readable while setup is incomplete." })
            Spacer(Modifier.height(30.dp))
            if (step == 0) {
                FeatureRow(Icons.Outlined.WifiOff, "Offline from the start", "No account. No cloud connection.")
                FeatureRow(Icons.Outlined.School, "Teach it what matters", "Keep facts, answers, notes and preferences.")
                FeatureRow(Icons.Outlined.Translate, "Your words, your language", "Urdu, Punjabi and a language you choose.")
                Spacer(Modifier.height(18.dp))
                Text("Text references work immediately. Included tools prepare locally and check device compatibility. Android 6 or later and sufficient free memory/storage are required; availability and speed vary by phone. Originals remain readable while language or speech setup is unavailable.", color = PocketMuted, style = MaterialTheme.typography.bodySmall)
            } else if (step == 1) {
                Text("PUNJABI SCRIPT", color = PocketAccent, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(script == "Arab", { script = "Arab" }, { Text("شاہ مکھی") })
                    FilterChip(script == "Guru", { script = "Guru" }, { Text("ਗੁਰਮੁਖੀ") })
                }
                Spacer(Modifier.height(20.dp))
                Text("EXTRA LANGUAGE", color = PocketAccent, style = MaterialTheme.typography.labelSmall)
                LanguageTools.optionalLanguages.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { option -> FilterChip(third == option.tag, { third = option.tag }, { Text(option.name) }) } } }
                Text("Text support does not guarantee translation or offline ASR/TTS for a language. Check each in Resources.", style = MaterialTheme.typography.bodySmall, color = PocketMuted)
            } else if (step == 3) {
                OfflinePreparationPanel(offlineRag, onMessage, showIndex = false)
                Spacer(Modifier.height(24.dp))
                SetupDeviceChecks(settings, speech, onMessage)
            } else if (recovery.isBlank()) {
                OutlinedTextField(pin, { pin = pinDigits(it) }, label = { Text("Administrator PIN · 6–12 digits") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                if (!existingAdministrator) OutlinedTextField(confirmation, { confirmation = pinDigits(it) }, label = { Text("Confirm PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                else Text("Your administrator PIN was created earlier. Enter it to finish setup.", color = PocketMuted)
            } else {
                Text("SAVE YOUR RECOVERY CODE", color = PocketAccent, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { SelectionContainer { Text(recovery, Modifier.padding(20.dp), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp) } }
                Spacer(Modifier.height(14.dp))
                Text("Store this code somewhere safe outside this phone. It can reset your PIN. It will not be shown again.", color = PocketMuted)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(acknowledged, { acknowledged = it }); Text("I saved my recovery code.") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(30.dp))
            Button(onClick = {
                error = ""
                if (step < 2) { if (settings.responseLanguage == settings.thirdLanguage) settings.responseLanguage = third; settings.thirdLanguage = third; settings.punjabiScript = script; step++ }
                else if (step == 3) onComplete()
                else if (recovery.isNotBlank()) step = 3
                else if (existingAdministrator) { busy = true; scope.launch { try { if (withContext(Dispatchers.IO) { auth.unlock(pin) }) { pin = ""; step = 3 } else error = "Incorrect administrator PIN." } catch(e: Exception) { error = e.message ?: "Could not unlock administrator." } finally { busy = false } } }
                else if (pin != confirmation || pin.length < 6) error = "Enter matching PINs with 6–12 digits."
                else { busy = true; scope.launch { try { recovery = withContext(Dispatchers.IO) { auth.configure(pin) }; pin = ""; confirmation = "" } catch(e: Exception) { error = e.message ?: "Could not configure administrator." } finally { busy = false } } }
            }, enabled = !busy && (step == 3 || recovery.isBlank() || acknowledged), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (step < 2) "Continue" else if (step == 3) { if (tools.searchReady && tools.translationReady) "Open Pocket AI" else "Continue with originals" } else if (existingAdministrator || recovery.isNotBlank()) "Prepare this phone" else "Create administrator")
            }
            if (step > 0 && recovery.isBlank()) TextButton(onClick = { step-- }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back") }
            if (existingAdministrator && step == 2) TextButton(onClick = { showRecovery = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use recovery code") }
        }
    }
    if (showRecovery) UnlockDialog(auth, onDismiss = { showRecovery = false }, onUnlocked = { showRecovery = false; step = 3 })
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, text: String) {
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = PocketAccent, modifier = Modifier.size(26.dp))
        Column(Modifier.padding(start = 18.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(text, style = MaterialTheme.typography.bodySmall, color = PocketMuted) }
    }
}

@Composable
private fun UnlockDialog(auth: AuthManager, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var recoverMode by remember { mutableStateOf(false) }
    var newRecovery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (recoverMode) "Recover administrator" else "Unlock Training Mode") },
        text = { Column {
            Text("Administrator authorization protects permanent knowledge.", color = PocketMuted)
            if (newRecovery.isNotBlank()) { Text("New recovery code: save it before continuing."); SelectionContainer { Text(newRecovery, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp)) } }
            else {
                if (recoverMode) OutlinedTextField(recovery, { recovery = it }, label = { Text("Recovery code") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pin, { pin = pinDigits(it) }, label = { Text(if (recoverMode) "New PIN · 6–12 digits" else "Administrator PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { recoverMode = !recoverMode; pin = ""; error = "" }) { Text(if (recoverMode) "Use PIN instead" else "Forgot PIN? Use recovery code") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            if (newRecovery.isNotBlank()) { onUnlocked(); return@TextButton }
            busy = true; error = ""
            scope.launch { try {
                if (recoverMode) { newRecovery = withContext(Dispatchers.IO) { auth.recover(recovery, pin) }; pin = "" }
                else { val ok = withContext(Dispatchers.IO) { auth.unlock(pin) }; if (ok) onUnlocked() else error = "Incorrect PIN. Try again." }
            } catch(e: Exception) { error = e.message ?: "Authorization failed." } finally { busy = false } }
        }) { Text(if (busy) "Checking…" else if (newRecovery.isNotBlank()) "Code saved · continue" else if (recoverMode) "Reset PIN" else "Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
private fun TrainingScreen(db: PocketDatabase, settings: AppSettings, edit: KnowledgeRecord?, voiceText: String,
    onVoiceConsumed: () -> Unit, onMicrophone: (String) -> Unit, recording: Boolean, processing: Boolean, processingLabel: String, onMessage: (String) -> Unit,
    onSaved: (Boolean) -> Unit, onHistory: () -> Unit, onCancelEdit: () -> Unit, localModelStatus: String, onModels: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember(edit?.id) { mutableStateOf(edit?.content ?: "") }
    var question by remember(edit?.id) { mutableStateOf(edit?.question ?: "") }
    var category by remember(edit?.id) { mutableStateOf(edit?.category ?: "General") }
    var language by remember(edit?.id) { mutableStateOf(edit?.language ?: settings.responseLanguage) }
    var source by remember(edit?.id) { mutableStateOf(edit?.source ?: "text") }
    var reference by remember(edit?.id) { mutableStateOf(edit?.sourceReference ?: "") }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<KnowledgeDraft?>(null) }
    var conflictWarning by remember { mutableStateOf("") }
    var documentUri by remember { mutableStateOf<Uri?>(null) }
    var documentPreview by remember { mutableStateOf<PendingDocumentImport?>(null) }
    var preparingDocument by remember { mutableStateOf(false) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> ExternalActivityFlow.active = false; documentUri = uri }
    LaunchedEffect(voiceText) { if (voiceText.isNotBlank()) { content = if (content.isBlank()) voiceText else content.trimEnd() + "\n" + voiceText; source = "voice"; onVoiceConsumed() } }
    LaunchedEffect(documentUri) {
        val uri = documentUri ?: return@LaunchedEffect
        busy = true
        preparingDocument = true
        try {
            val extracted = runInterruptible(Dispatchers.IO) {
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Imported document"
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    DocumentTextExtractor(context).extract(stream, name.take(200), mime)
                } ?: error("Could not open this document. Copy it to the phone and try again.")
            }
            val passages = runInterruptible(Dispatchers.IO) { DocumentChunker.chunk(extracted.text).size }
            documentPreview = PendingDocumentImport(
                text = extracted.text, name = extracted.fileName, format = extracted.format,
                pages = extracted.pages, chunks = passages, warnings = extracted.warnings, language = "en", script = "Latn",
                category = category.takeUnless { it.isBlank() || it == "General" } ?: "Documents",
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch(e: Exception) { onMessage(e.message ?: "Document import failed.") }
        finally { busy = false; preparingDocument = false; documentUri = null }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        PageHeading("OWNER WORKSPACE", if (edit == null) "Teach something new." else "Correct a memory.", "Facts you confirm here become permanent knowledge. Ordinary conversations do not.")
        Spacer(Modifier.height(14.dp))
        if (edit == null) {
            Card(colors = CardDefaults.cardColors(containerColor = PocketSurface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ask your documents", fontWeight = FontWeight.SemiBold)
                    Text("Import English TXT, Word (.docx) or a text-based PDF. Review and index it, then choose your question and answer language. English originals stay available in the reference library.", style = MaterialTheme.typography.bodySmall, color = PocketMuted)
                    Text(localModelStatus, style = MaterialTheme.typography.bodySmall, color = PocketAccent)
                    TextButton(onClick = onModels, enabled = !busy && !processing && !recording) { Text("Check offline tools") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (processing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(processingLabel, color = PocketAccent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (preparingDocument) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Extracting document text on this device…", color = PocketAccent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onMicrophone(language) }, enabled = !busy && !processing) { Icon(if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic, null, Modifier.size(18.dp)); Text(if (recording) " Finish voice" else " Teach by voice") }
            if (edit == null) OutlinedButton(onClick = {
                ExternalActivityFlow.active = true
                runCatching { documentPicker.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/pdf", "application/msword", "application/octet-stream")) }
                    .onFailure { ExternalActivityFlow.active = false; onMessage("No document picker is available. Open Android Files and check that a document provider is installed.") }
            }, enabled = !busy && !processing && !recording) { Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Text(" Import document") }
        }
        if (edit == null) Text("TXT · DOCX · text PDF · Legacy .doc needs conversion to .docx", color = PocketMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        Text("KNOWLEDGE LANGUAGE", color = PocketAccent, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { LanguageTools.configured(settings.thirdLanguage).forEach { option -> FilterChip(language == option.tag, { language = option.tag }, enabled = !recording && !busy && !processing, label = { Text(if (option.tag == "pa") { if (settings.punjabiScript == "Guru") "ਪੰਜਾਬੀ" else "پنجابی" } else option.nativeName) }) } }
        OutlinedTextField(question, { question = it.take(2000) }, label = { Text("Question or title · optional") }, placeholder = { Text("What should I remember this for?") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(12.dp))
        CompositionLocalProvider(LocalLayoutDirection provides if (LanguageTools.isRtl(content)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            OutlinedTextField(content, { content = it.take(20000) }, label = { Text("Knowledge, answer or note") }, placeholder = { Text("Enter exactly what you want Pocket AI to know…") }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(category, { category = it.take(80) }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(reference, { reference = it.take(500) }, label = { Text("Source reference · optional") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(20.dp))
        Button(enabled = content.isNotBlank() && !busy && !processing && !recording, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
            busy = true
            scope.launch { try {
                val (detected, script) = LanguageTools.detectScript(content, language, settings.punjabiScript)
                val draft = KnowledgeDraft(content.trim(), question.trim(), detected, script, category.trim().ifBlank { "General" }, source, reference)
                val duplicates = withContext(Dispatchers.IO) { db.findDuplicates(draft).filter { it.id != edit?.id } }
                if (duplicates.isNotEmpty()) onMessage("This content is already saved as #${duplicates.first().id}. Open it in Memory to edit.")
                else { val conflicts = withContext(Dispatchers.IO) { db.findConflicts(draft).filter { it.id != edit?.id } }; conflictWarning = if (conflicts.isEmpty()) "" else "A different answer exists for this question (${conflicts.joinToString { "#${it.id}" }}). Review both before confirming."; preview = draft }
            } catch(e: Exception) { onMessage(e.message ?: "Could not validate knowledge.") } finally { busy = false } }
        }) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else { Icon(Icons.Outlined.Check, null, Modifier.size(20.dp)); Text(" Review & save") } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onHistory) { Text("Training history") }
            if (edit != null) TextButton(onClick = { content = ""; question = ""; onCancelEdit() }) { Text("Cancel edit") }
        }
        Text("Saving creates a searchable local index; it does not retrain a model. Documents keep their original wording and filename references. Translated text is separate from the original, and its language tools must be ready before use.", style = MaterialTheme.typography.bodySmall, color = PocketMuted)
    }
    preview?.let { draft -> AlertDialog(onDismissRequest = { preview = null }, title = { Text(if (edit == null) "Save this knowledge?" else "Confirm correction?") },
        text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) { if (conflictWarning.isNotBlank()) Text(conflictWarning, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)); Text(draft.content); Spacer(Modifier.height(12.dp)); Text("${LanguageTools.name(draft.language)} · ${draft.category}", color = PocketMuted) } },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch { try { withContext(Dispatchers.IO) { db.saveKnowledge(draft, edit?.id) }; preview = null; onMessage("Knowledge saved and verified."); onSaved(false) } catch(e: Exception) { onMessage(e.message ?: "Save failed.") } finally { busy = false } } }) { Text("Confirm & save") } },
        dismissButton = { TextButton(onClick = { preview = null }) { Text("Keep editing") } }) }
    documentPreview?.let { document -> AlertDialog(
        onDismissRequest = { if (!busy) documentPreview = null }, title = { Text("Import this document?") },
        text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            Text(document.name, fontWeight = FontWeight.SemiBold)
            Text("${document.format}${document.pages?.let { " · $it pages" } ?: ""} · ${document.text.length} characters\n${document.chunks} passages before duplicate checks · ${document.category}", color = PocketMuted, modifier = Modifier.padding(vertical = 10.dp))
            Text("SOURCE LANGUAGE", color = PocketAccent, style = MaterialTheme.typography.labelSmall)
            Text("English is the default for imported documents. This does not change your answer language.", style = MaterialTheme.typography.bodySmall, color = PocketMuted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LanguageTools.configured(settings.thirdLanguage).forEach { option ->
                    FilterChip(selected = document.language == option.tag, enabled = !busy, onClick = {
                        val script = when (option.tag) { "en" -> "Latn"; "ur" -> "Arab"; "pa" -> settings.punjabiScript; else -> LanguageTools.detectScript(document.text, option.tag, settings.punjabiScript).second }
                        documentPreview = document.copy(language = option.tag, script = script)
                    }, label = { Text(option.nativeName) })
                }
            }
            document.warnings.forEach { warning -> Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
            Text("Review the extracted text. Confirming saves source-linked passages for document search; existing duplicates are skipped. Original file text is evidence to review, not instructions for the assistant.", modifier = Modifier.padding(bottom = 10.dp))
            SelectionContainer { Text(document.text.take(2400) + if (document.text.length > 2400) "\n… (preview only; all extracted text will be indexed)" else "", textAlign = if (LanguageTools.isRtl(document.text)) TextAlign.Right else TextAlign.Start) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch { try {
                val count = withContext(Dispatchers.IO) { db.importText(document.text, document.name, document.language, document.script, document.category) }
                documentPreview = null; onMessage("Indexed $count document passages. Ask about them in User Mode; filename references are kept."); onSaved(true)
            } catch(e: Exception) { onMessage(e.message ?: "Document import failed.") } finally { busy = false } }
        }) { Text(if (busy) "Indexing…" else "Save & index document") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { documentPreview = null }) { Text("Cancel") } }
    ) }
}

private data class PendingDocumentImport(
    val text: String, val name: String, val format: String, val pages: Int?, val chunks: Int, val warnings: List<String>,
    val language: String, val script: String, val category: String,
)

@Composable
private fun SourceCitationChip(db: PocketDatabase, sourceId: Long, number: Int, revision: Int, onClick: () -> Unit) {
    var label by remember(sourceId) { mutableStateOf("Source $number") }
    LaunchedEffect(sourceId, revision) {
        try {
            val record = withContext(Dispatchers.IO) { db.getKnowledge(sourceId) }
            label = if (record == null) "$number · Deleted source" else "$number · ${record.sourceReference.ifBlank { record.question.ifBlank { record.category } }}"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { label = "$number · Open source" }
    }
    AssistChip(onClick = onClick, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 270.dp)) },
        leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(17.dp)) })
}

@Composable
private fun LibraryScreen(
    db: PocketDatabase, revision: Int, unlocked: Boolean, section: String, onSectionChange: (String) -> Unit,
    onEdit: (KnowledgeRecord) -> Unit, onDelete: (KnowledgeRecord) -> Unit, onTeach: () -> Unit,
    onSpeak: (KnowledgeRecord) -> Unit, onMessage: (String) -> Unit,
    onReadDocument: (DocumentSummary) -> Unit, onReadSource: (Long) -> Unit,
    onUnlockEditing: () -> Unit, onChanged: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = section == "documents", onClick = { onSectionChange("documents") }, label = { Text("Documents") })
            FilterChip(selected = section != "documents", onClick = { onSectionChange("knowledge") }, label = { Text("All knowledge") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (section == "documents") DocumentLibraryScreen(db, revision, unlocked, onReadDocument, onTeach, onUnlockEditing, onChanged, onMessage)
            else MemoryScreen(db, revision, unlocked, onEdit, onDelete, onTeach, onSpeak, onMessage, onReadSource)
        }
    }
}

@Composable
private fun DocumentLibraryScreen(
    db: PocketDatabase, revision: Int, unlocked: Boolean, onRead: (DocumentSummary) -> Unit,
    onImport: () -> Unit, onUnlockEditing: () -> Unit, onChanged: () -> Unit, onMessage: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var documents by remember { mutableStateOf<List<DocumentSummary>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<DocumentSummary?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) { offset = 0 }
    LaunchedEffect(query, revision, offset) {
        if (query.isNotBlank()) delay(180)
        loading = true
        try {
            val batch = withContext(Dispatchers.IO) { db.listDocuments(query = query, limit = 20, offset = offset) }
            documents = if (offset == 0) batch else (documents + batch).distinctBy { it.id }
            hasMore = batch.size == 20
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "References could not be read." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PageHeading("REFERENCE LIBRARY", "Read your originals.", "Open saved document text even while User Mode is locked for editing.")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onImport) { Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Text(" Import reference") }
            if (!unlocked) TextButton(onClick = onUnlockEditing) { Text("Unlock editing") }
        }
        OutlinedTextField(query, { query = it; offset = 0 }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search reference names…") }, shape = RoundedCornerShape(18.dp))
        Spacer(Modifier.height(12.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        if (documents.isEmpty() && !loading) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Description, null, Modifier.size(44.dp), tint = PocketMuted)
                Text(if (query.isBlank()) "Your reference library starts here." else "No matching reference names.", modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
                if (query.isBlank()) TextButton(onClick = onImport) { Text("Import TXT, Word or text PDF") }
            }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(documents, key = { it.id }) { document ->
                Card(onClick = { onRead(document) }, colors = CardDefaults.cardColors(containerColor = PocketSurface)) {
                    Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, null, tint = PocketAccent, modifier = Modifier.size(23.dp))
                            Text(document.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 11.dp))
                            if (unlocked) IconButton(onClick = { deleting = document }, enabled = !deleteBusy) { Icon(Icons.Outlined.DeleteOutline, "Delete ${document.name}", Modifier.size(20.dp)) }
                        }
                        Text("${LanguageTools.name(document.language)} · ${document.chunkCount} saved passages", color = PocketMuted, style = MaterialTheme.typography.bodySmall)
                        Text("Imported ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(document.importedAt))}", color = PocketMuted, style = MaterialTheme.typography.labelSmall)
                        Text("Read original text  →", color = PocketAccent, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (hasMore) item { OutlinedButton(onClick = { offset += 20 }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Load more references") } }
        }
    }
    deleting?.let { document -> AlertDialog(
        onDismissRequest = { if (!deleteBusy) deleting = null }, title = { Text("Delete this reference?") },
        text = { Text("${document.name}\n\nThis removes its saved passages from the local library. Existing conversations remain, and their source links will show that the source was deleted. The file you originally selected is not removed.") },
        confirmButton = { TextButton(enabled = !deleteBusy, onClick = {
            deleteBusy = true
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) { db.deleteDocument(document.id) }
                    deleting = null; onChanged(); onMessage("Reference deleted · $count saved passages removed.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { onChanged(); onMessage(failure.message ?: "Unlock administrator access before deleting this reference.") }
                finally { deleteBusy = false }
            }
        }) { Text(if (deleteBusy) "Deleting…" else "Delete reference", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !deleteBusy, onClick = { deleting = null }) { Text("Keep reference") } },
    ) }
}

@Composable
private fun OriginalSourceWindow(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close source") }
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
                }
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
    }
}

@Composable
private fun SourcePassageViewer(
    db: PocketDatabase, sourceId: Long, revision: Int, settings: AppSettings, translationReady: Boolean,
    translateSource: suspend (Long, String, String) -> OfflineTranslation,
    onOpenTools: () -> Unit, onDismiss: () -> Unit, onReadDocument: (DocumentSummary) -> Unit,
) {
    var source by remember(sourceId) { mutableStateOf<KnowledgeRecord?>(null) }
    var document by remember(sourceId) { mutableStateOf<DocumentSummary?>(null) }
    var loading by remember(sourceId) { mutableStateOf(true) }
    var error by remember(sourceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(sourceId, revision) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) { db.getKnowledge(sourceId) to db.getDocumentForKnowledge(sourceId) }
            source = result.first; document = result.second; error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "This source could not be read." }
        finally { loading = false }
    }
    OriginalSourceWindow("Original source", onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val record = source
            if (!loading && error == null && record == null) {
                Text("This source was deleted.", style = MaterialTheme.typography.headlineSmall)
                Text("The saved answer remains in the conversation, but the original source passage is no longer in this library.", color = PocketMuted)
            }
            if (record != null && error == null) {
                Text(record.sourceReference.ifBlank { record.question.ifBlank { record.category } }, style = MaterialTheme.typography.titleLarge)
                Text("Original saved text · ${LanguageTools.name(record.language)}", color = PocketAccent, style = MaterialTheme.typography.labelMedium)
                Text("Current saved version · updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.updatedAt))}", color = PocketMuted, style = MaterialTheme.typography.labelSmall)
                Text("Check the original wording, names, numbers and surrounding context. An answer or translation does not change this source.", color = PocketMuted, style = MaterialTheme.typography.bodySmall)
                document?.let { original -> OutlinedButton(onClick = { onReadDocument(original) }, modifier = Modifier.fillMaxWidth()) { Text("Read all saved passages from this reference") } }
                HorizontalDivider()
                SelectionContainer { Text(record.content, lineHeight = 26.sp, textAlign = if (LanguageTools.isRtl(record.content)) TextAlign.Right else TextAlign.Start) }
                key(record.id, record.updatedAt) {
                    SourceTranslationPanel(db, record, settings, translationReady, translateSource, onOpenTools)
                }
            }
        }
    }
}

@Composable
private fun SourceTranslationPanel(
    db: PocketDatabase, source: KnowledgeRecord, settings: AppSettings, ready: Boolean,
    translateSource: suspend (Long, String, String) -> OfflineTranslation, onOpenTools: () -> Unit,
) {
    if (source.language.substringBefore('-') != "en") return
    var target by remember { mutableStateOf(settings.responseLanguage.takeIf { it in setOf("ur", "pa") } ?: "ur") }
    var result by remember { mutableStateOf<OfflineTranslation?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Optional local translation", style = MaterialTheme.typography.titleMedium)
        Text("The English original above stays unchanged. Check names, quantities and instructions against it.", color = PocketMuted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = target == "ur", onClick = { target = "ur"; result = null; failure = null }, enabled = !busy, label = { Text("اردو") })
            FilterChip(selected = target == "pa", onClick = { target = "pa"; result = null; failure = null }, enabled = !busy, label = { Text(if (settings.punjabiScript == "Guru") "ਪੰਜਾਬੀ" else "پنجابی") })
        }
        if (target == "pa" && settings.punjabiScript == "Arab") Text("Shahmukhi uses script conversion after Punjabi translation.", color = PocketMuted, style = MaterialTheme.typography.bodySmall)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (stopping) "Stopping local translation…" else "Translating on this phone…", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !stopping, onClick = { stopping = true; job?.cancel() }) { Text("Stop translation") }
        } else if (ready) {
            OutlinedButton(onClick = {
                if (busy) return@OutlinedButton
                busy = true; stopping = false; result = null; failure = null
                job = scope.launch {
                    try {
                        val translated = translateSource(source.id, target, if (target == "pa") settings.punjabiScript else "Arab")
                        val current = withContext(Dispatchers.IO) { db.getKnowledge(source.id) }
                        if (current != null && current.updatedAt == source.updatedAt && current.content == source.content && current.language == source.language && current.script == source.script && translated.sourceText == source.content) result = translated
                        else failure = "The source changed or was removed during translation. Reopen its current original before translating again."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failure = error.message ?: "Local translation could not finish. The original remains available." }
                    finally { busy = false; stopping = false; job = null }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Translate this passage") }
        } else {
            Text("Translation tools are not ready. The full original remains available above.", color = PocketMuted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenTools, modifier = Modifier.fillMaxWidth()) { Text("Prepare offline translation") }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        result?.let { translated ->
            Text(if (translated.cacheHit) "Saved local translation · reused" else "Translated locally", color = PocketAccent, style = MaterialTheme.typography.labelMedium)
            if (translated.scriptConverted) Text("Punjabi translation with Shahmukhi script conversion", color = PocketMuted, style = MaterialTheme.typography.labelSmall)
            if (translated.description.isNotBlank()) Text(translated.description, color = PocketMuted, style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(translated.text, lineHeight = 26.sp, textAlign = if (LanguageTools.isRtl(translated.text)) TextAlign.Right else TextAlign.Start) }
        }
    }
}

@Composable
private fun DocumentReader(db: PocketDatabase, document: DocumentSummary, revision: Int, onDismiss: () -> Unit) {
    var records by remember(document.id) { mutableStateOf<List<KnowledgeRecord>>(emptyList()) }
    var offset by remember(document.id) { mutableIntStateOf(0) }
    var hasMore by remember(document.id) { mutableStateOf(false) }
    var loading by remember(document.id) { mutableStateOf(true) }
    var error by remember(document.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(document.id, revision) { offset = 0 }
    LaunchedEffect(document.id, revision, offset) {
        loading = true
        try {
            val batch = withContext(Dispatchers.IO) { db.getDocumentChunks(document.id, limit = 12, offset = offset) }
            records = if (offset == 0) batch else (records + batch).distinctBy { it.id }
            hasMore = batch.size == 12; error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "The reference could not be read." }
        finally { loading = false }
    }
    OriginalSourceWindow(document.name, onDismiss) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(document.name, style = MaterialTheme.typography.headlineSmall)
                Text("${LanguageTools.name(document.language)} · original saved passages", color = PocketAccent, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                Text("Saved extracted text is shown in source order. Passages may overlap; document formatting and page positions are not preserved.", color = PocketMuted, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (!loading && records.isEmpty() && error == null) item { Text("This reference has no remaining saved passages. It may have been deleted.", color = PocketMuted) }
            items(records, key = { it.id }) { record ->
                Card(colors = CardDefaults.cardColors(containerColor = PocketSurface)) {
                    Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(record.sourceReference, color = PocketAccent, style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(record.content, lineHeight = 26.sp, textAlign = if (LanguageTools.isRtl(record.content)) TextAlign.Right else TextAlign.Start) }
                    }
                }
            }
            if (hasMore) item { OutlinedButton(enabled = !loading, onClick = { offset += 12 }, modifier = Modifier.fillMaxWidth()) { Text("Read more passages") } }
        }
    }
}

@Composable
private fun MemoryScreen(db: PocketDatabase, revision: Int, unlocked: Boolean, onEdit: (KnowledgeRecord) -> Unit,
    onDelete: (KnowledgeRecord) -> Unit, onTeach: () -> Unit, onSpeak: (KnowledgeRecord) -> Unit, onMessage: (String) -> Unit, onReadSource: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    var records by remember { mutableStateOf(emptyList<KnowledgeRecord>()) }
    var offset by remember { mutableIntStateOf(0) }
    var canLoadMore by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<KnowledgeRecord?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var categories by remember { mutableStateOf(emptyList<String>()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var manageCategories by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }
    var removeCategory by remember { mutableStateOf<String?>(null) }
    var categoryRevision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, revision, offset, selectedCategory, categoryRevision) {
        delay(if (query.isBlank()) 0 else 200)
        try {
            val page = withContext(Dispatchers.IO) { db.listKnowledge(query = query, category = selectedCategory, limit = 20, offset = offset) }
            records = if (offset == 0) page else records + page
            canLoadMore = page.size == 20
            total = withContext(Dispatchers.IO) { db.countKnowledge() }
            categories = withContext(Dispatchers.IO) { db.categories() }
        } catch(e: Exception) { onMessage(e.message ?: "Memory could not be read.") }
    }
    LaunchedEffect(revision) { offset = 0 }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        PageHeading("PERSONAL LIBRARY", "A place for what matters.", "$total memories saved on this device.")
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(query, { query = it; offset = 0 }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search facts and questions…") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selectedCategory == null, { selectedCategory = null; offset = 0 }, { Text("All memories") })
            categories.forEach { name -> FilterChip(selectedCategory == name, { selectedCategory = name; offset = 0 }, { Text(name) }) }
            if (unlocked) IconButton(onClick = { manageCategories = true }) { Icon(Icons.Outlined.FolderOpen, "Manage categories") }
        }
        Spacer(Modifier.height(14.dp))
        if (records.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.AutoStories, null, Modifier.size(48.dp), tint = PocketMuted)
            Text(if (query.isBlank()) "Your knowledge starts here." else "No matching memories.", modifier = Modifier.padding(16.dp))
            TextButton(onClick = onTeach) { Text("Teach a memory") }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(records, key = { it.id }) { record ->
                Card(colors = CardDefaults.cardColors(containerColor = PocketSurface)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(record.category.uppercase(), color = PocketAccent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)); Text("#${record.id} · ${record.language.uppercase()}", color = PocketMuted, style = MaterialTheme.typography.labelSmall) }
                        if (record.question.isNotBlank()) Text(record.question, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                        SelectionContainer { Text(record.content, modifier = Modifier.padding(top = 8.dp), textAlign = if (LanguageTools.isRtl(record.content)) TextAlign.Right else TextAlign.Start, maxLines = 20, lineHeight = 24.sp) }
                        if (record.sourceReference.isNotBlank()) Text(record.sourceReference, color = PocketMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        TextButton(onClick = { onReadSource(record.id) }) { Text("Read full source") }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Owner verified · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.updatedAt))}", color = PocketMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onSpeak(record) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.VolumeUp, "Read memory", Modifier.size(18.dp)) }
                            IconButton(onClick = { onEdit(record) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Edit, "Edit memory", Modifier.size(18.dp)) }
                            if (unlocked) IconButton(onClick = { delete = record }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.DeleteOutline, "Delete memory", Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            if (canLoadMore) item { OutlinedButton(onClick = { offset += 20 }, modifier = Modifier.fillMaxWidth()) { Text("Load more memories") } }
        }
    }
    delete?.let { record -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete memory #${record.id}?") }, text = { Text("This removes the saved knowledge. A deletion entry remains in training history.") }, confirmButton = { TextButton(onClick = { delete = null; onDelete(record) }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
    if (manageCategories) AlertDialog(onDismissRequest = { manageCategories = false }, title = { Text("Knowledge categories") }, text = {
        Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            categories.forEach { name -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                if (name != "General") IconButton(onClick = { removeCategory = name }) { Icon(Icons.Outlined.DeleteOutline, "Delete category $name", Modifier.size(18.dp)) }
            } }
            OutlinedTextField(newCategory, { newCategory = it.take(80) }, label = { Text("New category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = newCategory.isNotBlank(), onClick = { scope.launch { try { withContext(Dispatchers.IO) { db.addCategory(newCategory) }; newCategory = ""; categoryRevision++; offset = 0 } catch(e: Exception) { onMessage(e.message ?: "Could not create category.") } } }) { Text("Create category") } }, dismissButton = { TextButton(onClick = { manageCategories = false }) { Text("Done") } })
    removeCategory?.let { name -> AlertDialog(onDismissRequest = { removeCategory = null }, title = { Text("Remove $name?") }, text = { Text("Memories in this category will move to General.") }, confirmButton = { TextButton(onClick = { removeCategory = null; scope.launch { try { withContext(Dispatchers.IO) { db.deleteCategory(name) }; selectedCategory = null; offset = 0; categoryRevision++ } catch(e: Exception) { onMessage(e.message ?: "Could not remove category.") } } }) { Text("Remove category") } }, dismissButton = { TextButton(onClick = { removeCategory = null }) { Text("Cancel") } }) }
}

@Composable
private fun HistoryScreen(db: PocketDatabase, revision: Int) {
    var history by remember { mutableStateOf(emptyList<TrainingEvent>()) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(revision) { try { history = withContext(Dispatchers.IO) { db.trainingHistory(100) } } catch(e: Exception) { error = e.message ?: "Unlock Training Mode to view history." } }
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("AUDIT TRAIL", "Training history", "The 100 most recent teaching and correction events.") }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        if (history.isEmpty()) item { Text("No training events yet.", color = PocketMuted) }
        items(history, key = { it.id }) { event -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("${event.action.uppercase()} ${event.knowledgeId?.let { "· #$it" } ?: ""}", color = PocketAccent, style = MaterialTheme.typography.labelMedium)
            Text(event.details, Modifier.padding(vertical = 8.dp), maxLines = 5)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.createdAt)), style = MaterialTheme.typography.labelSmall, color = PocketMuted)
        } } }
    }
}

private fun pinDigits(value: String): String = value.mapNotNull { char ->
    Character.digit(char, 10).takeIf { it >= 0 }?.let { ('0'.code + it).toChar() }
}.take(12).joinToString("")
