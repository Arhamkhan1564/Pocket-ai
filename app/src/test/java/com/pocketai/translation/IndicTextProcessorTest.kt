package com.pocketai.translation

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class IndicTextProcessorTest {
    private fun fixtures() = javaClass.getResourceAsStream("/indic-text-processor-fixtures.tsv")!!.bufferedReader().use { it.readLines() }.map { it.split('\t') }
    private fun decode(text: String) = String(Base64.getDecoder().decode(text), Charsets.UTF_8)

    @Test fun englishPreprocessingMatchesOfficialToolkitFixtures() {
        val cases = fixtures().filter { it[0] == "PRE" }
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val prepared = IndicTextProcessor.preprocessEnglish(decode(case[2]))
            assertEquals("preprocess fixture ${case[1]}", decode(case[3]), prepared.text)
        }
    }

    @Test fun postprocessingMatchesActualGreedyAndOfficialEdgeFixtures() {
        val cases = fixtures().filter { it[0] == "POST" }
        assertTrue(cases.count { it[1].startsWith("greedy-") } >= 6)
        for (case in cases) {
            val prepared = IndicTextProcessor.preprocessEnglish(decode(case[2]))
            assertEquals("postprocess fixture ${case[1]}", decode(case[5]), IndicTextProcessor.postprocess(decode(case[4]), case[3], prepared.entities))
        }
    }

    @Test fun entityMapsAreRequestLocalAndLiteralCodesAreNotInventedEntities() {
        val first = IndicTextProcessor.preprocessEnglish("Contact first@example.com and second@domain.org.")
        val second = IndicTextProcessor.preprocessEnglish("Visit https://example.com/path today.")
        assertEquals(setOf("first@example.com", "second@domain.org"), first.entities.values.toSet())
        assertEquals(setOf("https://example.com/path"), second.entities.values.toSet())
        val firstValue = first.entities.getValue("<ID1>")
        assertEquals(firstValue, IndicTextProcessor.postprocess("< ID1 >", "urd_Arab", first.entities))
        assertEquals("https://example.com/path", IndicTextProcessor.postprocess("< ID1 >", "urd_Arab", second.entities))
        val literal = IndicTextProcessor.preprocessEnglish("Keep 250 mg and cedar-47 unchanged.")
        assertTrue(literal.entities.isEmpty())
        assertTrue(literal.text.contains("250 mg"))
        assertTrue(literal.text.contains("cedar-47"))
    }

    @Test fun onlyReviewedTargetsAreAcceptedAndArabicIsNotFabricated() {
        assertThrows(IllegalArgumentException::class.java) { IndicTextProcessor.postprocess("hello", "pan_Arab") }
        assertEquals("हैलो", IndicTextProcessor.postprocess("हैलो", "urd_Arab"))
        assertEquals("ਹੈਲੋ", IndicTextProcessor.postprocess("हैलो", "pan_Guru"))
        assertEquals("।॥", IndicTextProcessor.postprocess("। ॥", "pan_Guru"))
    }

    @Test fun reservedPlaceholdersAndEntitySourceLimitsAreEnforced() {
        for (source in listOf("<ID1>", "< ID1 >", "[ID 1]", "<id2]", "آئی ڈی 1", "आईडी1")) {
            assertThrows(source, IllegalArgumentException::class.java) { IndicTextProcessor.preprocessEnglish(source) }
        }
        assertTrue(IndicTextProcessor.preprocessEnglish("a ".repeat(4096)).text.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { IndicTextProcessor.preprocessEnglish("a".repeat(8193)) }
        assertThrows(IllegalArgumentException::class.java) { IndicTextProcessor.preprocessEnglish((1..129).joinToString(" ") { "#tag$it" }) }
        assertThrows(IllegalArgumentException::class.java) { IndicTextProcessor.postprocess("x", "urd_Arab", mapOf("<ID129>" to "value")) }
        assertThrows(IllegalArgumentException::class.java) { IndicTextProcessor.postprocess("x", "urd_Arab", mapOf("bad key" to "value")) }
        assertThrows(IllegalArgumentException::class.java) { IndicTextProcessor.postprocess("<ID1>".repeat(1000), "urd_Arab", mapOf("<ID1>" to "x".repeat(8192))) }
    }
}
