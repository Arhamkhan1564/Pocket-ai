package com.pocketai.voice

import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPresenceKernelTest {
    @Test fun emptySpeechProbabilitiesAndShortTransientDoNotBecomeSpeech() {
        assertFalse(SpeechPresenceDecision.hasSpeech(FloatArray(32), 32 * 512))
        val transient = FloatArray(32)
        transient[2] = .9f
        assertFalse(SpeechPresenceDecision.hasSpeech(transient, transient.size * 512))
    }

    @Test fun sustainedSpeechIsAcceptedAtReferenceDuration() {
        assertFalse(SpeechPresenceDecision.hasSpeech(FloatArray(8) { .8f }, 4_000))
        assertTrue(SpeechPresenceDecision.hasSpeech(FloatArray(8) { .8f }, 4_001))
        assertTrue(SpeechPresenceDecision.hasSpeech(FloatArray(8) { .8f }, 4_096))
    }

    @Test fun trailingSilenceCannotMakeAnIsolatedTransientLongEnough() {
        val probabilities = FloatArray(20)
        for (index in 0..5) probabilities[index] = .9f
        assertFalse(SpeechPresenceDecision.hasSpeech(probabilities, probabilities.size * 512))
    }

    @Test fun shortGapsWithinSpeechAreNotTreatedAsSeparateUtterances() {
        val probabilities = FloatArray(16) { .7f }
        for (index in 4..6) probabilities[index] = .1f
        assertTrue(SpeechPresenceDecision.hasSpeech(probabilities, probabilities.size * 512))
    }

    @Test fun validEndedSegmentIsAccepted() {
        val probabilities = FloatArray(24)
        for (index in 1..10) probabilities[index] = .75f
        assertTrue(SpeechPresenceDecision.hasSpeech(probabilities, probabilities.size * 512))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFinitePcmIsRejected() { SpeechPresenceDecision.validatePcm(floatArrayOf(Float.NaN)) }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangePcmIsRejected() { SpeechPresenceDecision.validatePcm(floatArrayOf(1.01f)) }

    @Test(expected = IllegalArgumentException::class)
    fun overlongPcmIsRejected() { SpeechPresenceDecision.validatePcm(FloatArray(SpeechPresenceDecision.MAX_SAMPLES + 1)) }

    @Test(expected = IllegalArgumentException::class)
    fun malformedFrameCountIsRejected() { SpeechPresenceDecision.hasSpeech(FloatArray(1), 1024) }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteProbabilityIsRejected() { SpeechPresenceDecision.hasSpeech(floatArrayOf(Float.NaN), 512) }

    @Test(expected = CancellationException::class)
    fun inputValidationHonorsCancellation() {
        SpeechPresenceDecision.validatePcm(FloatArray(4097)) { throw CancellationException("Cancelled") }
    }
}
