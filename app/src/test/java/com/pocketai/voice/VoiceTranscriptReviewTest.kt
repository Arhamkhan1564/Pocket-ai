package com.pocketai.voice

import com.pocketai.translation.PunjabiShahmukhi
import org.junit.Assert.*
import org.junit.Test

class VoiceTranscriptReviewTest {
    private val shahmukhi = VoiceRecordingRequest("pa-Arab-PK", VoiceTranscriptDestination.CHAT)

    @Test fun dominantGuruConvertsForManualDisplayWithRawAndLiteralsIntact() {
        val raw = "  ਇਹ ਮੇਰੀ ਦਵਾਈ ਹੈ ਅਤੇ ਇਸ ਨੂੰ ਇੱਥੇ ਹੀ ਰੱਖਣਾ ਹੈ। ਇਹ ਮੇਰੀ ਆਪਣੀ ਗੱਲ ਹੈ। Paracetamol cedar-47 C3PO 250mg ۱۲۳ ੧੨੩ 🧪\n"
        val review = VoiceTranscriptReview.prepare(raw, shahmukhi)
        assertTrue(review.scriptConverted)
        assertTrue(review.needsDialog)
        assertFalse(review.conversionFailed)
        assertEquals(raw, review.rawText)
        assertEquals(PunjabiShahmukhi.convert(raw), review.displayText)
        listOf("Paracetamol", "cedar-47", "C3PO", "250mg", "۱۲۳", "੧੨੩", "🧪").forEach { literal ->
            assertTrue("Literal changed: $literal", review.displayText.contains(literal))
        }
        assertEquals(raw.filter(Char::isWhitespace), review.displayText.filter(Char::isWhitespace))
        assertTrue(review.notice!!.contains("converted"))
        assertEquals(shahmukhi, review.request)
    }

    @Test fun requestedLanguageAndCapturedScriptDecideConversion() {
        val raw = "ਮੈਂ ਪੰਜਾਬੀ ਬੋਲਦਾ ਹਾਂ।"
        listOf("ur-PK", "hi-IN", "en", "pa-Guru-IN", "pa").forEach { tag ->
            val request = VoiceRecordingRequest(tag, VoiceTranscriptDestination.TRAINING)
            val review = VoiceTranscriptReview.prepare(raw, request)
            assertEquals(raw, review.displayText)
            assertEquals(raw, review.rawText)
            assertFalse(review.scriptConverted)
            assertFalse(review.needsDialog)
            assertEquals(VoiceTranscriptDestination.TRAINING, review.request.destination)
        }
        assertTrue(VoiceTranscriptReview.prepare(raw, shahmukhi.copy(languageTag = "pa-ARAB-pk")).scriptConverted)
    }

    @Test fun capturedRequestIsIndependentOfLaterLanguageChoice() {
        var currentTag = "pa-Arab-PK"
        var currentDestination = VoiceTranscriptDestination.TRAINING
        val captured = VoiceRecordingRequest(currentTag, currentDestination)
        currentTag = "ur-PK"
        currentDestination = VoiceTranscriptDestination.CHAT
        val review = VoiceTranscriptReview.prepare("ਇਹ ਇੱਕ ਕਿਤਾਬ ਹੈ।", captured)
        assertTrue(review.scriptConverted)
        assertEquals("pa-Arab-PK", review.request.languageTag)
        assertEquals(VoiceTranscriptDestination.TRAINING, review.request.destination)
        assertEquals("ur-PK", currentTag)
        assertEquals(VoiceTranscriptDestination.CHAT, currentDestination)
    }

    @Test fun latinOnlyOtherLanguagesAndMixedArabicOrDevanagariRemainRaw() {
        listOf("This is English 250mg", "دوا نہ لیں۔", "यह हिन्दी है।", "ਇਹ دوا ਹੈ", "ਇਹ दवा ਹੈ", "ਇਹ Ελληνικά ਹੈ", "ਇਹ 𐐀 ਹੈ").forEach { raw ->
            val review = VoiceTranscriptReview.prepare(raw, shahmukhi)
            assertEquals(raw, review.rawText)
            assertEquals(raw, review.displayText)
            assertFalse(review.scriptConverted)
            assertFalse(review.needsDialog)
            assertNotNull(review.notice)
        }
    }

    @Test fun dominanceRequiresTwoGuruLettersAndAtLeastAsManyAsLatin() {
        assertFalse(VoiceTranscriptReview.prepare("ਕ 123", shahmukhi).scriptConverted)
        assertFalse(VoiceTranscriptReview.prepare("ਇਹ English speech dominates", shahmukhi).scriptConverted)
        assertTrue(VoiceTranscriptReview.prepare("ਕਰ AB 123", shahmukhi).scriptConverted)
        assertTrue(VoiceTranscriptReview.prepare("ਕਰ ABC 123", shahmukhi).notice != null)
        assertFalse(VoiceTranscriptReview.prepare("ਕਰ ABC 123", shahmukhi).scriptConverted)
    }

    @Test fun bareOrRepeatedLanguageHintIsNotConverted() {
        listOf("ਪੰਜਾਬੀ", "ਪੰਜਾਬੀ ਪੰਜਾਬੀ", "ਪੰਜਾਬੀ 123", "ਪੰਜਾਬੀ C3PO", "ਪੰਜਾਬੀ English words dominate").forEach { raw ->
            val review = VoiceTranscriptReview.prepare(raw, shahmukhi)
            assertEquals(raw, review.displayText)
            assertFalse(review.scriptConverted)
        }
    }

    @Test fun unsupportedGuruFallsBackToWholeRawInsteadOfPartialConversion() {
        val raw = "ਇਹ ਗੋਲੀੑ ਹੈ 250mg।"
        val review = VoiceTranscriptReview.prepare(raw, shahmukhi)
        assertFalse(review.scriptConverted)
        assertTrue(review.conversionFailed)
        assertTrue(review.needsDialog)
        assertEquals(raw, review.rawText)
        assertEquals(raw, review.displayText)
        assertTrue(review.notice!!.contains("original recognition"))
    }
}
