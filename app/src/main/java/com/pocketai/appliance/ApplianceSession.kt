package com.pocketai.appliance

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ApplianceState(
    val running: Boolean = false,
    val status: String = "Stopped. Start a session while Pocket AI is visible.",
    val lastHeard: String = "",
    val lastAnswer: String = "",
    val startedAtElapsed: Long = 0L
)

/** Explicit, user-started foreground User Mode; never enabled at boot or after a crash. */
object ApplianceSession {
    private val mutableState = MutableStateFlow(ApplianceState())
    val state: StateFlow<ApplianceState> = mutableState.asStateFlow()

    fun start(context: Context) {
        if (mutableState.value.running) return
        val activity = activityFor(context)
        check(activity != null && !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
            "Start a hands-free session from the visible Pocket AI screen."
        }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Grant microphone permission before starting a hands-free session."
        }
        if (Build.VERSION.SDK_INT >= 33) check(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            "Allow notifications so the ongoing microphone session has a visible Stop control."
        }
        if (Build.VERSION.SDK_INT >= 24) {
            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            check(notifications.areNotificationsEnabled()) { "Enable Pocket AI notifications before starting this microphone session." }
        }
        mutableState.value = ApplianceState(running = true, status = "Preparing offline speech and microphone…")
        try {
            val intent = Intent(context, ApplianceForegroundService::class.java).setAction(ApplianceForegroundService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        } catch (failure: Exception) {
            publish { it.copy(running = false, status = failure.message ?: "Android did not allow the foreground microphone session.") }
            throw failure
        }
    }

    fun stop(context: Context) {
        if (!mutableState.value.running) return
        publish { it.copy(status = "Stopping microphone session…") }
        context.startService(Intent(context, ApplianceForegroundService::class.java).setAction(ApplianceForegroundService.ACTION_STOP))
    }

    internal fun publish(update: (ApplianceState) -> ApplianceState) {
        synchronized(mutableState) { mutableState.value = update(mutableState.value) }
    }

    private tailrec fun activityFor(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> if (context.baseContext !== context) activityFor(context.baseContext) else null
        else -> null
    }
}
