package com.pocketai.assistant

import org.junit.Assert.*
import org.junit.Test

class LanguageToolsTest {
    @Test fun englishRemainsAvailableAlongsideUrduPunjabiAndOptionalLanguage() {
        assertEquals(listOf("en", "ur", "pa", "fr"), LanguageTools.configured("fr").map { it.tag })
        assertEquals(listOf("en", "ur", "pa"), LanguageTools.configured("unknown").map { it.tag })
        assertEquals(listOf("en", "ur", "pa"), LanguageTools.configured("en").map { it.tag })
    }

    @Test fun identifiesScriptsWhileKeepingAmbiguousManualLanguage() {
        assertEquals("pa" to "Guru", LanguageTools.detectScript("ਪੰਜਾਬੀ", "en", "Arab"))
        assertEquals("pa" to "Arab", LanguageTools.detectScript("پنجابی", "pa", "Guru"))
        assertEquals("ur" to "Arab", LanguageTools.detectScript("اردو", "ur", "Arab"))
        assertEquals("ar" to "Arab", LanguageTools.detectScript("العربية", "ar", "Arab"))
        assertEquals("fa" to "Arab", LanguageTools.detectScript("فارسی", "fa", "Arab"))
        assertEquals("hi" to "Deva", LanguageTools.detectScript("हिन्दी", "hi", "Arab"))
    }

    @Test fun separatesPunjabiVoiceLocalesAndSupportsRtl() {
        assertEquals("pa-Guru-IN", LanguageTools.speechTag("pa", "Guru"))
        assertEquals("pa-Arab-PK", LanguageTools.speechTag("pa", "Arab"))
        assertEquals("ur-PK", LanguageTools.speechTag("ur", "Arab"))
        assertTrue(LanguageTools.isRtl("  اردو"))
        assertFalse(LanguageTools.isRtl("ਪੰਜਾਬੀ"))
        assertFalse(LanguageTools.isRtl("English"))
    }
}
