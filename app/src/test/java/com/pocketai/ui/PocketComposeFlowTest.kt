package com.pocketai.ui

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import com.pocketai.AppSettings
import com.pocketai.MainActivity
import com.pocketai.data.PocketDatabase
import com.pocketai.security.AuthManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Ignore
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Intended real Compose, PIN authentication, SQLite and retrieval flow. Currently quarantined:
 * Robolectric/Compose dialog-window idling does not settle after opening the PIN dialog on
 * this host, including with semantic input instead of IME input. The on-device PocketFlowTest
 * remains enabled; no successful end-to-end UI result is claimed for this ignored test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Ignore("Robolectric/Compose 1.7.6 dialog idling times out after opening PIN dialog on SDK 28 and 34; see documentation/VERIFICATION.md. Run enabled androidTest/PocketFlowTest on a stable device.")
class PocketComposeFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var context: Context
    private val prepareApp = object : ExternalResource() {
        override fun before() {
            context = ApplicationProvider.getApplicationContext()
            context.deleteDatabase("pocket_ai.db")
            context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE).edit().clear().commit()
            File(context.filesDir, "models").deleteRecursively()
            AppSettings(context).apply {
                setupComplete = true
                responseLanguage = "en"
                speechEnabled = false
            }
            AuthManager(context).apply { configure("246810"); lock() }
        }
    }

    @get:Rule val rules: TestRule = RuleChain.outerRule(prepareApp).around(compose)

    @Test fun protectedTeachingThenQuestionAndActivityRestartPreserveEvidence() {
        compose.onNodeWithText("User Mode").assertIsSelected()
        compose.onNodeWithText("Training Mode").performClick()
        setField("Administrator PIN", "246810")
        compose.onNodeWithText("Unlock", substring = false).performClick()
        awaitText("Knowledge, answer or note")

        setField("Question or title · optional", "What is our launch code?", scroll = true)
        setField("Knowledge, answer or note", "Our launch code is cedar.", scroll = true)
        setField("Category", "Team", scroll = true)
        compose.onNodeWithText("Review & save", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Confirm & save").performClick()
        awaitText("Our launch code is cedar.")

        compose.onNodeWithText("User Mode").performClick()
        setField("Ask Pocket AI…", "What is our launch code?")
        compose.onNodeWithContentDescription("Send message").performClick()
        awaitText("Our launch code is cedar.")
        compose.onNodeWithText("Saved sources", substring = true).assertExists()

        PocketDatabase(context, AuthManager(context)).use { db ->
            assertEquals(1, db.countKnowledge())
            assertEquals("Our launch code is cedar.", db.retrieve("launch code").single().content)
            assertEquals(2, db.conversations().size)
        }

        compose.activityRule.scenario.recreate()
        awaitText("Our launch code is cedar.")
        compose.onNodeWithText("User Mode").assertIsSelected()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setField(label: String, value: String, scroll: Boolean = false) {
        val field = compose.onNodeWithText(label, substring = false)
        if (scroll) field.performScrollTo()
        field.performSemanticsAction(SemanticsActions.SetText) { setText -> setText(AnnotatedString(value)) }
    }
}
