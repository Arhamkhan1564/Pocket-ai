package com.pocketai.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineModelAssetsTest {
    private lateinit var context: Context
    private lateinit var good: OfflinePackSpec
    private lateinit var expected: ByteArray
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "offline-models/test-pack").deleteRecursively()
        ShadowStatFs.registerStats(File(context.filesDir, "offline-models/test-pack").absolutePath, 1_000_000, 900_000, 800_000)
        expected = context.assets.open("THIRD_PARTY_NOTICES.txt").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it.toInt() and 255) }
        good = OfflinePackSpec("test-pack", "Test pack", listOf(OfflinePackFile("fixture.txt", "THIRD_PARTY_NOTICES.txt", expected.size.toLong(), sha)))
    }

    @Test fun installationIsVerifiedReusedAndRepairsChangedPrivateBytes() = runBlocking {
        val directory = OfflineModelAssets.prepare(context, good)
        val installed = File(directory, "fixture.txt")
        assertArrayEquals(expected, installed.readBytes())
        val timestamp = installed.lastModified()
        assertEquals(directory, OfflineModelAssets.prepare(context, good))
        assertEquals(timestamp, installed.lastModified())
        installed.writeBytes(ByteArray(expected.size) { 42 })
        installed.setLastModified(timestamp + 5000)
        assertNull(OfflineModelAssets.installed(context, good))
        OfflineModelAssets.prepare(context, good)
        assertArrayEquals(expected, installed.readBytes())
    }

    @Test fun failedChecksumPreservesPreviousPackAndUserFiles() = runBlocking {
        val directory = OfflineModelAssets.prepare(context, good)
        val canary = File(context.filesDir, "saved-knowledge-canary.txt").apply { writeText("Keep this reference") }
        val bad = good.copy(files = good.files.map { it.copy(sha256 = "0".repeat(64)) })
        var failed = false
        try { OfflineModelAssets.prepare(context, bad) } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        assertArrayEquals(expected, File(directory, "fixture.txt").readBytes())
        assertEquals("Keep this reference", canary.readText())
        assertNotNull(OfflineModelAssets.installed(context, good))
        assertFalse(File(directory, "fixture.txt.part").exists())
    }
}
