package com.pocketai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketai.security.AuthManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crosses the actual parser/SQLite boundary, persistence and a clean-store restore.
 * This is a development-host data acceptance check, not Android UI or model-quality evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineDocumentAcceptanceTest {
    private lateinit var context: Context
    private lateinit var auth: AuthManager
    private lateinit var db: PocketDatabase
    private val modelHash = "c".repeat(64)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("pocket_ai.db")
        context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).edit().clear().commit()
        auth = AuthManager(context).also { it.configure("246810") }
        db = PocketDatabase(context, auth)
    }

    @After fun close() { db.close() }

    @Test fun realDocumentExtractionImportRestartRestoreAndDeletionPreserveSourceIdentity() {
        val extractor = DocumentTextExtractor(context)
        val txtText = "TXT reference code cedar-47.\nKeep the original Urdu label اردو and Punjabi ਪੰਜਾਬੀ."
        val docxText = "The calibration shelf stores the blue sample."
        val pdfText = "The workshop opens at nine on Fridays."
        val extracted = listOf(
            extractor.extract(ByteArrayInputStream(txtText.toByteArray(Charsets.UTF_8)), "txt reference #999.txt"),
            extractor.extract(ByteArrayInputStream(docx(docxText)), "calibration.docx"),
            extractor.extract(ByteArrayInputStream(pdf(pdfText)), "workshop.pdf", "application/pdf"),
        )
        assertEquals(listOf("TXT", "DOCX", "PDF"), extracted.map { it.format })
        assertEquals(txtText, extracted[0].text)
        assertEquals(docxText, extracted[1].text)
        assertEquals(1, extracted[2].pages)
        assertTrue(extracted[2].text.contains(pdfText))
        // Extraction/preview alone must not save permanent knowledge.
        assertEquals(0, db.countKnowledge())
        extracted.forEach { source ->
            assertEquals(1, db.importText(source.text, source.fileName, "en", "Latn", "Acceptance references"))
        }
        val manualId = db.saveKnowledge(KnowledgeDraft("An unrelated personal note remains separately editable."))
        val catalog = db.listDocuments().associateBy { it.name }
        assertEquals(extracted.map { it.fileName }.toSet(), catalog.keys)
        val records = extracted.map { source ->
            val document = catalog.getValue(source.fileName)
            val record = db.getDocumentChunks(document.id).single()
            assertEquals(source.text, record.content)
            assertEquals(document.id, db.getDocumentForKnowledge(record.id)?.id)
            assertEquals(source.fileName, record.question)
            assertTrue(record.verified)
            record
        }
        assertEquals(records[0].id, db.retrieve("cedar-47").single().id)
        assertEquals(records[1].id, db.retrieve("calibration blue sample").single().id)
        assertEquals(records[2].id, db.retrieve("workshop Fridays").single().id)
        val sourceIds = records.map { it.id }
        db.addConversation("assistant", "Read the three saved reference passages.",
            sources = "Visible filename #999 is not a trusted source identifier.", sourceIds = sourceIds)
        val pdfRecord = records[2]
        assertTrue(storeDerivedFixture(pdfRecord))

        // A new helper and authorization object represent a reopened process/store.
        db.close()
        auth = AuthManager(context)
        db = PocketDatabase(context, auth)
        assertFalse(auth.isUnlocked)
        assertEquals(4, db.countKnowledge())
        assertEquals(catalog, db.listDocuments().associateBy { it.name })
        assertEquals(sourceIds, db.conversations().single().sourceIds)
        assertFalse(999L in db.conversations().single().sourceIds)
        assertEquals(pdfRecord.id, db.nearestKnowledgeVectors(axis(), modelHash).single().knowledgeId)
        assertThrows(IllegalStateException::class.java) { db.deleteDocument(catalog.getValue("workshop.pdf").id) }
        assertEquals(3, db.listDocuments().size)
        assertTrue(auth.unlock("246810"))
        val backup = db.exportBackup()
        assertEquals(2, JSONObject(backup).getInt("version"))

        // Restore into an actually empty SQLite store; credentials remain separate.
        db.close()
        assertTrue(context.deleteDatabase("pocket_ai.db"))
        auth = AuthManager(context)
        db = PocketDatabase(context, auth)
        assertEquals(0, db.countKnowledge())
        assertThrows(IllegalStateException::class.java) { db.restoreBackup(backup) }
        assertEquals(0, db.countKnowledge())
        assertTrue(auth.unlock("246810"))
        assertEquals(4, db.restoreBackup(backup))
        assertEquals(catalog, db.listDocuments().associateBy { it.name })
        records.forEach { old ->
            assertEquals(old, db.getKnowledge(old.id))
            assertEquals(catalog.getValue(old.question).id, db.getDocumentForKnowledge(old.id)?.id)
        }
        assertEquals(sourceIds, db.conversations().single().sourceIds)
        assertTrue(db.nearestKnowledgeVectors(axis(), modelHash).isEmpty())
        assertTrue(pdfRecord.id in db.pendingEmbeddingKnowledge(modelHash).map { it.id })

        // Vectors below are deterministic lifecycle fixtures, not actual AI embeddings.
        assertTrue(storeDerivedFixture(db.getKnowledge(pdfRecord.id)!!))
        assertEquals(1, db.deleteDocument(catalog.getValue("workshop.pdf").id))
        assertNull(db.getKnowledge(pdfRecord.id))
        assertNull(db.getDocumentForKnowledge(pdfRecord.id))
        assertTrue(db.nearestKnowledgeVectors(axis(), modelHash).isEmpty())
        assertTrue(db.retrieve("workshop Fridays").none { it.id == pdfRecord.id })
        assertEquals(setOf("txt reference #999.txt", "calibration.docx"), db.listDocuments().map { it.name }.toSet())
        assertNotNull(db.getKnowledge(manualId))
        assertEquals(sourceIds, db.conversations().single().sourceIds) // Deleted-source history stays explicit.
        assertEquals("ok", db.integrityCheck())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("pocket-document-") })
    }

    private fun storeDerivedFixture(record: KnowledgeRecord) = db.storeKnowledgeVectors(
        record.id, modelHash, record.updatedAt, record.content,
        listOf(KnowledgeVectorPart(0, 0, record.content.length, axis())),
    )

    private fun axis() = FloatArray(384).apply { this[0] = 1f }

    private fun docx(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"""
        ZipOutputStream(output).use { zip ->
            mapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
                "word/document.xml" to xml,
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun pdf(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(50f, 700f); stream.showText(text); stream.endText()
            }
            document.save(output)
        }
        return output.toByteArray()
    }
}
