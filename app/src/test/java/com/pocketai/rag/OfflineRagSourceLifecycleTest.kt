package com.pocketai.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketai.AppSettings
import com.pocketai.data.PocketDatabase
import com.pocketai.models.ModelManager
import com.pocketai.security.AuthManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises actual source lookup/version checks; translation waits at a controlled boundary, without ML. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineRagSourceLifecycleTest {
    private lateinit var context: Context
    private lateinit var auth: AuthManager
    private lateinit var db: PocketDatabase
    private lateinit var settings: AppSettings
    private lateinit var models: ModelManager
    private val services = mutableListOf<OfflineRagServices>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("pocket_ai.db")
        context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).edit().clear().commit()
        auth = AuthManager(context).also { it.configure("123456") }
        db = PocketDatabase(context, auth)
        settings = AppSettings(context)
        models = ModelManager(context)
    }

    @After fun close() {
        services.forEach { it.close() }
        db.close()
    }

    @Test fun editedSourceCannotPublishTranslationOfItsPreviousContent() = runBlocking {
        withTimeout(10_000) {
            val source = importedSource()
            val service = delayedService()
            val pending = async { runCatching { service.translateSource(source.id, "ur") } }
            assertEquals(source.content, service.entered.await())

            val corrected = "The reference opening time is ten in the morning."
            db.saveKnowledge(source.toDraft().copy(content = corrected), source.id)
            service.release.complete(Unit)

            val result = pending.await()
            assertTrue("A translation of superseded source text must be rejected", result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(corrected, db.getKnowledge(source.id)?.content)
        }
    }

    @Test fun deletedDocumentCannotPublishItsDelayedSourceTranslation() = runBlocking {
        withTimeout(10_000) {
            val source = importedSource()
            val document = db.getDocumentForKnowledge(source.id)!!
            val service = delayedService()
            val pending = async { runCatching { service.translateSource(source.id, "ur") } }
            assertEquals(source.content, service.entered.await())

            assertEquals(1, db.deleteDocument(document.id))
            service.release.complete(Unit)

            val result = pending.await()
            assertTrue("A removed source must not yield a usable delayed translation", result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertNull(db.getKnowledge(source.id))
            assertNull(db.getDocumentForKnowledge(source.id))
        }
    }

    @Test fun sameLanguageOriginalRemainsReadableWhileAdministrationIsLocked() = runBlocking {
        withTimeout(10_000) {
            val source = importedSource()
            val revision = db.knowledgeRevision()
            val service = OfflineRagServices(context, db, settings, models).also(services::add)
            auth.lock()

            // The real English passthrough needs neither a model session nor administrator authorization.
            val original = service.translateSource(source.id, "en")

            assertEquals(source.content, original.text)
            assertEquals(source.content, original.sourceText)
            assertFalse(original.scriptConverted)
            assertEquals(source, db.getKnowledge(source.id))
            assertEquals(revision, db.knowledgeRevision())
            assertFalse(auth.isUnlocked)
        }
    }

    private fun importedSource() = run {
        assertEquals(1, db.importText("The reference opening time is nine in the morning.", "opening-times.txt"))
        db.getDocumentChunks(db.listDocuments().single().id).single()
    }

    private fun delayedService() = DelayedTranslation(context, db, settings, models).also(services::add)

    private class DelayedTranslation(context: Context, db: PocketDatabase, settings: AppSettings, models: ModelManager) :
        OfflineRagServices(context, db, settings, models) {
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()

        override suspend fun translateText(source: String, targetLanguage: String, script: String): OfflineTranslation {
            entered.complete(source)
            release.await()
            return OfflineTranslation("مقامی ترجمے کا آزمائشی متن۔", source)
        }
    }
}
