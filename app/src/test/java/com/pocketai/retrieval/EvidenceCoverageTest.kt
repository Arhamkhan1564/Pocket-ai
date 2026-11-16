package com.pocketai.retrieval

import com.pocketai.data.KnowledgeRecord
import org.junit.Assert.*
import org.junit.Test

class EvidenceCoverageTest {
    private fun record(text: String, name: String = "reference.txt") = KnowledgeRecord(
        1, text, name, "en", "Latn", "General", "document", "$name · chunk 1/1", null, 1, 1, true)

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
