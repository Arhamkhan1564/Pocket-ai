package com.pocketai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketai.security.AuthManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PocketDatabaseTest {
    private lateinit var context: Context
    private lateinit var auth: AuthManager
    private lateinit var db: PocketDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("pocket_ai.db")
        context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).edit().clear().commit()
        auth = AuthManager(context)
        auth.configure("654321")
        db = PocketDatabase(context, auth)
    }

    @After fun tearDown() { db.close() }

    @Test fun knowledgeSurvivesReopenAndCorrectionsUpdateRetrieval() {
        val id = db.saveKnowledge(KnowledgeDraft("Pakistan became independent in 1947.", "When did Pakistan become independent?"))
        db.close()
        db = PocketDatabase(context, auth)
        assertEquals("Pakistan became independent in 1947.", db.getKnowledge(id)?.content)
        assertEquals(id, db.retrieve("When did Pakistan become independent?").single().id)
        db.saveKnowledge(KnowledgeDraft("Pakistan celebrates independence on 14 August.", "When is Pakistan's independence day?"), id)
        assertEquals(id, db.retrieve("Pakistan independence day").single().id)
        assertEquals("corrected", db.trainingHistory().first().action)
        db.deleteKnowledge(id)
        assertNull(db.getKnowledge(id))
        assertTrue(db.retrieve("Pakistan").isEmpty())
        assertEquals("ok", db.integrityCheck())
    }

    @Test fun verifiedKnowledgeRequiresAnActiveAdministratorSession() {
        val original = db.saveKnowledge(KnowledgeDraft("A protected fact"))
        val backup = db.exportBackup()
        auth.lock()
        assertThrows(IllegalStateException::class.java) { db.saveKnowledge(KnowledgeDraft("Unauthorized fact")) }
        assertThrows(IllegalStateException::class.java) { db.deleteKnowledge(original) }
        assertThrows(IllegalStateException::class.java) { db.restoreBackup(backup) }
        assertThrows(IllegalStateException::class.java) { db.exportBackup() }
        assertThrows(IllegalStateException::class.java) { db.addCategory("Private") }
        assertThrows(IllegalStateException::class.java) { db.importText("Unreviewed reference", "reference.txt") }
        db.addConversation("user", "Remember this unverified conversation")
        assertEquals(1, db.countKnowledge())
        assertEquals(1, db.conversations().size)
        assertEquals(original, db.retrieve("protected fact").single().id)
    }

    @Test fun unicodeSearchPreservesOriginalUrduAndPunjabiScripts() {
        val urdu = "پاکِستان ۱۹۴۷ میں آزاد ہوا۔"
        val punjabi = "ਪੰਜਾਬੀ ਇੱਕ ਭਾਸ਼ਾ ਹੈ।"
        val first = db.saveKnowledge(KnowledgeDraft(urdu, language = "ur", script = "Arab"))
        val second = db.saveKnowledge(KnowledgeDraft(punjabi, language = "pa", script = "Guru"))
        assertEquals(urdu, db.getKnowledge(first)?.content)
        assertEquals(first, db.retrieve("پاکستان", "ur").single().id)
        assertEquals(second, db.retrieve("ਪੰਜਾਬੀ", "pa").single().id)
        assertEquals(listOf(first), db.listKnowledge("پاکستان").map { it.id })
    }

    @Test fun duplicatesAndPotentialQaConflictsAreDetected() {
        val draft = KnowledgeDraft("  The sky is blue.  ", "What colour is the sky?")
        val id = db.saveKnowledge(draft)
        assertEquals(id, db.findDuplicates(draft.copy(content = "THE SKY IS BLUE!")).single().id)
        assertThrows(IllegalArgumentException::class.java) { db.saveKnowledge(draft.copy(content = "The sky is blue!")) }
        assertEquals(id, db.findConflicts(draft.copy(content = "The sky is gray today.")).single().id)
        db.saveKnowledge(draft.copy(content = "The sky is blue today."), id)
        assertEquals(1, db.countKnowledge())
    }

    @Test fun backupRestoresKnowledgeAndConversationWithoutReplacingCredentials() {
        val id = db.saveKnowledge(KnowledgeDraft("A durable definition", category = "Definitions", sourceReference = "owner notebook"))
        db.addConversation("user", "What is a definition?")
        db.addConversation("assistant", "A durable definition", sources = "#$id")
        val backup = db.exportBackup()
        db.deleteKnowledge(id)
        db.saveKnowledge(KnowledgeDraft("Temporary fact"))
        assertEquals(1, db.restoreBackup(backup))
        assertEquals(id, db.listKnowledge().single().id)
        assertEquals("A durable definition", db.listKnowledge().single().content)
        assertEquals("owner notebook", db.listKnowledge().single().sourceReference)
        assertEquals(2, db.conversations().size)
        assertTrue("Definitions" in db.categories())
        auth.lock()
        assertTrue(auth.unlock("654321"))
        assertFalse(backup.contains("pin_hash"))
    }

    @Test fun invalidRestoreDoesNotDestroyExistingKnowledge() {
        db.saveKnowledge(KnowledgeDraft("Keep this knowledge"))
        val backup = JSONObject(db.exportBackup())
        backup.getJSONArray("knowledge").put(JSONObject().put("id", 99).put("content", "").put("language", "en"))
        assertThrows(IllegalArgumentException::class.java) { db.restoreBackup(backup.toString()) }
        assertEquals("Keep this knowledge", db.listKnowledge().single().content)
        val unknown = JSONObject(db.exportBackup()).put("version", 999)
        assertThrows(IllegalArgumentException::class.java) { db.restoreBackup(unknown.toString()) }
        assertEquals(1, db.countKnowledge())
    }

    @Test fun paginationAndDocumentImportRemainBoundedAndSourceLinked() {
        repeat(37) { db.saveKnowledge(KnowledgeDraft("Unique test fact number $it")) }
        val first = db.listKnowledge(limit = 20)
        val second = db.listKnowledge(limit = 20, offset = 20)
        assertEquals(20, first.size)
        assertEquals(17, second.size)
        assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
        assertEquals(2, db.importText("The first paragraph.\n\nThe second paragraph.", "notes.txt"))
        assertEquals(0, db.importText("The first paragraph.\n\nThe second paragraph.", "notes.txt"))
        assertEquals(2, db.listKnowledge(category = "Documents").size)
        assertTrue(db.listKnowledge(category = "Documents").all { it.sourceReference.startsWith("notes.txt") })
        assertTrue(db.retrieve("what is").isEmpty())
    }

    @Test fun retrievalCanFindWordsBeyondTheBeginningOfLongNotes() {
        val id = db.saveKnowledge(KnowledgeDraft((1..100).joinToString(" ") { "word$it" } + " photosynthesis stores energy"))
        assertEquals(id, db.retrieve("What is photosynthesis?").single().id)
        db.addConversation("assistant", "Previous answer")
        db.clearConversations()
        assertTrue(db.conversations().isEmpty())
        assertEquals(1, db.countKnowledge())
    }

    @Test fun englishDocumentsCanBeRetrievedWithUrduPunjabiAndRomanKeywords() {
        db.importText("A headache is pain or discomfort in the head. This is an educational reference.", "headache-reference.txt")
        db.importText("A cough is a protective reflex. Fever means an elevated body temperature.", "cough-fever-reference.txt")
        val queries = listOf("مجھے سر میں درد ہے کونسی دوا" to "ur", "ਮੈਨੂੰ ਸਿਰ ਦਰਦ ਹੈ" to "pa", "ਮੈਨੂੰ ਸਿਰ ਵਿੱਚ ਦਰਦ ਹੈ" to "pa", "مینوں سر وچ درد اے" to "pa", "mainu sir dard hai" to "pa", "mujhe sar mein dard hai" to "ur")
        queries.forEach { (question, language) ->
            val first = db.retrieve(question, language).first()
            assertTrue("No headache match for $question", first.content.contains("headache"))
            assertEquals("en", first.language)
            assertEquals("headache-reference.txt · chunk 1/1", first.sourceReference)
        }
        assertTrue(db.retrieve("بخار", "ur").first().content.contains("Fever"))
        assertTrue(db.retrieve("ਖੰਘ", "pa").first().content.contains("cough"))
    }

    @Test fun medicineNameExpansionRetrievesTheOriginalReferencedExcerpt() {
        val text = "Paracetamol is also called acetaminophen. Side effects and warnings must be checked in the original leaflet."
        db.importText(text, "medicine-leaflet.docx", category = "Medicine references")
        val match = db.retrieve("پیراسیٹامول کے مضر اثرات", "ur").single()
        assertEquals(text, match.content)
        assertEquals("medicine-leaflet.docx · chunk 1/1", match.sourceReference)
        assertEquals("medicine-leaflet.docx", match.question)
        assertEquals(match.id, db.retrieve("ਪੈਰਾਸੀਟਾਮੋਲ", "pa").single().id)
    }

    @Test fun broadMedicineKeywordsDoNotCrowdOutAnOlderSpecificSymptomReference() {
        db.importText("Fever is an elevated body temperature.", "fever-reference.txt")
        repeat(120) { db.saveKnowledge(KnowledgeDraft("Medicine reference number $it describes a different condition.", category = "Medicine references")) }
        val results = db.retrieve("Which medicine for fever?", "en")
        assertTrue(results.isNotEmpty())
        assertTrue(results.first().content.contains("Fever"))
    }

    @Test fun documentDuplicatesKeepDistinctFileProvenanceAndSurvivePortableBackup() {
        val text = "An original document sentence with its own provenance."
        db.saveKnowledge(KnowledgeDraft(text))
        assertEquals(1, db.importText(text, "first.docx"))
        assertEquals(0, db.importText(text, "first.docx"))
        assertEquals(1, db.importText(text, "second.pdf"))
        assertEquals(setOf("first.docx · chunk 1/1", "second.pdf · chunk 1/1"), db.listKnowledge(category = "Documents").map { it.sourceReference }.toSet())
        val backup = db.exportBackup()
        assertEquals(3, db.restoreBackup(backup))
        assertEquals(2, db.listKnowledge(category = "Documents").size)
        assertTrue(db.listKnowledge(category = "Documents").all { it.language == "en" && it.script == "Latn" })
        assertTrue(db.findConflicts(KnowledgeDraft("Another legitimate passage", question = "first.docx", source = "document")).isEmpty())
    }

    @Test fun invalidBulkImportCannotLeavePartialRecordsOrAuditEvents() {
        db.saveKnowledge(KnowledgeDraft("Keep existing knowledge"))
        val events = db.trainingHistory().size
        assertThrows(IllegalArgumentException::class.java) { db.importText("Good paragraph.\n\nAnother paragraph.", "reference.txt", category = "X".repeat(101)) }
        assertThrows(IllegalArgumentException::class.java) { db.importText((1..1001).joinToString("\n\n") { "Paragraph $it" }, "too-many.txt") }
        assertEquals(1, db.countKnowledge())
        assertEquals(events, db.trainingHistory().size)
    }
}
