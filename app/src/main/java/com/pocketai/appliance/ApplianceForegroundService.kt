package com.pocketai.appliance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.pocketai.AppSettings
import com.pocketai.MainActivity
import com.pocketai.R
import com.pocketai.assistant.AssistantService
import com.pocketai.assistant.LanguageTools
import com.pocketai.data.PocketDatabase
import com.pocketai.engine.LocalAiEngine
import com.pocketai.models.ModelManager
import com.pocketai.rag.OfflineRagServices
import com.pocketai.security.AuthManager
import com.pocketai.voice.SpeechController
import com.pocketai.voice.OfflineSpeechModels
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A maximum thirty-minute microphone foreground session. Each six-second capture, Whisper
 * request, retrieval/generation, and offline TTS playback runs sequentially. No wake word,
 * boot activation, background restart, saved recording, or permanent knowledge writes.
 */
class ApplianceForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private var timeoutJob: Job? = null
    private var speech: SpeechController? = null
    private var engine: LocalAiEngine? = null
    private var database: PocketDatabase? = null
    private var offlineRag: OfflineRagServices? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish("Hands-free session stopped.")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            finish("Stopped. Restart the session from the app.")
            return START_NOT_STICKY
        }
        if (stopping) {
            ApplianceSession.publish { it.copy(running = false, status = "The previous session is still stopping. Start again in a moment.") }
            stopSelf()
            return START_NOT_STICKY
        }
        if (sessionJob?.isActive == true) return START_NOT_STICKY
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission was revoked. Start again from the app after granting access."
            }
            foreground()
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAI:ExplicitVoiceSession").apply {
                setReferenceCounted(false)
                acquire(MAX_SESSION_MS + 5_000L)
            }
            val started = SystemClock.elapsedRealtime()
            ApplianceSession.publish { it.copy(running = true, startedAtElapsed = started, status = "Checking local speech resources…") }
            timeoutJob = scope.launch {
                delay(MAX_SESSION_MS)
                finish("The 30-minute session ended. Start another session from the app.")
            }
            sessionJob = scope.launch {
                try { runSession() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { finish(failure.message ?: "The voice session stopped because a resource became unavailable.") }
            }
        } catch (failure: Exception) {
            finish(failure.message ?: "Android could not start the foreground microphone service.")
        }
        return START_NOT_STICKY
    }

    private suspend fun runSession() {
        val settings = AppSettings(this)
        val models = ModelManager(this)
        val auth = AuthManager(this).apply { lock() }
        val db = PocketDatabase(this, auth).also { database = it }
        val localEngine = LocalAiEngine(this).also { engine = it }
        val rag = OfflineRagServices(this, db, settings, models).also { offlineRag = it }
        update("Preparing local search and translation resources…")
        // Preparation reads only APK/local files. The existing 30-minute session
        // deadline includes this phase, and cancellation propagates through it.
        rag.prepare()
        currentCoroutineContext().ensureActive()
        val speaker = SpeechController(this).also { speech = it }
        val initialPlan = OfflineSpeechModels.prepare(this, models, settings, speechTag(settings))
        speaker.checkMemory(initialPlan)
        val ready = withTimeoutOrNull(12_000L) { speaker.state.first { it.ttsReady } }
        require(ready != null) { "The Android offline speech engine did not initialize. Configure an offline voice in Audio settings first." }
        val initialTag = speechTag(settings)
        require(speaker.hasOfflineVoice(initialTag)) { "No installed offline voice for $initialTag. Hands-free mode needs audible replies; configure a compatible voice first." }
        require(settings.voiceVolume > 0f) { "Increase voice volume before starting hands-free mode." }
        val assistant = AssistantService(db, models, localEngine, settings, offlineRag = rag)
        say(speaker, startupText(settings), initialTag, settings)
        while (!stopping) {
            currentCoroutineContext().ensureActive()
            val plan = OfflineSpeechModels.prepare(this, models, settings, speechTag(settings))
            val language = plan.primaryLanguage
            speaker.checkMemory(plan)
            require(speaker.hasOfflineVoice(plan.languageTag)) { "The selected response language has no installed offline voice. Session stopped." }
            update(if (plan.primaryLanguage in setOf("ur", "pa")) "Listening for six seconds · unreviewed recognition; check exact words and numbers with tap-to-talk" else "Listening for six seconds…")
            speaker.startRecording()
            delay(RECORDING_MS)
            val question = try {
                update("Recognizing speech locally…")
                speaker.finishTranscription(plan).trim()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = failure.message.orEmpty()
                if (message.contains("No audible speech", true) || message.contains("No speech was recognized", true) ||
                    message.startsWith("No clear speech was detected", ignoreCase = true) ||
                    message.contains("too short", true)) {
                    update("No speech detected. Listening again shortly…")
                    delay(400L)
                    continue
                }
                throw failure
            }
            if (question.lowercase(Locale.ROOT) in setOf("[blank_audio]", "[silence]", "(silence)", "(music)", "[music]")) continue
            ApplianceSession.publish { it.copy(lastHeard = question, status = "Finding an offline answer…") }
            if (isStopCommand(question)) {
                say(speaker, stopText(settings), speechTag(settings), settings)
                finish("Hands-free session stopped by voice command.")
                return
            }
            val answer = withContext(Dispatchers.IO) {
                val result = assistant.answer(question)
                db.addConversation("user", question, language)
                db.addConversation("assistant", result.text, settings.responseLanguage, result.storedSources, sourceIds = result.sourceIds)
                result
            }
            ApplianceSession.publish { it.copy(lastAnswer = answer.text, status = "Speaking offline…") }
            say(speaker, answer.text, speechTag(settings), settings)
            delay(350L) // Speaker tail must not be captured as the next user's question.
        }
    }

    private suspend fun say(speaker: SpeechController, text: String, tag: String, settings: AppSettings) {
        speaker.speechRate = settings.speechRate
        speaker.volume = settings.voiceVolume
        require(speaker.hasOfflineVoice(tag)) { "No installed offline voice for $tag. Session stopped." }
        speaker.speak(text, tag)?.let { error(it) }
        val completed = withTimeoutOrNull(90_000L) { speaker.state.first { !it.speaking } }
        require(completed != null) { "The speech engine did not finish playback. Session stopped." }
        check(completed.message == "Ready") { "Offline speech playback failed: ${completed.message}" }
    }

    private fun foreground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Hands-free microphone session", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Visible controls for the explicitly started offline voice session"; setSound(null, null) }
            manager.createNotificationChannel(channel)
            require(manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
                "Enable the hands-free session notification channel before starting."
            }
        }
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 740, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or immutable)
        val stop = PendingIntent.getService(this, 741, Intent(this, ApplianceForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or immutable)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder.setSmallIcon(R.drawable.ic_launcher).setContentTitle("Pocket AI microphone is active")
            .setContentText("Offline six-second voice turns · ends within 30 minutes")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun update(message: String) = ApplianceSession.publish { it.copy(status = message) }

    private fun finish(message: String) {
        if (stopping) return
        stopping = true
        ApplianceSession.publish { it.copy(running = false, status = message) }
        timeoutJob?.cancel()
        sessionJob?.cancel()
        speech?.cancelTranscription()
        engine?.cancel()
        offlineRag?.cancel()
        speech?.close()
        speech = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) finish("The voice service ended. Restart it from the app.")
        scope.cancel()
        offlineRag?.close()
        offlineRag = null
        // An in-flight native task owns the database until cancellation returns; closing
        // this helper is safe once its transaction has released SQLite's own lock.
        database?.close()
        database = null
        super.onDestroy()
    }

    private fun speechTag(settings: AppSettings): String = LanguageTools.speechTag(settings.responseLanguage, settings.punjabiScript)

    private fun startupText(settings: AppSettings): String = when (settings.responseLanguage) {
        "ur" -> "پاکٹ اے آئی تیار ہے۔ جواب کے بعد بولیں۔ بند کرنے کے لیے سٹاپ اسسٹنٹ کہیں۔"
        "pa" -> if (settings.punjabiScript == "Guru") "ਪਾਕੇਟ ਏ ਆਈ ਤਿਆਰ ਹੈ। ਜਵਾਬ ਤੋਂ ਬਾਅਦ ਬੋਲੋ। ਬੰਦ ਕਰਨ ਲਈ ਸਟਾਪ ਅਸਿਸਟੈਂਟ ਕਹੋ।" else "پاکٹ اے آئی تیار اے۔ جواب توں بعد بولو۔ بند کرن لئی سٹاپ اسسٹنٹ کہو۔"
        else -> "Pocket AI is ready. Speak after each reply. Say stop assistant to end this session."
    }

    private fun stopText(settings: AppSettings): String = when (settings.responseLanguage) {
        "ur" -> "پاکٹ اے آئی بند ہو رہا ہے۔"
        "pa" -> if (settings.punjabiScript == "Guru") "ਪਾਕੇਟ ਏ ਆਈ ਬੰਦ ਹੋ ਰਿਹਾ ਹੈ।" else "پاکٹ اے آئی بند ہو رہیا اے۔"
        else -> "Pocket AI voice session is stopping."
    }

    companion object {
        const val ACTION_START = "com.pocketai.action.START_APPLIANCE_SESSION"
        const val ACTION_STOP = "com.pocketai.action.STOP_APPLIANCE_SESSION"
        const val MAX_SESSION_MS = 30L * 60L * 1000L
        private const val RECORDING_MS = 6_000L
        private const val CHANNEL = "pocket_ai_appliance"
        private const val NOTIFICATION_ID = 740

        internal fun isStopCommand(text: String): Boolean {
            val clean = LanguageTools.normalize(text).lowercase(Locale.ROOT).trimEnd('.', '!', '?', '۔', '؟', '।')
            return clean in setOf("stop assistant", "stop pocket ai", "end voice session", "بند ہو جاؤ", "بند کریں", "بند ہو جاو", "بند ہوجاؤ", "ਬੰਦ ਹੋ ਜਾਓ", "سٹاپ اسسٹنٹ")
        }
    }
}
