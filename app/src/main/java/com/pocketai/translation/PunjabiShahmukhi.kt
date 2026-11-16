package com.pocketai.translation

import java.text.Normalizer

/**
 * Original, deterministic Gurmukhi-to-Shahmukhi spelling conversion.
 *
 * This consumes Punjabi text produced by the Punjabi translation model; it does not translate
 * English or Urdu into Punjabi. Alphabet correspondence alone cannot recover every Shahmukhi
 * spelling (loanword letters, vowel length, schwa, dialect and tone are ambiguous). The small
 * spelling glossary below supplies common Punjabi function words and the tested reference terms.
 * Short i/u use Arabic vowel marks; inherent short a is normally unwritten. Retroflex n uses ݨ.
 *
 * Text outside the Gurmukhi block, numeric characters, spacing and punctuation are copied exactly.
 * Unsupported Gurmukhi signs or malformed clusters throw instead of disappearing. In particular,
 * the rare explicit tone mark U+0A51 is not silently erased. This is not clinical validation or
 * a claim of fluent-speaker spelling quality; the original source must remain available.
 */
object PunjabiShahmukhi {
    fun convert(text: String): String = buildString(text.length) {
        var position = 0
        while (position < text.length) {
            val current = text[position]
            if (!isGuruSign(current)) {
                append(current)
                position++
                continue
            }
            val start = position
            while (position < text.length && (isGuruSign(text[position]) || isJoiner(text[position]))) position++
            val original = text.substring(start, position)
            val normalized = Normalizer.normalize(original, Normalizer.Form.NFD)
            append(spellings[normalized] ?: convertWord(normalized))
        }
    }

    /** Digits/punctuation are preserved and do not mean an incomplete script conversion. */
    fun hasUnconvertedGuru(text: String): Boolean = text.any(::isGuruSign)

    private fun convertWord(word: String): String = buildString(word.length * 2) {
        var index = 0
        var geminateNext = false
        var vowelAllowed = false
        var previousConsonant = false
        var afterVirama = false
        while (index < word.length) {
            val char = word[index]
            when {
                char in consonants -> {
                    var letters = if (char == 'ਹ' && afterVirama) "ھ" else consonants.getValue(char)
                    if (word.getOrNull(index + 1) == NUKTA) {
                        letters = nuktaConsonants[char] ?: unsupported(NUKTA, "unsupported consonant/nukta combination")
                        index++
                    }
                    if (geminateNext) {
                        // The doubled sound is the consonant, not the aspiration marker ھ.
                        append(letters[0]); append('ّ'); append(letters.substring(1))
                        geminateNext = false
                    } else append(letters)
                    vowelAllowed = true
                    previousConsonant = true
                    afterVirama = false
                }
                char in independentVowels -> {
                    if (geminateNext) unsupported(ADDAK, "gemination must precede a consonant")
                    append(when {
                        isNotEmpty() && char == 'ਆ' -> "ا"
                        isNotEmpty() && char == 'ਈ' -> "ئی"
                        isNotEmpty() && char == 'ਇ' -> "ئِ"
                        isNotEmpty() && char == 'ਏ' -> "ئے"
                        else -> independentVowels.getValue(char)
                    })
                    vowelAllowed = false
                    previousConsonant = false
                }
                char == '\u0A72' || char == '\u0A73' -> {
                    if (geminateNext) unsupported(ADDAK, "gemination must precede a consonant")
                    append('ا') // An explicit vowel carrier; the following sign supplies its vowel.
                    vowelAllowed = true
                    previousConsonant = false
                    afterVirama = false
                }
                char in vowelSigns -> {
                    if (!vowelAllowed) unsupported(char, "vowel sign without a consonant or carrier")
                    val next = nextSignificant(word, index + 1)
                    append(when (char) {
                        '\u0A3E' -> "ا"
                        '\u0A3F' -> "ِ"
                        '\u0A40' -> "ی"
                        '\u0A41' -> "ُ"
                        '\u0A42', '\u0A4B', '\u0A4C' -> "و"
                        '\u0A47', '\u0A48' -> if (next == null) "ے" else "ی"
                        else -> unsupported(char)
                    })
                    vowelAllowed = false
                    previousConsonant = false
                }
                char == BINDI || char == TIPPI || char == ADAK_BINDI -> {
                    if (isEmpty()) unsupported(char, "nasal sign without a preceding sound")
                    val next = nextSignificant(word, index + 1)
                    append(when {
                        next == null -> 'ں'
                        next in labialConsonants -> 'م'
                        else -> 'ن'
                    })
                    if (char == ADAK_BINDI) {
                        if (next !in consonants || geminateNext) unsupported(char, "combined nasal/gemination sign needs a following consonant")
                        geminateNext = true
                    }
                    vowelAllowed = false
                    previousConsonant = false
                }
                char == ADDAK -> {
                    if (isEmpty() || geminateNext || nextSignificant(word, index + 1) !in consonants) unsupported(char, "gemination needs a following consonant")
                    geminateNext = true
                    vowelAllowed = false
                    previousConsonant = false
                }
                char == VIRAMA -> {
                    if (!previousConsonant || nextSignificant(word, index + 1) !in consonants) unsupported(char, "virama needs a consonant cluster")
                    // Inherent short a is already unwritten; no Arabic vowel is added to a cluster.
                    vowelAllowed = false
                    previousConsonant = false
                    afterVirama = true
                }
                char == '\u0A75' -> {
                    if (!previousConsonant) unsupported(char, "yakash needs a preceding consonant")
                    append('ی')
                    vowelAllowed = false
                    previousConsonant = true
                }
                isJoiner(char) -> append(char)
                else -> unsupported(char)
            }
            index++
        }
        if (geminateNext) unsupported(ADDAK, "unfinished gemination")
    }

