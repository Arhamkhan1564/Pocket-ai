package com.pocketai.data

import org.junit.Assert.*
import org.junit.Test

class SearchTextTest {
    @Test fun curatedExpansionAddsSearchKeywordsButNeverChoosesMedicationForSymptoms() {
        val query = SearchText.keywordQuery("مجھے سر میں درد ہے کونسی دوا لوں")
        assertTrue("headache" in query.terms)
        assertFalse("paracetamol" in query.terms)
        assertFalse("ibuprofen" in query.terms)
        assertFalse("medicine" in query.primaryTerms)
        assertTrue(query.terms.size <= 64)
        assertTrue(SearchText.keywordQuery("what is").terms.isEmpty())
    }

    @Test fun namedMedicineAndGurmukhiQueriesExpandWithoutClaimingTranslation() {
        assertTrue("paracetamol" in SearchText.keywordQuery("ਪੈਰਾਸੀਟਾਮੋਲ ਦੀ ਖੁਰਾਕ").terms)
        assertTrue("fever" in SearchText.keywordQuery("mainu bukhar hai").terms)
        assertTrue("cough" in SearchText.keywordQuery("ਖੰਘ ਲਈ ਦਵਾਈ").terms)
        assertEquals(listOf("unknownword"), SearchText.keywordQuery("unknownword").terms)
    }

    @Test fun normalizesUnicodeVariantsForSearchWithoutMutatingInput() {
        val original = "  كِتاب  يہ ہے۔  "
        assertEquals("کتاب یہ ہے", SearchText.normalize(original))
        assertEquals("  كِتاب  يہ ہے۔  ", original)
        assertEquals("photosynthesis stores energy", SearchText.normalize("Photosynthesis—stores\nenergy!"))
    }

    @Test fun preservesManualPunjabiForAmbiguousArabicScript() {
        assertEquals("pa", SearchText.detectedLanguage("پاکستان", "pa"))
        assertEquals("ur", SearchText.detectedLanguage("پاکستان", "en"))
        assertEquals("ar", SearchText.detectedLanguage("العربية", "ar"))
        assertEquals("fa", SearchText.detectedLanguage("فارسی", "fa"))
        assertEquals("pa", SearchText.detectedLanguage("ਪੰਜਾਬੀ", "en"))
    }
}
