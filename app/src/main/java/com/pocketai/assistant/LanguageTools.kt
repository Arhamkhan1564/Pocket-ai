package com.pocketai.assistant

import java.text.Normalizer

data class LanguageOption(val tag: String, val name: String, val nativeName: String)

object LanguageTools {
    val optionalLanguages = listOf(
        LanguageOption("en", "English", "English"),
        LanguageOption("hi", "Hindi", "हिन्दी"),
        LanguageOption("ar", "Arabic", "العربية"),
        LanguageOption("fa", "Persian", "فارسی"),
        LanguageOption("es", "Spanish", "Español"),
        LanguageOption("fr", "French", "Français"),
        LanguageOption("de", "German", "Deutsch"),
        LanguageOption("zh", "Chinese", "中文")
    )
    fun configured(third: String) = listOf(
        LanguageOption("ur", "Urdu", "اردو"),
        LanguageOption("pa", "Punjabi", "پنجابی / ਪੰਜਾਬੀ"),
        optionalLanguages.firstOrNull { it.tag == third } ?: optionalLanguages.first()
    )
    fun name(tag: String) = (configured(tag) + optionalLanguages).firstOrNull { it.tag == tag }?.name ?: tag
    fun isRtl(text: String) = text.firstOrNull { Character.isLetter(it) }?.let {
        Character.getDirectionality(it) in setOf(Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC)
    } ?: false
    /** Arabic script cannot reliably distinguish Punjabi and Urdu; keep manual selection. */
    fun detectScript(text: String, selectedLanguage: String, punjabiScript: String): Pair<String, String> = when {
        text.any { it in '\u0A00'..'\u0A7F' } -> "pa" to "Guru"
        text.any { it in '\u0600'..'\u06FF' } -> (if (selectedLanguage in setOf("pa", "ur", "ar", "fa")) selectedLanguage else "ur") to "Arab"
        selectedLanguage == "pa" -> "pa" to punjabiScript
        else -> selectedLanguage to if (text.any { it in '\u0900'..'\u097F' }) "Deva" else "Latn"
    }
    fun speechTag(language: String, script: String) = when (language) {
        "pa" -> if (script == "Guru") "pa-Guru-IN" else "pa-Arab-PK"
        "ur" -> "ur-PK"
        else -> language
    }
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
        .replace('ي', 'ی').replace('ك', 'ک').trim()
    fun unknown(language: String, script: String = "Arab") = when (language) {
        "ur" -> "اس سوال کے لیے کوئی متعلقہ محفوظ معلومات نہیں ملیں۔ ٹریننگ موڈ میں معلومات شامل کریں، یا مقامی AI ماڈل درآمد کریں۔"
        "pa" -> if (script == "Guru") "ਇਸ ਸਵਾਲ ਲਈ ਸੰਬੰਧਿਤ ਸੰਭਾਲੀ ਜਾਣਕਾਰੀ ਨਹੀਂ ਮਿਲੀ। ਟ੍ਰੇਨਿੰਗ ਮੋਡ ਵਿੱਚ ਜਾਣਕਾਰੀ ਜੋੜੋ ਜਾਂ ਸਥਾਨਕ AI ਮਾਡਲ ਇੰਪੋਰਟ ਕਰੋ।" else "اس سوال لئی متعلقہ محفوظ معلومات نہیں لبھیاں۔ ٹریننگ موڈ وچ معلومات شامل کرو یا مقامی AI ماڈل درآمد کرو۔"
        else -> "I couldn’t find matching saved knowledge. Teach me in Training Mode, or import a compatible local AI model in Resources."
    }
}
