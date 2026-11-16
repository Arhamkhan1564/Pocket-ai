package com.pocketai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketai.data.PocketDatabase
import com.pocketai.security.AuthManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a disposable debug installation; never run against a user's installed data. */
@RunWith(AndroidJUnit4::class)
class PocketFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun protectedTeachingRetrievalAndRestartWorkWithoutModels() {
        check(BuildConfig.DEBUG) { "Flow tests require a disposable debug app." }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiTimeout = InstrumentationRegistry.getArguments().getString("uiTimeoutMs")?.toLongOrNull()?.coerceIn(30_000L, 180_000L) ?: 30_000L
        context.deleteDatabase("pocket_ai.db")
        context.getSharedPreferences("pocket_ai_auth", 0).edit().clear().commit()
        context.getSharedPreferences("pocket_settings", 0).edit().clear().commit()
        val settings = AppSettings(context).apply { setupComplete = true; speechEnabled = false; responseLanguage = "en" }
        val auth = AuthManager(context).apply { configure("246810"); lock() }
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.onNodeWithText("Training Mode").performClick()
            compose.onNodeWithText("Administrator PIN").performTextInput("246810")
            compose.onNodeWithText("Unlock", substring = false).performClick()
            compose.waitUntil(uiTimeout) { compose.onAllNodesWithText("Knowledge, answer or note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Question or title · optional").performTextInput("What is our launch code?")
            compose.onNodeWithText("Knowledge, answer or note").performTextInput("Our launch code is cedar.")
            compose.onNodeWithText("Category", substring = false).performTextClearance()
            compose.onNodeWithText("Category", substring = false).performTextInput("Team")
            compose.onNodeWithText("Review & save", substring = true).performScrollTo().performClick()
            compose.onNodeWithText("Confirm & save").performClick()
            compose.waitUntil(uiTimeout) { compose.onAllNodesWithText("Our launch code is cedar.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("User Mode").performClick()
            compose.onNodeWithText("Ask Pocket AI…").performTextInput("What is our launch code?")
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.waitUntil(uiTimeout) { compose.onAllNodesWithText("Our launch code is cedar.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Saved sources", substring = true).assertExists()
            PocketDatabase(context, auth).use { db ->
                assertEquals(1, db.countKnowledge())
                assertEquals("Our launch code is cedar.", db.retrieve("launch code").first().content)
                assertEquals(2, db.conversations().size)
                assertFalse(auth.isUnlocked)
            }
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(uiTimeout) { compose.onAllNodesWithText("Our launch code is cedar.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("User Mode").assertIsSelected()
            assertTrue(settings.setupComplete)
        } catch (failure: Throwable) {
            runCatching {
                java.io.File(context.cacheDir, "flow-failure-tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "flow-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            throw failure
        } finally { scenario.close() }
    }
}
