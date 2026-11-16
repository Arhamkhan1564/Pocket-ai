package com.pocketai.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class MicrophoneMeasurement(
    val sampleCount: Int,
    val rms: Double,
    val peak: Double,
    val durationMillis: Long,
) {
    val rmsDbFs: Double get() = 20.0 * log10(rms.coerceAtLeast(0.00000001))
    /** A level indicator only: ambient noise can also cross this threshold. */
    val soundDetected: Boolean get() = rms >= 0.003 || peak >= 0.02
}

/** Short setup checks. No audio files or whole-recording buffers are created. */
object AudioDiagnostics {
    private const val SAMPLE_RATE = 16_000
    private const val MAX_SAMPLES = SAMPLE_RATE * 2

    suspend fun measureMicrophone(
        context: Context,
        routeRecorder: (AudioRecord) -> Boolean = { true },
    ): MicrophoneMeasurement = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission was not granted. Text features remain available.")
        }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Android does not report support for 16 kHz mono microphone capture." }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 4096))
        val buffer = ShortArray(1024)
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not initialize. It may be in use by another app." }
            check(routeRecorder(recorder)) { "The selected microphone route could not be applied. Try Android's default route." }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Android could not start microphone capture." }
            val start = SystemClock.elapsedRealtime()
            var count = 0
            var sumOfSquares = 0.0
            var peak = 0
            while (count < MAX_SAMPLES && SystemClock.elapsedRealtime() - start < 2_000) {
                currentCoroutineContext().ensureActive()
                val received = recorder.read(buffer, 0, minOf(buffer.size, MAX_SAMPLES - count), AudioRecord.READ_NON_BLOCKING)
                check(received >= 0) { "Android microphone read failed (code $received)." }
                if (received == 0) {
                    delay(10)
                } else {
                    repeat(received) { index ->
                        val sample = buffer[index].toInt()
                        sumOfSquares += sample.toDouble() * sample.toDouble()
                        peak = maxOf(peak, abs(sample))
                        buffer[index] = 0
                    }
                    count += received
                }
            }
            check(count > 0) { "No audio samples arrived. Check the microphone, permission and Android privacy controls." }
            MicrophoneMeasurement(count, sqrt(sumOfSquares / count) / 32768.0,
                peak / 32768.0, SystemClock.elapsedRealtime() - start)
        } finally {
            buffer.fill(0)
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            recorder.release()
        }
    }

    /** A true return means Android accepted the tone, never that a person heard a speaker. */
    suspend fun queueSpeakerTone(): Boolean = withContext(Dispatchers.IO) {
        val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        try {
            val queued = generator.startTone(ToneGenerator.TONE_PROP_BEEP, 500)
            if (queued) delay(550)
            queued
        } finally {
            generator.stopTone()
            generator.release()
        }
    }
}