    private fun nextSignificant(word: String, start: Int): Char? {
        var index = start
        while (index < word.length && isJoiner(word[index])) index++
        return word.getOrNull(index)
    }

    private fun unsupported(char: Char, reason: String = "no supported spelling correspondence"): Nothing =
        throw IllegalArgumentException("Unsupported Gurmukhi U+${char.code.toString(16).uppercase().padStart(4, '0')}: $reason. Keep the original text.")

    private fun isJoiner(char: Char) = char == '\u200C' || char == '\u200D'
    private fun isGuruSign(char: Char) = char in '\u0A00'..'\u0A7F' && char !in '\u0A66'..'\u0A6F' &&
        Character.getType(char) !in punctuationTypes

    private val punctuationTypes = setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())

    private const val NUKTA = '\u0A3C'
    private const val BINDI = '\u0A02'
    private const val TIPPI = '\u0A70'
    private const val ADDAK = '\u0A71'
    private const val ADAK_BINDI = '\u0A01'
    private const val VIRAMA = '\u0A4D'
    private val vowelSigns = setOf('ਾ', 'ਿ', 'ੀ', 'ੁ', 'ੂ', 'ੇ', 'ੈ', 'ੋ', 'ੌ')
    private val labialConsonants = setOf('ਪ', 'ਫ', 'ਬ', 'ਭ', 'ਮ')
    private val independentVowels = mapOf(
        'ਅ' to "ا", 'ਆ' to "آ", 'ਇ' to "اِ", 'ਈ' to "ای", 'ਉ' to "اُ", 'ਊ' to "او",
        'ਏ' to "اے", 'ਐ' to "ای", 'ਓ' to "او", 'ਔ' to "اَو"
    )
    private val consonants = mapOf(
        'ਕ' to "ک", 'ਖ' to "کھ", 'ਗ' to "گ", 'ਘ' to "گھ", 'ਙ' to "نگ",
        'ਚ' to "چ", 'ਛ' to "چھ", 'ਜ' to "ج", 'ਝ' to "جھ", 'ਞ' to "نی",
        'ਟ' to "ٹ", 'ਠ' to "ٹھ", 'ਡ' to "ڈ", 'ਢ' to "ڈھ", 'ਣ' to "ݨ",
        'ਤ' to "ت", 'ਥ' to "تھ", 'ਦ' to "د", 'ਧ' to "دھ", 'ਨ' to "ن",
        'ਪ' to "پ", 'ਫ' to "پھ", 'ਬ' to "ب", 'ਭ' to "بھ", 'ਮ' to "م",
        'ਯ' to "ی", 'ਰ' to "ر", 'ਲ' to "ل", 'ਵ' to "و", 'ੜ' to "ڑ", 'ਸ' to "س", 'ਹ' to "ہ"
    )
    private val nuktaConsonants = mapOf('ਕ' to "ق", 'ਖ' to "خ", 'ਗ' to "غ", 'ਜ' to "ز", 'ਫ' to "ف", 'ਸ' to "ش", 'ਲ' to "ل", 'ਡ' to "ڑ")

    // Exact whole-word Punjabi spellings. These entries add no sentences, advice or clinical claims.
    private val spellings = mapOf(
        "ਵਿੱਚ" to "وچ", "ਵਿਚ" to "وچ", "ਨਾ" to "نہ", "ਨਹੀਂ" to "نہیں",
        "ਹੈ" to "ہے", "ਹਨ" to "ہن", "ਹਾਂ" to "ہاں", "ਇਹ" to "ایہ", "ਉਹ" to "اوہ",
        "ਇਸ" to "اس", "ਉਸ" to "اس", "ਇੱਕ" to "اک", "ਇਕ" to "اک", "ਮੈਂ" to "میں",
        "ਅਸੀਂ" to "اسیں", "ਤੁਸੀਂ" to "تسیں", "ਤੁਹਾਡਾ" to "تہاڈا", "ਤੁਹਾਡੀ" to "تہاڈی", "ਤੁਹਾਡੇ" to "تہاڈے",
        "ਕਿਵੇਂ" to "کیویں", "ਲਈ" to "لئی", "ਨੂੰ" to "نوں", "ਤੋਂ" to "توں",
        "ਹੁੰਦਾ" to "ہوندا", "ਹੁੰਦੀ" to "ہوندی", "ਹੁੰਦੇ" to "ہوندے", "ਕਿਤਾਬ" to "کتاب",
        "ਦਵਾਈ" to "دوائی", "ਦਵਾਈਆਂ" to "دوائیاں", "ਖੁਰਾਕ" to "خوراک", "ਸਿਹਤ" to "صحت",
        "ਮਿਲੀਗ੍ਰਾਮ" to "ملیگرام", "ਪੈਰਾਸੀਟਾਮੋਲ" to "پیراسیٹامول"
    ).mapKeys { Normalizer.normalize(it.key, Normalizer.Form.NFD) }
}
