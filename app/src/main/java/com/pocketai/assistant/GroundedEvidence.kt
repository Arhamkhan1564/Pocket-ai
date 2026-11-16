package com.pocketai.assistant

import com.pocketai.data.KnowledgeRecord
import com.pocketai.data.SearchText
import java.util.Locale

/** Bounded source selection keeps complete sentences and the original provenance. */
internal object GroundedEvidence {
    fun reference(record: KnowledgeRecord, index: Int): String = buildString {
        append("[$index] #${record.id} ")
        append(record.sourceReference.ifBlank { record.question.ifBlank { "Saved knowledge" } })
        append(" · ${record.category}")
    }

    fun passage(record: KnowledgeRecord, query: String, maxChars: Int = 550): String {
        if (record.content.length <= maxChars) return record.content
        val terms = SearchText.terms(query).filter { it.length >= 3 }.toSet()
        val paragraphs = record.content.split(Regex("\\n\\s*\\n"))
        val candidates = paragraphs.flatMap { paragraph ->
            if (paragraph.length <= maxChars) listOf(paragraph.trim())
            else paragraph.split(Regex("(?<=[.!?۔])\\s+")).map(String::trim)
        }.filter { it.isNotBlank() && it.length <= maxChars }
        // No substring cut: a dose, unit, negation or warning must not be split mid-sentence.
        return candidates.maxByOrNull { line -> SearchText.terms(line).count { it in terms } } ?: record.content
    }

    fun sources(records: List<KnowledgeRecord>): String = records.mapIndexed { i, record -> reference(record, i + 1) }.joinToString("\n")

    fun exact(records: List<KnowledgeRecord>): String = records.mapIndexed { i, record ->
        if (records.size == 1 && record.source != "document") record.content
        else "${reference(record, i + 1)}\n${record.content}"
    }.joinToString("\n\n")

    fun hasUnsupportedNumbers(answer: String, original: String): Boolean = !numbers(original).containsAll(numbers(answer))
    fun hasUnsupportedIdentifiers(answer: String, original: String): Boolean = !identifiers(original).containsAll(identifiers(answer))
    fun preservesIdentifiers(original: String, output: String): Boolean = identifiers(original).all { output.contains(it) }

    fun safeTranslation(original: String, output: String, languageTag: String, medical: Boolean): Boolean {
        if (output.isBlank() || !MedicalGuidance.matchesRequestedScript(output, languageTag)) return false
        if (numbers(output) != numbers(original)) return false
        if (!preservesIdentifiers(original, output) || hasUnsupportedIdentifiers(output, original)) return false
        if (medical && (measurements(output) != measurements(original) || MedicalGuidance.containsPersonalPrescription(output))) return false
        if (medical && negation.containsMatchIn(original) && !negation.containsMatchIn(output)) return false
        return !Regex("\\[(\\d+)\\]").containsMatchIn(output) // A translation is not allowed to invent source indexes.
    }

    fun validCitations(answer: String, sourceCount: Int): Boolean = Regex("\\[(\\d+)\\]").findAll(answer)
        .all { match -> match.groupValues[1].toIntOrNull()?.let { it in 1..sourceCount } == true }

    private fun numbers(text: String): Set<String> = Regex("\\p{Nd}+(?:[.,٫]\\p{Nd}+)?").findAll(
        text.replace(Regex("\\[\\d+\\]"), "")
    ).map { canonicalNumber(it.value) }.toSet()

    private fun identifiers(text: String): Set<String> = Regex("(?<![\\p{L}\\p{Nd}])\\p{L}[\\p{L}\\p{Nd}_-]*\\p{Nd}[\\p{L}\\p{Nd}_-]*(?![\\p{L}\\p{Nd}])")
        .findAll(text).map { it.value }.toSet()

    private val negation = Regex("\\b(?:no|not|never|avoid|without|don't|cannot|can't|contraindicated)\\b|" +
        "نہیں|نہ |مت |اجتناب|پرہیز|بغیر|منع|ممنوع|ਨਹੀਂ|ਨਹੀ|ਨਾ |ਪਰਹੇਜ਼|ਬਿਨਾਂ|ਮਨਾਹੀ|ਵਰਜਿਤ", RegexOption.IGNORE_CASE)

    private fun canonicalNumber(value: String): String = buildString {
        value.forEach { character ->
            val number = Character.digit(character, 10)
            append(if (number >= 0) ('0'.code + number).toChar() else if (character == '٫') '.' else character)
        }
    }

    private fun measurements(text: String): Set<String> {
        val units = "micrograms?|mcg|μg|µg|milligrams?|mg|kilograms?|kg|grams?|g|millilit(?:er|re)s?|ml|lit(?:er|re)s?|l|iu|units?|" +
            "ملی\\s*گرام|مائیکرو\\s*گرام|کلو\\s*گرام|گرام|ملی\\s*لیٹر|لیٹر|" +
            "ਮਿਲੀ\\s*ਗ੍ਰਾਮ|ਮਾਈਕਰੋ\\s*ਗ੍ਰਾਮ|ਕਿਲੋ\\s*ਗ੍ਰਾਮ|ਗ੍ਰਾਮ|ਮਿਲੀ\\s*ਲੀਟਰ|ਲੀਟਰ"
        return Regex("(\\p{Nd}+(?:[.,٫]\\p{Nd}+)?)\\s*($units)(?!\\p{L})", RegexOption.IGNORE_CASE).findAll(text).map { match ->
            val unit = match.groupValues[2].lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
            val normalized = when (unit) {
                "mg", "milligram", "milligrams", "ملیگرام", "ਮਿਲੀਗ੍ਰਾਮ" -> "mg"
                "mcg", "μg", "µg", "microgram", "micrograms", "مائیکروگرام", "ਮਾਈਕਰੋਗ੍ਰਾਮ" -> "mcg"
                "kg", "kilogram", "kilograms", "کلوگرام", "ਕਿਲੋਗ੍ਰਾਮ" -> "kg"
                "g", "gram", "grams", "گرام", "ਗ੍ਰਾਮ" -> "g"
                "ml", "milliliter", "milliliters", "millilitre", "millilitres", "ملیلیٹر", "ਮਿਲੀਲੀਟਰ" -> "ml"
                "l", "liter", "liters", "litre", "litres", "لیٹر", "ਲੀਟਰ" -> "l"
                else -> unit
            }
            canonicalNumber(match.groupValues[1]) + normalized
        }.toSet()
    }
}
