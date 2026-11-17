package com.pocketai.retrieval

import org.junit.Assert.*
import org.junit.Test

/** Subject names are fixtures only: production must not contain a topic-name dictionary. */
class QuestionAnchorsTest {
    private data class LanguageCase(val question: String, val proof: String, val airport: String, val garage: String)
    private val languages = listOf(
        LanguageCase("What is the workshop access code?", "The workshop access code is cedar-47.", "What is the airport access code?", "What is the garage access code?"),
        LanguageCase("ورکشاپ کا کوڈ کیا ہے؟", "ورکشاپ کا کوڈ cedar-47 ہے۔", "ہوائی اڈے کا کوڈ کیا ہے؟", "گیراج کا کوڈ کیا ہے؟"),
        LanguageCase("ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ ਕੀ ਹੈ?", "ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ cedar-47 ਹੈ।", "ਹਵਾਈ ਅੱਡੇ ਦਾ ਕੋਡ ਕੀ ਹੈ?", "ਗੈਰਾਜ ਦਾ ਕੋਡ ਕੀ ਹੈ?"),
        LanguageCase("ورکشاپ دا کوڈ کی اے؟", "ورکشاپ دا کوڈ cedar-47 اے۔", "ہوائی اڈے دا کوڈ کی اے؟", "گیراج دا کوڈ کی اے؟"),
    )

    @Test fun knownCodeSubjectsMatchEvidenceInTheQuestionScript() {
        for (case in languages) {
            assertTrue(case.question, QuestionAnchors.isCodeQuestion(case.question))
            assertTrue(case.question, QuestionAnchors.topicWords(case.question).isNotEmpty())
            assertTrue(case.question, QuestionAnchors.matches(case.question, case.proof))
        }
    }

    @Test fun nearbyAirportAndGarageSubjectsCannotBorrowTheWorkshopCode() {
        for (case in languages) {
            assertTrue(case.airport, QuestionAnchors.isCodeQuestion(case.airport))
            assertTrue(case.garage, QuestionAnchors.isCodeQuestion(case.garage))
            assertFalse(case.airport, QuestionAnchors.matches(case.airport, case.proof))
            assertFalse(case.garage, QuestionAnchors.matches(case.garage, case.proof))
        }
    }

    @Test fun genericCodeFieldsDoNotSupplyTheirOwnSubject() {
        val generic = listOf("What is the code?", "What is the access code?", "کوڈ کیا ہے؟", "ਕੋਡ ਕੀ ਹੈ?", "کوڈ کی اے؟", "What is code 47?")
        for (question in generic) {
            assertTrue(question, QuestionAnchors.isCodeQuestion(question))
            assertTrue(question, QuestionAnchors.topicWords(question).isEmpty())
            assertFalse(question, QuestionAnchors.matches(question, "The code is 47. کوڈ 47 ہے۔ ਕੋਡ 47 ਹੈ।"))
        }
    }

    @Test fun allWordsInAMultiwordSubjectAreRequired() {
        val question = "What is the north workshop access code?"
        assertEquals(setOf("north", "workshop"), QuestionAnchors.topicWords(question).toSet())
        assertTrue(QuestionAnchors.matches(question, "The north workshop access code is cedar-47."))
        assertFalse(QuestionAnchors.matches(question, "The workshop access code is cedar-47."))
        assertFalse(QuestionAnchors.matches(question, "The north airport access code is cedar-47."))
        assertFalse(QuestionAnchors.matches(question, "The south workshop access code is cedar-47."))
        assertTrue(QuestionAnchors.matches("نیلی ورکشاپ کا کوڈ کیا ہے؟", "نیلی ورکشاپ کا کوڈ cedar-47 ہے۔"))
        assertFalse(QuestionAnchors.matches("نیلی ورکشاپ کا کوڈ کیا ہے؟", "ورکشاپ کا کوڈ cedar-47 ہے۔"))
        assertTrue(QuestionAnchors.matches("ਨੀਲੀ ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ ਕੀ ਹੈ?", "ਨੀਲੀ ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ cedar-47 ਹੈ।"))
        assertFalse(QuestionAnchors.matches("ਨੀਲੀ ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ ਕੀ ਹੈ?", "ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ cedar-47 ਹੈ।"))
    }

    @Test fun subjectMatchingUsesWholeTokensInsteadOfSubstrings() {
        assertFalse(QuestionAnchors.matches("What is the shop code?", "The workshop code is cedar-47."))
        assertFalse(QuestionAnchors.matches("What is the hangar code?", "The hangarbay code is cedar-47."))
        assertTrue(QuestionAnchors.matches("What is the shop code?", "The shop code is cedar-47."))
        assertFalse(QuestionAnchors.matches("ورکشاپ کا کوڈ کیا ہے؟", "ورکشاپوں کا کوڈ cedar-47 ہے۔"))
    }

