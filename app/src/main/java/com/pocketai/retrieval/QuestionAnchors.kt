package com.pocketai.retrieval

import com.pocketai.data.SearchText

/**
 * Conservative subject matching for code/access-phrase reference questions.
 *
 * Callers supply the original source for a Latin question, or an actual local
 * forward translation in the question's language and script. This helper never
 * translates, guesses topic synonyms, or treats a generic code field as a topic.
 * Every retained subject token must occur in that evidence; a match alone does
 * not establish factual entailment or replace source/field validation.
 */
object QuestionAnchors {
    private const val MAX_QUESTION_CHARACTERS = 2000
    private const val MAX_CLASSIFICATION_CHARACTERS = 4000
    private const val MAX_QUESTION_TOKENS = 64
    private const val MAX_TOPIC_TOKENS = 16
    private const val MAX_EVIDENCE_CHARACTERS = 100_000

    fun isCodeQuestion(question: String): Boolean {
        if (question.isBlank()) return false
        // Keep the field classification when extraction is over budget, so a
        // caller can reject empty anchors rather than bypass code validation.
        val words = SearchText.normalize(question.take(MAX_CLASSIFICATION_CHARACTERS)).split(' ').filter(String::isNotBlank)
        val normalized = words.joinToString(" ")
        if (specificCredentialFields.any { SearchText.containsTerm(normalized, it) }) return false
        return words.any { it in codeFields } ||
            (words.any { it in accessFields } && words.any { it in phraseFields })
    }

    fun topicWords(question: String): List<String> {
        if (!isCodeQuestion(question)) return emptyList()
        val words = words(question) ?: return emptyList()
        val topics = words.filter { token ->
            token !in functionAndFieldWords &&
                // Reuse the search layer's existing stopwords without expanding
                // its concepts or silently truncating this whole question.
                SearchText.keywordQuery(token).literalTerms.contains(token)
        }.distinct()
        if (topics.size > MAX_TOPIC_TOKENS || topics.none { token -> token.any(Char::isLetter) }) return emptyList()
        return topics
    }

    fun matches(question: String, evidenceInQuestionLanguage: String): Boolean {
        val topics = topicWords(question)
        if (topics.isEmpty() || evidenceInQuestionLanguage.length > MAX_EVIDENCE_CHARACTERS) return false
        val script = questionScript(question)
        if (script == "Unknown" || (script != "Latn" && !hasScript(evidenceInQuestionLanguage, script))) return false
        val normalized = SearchText.normalize(evidenceInQuestionLanguage)
        return topics.all { SearchText.containsTerm(normalized, it) }
    }

    /** Arabic script does not distinguish Urdu from Punjabi; the caller chooses the language. */
    fun questionScript(question: String): String {
        val guru = question.count { it in '\u0A00'..'\u0A7F' && it.isLetter() }
        val arab = question.count { it in '\u0600'..'\u06FF' && it.isLetter() }
        if (guru > 0 && guru >= arab) return "Guru"
        if (arab > 0) return "Arab"
        return if (question.any { it.isLetter() && it.code <= 0x024F }) "Latn" else "Unknown"
    }

    private fun words(question: String): List<String>? {
        if (question.isBlank() || question.length > MAX_QUESTION_CHARACTERS) return null
        return SearchText.normalize(question).split(' ').filter(String::isNotBlank)
            .takeIf { it.size <= MAX_QUESTION_TOKENS }
    }

    private fun hasScript(text: String, script: String): Boolean = text.any {
        it.isLetter() && when (script) { "Guru" -> it in '\u0A00'..'\u0A7F'; "Arab" -> it in '\u0600'..'\u06FF'; else -> false }
    }

    private fun normalizedSet(vararg text: String): Set<String> = text.flatMap { SearchText.normalize(it).split(' ') }.filter(String::isNotBlank).toSet()
    private val codeFields = normalizedSet("code codes", "کوڈ کوڈز", "ਕੋਡ ਕੋਡਾਂ")
    private val accessFields = normalizedSet("access", "رسائی", "ਪਹੁੰਚ")
    private val phraseFields = normalizedSet("phrase phrases passphrase passphrases", "جملہ جملے فقرہ فقرے عبارت", "ਵਾਕ ਵਾਕਾਂ")
    private val specificCredentialFields = setOf(
        "pin", "pins", "passcode", "passcodes", "password", "passwords", "wifi", "wi fi", "ssid",
        "پن", "پاس ورڈ", "وائی فائی", "ਪਿਨ", "ਪਿੰਨ", "ਪਾਸਵਰਡ", "ਵਾਈਫਾਈ", "ਵਾਈ ਫਾਈ",
    ).map(SearchText::normalize).toSet()

    // Field words and grammatical/request words only. No places, institutions,
    // medicines, topic names, or translation dictionary are supplied here.
    private val functionAndFieldWords = normalizedSet(
        "code codes access entry entrance unlock unlocks unlocking open opens opening phrase phrases passphrase passphrases secret security credential credentials",
        "document documents saved reference references text passage passages according belong belongs belonging used use using called say says does will would could may might find show needed need required correct valid current s",
        "کوڈ کوڈز رسائی داخل داخلہ داخلے کھولنے کھولتا کھولیں والا والے والی بتاؤ دکھائیں درکار ضروری دستاویز دستاویزات محفوظ حوالہ حوالے عبارت متن جملہ جملے فقرہ فقرے الفاظ لفظ کس کیسا کون کونسا کونسی",
        "لئی واسطے دسو دس کیہ کیہڑا کیڑا کیہڑی کدا ہون ہوون داخلا کھولن کھلن لگدا لگن ہوئے ایہہ ایہ تے کہندا کہندے کہندی",
        "ਕੋਡ ਕੋਡਾਂ ਪਹੁੰਚ ਦਾਖਲਾ ਦਾਖਲੇ ਦਾਖ਼ਲਾ ਦਾਖ਼ਲੇ ਵਾਕ ਵਾਕਾਂ ਸ਼ਬਦ ਲਫ਼ਜ਼ ਖੋਲ੍ਹਦਾ ਖੋਲ੍ਹਦੀ ਖੋਲ੍ਹਦੇ ਖੋਲ੍ਹਣ ਖੁੱਲ੍ਹਦਾ ਲੋੜ ਲੋੜੀਂਦਾ ਚਾਹੀਦਾ ਚਾਹੀਦੀ ਚਾਹੀਦੇ ਦੱਸ ਦਿਉ ਦਿਖਾਓ ਸੰਭਾਲਿਆ ਸੰਭਾਲੇ ਸੁਰੱਖਿਆ ਹਵਾਲਾ ਹਵਾਲੇ ਦਸਤਾਵੇਜ਼ ਦਸਤਾਵੇਜ਼ਾਂ ਇਹ ਉਹ ਇਸ ਉਸ ਦਾ ਦੀ ਦੇ ਕਿਵੇਂ ਕਹਿੰਦਾ ਕਹਿੰਦੀ ਕਹਿੰਦੇ",
    ) + codeFields + accessFields + phraseFields
}
