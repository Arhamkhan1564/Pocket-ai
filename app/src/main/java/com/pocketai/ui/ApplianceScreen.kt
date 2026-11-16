package com.pocketai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketai.appliance.ApplianceSession

@Composable
fun ApplianceScreen(onMessage: (String) -> Unit, onModels: () -> Unit, onAudio: () -> Unit, onStart: () -> Unit) {
    val context = LocalContext.current
    val state by ApplianceSession.state.collectAsState()
    var permissionRevision by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionRevision++
        onMessage(if (result.values.all { it }) "Permissions ready. Tap Start session to activate the microphone." else "Microphone and notification permissions are required for an appliance session.")
    }
    val needed = remember(permissionRevision) {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeading("DEDICATED ASSISTANT", "A voice beyond the screen.", "An optional foreground session for use after setup and audio testing.")
        Card(colors = CardDefaults.cardColors(containerColor = PocketSurface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (state.running) "SESSION ACTIVE" else "SESSION STOPPED", color = PocketAccent, fontWeight = FontWeight.SemiBold)
                Text(state.status)
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Text("Each turn listens for six seconds, transcribes locally, answers, then speaks. Wait for the reply to finish before speaking again. The microphone does not listen during inference or playback.", color = PocketMuted)
        if (state.lastHeard.isNotBlank()) Card { Column(Modifier.padding(16.dp)) { Text("LAST HEARD", color = PocketAccent, style = MaterialTheme.typography.labelSmall); SelectionContainer { Text(state.lastHeard) } } }
        if (state.lastAnswer.isNotBlank()) Card { Column(Modifier.padding(16.dp)) { Text("LAST ANSWER", color = PocketAccent, style = MaterialTheme.typography.labelSmall); SelectionContainer { Text(state.lastAnswer) } } }
        if (state.running) Button(onClick = { ApplianceSession.stop(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Stop session") }
        else Button(onClick = { if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray()) else onStart() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (needed.isEmpty()) "Start session" else "Allow microphone & notifications") }
        OutlinedButton(onClick = onModels, modifier = Modifier.fillMaxWidth()) { Text("Set up a speech model") }
        OutlinedButton(onClick = onAudio, modifier = Modifier.fillMaxWidth()) { Text("Check offline voice & speaker") }
        Text("Requires a 64-bit device, an imported compatible Whisper tiny/base model, an installed offline voice, and audible volume. The ongoing notification includes a Stop control. Say “stop assistant” to end a session.", color = PocketMuted, style = MaterialTheme.typography.bodySmall)
        Text("Sessions end after 30 minutes and keep the CPU awake while active. Android or the device manufacturer may stop background audio. Start from the visible app each time. Wake words and startup after boot are future features.", color = PocketMuted, style = MaterialTheme.typography.bodySmall)
        Text("Microphone recordings stay temporarily in memory. This session runs User Mode and cannot teach or change permanent knowledge.", color = PocketMuted, style = MaterialTheme.typography.bodySmall)
    }
}
