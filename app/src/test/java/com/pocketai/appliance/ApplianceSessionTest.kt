package com.pocketai.appliance

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApplianceSessionTest {
    @Before fun resetState() { ApplianceSession.publish { ApplianceState() } }

    @Test fun backgroundContextCannotStartMicrophoneService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try { ApplianceSession.start(context); fail("A visible Activity is required") }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("visible")) }
        assertFalse(ApplianceSession.state.value.running)
    }

    @Test fun visibleActivityStillNeedsExplicitMicrophonePermission() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(VisibleActivity::class.java).setup()
        val activity = controller.get()
        try {
            try { ApplianceSession.start(activity); fail("Microphone permission is required") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("microphone permission")) }
            assertFalse(ApplianceSession.state.value.running)
            assertNull(shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedService)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun explicitStopCommandsSupportEnglishAndLocalScripts() {
        assertTrue(ApplianceForegroundService.isStopCommand("Stop assistant!"))
        assertTrue(ApplianceForegroundService.isStopCommand("بند کریں۔"))
        assertTrue(ApplianceForegroundService.isStopCommand("ਬੰਦ ਹੋ ਜਾਓ।"))
        assertFalse(ApplianceForegroundService.isStopCommand("What happens when I say stop assistant?"))
        assertFalse(ApplianceForegroundService.isStopCommand("Please remember stop assistant"))
    }

    @Test fun processRecoveryDoesNotRestartMicrophoneAutomatically() {
        val controller = Robolectric.buildService(ApplianceForegroundService::class.java).create()
        try {
            val result = controller.get().onStartCommand(null, 0, 1)
            assertEquals(android.app.Service.START_NOT_STICKY, result)
            assertFalse(ApplianceSession.state.value.running)
            assertTrue(ApplianceSession.state.value.status.contains("Restart"))
        } finally { controller.destroy() }
    }

    class VisibleActivity : Activity() { override fun hasWindowFocus(): Boolean = true }
}
