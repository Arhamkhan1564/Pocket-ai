package com.pocketai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.random.Random

class PunjabiCtcKernelTest {
    @Test fun frameWindowsCoverEveryAbsoluteFrameExactlyOnce() {
        val lengths = listOf(400, 719, 720, 191_999, 192_000, 192_001, 192_319, 192_320,
            358_400, 371_199, 371_200, PunjabiCtcFrames.MAX_SAMPLES) +
            List(256) { Random(it).nextInt(400, PunjabiCtcFrames.MAX_SAMPLES + 1) }
        for (length in lengths) {
            val windows = PunjabiCtcFrames.windows(length)
            assertEquals(0, windows.first().start)
            assertEquals(length, windows.last().endExclusive)
            var next = 0
            for ((index, window) in windows.withIndex()) {
                assertEquals(0, window.start % PunjabiCtcFrames.STRIDE_SAMPLES)
                assertTrue(window.endExclusive - window.start <= PunjabiCtcFrames.WINDOW_SAMPLES)
                for (frame in 0 until PunjabiCtcFrames.frameCount(window.endExclusive - window.start)) {
                    if (PunjabiCtcFrames.keepsFrame(windows, index, frame)) {
                        assertEquals("Recording length $length, window $index", next++, window.start / 320 + frame)
                    }
                }
            }
            assertEquals(PunjabiCtcFrames.frameCount(length), next)
        }
    }

    @Test fun completeWindowDoesNotCreateAnExtraTrailingWindow() {
        assertEquals(listOf(PunjabiCtcWindow(0, 192_000)), PunjabiCtcFrames.windows(192_000))
        assertEquals(listOf(PunjabiCtcWindow(0, 192_000), PunjabiCtcWindow(179_200, 192_001)),
            PunjabiCtcFrames.windows(192_001))
    }

    @Test fun ctcCollapseRetainsBlankSeparatedLettersAcrossWindows() {
        val labels = listOf("<pad>", "|", "ਕ", "ਾ")
        assertEquals("ਕਕਾ ਕ", PunjabiCtcFrames.decode(intArrayOf(2, 2, 0, 2, 3, 3, 1, 2), labels, 0, "|"))
        assertEquals("ਕ", PunjabiCtcFrames.decode(intArrayOf(2, 2) + intArrayOf(2, 2), labels, 0, "|"))
        assertEquals("ਕਕ", PunjabiCtcFrames.decode(intArrayOf(2, 2) + intArrayOf(0, 2), labels, 0, "|"))
        assertEquals("", PunjabiCtcFrames.decode(intArrayOf(0, 0), labels, 0, "|"))
    }

    @Test fun normalizerHandlesDcAndSilenceWithoutNonfiniteValues() {
        assertTrue(PunjabiCtcFrames.normalize(FloatArray(400)).all { it == 0f })
        assertTrue(PunjabiCtcFrames.normalize(FloatArray(400) { .25f }).all { it == 0f })
        val pcm = FloatArray(400) { if (it % 2 == 0) .5f else -.5f }
        val normalized = PunjabiCtcFrames.normalize(pcm)
        assertEquals(.5f, pcm[0], 0f)
        assertEquals(.9999998f, normalized[0], .000001f)
        assertEquals(-.9999998f, normalized[1], .000001f)
        assertFalse(normalized.any { !it.isFinite() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlongRecordingsAreRejected() { PunjabiCtcFrames.windows(PunjabiCtcFrames.MAX_SAMPLES + 1) }

    @Test(expected = IllegalArgumentException::class)
    fun shortRecordingsAreRejected() { PunjabiCtcFrames.windows(399) }

    @Test(expected = IllegalArgumentException::class)
    fun invalidAudioIsRejected() { PunjabiCtcFrames.normalize(FloatArray(400) { Float.NaN }) }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangePcmIsRejected() { PunjabiCtcFrames.normalize(FloatArray(400) { 1.1f }) }

    @Test(expected = CancellationException::class)
    fun normalizationHonorsCancellation() {
        PunjabiCtcFrames.normalize(FloatArray(400)) { throw CancellationException("Cancelled") }
    }
}
