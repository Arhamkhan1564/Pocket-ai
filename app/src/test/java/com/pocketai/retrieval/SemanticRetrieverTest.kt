package com.pocketai.retrieval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketai.data.KnowledgeDraft
import com.pocketai.data.PocketDatabase
import com.pocketai.security.AuthManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SemanticRetrieverTest {
    private lateinit var db: PocketDatabase
    private lateinit var retriever: SemanticRetriever

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("pocket_ai.db")
        context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).edit().clear().commit()
        val auth = AuthManager(context).also { it.configure("123456") }
        db = PocketDatabase(context, auth)
        // No native model or vectors are installed. The zero-indexed branch
        // must return its notice without trying to initialize an ORT session.
        retriever = SemanticRetriever(db, OfflineEmbeddingService(context))
    }

    @After fun tearDown() { db.close() }

    @Test fun untranslatedUrduQuestionReportsIncompleteIndexInsteadOfAbsentKnowledge() = runBlocking {
        saveWorkshopReference()
        assertEquals(1, db.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256))

        val result = retriever.retrieve("سٹور کی کھڑکی کا رنگ کیا ہے؟", "ur", semanticReady = true)

        assertTrue(result.records.isEmpty())
        assertFalse(result.semantic)
        assertTrue(result.excerpts.isEmpty())
        assertTrue(result.notice?.isNotBlank() == true)
        assertTrue(result.notice!!.contains("index", ignoreCase = true))
    }

    @Test fun availableEnglishKeywordMatchSurvivesAnIncompleteSemanticIndex() = runBlocking {
        val id = saveWorkshopReference()
        assertEquals(1, db.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256))

        val result = retriever.retrieve("What is the workshop code?", "en", semanticReady = true)

        assertEquals(listOf(id), result.records.map { it.id })
        assertEquals("manual.pdf · chunk 1/1", result.records.single().sourceReference)
        assertFalse(result.semantic)
        assertTrue(result.notice?.isNotBlank() == true)
        assertTrue(result.notice!!.contains("index", ignoreCase = true))
    }

    @Test fun emptyDatabaseHasNormalNoMatchWithoutAnIndexingFailureNotice() = runBlocking {
        assertEquals(0, db.countKnowledge())
        assertEquals(0, db.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256))

        val result = retriever.retrieve("What is the workshop code?", "en", semanticReady = true)

        assertTrue(result.records.isEmpty())
        assertFalse(result.semantic)
        assertTrue(result.excerpts.isEmpty())
        assertNull(result.notice)
    }

    @Test fun aSharedCodeWordCannotAnswerForAnotherPlace() = runBlocking {
        saveWorkshopReference()
        assertTrue(retriever.retrieve("What is the airport code?", "en", semanticReady = false).records.isEmpty())
        assertTrue(retriever.retrieve("What is the garage access code?", "en", semanticReady = false).records.isEmpty())
    }

    @Test fun approvedQuestionAnswerPairsKeepTheirShortAnswerRetrievable() = runBlocking {
        val id = db.saveKnowledge(KnowledgeDraft("cedar-47", "What is the workshop code?"))
        val result = retriever.retrieve("What is the workshop code?", "en", semanticReady = false)
        assertEquals(listOf(id), result.records.map { it.id })
        assertEquals("cedar-47", result.records.single().content)
    }

    private fun saveWorkshopReference(): Long = db.saveKnowledge(KnowledgeDraft(
        "The workshop access code is cedar-47.",
        source = "document",
        sourceReference = "manual.pdf · chunk 1/1",
    ))
}
