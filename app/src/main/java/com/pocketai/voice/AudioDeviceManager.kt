package com.pocketai.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Enumerates live Android routes. IDs are intentionally never persisted or hard-coded. */
class AudioDeviceManager(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    val devices: StateFlow<List<AudioDevice>> = mutableDevices.asStateFlow()
    private var preferredInputId: Int? = null

    data class AudioDevice(
        val id: Int,
        val label: String,
        val kind: String,
        val input: Boolean,
        val output: Boolean,
    )

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        mutableDevices.value = (manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() +
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()).distinctBy { it.id }.map {
            AudioDevice(it.id, it.productName?.toString().orEmpty().ifBlank { deviceKind(it.type) },
                deviceKind(it.type), it.isSource, it.isSink)
        }
        if (preferredInputId != null && mutableDevices.value.none { it.id == preferredInputId && it.input }) {
            preferredInputId = null
        }
    }

    /** null selects the built-in microphone when available, otherwise Android's current route. */
    fun selectInput(deviceId: Int?) {
        require(deviceId == null || manager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.id == deviceId }) {
            "That microphone is no longer connected."
        }
        preferredInputId = deviceId
    }

    fun routeRecorder(recorder: AudioRecord): Boolean {
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val preferred = inputs.firstOrNull { it.id == preferredInputId }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return preferred == null || recorder.setPreferredDevice(preferred)
    }

    fun outputSummary(): String = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .joinToString { "${it.productName} (${deviceKind(it.type)})" }
        .ifBlank { "No audio output reported by Android" }

    override fun close() { manager.unregisterAudioDeviceCallback(callback) }

    private fun deviceKind(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth audio"
        else -> "Android audio route ($type)"
    }
}
