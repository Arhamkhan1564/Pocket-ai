package com.pocketai.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketai.AppSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppSettingsSpeechSelectionTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun explicitDisableSurvivesReloadAndDiffersFromUntouchedFirstRun() {
        val settings = AppSettings(context)
        assertNull(settings.selectedWhisperId)
        assertFalse(settings.speechRecognitionSelectionConfigured)

        settings.selectedWhisperId = "included-entry"
        settings.selectedWhisperId = null

        val reloaded = AppSettings(context)
        assertNull(reloaded.selectedWhisperId)
        assertTrue(reloaded.speechRecognitionSelectionConfigured)
    }

    @Test fun importedLegacyChoiceIsConfiguredAndIsNotReplacedByUnrelatedSettings() {
        context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE).edit().putString("whisper_id", "custom-import").commit()
        val settings = AppSettings(context)
        assertTrue(settings.speechRecognitionSelectionConfigured)
        settings.punjabiScript = "Guru"
        settings.responseLanguage = "ur"

        val reloaded = AppSettings(context)
        assertEquals("custom-import", reloaded.selectedWhisperId)
        assertTrue(reloaded.speechRecognitionSelectionConfigured)
    }
}