    @Test fun explicitPinPasswordAndWifiFieldsAreOutsideCodeMatching() {
        val excluded = listOf(
            "What is the workshop PIN code?", "What is the workshop passcode?", "What is the workshop password code?",
            "What is the workshop Wi-Fi access code?", "What is the workshop WiFi code?", "What is the workshop wi fi code?",
            "ورکشاپ کا پن کوڈ کیا ہے؟", "ورکشاپ کا پاس ورڈ کوڈ کیا ہے؟", "ورکشاپ کے وائی فائی کا کوڈ کیا ہے؟",
            "ਵਰਕਸ਼ਾਪ ਦਾ ਪਿਨ ਕੋਡ ਕੀ ਹੈ?", "ਵਰਕਸ਼ਾਪ ਦਾ ਪਿੰਨ ਕੋਡ ਕੀ ਹੈ?", "ਵਰਕਸ਼ਾਪ ਦਾ ਪਾਸਵਰਡ ਕੋਡ ਕੀ ਹੈ?", "ਵਰਕਸ਼ਾਪ ਦੇ ਵਾਈ ਫਾਈ ਦਾ ਕੋਡ ਕੀ ਹੈ?",
            "ورکشاپ دا پن کوڈ کی اے؟", "ورکشاپ دے وائی فائی دا کوڈ کی اے؟",
        )
        for (question in excluded) {
            assertFalse(question, QuestionAnchors.isCodeQuestion(question))
            assertTrue(question, QuestionAnchors.topicWords(question).isEmpty())
            assertFalse(question, QuestionAnchors.matches(question, languages.joinToString(" ") { it.proof }))
        }
    }

    @Test fun possessivesAndRequestWordsDoNotBecomeSubjectRequirements() {
        val variants = listOf(
            "What is the workshop's access code?",
            "Please tell me what the workshop’s access code is.",
            "Can you please tell me the access code for the workshop?",
        )
        for (question in variants) {
            assertEquals(question, listOf("workshop"), QuestionAnchors.topicWords(question))
            assertTrue(question, QuestionAnchors.matches(question, "The workshop access code is cedar-47."))
        }
    }

    @Test fun nonLatinQuestionsRequireProofInTheirOwnScript() {
        for (case in languages.drop(1)) assertFalse(case.question, QuestionAnchors.matches(case.question, languages.first().proof))
        assertFalse(QuestionAnchors.matches("ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ ਕੀ ਹੈ?", "ورکشاپ دا کوڈ cedar-47 اے۔"))
        assertFalse(QuestionAnchors.matches("ورکشاپ دا کوڈ کی اے؟", "ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ cedar-47 ਹੈ।"))
    }

    @Test fun subjectNumbersArePreservedAndCannotMatchANumberPrefix() {
        val question = "What is the access code for Room 47?"
        assertEquals(setOf("room", "47"), QuestionAnchors.topicWords(question).toSet())
        assertTrue(QuestionAnchors.matches(question, "ROOM 47 access code is cedar-47."))
        // Keep47 absent from the code value too: this helper checks anchor
        // occurrence, while its caller owns source/field entailment checks.
        assertFalse(QuestionAnchors.matches(question, "Room 48 access code is cedar-99."))
        assertFalse(QuestionAnchors.matches(question, "Room 470 access code is cedar-99."))
        assertFalse(QuestionAnchors.matches("What is code 47?", "The code is 47."))
    }

    @Test fun accessPhrasesAreSupportedButAnUnrelatedPhraseIsNotACodeQuestion() {
        val question = "What is the workshop access phrase?"
        assertTrue(QuestionAnchors.isCodeQuestion(question))
        assertEquals(listOf("workshop"), QuestionAnchors.topicWords(question))
        assertTrue(QuestionAnchors.matches(question, "The workshop access phrase is cedar-47."))
        assertFalse(QuestionAnchors.matches("What is the airport access phrase?", "The workshop access phrase is cedar-47."))
        assertFalse(QuestionAnchors.isCodeQuestion("What does this phrase mean?"))
        assertFalse(QuestionAnchors.matches("What does this phrase mean?", "This phrase means welcome."))
    }

    @Test fun scriptDetectionSeparatesSupportedScriptsAndNonletters() {
        assertEquals("Latn", QuestionAnchors.questionScript("What is the workshop code?"))
        assertEquals("Arab", QuestionAnchors.questionScript("ورکشاپ کا کوڈ کیا ہے؟"))
        assertEquals("Arab", QuestionAnchors.questionScript("ورکشاپ دا کوڈ کی اے؟"))
        assertEquals("Guru", QuestionAnchors.questionScript("ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ ਕੀ ਹੈ?"))
        assertEquals("Unknown", QuestionAnchors.questionScript("12345 ?!"))
        assertEquals("Unknown", QuestionAnchors.questionScript(""))
    }

    @Test fun characterTokenAndTopicBoundsRejectInsteadOfTruncating() {
        val proof = "The workshop code is cedar-47."
        val base = "workshop code"
        val atCharacterLimit = base + " ".repeat(2000 - base.length)
        assertTrue(QuestionAnchors.matches(atCharacterLimit, proof))
        assertTrue(QuestionAnchors.topicWords(atCharacterLimit + " ").isEmpty())
        assertFalse(QuestionAnchors.matches(atCharacterLimit + " ", proof))

        val atTokenLimit = (List(62) { "the" } + listOf("workshop", "code")).joinToString(" ")
        assertTrue(QuestionAnchors.matches(atTokenLimit, proof))
        assertTrue(QuestionAnchors.topicWords("$atTokenLimit the").isEmpty())
        assertFalse(QuestionAnchors.matches("$atTokenLimit the", proof))

        val topics = ('a'..'p').map { "subject$it" }
        val atTopicLimit = topics.joinToString(" ") + " code"
        val evidence = topics.joinToString(" ") + " access code is cedar-47."
        assertEquals(16, QuestionAnchors.topicWords(atTopicLimit).size)
        assertTrue(QuestionAnchors.matches(atTopicLimit, evidence))
        assertTrue(QuestionAnchors.topicWords("subjectq $atTopicLimit").isEmpty())
        assertFalse(QuestionAnchors.matches("subjectq $atTopicLimit", evidence))
    }
}
