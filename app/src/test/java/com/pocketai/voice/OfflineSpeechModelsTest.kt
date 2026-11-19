package com.pocketai.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketai.AppSettings
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Metadata/selection safety checks, not recognition quality or model-runtime benchmarks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineSpeechModelsTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var model: File
    private lateinit var vocabulary: File

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE).edit().clear().commit()
        directory = File(context.cacheDir, "speech-plan-test").apply { deleteRecursively(); mkdirs() }
        model = File(directory, "selected-model.bin").apply { writeBytes(ByteArray(1024) { 3 }) }
        vocabulary = File(directory, "vocab.json").apply { writeText(validVocabulary().toString()) }
    }

    @After fun cleanup() {
        directory.deleteRecursively()
        context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun explicitDisableSurvivesRestartAndDiffersFromUnconfiguredSelection() {
        val settings = AppSettings(context)
        assertTrue(!settings.speechRecognitionSelectionConfigured)
        settings.selectedWhisperId = null
        val restarted = AppSettings(context)
        assertTrue(restarted.speechRecognitionSelectionConfigured)
        assertEquals(null, restarted.selectedWhisperId)
    }

    @Test fun customSelectionAndLaterDisableRemainUserChoicesAfterRestart() {
        val settings = AppSettings(context)
        val customId = "11111111-2222-3333-4444-555555555555"
        settings.selectedWhisperId = customId
        assertTrue(AppSettings(context).speechRecognitionSelectionConfigured)
        assertEquals(customId, AppSettings(context).selectedWhisperId)
        settings.selectedWhisperId = null
        assertTrue(AppSettings(context).speechRecognitionSelectionConfigured)
        assertEquals(null, AppSettings(context).selectedWhisperId)
    }

    @Test fun preMarkerCustomSelectionIsPreservedWhenUpgradingSettings() {
        val customId = "11111111-2222-3333-4444-555555555555"
        context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE).edit()
            .putString("whisper_id", customId).remove("speech_selection_set").commit()
        val upgraded = AppSettings(context)
        assertTrue(upgraded.speechRecognitionSelectionConfigured)
        assertEquals(customId, upgraded.selectedWhisperId)
    }

    @Test fun capturedWhisperPlanRejectsRemovedOrReplacedResource() {
        val plan = SpeechRecognitionPlan(model, SpeechBackend.WHISPER, "ur-PK")
        plan.validateFiles()
        model.writeBytes(ByteArray(2048))
        assertThrows(IllegalArgumentException::class.java) { plan.validateFiles() }
        assertTrue(model.delete())
        assertThrows(IllegalArgumentException::class.java) { plan.validateFiles() }
    }

    @Test fun changedTimestampCannotSilentlyReuseSameSizeRecordingPlan() {
        val plan = SpeechRecognitionPlan(model, SpeechBackend.WHISPER, "en")
        assertTrue(model.setLastModified(plan.modelModified + 2_000))
        assertEquals(plan.modelBytes, model.length())
        assertThrows(IllegalArgumentException::class.java) { plan.validateFiles() }
    }

    @Test fun capturedSpeechDetectorCannotBeRemovedOrReplacedDuringRecording() {
        val detector = File(directory, "silero-vad.onnx").apply { writeBytes(ByteArray(256)) }
        val plan = SpeechRecognitionPlan(model, SpeechBackend.WHISPER, "ur-PK", speechPresenceFile = detector)
        plan.validateFiles()
        detector.writeBytes(ByteArray(512))
        assertThrows(IllegalArgumentException::class.java) { plan.validateFiles() }
        assertTrue(detector.delete())
        assertThrows(IllegalArgumentException::class.java) { plan.validateFiles() }
    }

    @Test fun ctcPlanRequiresPunjabiAndItsCapturedVocabulary() {
        val plan = SpeechRecognitionPlan(model, SpeechBackend.PUNJABI_CTC, "pa-Guru-IN", vocabulary)
        plan.validateFiles()
        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(languageTag = "ur-PK").validateFiles()
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(vocabularyFile = null).validateFiles()
        }
        vocabulary.appendText(" ")
        assertThrows(IllegalArgumentException::class.java) { plan.validateFiles() }
    }

    @Test fun ctcPlanRejectsDeletedVocabulary() {
        val plan = SpeechRecognitionPlan(model, SpeechBackend.PUNJABI_CTC, "pa-Arab-PK", vocabulary)
        assertTrue(vocabulary.delete())
        assertThrows(IllegalArgumentException::class.java) { plan.validateFiles() }
    }

    @Test fun vocabularyUsesDeclaredIdsRatherThanJsonInsertionOrder() {
        val labels = OfflineSpeechModels.punjabiVocabulary(vocabulary)
        assertEquals(65, labels.size)
        assertEquals("<pad>", labels[1])
        assertEquals("|", labels[4])
        assertEquals("token-64", labels[64])
    }

    @Test fun vocabularyRejectsDuplicateIds() {
        vocabulary.writeText(validVocabulary().put("token-64", 63).toString())
        assertThrows(IllegalArgumentException::class.java) { OfflineSpeechModels.punjabiVocabulary(vocabulary) }
    }

    @Test fun vocabularyRejectsFractionalIdsInsteadOfTruncatingThem() {
        vocabulary.writeText(validVocabulary().put("token-64", 64.5).toString())
        assertThrows(IllegalArgumentException::class.java) { OfflineSpeechModels.punjabiVocabulary(vocabulary) }
    }

    @Test fun vocabularyRejectsIncorrectBlankToken() {
        val json = validVocabulary().apply { remove("<pad>"); put("wrong-blank", 1) }
        vocabulary.writeText(json.toString())
        assertThrows(IllegalArgumentException::class.java) { OfflineSpeechModels.punjabiVocabulary(vocabulary) }
    }

    @Test fun vocabularyRejectsMissingWordDelimiter() {
        val json = validVocabulary().apply { remove("|"); put("wrong-delimiter", 4) }
        vocabulary.writeText(json.toString())
        assertThrows(IllegalArgumentException::class.java) { OfflineSpeechModels.punjabiVocabulary(vocabulary) }
    }

    private fun validVocabulary(): JSONObject = JSONObject().apply {
        // Reverse insertion deliberately differs from token-ID order.
        for (id in 64 downTo 0) put(when (id) { 1 -> "<pad>"; 4 -> "|"; else -> "token-$id" }, id)
    }
}
