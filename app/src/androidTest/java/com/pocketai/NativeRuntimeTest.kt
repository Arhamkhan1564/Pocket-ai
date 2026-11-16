package com.pocketai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketai.engine.NativeBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Real imported test weights are injected by the operator, never included in either APK. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NativeRuntimeTest {
    private val fixtures = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "native-fixtures")

    @Before fun requiresExternalFixtures() {
        assumeTrue("Inject native test weights into cache/native-fixtures to run this optional test",
            File(fixtures, "stories260K.gguf").isFile && File(fixtures, "ggml-tiny.bin").isFile && File(fixtures, "jfk.wav").isFile)
        assertNull("Native runtime must load on this test device", NativeBridge.availabilityMessage())
    }

    @Test fun aWhisperRecognizesKnownSpeechBeforeAnyLlmLoad() {
        val audio = waveSamples(File(fixtures, "jfk.wav"))
        val text = NativeBridge.transcribe(File(fixtures, "ggml-tiny.bin").absolutePath, audio, "en", 2)
        assertTrue(text, text.contains("country", ignoreCase = true) && text.contains("ask", ignoreCase = true))
    }

    @Test fun bGgufGeneratesTextAfterSpeechModelUnloads() {
        val answer = NativeBridge.generate(File(fixtures, "stories260K.gguf").absolutePath,
            "", "Once upon a time", 512, 48, 2)
        assertTrue("Expected generated token text", answer.length > 10)
    }

    @Test fun cMissingModelAndInvalidAudioReturnRecoverableErrors() {
        var rejected = false
        try {
            NativeBridge.generate(File(fixtures, "missing.gguf").absolutePath, "", "Hello", 512, 16, 1)
        } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Missing model must be rejected", rejected)
        rejected = false
        try {
            NativeBridge.transcribe(File(fixtures, "ggml-tiny.bin").absolutePath, FloatArray(10), "en", 1)
        } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Invalid audio must be rejected", rejected)
    }

    private fun waveSamples(file: File): FloatArray {
        val bytes = file.readBytes()
        val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(wave.getInt(0) == 0x46464952 && wave.getInt(8) == 0x45564157)
        var offset = 12
        var validFormat = false
        while (offset + 8 <= bytes.size) {
            val chunkId = wave.getInt(offset)
            val length = wave.getInt(offset + 4)
            require(length >= 0 && offset + 8L + length <= bytes.size)
            if (chunkId == 0x20746d66) {
                require(length >= 16 && wave.getShort(offset + 8).toInt() == 1 && wave.getShort(offset + 10).toInt() == 1 &&
                    wave.getInt(offset + 12) == 16000 && wave.getShort(offset + 22).toInt() == 16)
                validFormat = true
            }
            if (chunkId == 0x61746164 && validFormat) return FloatArray(length / 2) { index ->
                wave.getShort(offset + 8 + index * 2).toFloat() / 32768f
            }
            offset += 8 + length + length % 2
        }
        error("No PCM audio data")
    }
}
