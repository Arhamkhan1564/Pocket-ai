package com.pocketai.voice

import com.pocketai.translation.PunjabiShahmukhi
import java.text.Normalizer
import java.util.Locale

enum class VoiceTranscriptDestination { CHAT, TRAINING }

/** Immutable choices for one recording; later settings changes must not reinterpret it. */
data class VoiceRecordingRequest(val languageTag: String, val destination: VoiceTranscriptDestination)

/** Transient text for manual review, not a language-detection or recognition-accuracy result. */
data class VoiceTranscriptReview(
    val request: VoiceRecordingRequest,
    val rawText: String,
    val displayText: String,
    val scriptConverted: Boolean = false,
    val conversionFailed: Boolean = false,
    val notice: String? = null,
) {
    val needsDialog: Boolean get() = scriptConverted || conversionFailed

    companion object {
        /** Conversion is presentation only. Other scripts and all raw text remain untouched. */
        fun prepare(rawText: String, request: VoiceRecordingRequest): VoiceTranscriptReview {
            fun unchanged(notice: String? = null) = VoiceTranscriptReview(request, rawText, rawText, notice = notice)
            val tag = request.languageTag.lowercase(Locale.ROOT).split('-')
            if (tag.firstOrNull() != "pa" || "arab" !in tag) return unchanged()

            var guru = 0
            var latin = 0
            var other = 0
            var position = 0
            while (position < rawText.length) {
                val codePoint = Character.codePointAt(rawText, position)
                if (Character.isLetter(codePoint)) when {
                    codePoint in 0x0A00..0x0A7F -> guru++
                    isLatinLetter(codePoint) -> latin++
                    else -> other++
                }
                position += Character.charCount(codePoint)
            }
            if (other > 0 || guru < 2 || guru < latin || onlyKnownHint(rawText)) {
                return unchanged("Original recognition kept without script conversion. Review its words, language and script before use.")
            }
            return try {
                val converted = PunjabiShahmukhi.convert(rawText)
                require(!PunjabiShahmukhi.hasUnconvertedGuru(converted))
                VoiceTranscriptReview(request, rawText, converted, scriptConverted = true,
                    notice = "Display converted from Gurmukhi to Shahmukhi. This does not verify speech recognition; check words and numbers.")
            } catch (_: IllegalArgumentException) {
                VoiceTranscriptReview(request, rawText, rawText, conversionFailed = true,
                    notice = "Shahmukhi display conversion could not be completed. The original recognition is kept; review or correct it.")
            }
        }

        private fun isLatinLetter(codePoint: Int): Boolean =
            codePoint in 0x0041..0x005A || codePoint in 0x0061..0x007A || codePoint in 0x00C0..0x024F ||
                codePoint in 0x1E00..0x1EFF || codePoint in 0x2C60..0x2C7F ||
                codePoint in 0xA720..0xA7FF || codePoint in 0xAB30..0xAB6F ||
                codePoint in 0xFF21..0xFF3A || codePoint in 0xFF41..0xFF5A

        /** A repeated bare language hint is not sufficient evidence to offer converted recognition. */
        private fun onlyKnownHint(text: String): Boolean {
            val words = Regex("[\\u0A00-\\u0A7F]+").findAll(text.replace("\u200C", "").replace("\u200D", ""))
                .map { it.value.filterNot(Char::isDigit) }.filter { word -> word.any(Char::isLetter) }.toList()
            return words.isNotEmpty() && words.all { Normalizer.normalize(it, Normalizer.Form.NFC) == "ਪੰਜਾਬੀ" }
        }
    }
}
