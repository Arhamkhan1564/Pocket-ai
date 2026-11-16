package com.pocketai.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketai.AppSettings
import com.pocketai.data.KnowledgeDraft
import com.pocketai.data.PocketDatabase
import com.pocketai.engine.LocalAiEngine
import com.pocketai.models.ModelManager
import com.pocketai.security.AuthManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantServiceTest {
    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var db: PocketDatabase
    private lateinit var service: AssistantService

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("pocket_ai.db")
        context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "models").deleteRecursively()
        val auth = AuthManager(context).also { it.configure("123456") }
        db = PocketDatabase(context, auth)
        settings = AppSettings(context)
        service = AssistantService(db, ModelManager(context), LocalAiEngine(context), settings)
    }

    @After fun tearDown() { db.close() }

    @Test fun noModelReturnsExactSavedEvidenceWithItsSource() = runBlocking {
        val content = "Photosynthesis converts light energy into chemical energy."
        val id = db.saveKnowledge(KnowledgeDraft(content, "What is photosynthesis?", category = "Science"))
        val answer = service.answer("What is photosynthesis?")
        assertEquals(content, answer.text)
        assertTrue(answer.sources.contains("#$id"))
        assertTrue(answer.sources.contains("Science"))
        assertEquals("Saved knowledge · original text", answer.engine)
    }

    @Test fun ordinaryConversationNeverBecomesVerifiedKnowledge() = runBlocking {
        db.addConversation("user", "The moon is made of cheese")
        db.addConversation("assistant", "An unverified conversation statement")
        val answer = service.answer("What is the moon made of?")
        assertEquals("No matching knowledge", answer.engine)
        assertEquals("", answer.sources)
        assertEquals(0, db.countKnowledge())
    }

    @Test fun repeatFindsMostRecentAssistantInChronologicalHistory() = runBlocking {
        db.addConversation("user", "First question")
        db.addConversation("assistant", "First answer", sources = "#1")
        db.addConversation("user", "Second question")
        db.addConversation("assistant", "Latest answer", sources = "#2")
        db.addConversation("user", "Repeat your previous answer")
        val answer = service.answer("Repeat your previous answer?")
        assertEquals("Latest answer", answer.text)
        assertEquals("#2", answer.sources)
        assertEquals("Command", answer.engine)
    }

    @Test fun speedCommandChangesTheRealSettingAndHonorsTheMinimum() = runBlocking {
        settings.speechRate = .9f
        val answer = service.answer("Speak more slowly.")
        assertEquals(.75f, settings.speechRate, .001f)
        assertEquals("Command", answer.engine)
        repeat(10) { service.answer("slower") }
        assertEquals(.5f, settings.speechRate, .001f)
        assertEquals(.5f, AppSettings(context).speechRate, .001f)
    }

    @Test fun languageCommandsOnlySelectTheThreeConfiguredLanguages() = runBlocking {
        settings.thirdLanguage = "fr"
        assertEquals("Command", service.answer("Change the response language to Urdu.").engine)
        assertEquals("ur", settings.responseLanguage)
        assertEquals("Command", service.answer("speak Punjabi").engine)
        assertEquals("pa", settings.responseLanguage)
        assertEquals("Command", service.answer("respond in French").engine)
        assertEquals("fr", settings.responseLanguage)
        assertEquals("No matching knowledge", service.answer("speak Spanish").engine)
        assertEquals("fr", settings.responseLanguage)
    }

    @Test fun unknownQuestionUsesConfiguredUrduAndDoesNotInventFacts() = runBlocking {
        settings.responseLanguage = "ur"
        val answer = service.answer("نامعلوم سوال")
        assertEquals(LanguageTools.unknown("ur"), answer.text)
        assertTrue(LanguageTools.isRtl(answer.text))
        assertEquals("", answer.sources)
        assertEquals(0, db.countKnowledge())
    }

    @Test fun unknownPunjabiResponseUsesChosenGurmukhiScript() = runBlocking {
        settings.responseLanguage = "pa"
        settings.punjabiScript = "Guru"
        val answer = service.answer("ਅਣਜਾਣ ਸਵਾਲ")
        assertTrue(answer.text.any { it in '\u0A00'..'\u0A7F' })
        assertFalse(LanguageTools.isRtl(answer.text))
        assertEquals("No matching knowledge", answer.engine)
    }
}
