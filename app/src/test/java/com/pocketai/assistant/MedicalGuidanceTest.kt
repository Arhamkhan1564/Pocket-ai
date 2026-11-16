package com.pocketai.assistant

import org.junit.Assert.*
import org.junit.Test

class MedicalGuidanceTest {
    @Test fun activeEnglishAndRomanRedFlagsEscalate() {
        listOf("I have severe chest pain", "I can’t breathe", "I took too many tablets",
            "mere seene mein dard hai", "saans nahi aa rahi", "مجھے سینے میں شدید درد ہے",
            "ਮੈਨੂੰ ਛਾਤੀ ਵਿੱਚ ਤੇਜ਼ ਦਰਦ ਹੈ").forEach {
            assertEquals(it, MedicalGuidance.Intent.URGENT, MedicalGuidance.assess(it).intent)
        }
    }

    @Test fun educationalAndNegatedChestPainAreNotCurrentEmergencyReports() {
        listOf("What does the document say about chest pain?", "I have no chest pain; what is aspirin used for?",
            "سینے میں درد نہیں ہے، اسپرین کیا ہے؟", "What is overdose?",
            "Tell me what the leaflet says about chest pain", "Tell me about chest pain").forEach {
            assertNotEquals(it, MedicalGuidance.Intent.URGENT, MedicalGuidance.assess(it).intent)
        }
        assertEquals(MedicalGuidance.Intent.URGENT, MedicalGuidance.assess("I have no fever but I cannot breathe").intent)
        assertEquals(MedicalGuidance.Intent.URGENT, MedicalGuidance.assess("I have no fever and I cannot breathe").intent)
        assertEquals(MedicalGuidance.Intent.URGENT, MedicalGuidance.assess("I have no fever, I cannot breathe").intent)
        assertEquals(MedicalGuidance.Intent.URGENT, MedicalGuidance.assess("مجھے بخار نہیں ہے اور سانس نہیں آ رہی").intent)
    }

    @Test fun personalSymptomsAndMedicineChoicesGetIntakeInsteadOfPrescription() {
        listOf("I have a fever", "My child has a fever", "Can I take ibuprofen?", "Which medicine for fever?",
            "mujhe bukhar hai konsi dawai loon", "mainu bukhar hai kehri dawai lawa", "مجھے بخار ہے کون سی دوا لوں؟").forEach {
            assertEquals(it, MedicalGuidance.Intent.PERSONAL_TREATMENT, MedicalGuidance.assess(it).intent)
        }
        listOf("Which medications should my patient use for sneezing?", "Which drugs should I take?", "Which tablets for fever?", "My patient has a fever").forEach {
            assertEquals(it, MedicalGuidance.Intent.PERSONAL_TREATMENT, MedicalGuidance.assess(it).intent)
        }
    }

    @Test fun ordinaryMedicineDocumentQueriesAreSupportedInformationRequests() {
        listOf("What is paracetamol used for?", "What dose does the leaflet list for adults?",
            "What does my document say about fever?", "Tell me what paracetamol is used for",
            "I want to understand aspirin", "Tell me what the leaflet says about paracetamol",
            "I have no chest pain; what is aspirin used for?").forEach {
            assertEquals(it, MedicalGuidance.Intent.MEDICINE_INFORMATION, MedicalGuidance.assess(it).intent)
        }
        assertEquals(MedicalGuidance.Intent.NORMAL, MedicalGuidance.assess("What is photosynthesis?").intent)
    }

    @Test fun intakeAsksEssentialContextAndProfessionalReview() {
        val message = MedicalGuidance.treatmentMessage("en", true)
        listOf("age", "duration", "pregnancy", "allergies", "medicines", "kidney", "clinician", "pharmacist").forEach { assertTrue(it, message.contains(it)) }
        assertFalse(message.contains("500"))
    }

    @Test fun urgentAndFallbackMessagesUseTheSelectedScripts() {
        assertTrue(MedicalGuidance.urgentMessage("ur").any { it in '\u0600'..'\u06FF' })
        assertTrue(MedicalGuidance.urgentMessage("pa-Arab").any { it in '\u0600'..'\u06FF' })
        assertTrue(MedicalGuidance.urgentMessage("pa-Guru").any { it in '\u0A00'..'\u0A7F' })
        assertTrue(MedicalGuidance.translationUnavailable("pa-Guru").any { it in '\u0A00'..'\u0A7F' })
        assertTrue(MedicalGuidance.noSourcesMessage("en").contains("no matching saved"))
    }

