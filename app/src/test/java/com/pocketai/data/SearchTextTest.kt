package com.pocketai.data

import org.junit.Assert.*
import org.junit.Test

class SearchTextTest {
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
