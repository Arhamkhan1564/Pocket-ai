package com.pocketai.data

import java.text.Normalizer
import java.util.Locale

data class KnowledgeDraft(
    val content: String,
    val question: String = "",
    val language: String = "en",
    val script: String = "Latn",
    val category: String = "General",
    val source: String = "text",
    val sourceReference: String = "",
    val confidence: Double? = null,
)

data class KnowledgeRecord(
    val id: Long,
    val content: String,
    val question: String,
    val language: String,
    val script: String,
    val category: String,
    val source: String,
    val sourceReference: String,
    val confidence: Double?,
    val createdAt: Long,
    val updatedAt: Long,
    val verified: Boolean,
) {
    fun toDraft() = KnowledgeDraft(content, question, language, script, category, source, sourceReference, confidence)
}

data class ConversationRecord(val id: Long, val role: String, val content: String, val language: String, val sources: String, val createdAt: Long)
data class TrainingEvent(val id: Long, val knowledgeId: Long?, val action: String, val details: String, val createdAt: Long)

/** Search normalization only. The administrator's original script and text are never overwritten. */
object SearchText {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]"), "")
        .replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک')
        .replace(Regex("[\\p{P}\\p{S}\\p{Z}\\s]+"), " ").trim()

    fun terms(value: String): List<String> = normalize(value).split(' ').filter { it.isNotBlank() }.distinct().take(20)

    fun detectedLanguage(text: String, fallback: String = "en"): String = when {
        text.any { it in '\u0A00'..'\u0A7F' } -> "pa"
        text.any { it in '\u0600'..'\u06FF' } -> if (fallback in setOf("pa", "ur", "ar", "fa")) fallback else "ur"
        else -> fallback
    }
}
