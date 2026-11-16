package com.pocketai.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import java.io.File

data class HardwareSnapshot(
    val items: Map<String, String>,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val totalStorageBytes: Long,
    val availableStorageBytes: Long,
    val appHeapBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
)

class HardwareDiagnostics(context: Context) {
    private val appContext = context.applicationContext

    /** Detects only the running phone. It does not infer compatibility from a device name. */
    fun snapshot(): HardwareSnapshot {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val storage = StatFs(appContext.filesDir.absolutePath)
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val runtime = Runtime.getRuntime()
        val heap = runtime.totalMemory() - runtime.freeMemory()
        val cpu = runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.filter { it.startsWith("Hardware") || it.startsWith("model name") || it.startsWith("Processor") }
                    .distinct().take(4).joinToString("\n") { it.take(200) }
            }
        }.getOrDefault("").ifBlank { "Not exposed by Android" }
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light throttling"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate throttling"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe throttling"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown imminent"
                else -> "Unavailable"
            }
        } else "Requires Android 10 or later"
        val external = appContext.getExternalFilesDirs(null).filterNotNull().map { dir ->
            val stats = runCatching { StatFs(dir.absolutePath) }.getOrNull()
            "${dir.absolutePath}: ${stats?.let { formatBytes(it.availableBytes) } ?: "unavailable"} free"
        }
        val items = linkedMapOf(
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "CPU architectures" to Build.SUPPORTED_ABIS.joinToString(", "),
            "Processor" to cpu,
            "CPU cores" to runtime.availableProcessors().toString(),
            "Total RAM" to formatBytes(memory.totalMem),
            "Available RAM" to "${formatBytes(memory.availMem)}${if (memory.lowMemory) " · Android low-memory condition" else ""}",
            "App heap used" to formatBytes(heap),
            "Native heap allocated" to formatBytes(Debug.getNativeHeapAllocatedSize()),
            "App proportional RAM" to formatBytes(Debug.getPss() * 1024),
            "App CPU time" to "${Process.getElapsedCpuTime()} ms since process start",
            "App heap limit" to formatBytes(runtime.maxMemory()),
            "Android memory class" to "${manager.memoryClass} MB",
            "Internal capacity" to formatBytes(storage.totalBytes),
            "Internal free" to formatBytes(storage.availableBytes),
            "Battery" to if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "Unavailable",
            "Charging" to when (status) { BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"; BatteryManager.BATTERY_STATUS_FULL -> "Full"; else -> "Not charging / unavailable" },
            "Battery temperature" to if (temp >= 0) "${temp / 10.0} °C" else "Unavailable",
            "Thermal status" to thermal,
            "Microphone feature" to if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) "Available" else "Not reported",
            "Audio inputs" to inputs.joinToString("\n") { "${it.productName} (type ${it.type})" }.ifBlank { "None reported" },
            "Audio outputs" to outputs.joinToString("\n") { "${it.productName} (type ${it.type})" }.ifBlank { "None reported" },
            "USB host / OTG" to if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) "Host capability reported; attach hardware to test" else "Not reported",
            "App-accessible external storage" to external.joinToString("\n").ifBlank { "None mounted" },
            "Storage import" to "System file picker supports internal and mounted USB providers where Android permits",
            "Network access" to "No Internet permission in this application"
        )
        return HardwareSnapshot(items, memory.totalMem, memory.availMem, storage.totalBytes, storage.availableBytes, heap)
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var index = 0
            while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
            return java.lang.String.format(java.util.Locale.ROOT, "%.1f %s", value, units[index])
        }
    }
}
