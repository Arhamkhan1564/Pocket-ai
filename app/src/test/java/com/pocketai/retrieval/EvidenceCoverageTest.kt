package com.pocketai.retrieval

import com.pocketai.data.KnowledgeRecord
import org.junit.Assert.*
import org.junit.Test

class EvidenceCoverageTest {
    private fun record(text: String, name: String = "reference.txt") = KnowledgeRecord(
        1, text, name, "en", "Latn", "General", "document", "$name · chunk 1/1", null, 1, 1, true)

    @Test fun unknownNamedMedicineCannotMatchGenericSavedDocumentWords() {
        assertFalse(EvidenceCoverage.allows("What does the saved document say about Novamed?",
            record("The saved reference discusses paracetamol and a pregnancy warning.")))
    }

    @Test fun keepingMedicineColdDoesNotRequireACommonColdReference() {
        assertTrue(EvidenceCoverage.allows("What does the document say about keeping insulin cold?",
            record("Keep insulin in the refrigerator between 2 and 8 degrees Celsius.", "storage.txt")))
    }

    @Test fun birthDateAndBothGurmukhiPinSpellingsNeedTheirActualFields() {
        val codeOnly = record("The workshop code is cedar-47.")
        assertFalse(EvidenceCoverage.allows("What is the workshop owner's birth date?", codeOnly))
        assertFalse(EvidenceCoverage.allows("ਦਰਵਾਜ਼ੇ ਦਾ ਪਿਨ ਕੋਡ ਕੀ ਹੈ?", codeOnly))
        assertFalse(EvidenceCoverage.allows("ਦਰਵਾਜ਼ੇ ਦਾ ਪਿੰਨ ਕੋਡ ਕੀ ਹੈ?", codeOnly))
    }

    @Test fun aCodeInTheFilenameDoesNotCreateACodeFieldInTheBody() {
        assertFalse(EvidenceCoverage.allows("What is the workshop code?",
            record("The workshop is on the ground floor.", "workshop-code.txt")))
    }

    @Test fun anApprovedQuestionProvidesContextForItsShortAnswer() {
        val answer = record("cedar-47").copy(question = "What is the workshop code?", source = "text", sourceReference = "")
        assertTrue(EvidenceCoverage.allows("What is the workshop code?", answer))
        assertTrue(EvidenceCoverage.covers("What is the workshop code?", listOf(answer)))
    }

    @Test fun missingPinCannotBeSatisfiedByAnUnrelatedWorkshopFactOrFilename() {
        val source = record("The workshop is on the ground floor.", "PIN-information.txt")
        assertFalse(EvidenceCoverage.allows("What is the workshop PIN?", source))
        assertFalse(EvidenceCoverage.covers("What is the workshop PIN?", listOf(source)))
        assertTrue(EvidenceCoverage.allows("What is the workshop PIN?", record("The workshop PIN is 1234.")))
    }

    @Test fun aGenericMedicineMatchCannotAnswerForAnotherNamedDrug() {
        assertFalse(EvidenceCoverage.allows("What is Novamed used for?", record("Aspirin is described in this leaflet.")))
        assertTrue(EvidenceCoverage.allows("What is Novamed used for?", record("Novamed is described in this leaflet.")))
        assertFalse(EvidenceCoverage.allows("پیراسیٹامول کے بارے میں بتائیں", record("Ibuprofen warnings are described here.")))
    }

    @Test fun mixedSourceFieldsMustActuallyExistInTheRetrievedContent() {
        val reference = record("The password is cedar-47. This is a workshop entrance reference.")
        assertTrue(EvidenceCoverage.covers("What is the password?", listOf(reference)))
        assertFalse(EvidenceCoverage.covers("What is the WiFi password?", listOf(reference)))
        assertTrue(EvidenceCoverage.covers("What is the WiFi password?", listOf(record("The WiFi password is cedar-47."))))
    }
}
