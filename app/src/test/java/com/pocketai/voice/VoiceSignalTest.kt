package com.pocketai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSignalTest {
    @Test(expected = IllegalArgumentException::class)
    fun silentMicrophoneDoesNotReachWhisper() {
        SpeechController.speechSamples(ShortArray(32000), 32000)
    }

    @Test
    fun silenceIsTrimmedButPausesWithinSpeechArePreserved() {
        val source = ShortArray(64000)
        for (index in 16000 until 24000) source[index] = 3000
        for (index in 40000 until 48000) source[index] = -3000
        val result = SpeechController.speechSamples(source, source.size)
        // 2 seconds spanning the two speech regions, plus 200 ms on each side.
        assertEquals(38400, result.size)
        assertEquals(0f, result[0], 0f)
        assertTrue(result[3200] > 0f)
        assertEquals(0f, result[20000], 0f)
        assertTrue(result[30000] < 0f)
        assertEquals(0f, result.last(), 0f)
    }

    @Test
    fun pcmConversionPreservesQuietSpeechAndSign() {
        val source = ShortArray(16000) { if (it % 2 == 0) 100 else -100 }
        val result = SpeechController.speechSamples(source, source.size)
        assertEquals(source.size, result.size)
        assertEquals(100f / 32768f, result[0], 0f)
        assertEquals(-100f / 32768f, result[1], 0f)
    }
}
