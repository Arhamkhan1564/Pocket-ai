package com.pocketai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketai.security.AuthManager
import org.json.JSONArray
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
class DocumentStoreAndVectorTest {
    private lateinit var context: Context
    private lateinit var auth: AuthManager
    private lateinit var db: PocketDatabase
    private val modelHash = "a".repeat(64)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("pocket_ai.db")
        context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).edit().clear().commit()
        auth = AuthManager(context).also { it.configure("123456") }
        db = PocketDatabase(context, auth)
    }
    @After fun close() { db.close() }

    @Test fun documentReaderWorksLockedAndDeletionRemovesOnlyThatDocument() {
        val manual = db.saveKnowledge(KnowledgeDraft("A personal fact unrelated to the imported sources."))
        db.importText("First source passage.\n\nSecond source passage.", "first.txt")
        db.importText("A different reference with persistent source content.", "second.txt")
        val document = db.listDocuments("first").single()
        val first = db.getDocumentChunks(document.id, limit = 1).single()
        assertEquals(2, document.chunkCount)
        assertEquals("First source passage.", first.content)
        assertEquals("Second source passage.", db.getDocumentChunks(document.id, limit = 1, offset = 1).single().content)
        assertEquals(document.id, db.getDocumentForKnowledge(first.id)?.id)
        auth.lock()
        assertEquals(first.content, db.getKnowledge(first.id)?.content)
        assertTrue(store(first))
        assertEquals(2, db.listDocuments().size)
        assertEquals(2, db.getDocumentChunks(document.id).size)
        assertThrows(IllegalStateException::class.java) { db.deleteDocument(document.id) }
        assertTrue(auth.unlock("123456"))
        assertEquals(2, db.deleteDocument(document.id))
        assertNull(db.getKnowledge(first.id))
        assertTrue(db.nearestKnowledgeVectors(axis(0), modelHash).isEmpty())
        assertNull(db.getDocumentForKnowledge(first.id))
        assertEquals("second.txt", db.listDocuments().single().name)
        assertNotNull(db.getKnowledge(manual))
        assertTrue(db.retrieve("First source passage").none { it.id == first.id })
        assertEquals("document_deleted", db.trainingHistory().first().action)
        assertEquals("ok", db.integrityCheck())
    }

    @Test fun documentCatalogAndTrustedSourceIdsRoundTripInVersion2Backup() {
        db.importText("An English evidence passage.\n\nA second evidence passage.", "reference #999.txt")
        val document = db.listDocuments().single()
        val ids = db.getDocumentChunks(document.id).map { it.id }
        db.addConversation("assistant", "An answer with separately stored provenance.", sources = "Untrusted visible filename #999", sourceIds = ids)
        val backup = db.exportBackup()
        assertEquals(2, JSONObject(backup).getInt("version"))
        db.deleteDocument(document.id)
        assertEquals(2, db.restoreBackup(backup))
        assertEquals(document, db.listDocuments().single())
        assertEquals(ids, db.getDocumentChunks(document.id).map { it.id })
        assertEquals(ids, db.conversations().single().sourceIds)
        assertFalse(999L in db.conversations().single().sourceIds)
    }

    @Test fun oldVersion1BackupRebuildsCatalogWithoutInventingSourceIds() {
        db.importText("An older original source paragraph.", "old #333.txt")
        db.addConversation("assistant", "Legacy answer", sources = "@engine=Saved knowledge\n#333 old #999.txt")
        val backup = JSONObject(db.exportBackup()).put("version", 1)
        backup.remove("documents"); backup.remove("documentChunks")
        backup.getJSONArray("conversations").getJSONObject(0).remove("sourceIds")
        assertEquals(1, db.restoreBackup(backup.toString()))
        assertEquals("old #333.txt", db.listDocuments().single().name)
        assertEquals("An older original source paragraph.", db.getDocumentChunks(db.listDocuments().single().id).single().content)
        assertTrue(db.conversations().single().sourceIds.isEmpty())
    }

    @Test fun invalidDocumentMappingsAndSourceIdArraysLeaveExistingDataUntouched() {
        val manual = db.saveKnowledge(KnowledgeDraft("Keep this manual fact."))
        db.importText("A referenced source.", "reference.txt")
        val backup = JSONObject(db.exportBackup())
        backup.getJSONArray("documentChunks").getJSONObject(0).put("knowledgeId", manual)
        assertThrows(IllegalArgumentException::class.java) { db.restoreBackup(backup.toString()) }
        assertEquals(2, db.countKnowledge())
        assertEquals(1, db.listDocuments().size)
        val badSources = JSONObject(db.exportBackup()).put("conversations", JSONArray().put(JSONObject()
            .put("role", "assistant").put("content", "Invalid provenance").put("sourceIds", JSONArray().put(-1))))
        assertThrows(IllegalArgumentException::class.java) { db.restoreBackup(badSources.toString()) }
        assertEquals(2, db.countKnowledge())
        assertThrows(IllegalArgumentException::class.java) { db.addConversation("assistant", "Invalid", sourceIds = listOf(1, 1)) }
    }

    @Test fun realVersion1SchemaMigrationPreservesKnowledgeAndHistory() {
        db.importText("An original legacy document passage.", "legacy.txt")
        val id = db.listKnowledge().single().id
        db.addConversation("assistant", "A prior answer", sources = "#987 untrusted label", sourceIds = listOf(id))
        val sqlite = db.writableDatabase
        sqlite.execSQL("ALTER TABLE conversations RENAME TO legacy_conversations")
        sqlite.execSQL("""CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT,
            role TEXT NOT NULL, content TEXT NOT NULL, language TEXT NOT NULL, sources TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)""")
        sqlite.execSQL("INSERT INTO conversations(id, role, content, language, sources, created_at) SELECT id, role, content, language, sources, created_at FROM legacy_conversations")
        sqlite.execSQL("DROP TABLE legacy_conversations")
        sqlite.execSQL("DROP TABLE knowledge_vectors")
        sqlite.delete("document_chunks", null, null); sqlite.delete("documents", null, null)
        sqlite.version = 1
        db.close(); db = PocketDatabase(context, auth)
        assertEquals(2, db.readableDatabase.version)
        assertEquals(id, db.listKnowledge().single().id)
        assertEquals("A prior answer", db.conversations().single().content)
        assertTrue(db.conversations().single().sourceIds.isEmpty())
        assertEquals("legacy.txt", db.listDocuments().single().name)
        assertTrue(db.storeKnowledgeVectors(id, modelHash, db.getKnowledge(id)!!.updatedAt, db.getKnowledge(id)!!.content,
            listOf(KnowledgeVectorPart(0, 0, 8, axis(0)))))
    }

    @Test fun vectorSearchNormalizesAndKeepsBestWindowPerDistinctSource() {
        val first = saved("First original source with more than one searchable window.")
        val second = saved("Second original source with different semantic content.")
        val pending = saved("Third source awaits indexing.")
        assertTrue(db.storeKnowledgeVectors(first.id, modelHash, first.updatedAt, first.content, listOf(
            KnowledgeVectorPart(0, 0, 5, axis(1)), KnowledgeVectorPart(1, 6, 20, axis(0, 2f)))))
        val secondVector = FloatArray(384).apply { this[0] = .8f; this[1] = .6f }
        assertTrue(db.storeKnowledgeVectors(second.id, modelHash, second.updatedAt, second.content,
            listOf(KnowledgeVectorPart(0, 0, 10, secondVector))))
        val hits = db.nearestKnowledgeVectors(axis(0), modelHash, limit = 2)
        assertEquals(listOf(first.id, second.id), hits.map { it.knowledgeId })
        assertEquals(1.0, hits[0].score, .00001)
        assertEquals(.8, hits[1].score, .00001)
        assertEquals(6, hits[0].startOffset)
        assertEquals(listOf(pending.id), db.pendingEmbeddingKnowledge(modelHash).map { it.id })
        assertEquals(3, db.pendingEmbeddingKnowledge("b".repeat(64)).size)
    }

    @Test fun editsRejectStaleVectorWritesEvenWhenTimestampIsIdentical() {
        val original = saved("The original source content.")
        assertTrue(store(original))
        db.saveKnowledge(original.toDraft().copy(content = "The corrected source content."), original.id)
        db.writableDatabase.execSQL("UPDATE knowledge SET updated_at=? WHERE id=?", arrayOf(original.updatedAt, original.id))
        assertTrue(db.nearestKnowledgeVectors(axis(0), modelHash).isEmpty())
        assertFalse(store(original))
        val current = db.getKnowledge(original.id)!!
        assertTrue(store(current))
        db.deleteKnowledge(current.id)
        assertTrue(db.nearestKnowledgeVectors(axis(0), modelHash).isEmpty())
    }

    @Test fun vectorsAreDerivedNotExportedAndAreClearedOnRestore() {
        val record = saved("Persistent source, regenerable vector index.")
        assertTrue(store(record))
        val backup = db.exportBackup()
        assertFalse(backup.contains("knowledge_vectors"))
        assertFalse(backup.contains(modelHash))
        db.restoreBackup(backup)
        assertTrue(db.nearestKnowledgeVectors(axis(0), modelHash).isEmpty())
        assertEquals(record.id, db.pendingEmbeddingKnowledge(modelHash).single().id)
    }

    @Test fun invalidVectorsAndWindowsDoNotPoisonAnExistingIndex() {
        val record = saved("A source that has a valid searchable embedding.")
        assertTrue(store(record))
        val invalid = listOf(FloatArray(383), FloatArray(384), axis(0).apply { this[5] = Float.NaN }, axis(0).apply { this[5] = Float.POSITIVE_INFINITY })
        invalid.forEach { vector -> assertThrows(IllegalArgumentException::class.java) {
            db.storeKnowledgeVectors(record.id, modelHash, record.updatedAt, record.content, listOf(KnowledgeVectorPart(0, 0, 5, vector)))
        } }
        assertThrows(IllegalArgumentException::class.java) { db.storeKnowledgeVectors(record.id, modelHash, record.updatedAt, record.content, listOf(KnowledgeVectorPart(0, 0, record.content.length + 1, axis(0)))) }
        assertThrows(IllegalArgumentException::class.java) { db.pendingEmbeddingKnowledge("unverified model") }
        assertThrows(IllegalArgumentException::class.java) { db.nearestKnowledgeVectors(axis(0), modelHash, 21) }
        assertEquals(record.id, db.nearestKnowledgeVectors(axis(0), modelHash).single().knowledgeId)
    }

    private fun saved(content: String): KnowledgeRecord = db.getKnowledge(db.saveKnowledge(KnowledgeDraft(content)))!!
    private fun store(record: KnowledgeRecord) = db.storeKnowledgeVectors(record.id, modelHash, record.updatedAt, record.content,
        listOf(KnowledgeVectorPart(0, 0, record.content.length, axis(0))))
    private fun axis(index: Int, value: Float = 1f) = FloatArray(384).apply { this[index] = value }
}
