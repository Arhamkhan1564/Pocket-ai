package com.pocketai.translation

import org.junit.Assert.*
import org.junit.Test

class PunjabiShahmukhiTest {
    @Test fun acceptedPunjabiNmtFixtureKeepsItsFactsAndNegationInScriptConversion() {
        val original = "ਗੋਲੀ ਵਿੱਚ 250 ਮਿਲੀਗ੍ਰਾਮ ਪੈਰਾਸੀਟਾਮੋਲ ਹੁੰਦਾ ਹੈ। ਗਰਭ ਅਵਸਥਾ ਦੌਰਾਨ ਇਸ ਦੀ ਵਰਤੋਂ ਨਾ ਕਰੋ।"
        val converted = PunjabiShahmukhi.convert(original)
        assertEquals("گولی وچ 250 ملیگرام پیراسیٹامول ہوندا ہے। گربھ اوستھا دوران اس دی ورتوں نہ کرو।", converted)
        assertFalse(PunjabiShahmukhi.hasUnconvertedGuru(converted))
        assertTrue(converted.contains("نہ کرو"))
    }

    @Test fun commonPunjabiSentencesRemainPunjabiInsteadOfBecomingUrduTranslations() {
        assertEquals("میں پنجابی بولدا ہاں।", PunjabiShahmukhi.convert("ਮੈਂ ਪੰਜਾਬੀ ਬੋਲਦਾ ਹਾਂ।"))
        assertEquals("ایہ اک کتاب ہے।", PunjabiShahmukhi.convert("ਇਹ ਇੱਕ ਕਿਤਾਬ ਹੈ।"))
        assertEquals("تسیں کیویں ہو?", PunjabiShahmukhi.convert("ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ?"))
        assertEquals("اس دی دوائی نہیں।", PunjabiShahmukhi.convert("ਇਸ ਦੀ ਦਵਾਈ ਨਹੀਂ।"))
    }

    @Test fun latinMedicineNamesCodesAllDigitsEmojiAndPunctuationAreCopiedExactly() {
        val original = "ਗੋਲੀ੶\tParacetamol 250mg cedar-47 C3PO NaCl 500.25 ۱۲۳ ١٢٣ ੧੨੩\n(ਨਾ ਕਰੋ!) 🧪🙂 Cafe\u0301 — #42।"
        val converted = PunjabiShahmukhi.convert(original)
        listOf("Paracetamol", "250mg", "cedar-47", "C3PO", "NaCl", "500.25", "۱۲۳", "١٢٣", "੧੨੩", "🧪🙂", "Cafe\u0301").forEach {
            assertTrue("Literal changed: $it", converted.contains(it))
        }
        assertEquals(original.filter(Char::isWhitespace), converted.filter(Char::isWhitespace))
        assertEquals(punctuation(original), punctuation(converted))
        assertFalse(PunjabiShahmukhi.hasUnconvertedGuru(converted))
    }

    @Test fun aspiratesNuktaAndCanonicalUnicodeVariantsHaveExplicitMappings() {
        assertEquals("کھ گھ چھ جھ ٹھ ڈھ تھ دھ پھ بھ", PunjabiShahmukhi.convert("ਖ ਘ ਛ ਝ ਠ ਢ ਥ ਧ ਫ ਭ"))
        val composed = PunjabiShahmukhi.convert("ਖ਼ ਗ਼ ਜ਼ ਫ਼ ਸ਼ ਲ਼")
        assertEquals("خ غ ز ف ش ل", composed)
        assertEquals(composed, PunjabiShahmukhi.convert("ਖ਼ ਗ਼ ਜ਼ ਫ਼ ਸ਼ ਲ਼"))
        assertEquals("ٹ ڈ ݨ ڑ ق", PunjabiShahmukhi.convert("ਟ ਡ ਣ ੜ ਕ਼"))
    }

    @Test fun nasalAssimilationGeminationAndConsonantClustersAreHandled() {
        assertEquals("اکّھ امب انگ پنجاب ہاں", PunjabiShahmukhi.convert("ਅੱਖ ਅੰਬ ਅੰਗ ਪੰਜਾਬ ਹਾਂ"))
        assertEquals("پربھاو کرم", PunjabiShahmukhi.convert("ਪ੍ਰਭਾਵ ਕ੍ਰਮ"))
        assertEquals("دے دیکھو", PunjabiShahmukhi.convert("ਦੇ ਦੇਖੋ"))
        assertEquals("ک\u200Dر", PunjabiShahmukhi.convert("ਕ੍\u200Dਰ"))
        assertEquals("آئی گئی گولیاں پڑھائی", PunjabiShahmukhi.convert("ਆਈ ਗਈ ਗੋਲੀਆਂ ਪੜ੍ਹਾਈ"))
    }

    @Test fun unsupportedSignsAndBrokenClustersAreReportedRatherThanDropped() {
        val tone = assertThrows(IllegalArgumentException::class.java) { PunjabiShahmukhi.convert("ਗੋਲੀੑ 🧪") }
        assertTrue(tone.message!!.contains("U+0A51"))
        assertTrue(PunjabiShahmukhi.hasUnconvertedGuru("ਅਣਜਾਣੑ"))
        assertThrows(IllegalArgumentException::class.java) { PunjabiShahmukhi.convert("ੱਕ") }
        assertThrows(IllegalArgumentException::class.java) { PunjabiShahmukhi.convert("ਕ੍") }
        assertThrows(IllegalArgumentException::class.java) { PunjabiShahmukhi.convert("ਕ਼਼") }
        assertThrows(IllegalArgumentException::class.java) { PunjabiShahmukhi.convert("ਿ") }
    }

    @Test fun nonGurmukhiTextIsNeverRelabeledOrModified() {
        val literals = listOf("Urdu is not Punjabi: دوا نہ لیں۔", "ASCII cedar-47 250 mg", "", "۱۲۳ ١٢٣ ੧੨੩ 🧪")
        literals.forEach { assertEquals(it, PunjabiShahmukhi.convert(it)) }
    }

    private fun punctuation(value: String) = value.filter { char -> Character.getType(char) in setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt()) }
}