    @Test fun doseTranslationRejectsInventedQuantitiesConversionsAndPersonalDirections() {
        val original = "The tablet contains 5 mg of medicine."
        assertTrue(GroundedEvidence.safeTranslation(original, "گولی میں 5 mg دوا ہوتی ہے۔", "ur", true))
        assertFalse(GroundedEvidence.safeTranslation(original, "گولی میں 50 mg دوا ہوتی ہے۔", "ur", true))
        assertFalse(GroundedEvidence.safeTranslation(original, "گولی میں 5 g دوا ہوتی ہے۔", "ur", true))
        assertFalse(GroundedEvidence.safeTranslation(original, "گولی میں دوا ہوتی ہے۔", "ur", true))
        assertFalse(GroundedEvidence.safeTranslation(original, "The tablet contains 5 mg.", "ur", true))
        assertTrue(MedicalGuidance.containsPersonalPrescription("You should take 5 mg now"))
    }

    @Test fun nativeReasoningIsNotDisplayedAsAnAnswer() {
        assertEquals("Answer", com.pocketai.engine.LocalAiEngine.stripReasoning("<think>private analysis</think>Answer"))
        assertEquals("", com.pocketai.engine.LocalAiEngine.stripReasoning("<think>unfinished reasoning"))
    }

    @Test fun medicineTranslationCannotSilentlyDropTheOnlyNegatedWarning() {
        val warning = "Do not use during pregnancy."
        assertFalse(GroundedEvidence.safeTranslation(warning, "حمل کے دوران استعمال کریں۔", "ur", true))
        assertTrue(GroundedEvidence.safeTranslation(warning, "حمل کے دوران استعمال نہ کریں۔", "ur", true))
        assertFalse(GroundedEvidence.safeTranslation("حمل کے دوران استعمال نہ کریں۔", "ਗਰਭ ਅਵਸਥਾ ਦੌਰਾਨ ਵਰਤੋਂ ਕਰੋ।", "pa-Guru", true))
        assertTrue(GroundedEvidence.safeTranslation("حمل کے دوران استعمال نہ کریں۔", "ਗਰਭ ਅਵਸਥਾ ਦੌਰਾਨ ਵਰਤੋਂ ਨਾ ਕਰੋ।", "pa-Guru", true))
    }

    @Test fun faithfulQuotedLeafletInstructionsAreAllowedWithoutCreatingPersonalAdvice() {
        val original = "Take 5 mg daily. Do not use during pregnancy."
        assertTrue(GroundedEvidence.safeTranslation(original, "روزانہ 5 mg لیں۔ حمل کے دوران استعمال نہ کریں۔", "ur", true))
        assertFalse(GroundedEvidence.safeTranslation("The tablet contains 5 mg of medicine.", "روزانہ 5 mg لیں۔", "ur", true))
        assertFalse(GroundedEvidence.safeTranslation(original, "روزانہ 50 mg لیں۔ حمل کے دوران استعمال نہ کریں۔", "ur", true))
        assertFalse(GroundedEvidence.safeTranslation(original, "آپ کو روزانہ 5 mg لینا چاہیے۔ حمل کے دوران استعمال نہ کریں۔", "ur", true))
        assertEquals(MedicalGuidance.Intent.PERSONAL_TREATMENT, MedicalGuidance.assess("Which medicine should I take?").intent)
    }

    @Test fun literalAlphanumericIdentifiersCannotBeTranslatedOrCorrupted() {
        val original = "The workshop code is cedar-47."
        assertFalse(GroundedEvidence.safeTranslation(original, "ورکشاپ کا کوڈ چEAR-47 ہے۔", "ur", false))
        assertTrue(GroundedEvidence.safeTranslation(original, "ورکشاپ کا کوڈ cedar-47 ہے۔", "ur", false))
        assertTrue(GroundedEvidence.hasUnsupportedIdentifiers("کوڈ چEAR-47 ہے۔", original))
    }
}
