package com.pocketai.models

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pocketai.AppSettings
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Verifies install integrity and restart/concurrent behavior without depending on model downloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BundledModelInstallTest {
    private lateinit var context: Context
    private lateinit var models: ModelManager
    private lateinit var source: File
    private lateinit var checksum: String

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("pocket_bundled_speech", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "models").deleteRecursively()
        models = ModelManager(context)
        ShadowStatFs.registerStats(File(context.filesDir, "models").absolutePath, 1_000_000, 900_000, 800_000)
        source = File(context.cacheDir, "bundled-speech-fixture.bin")
        RandomAccessFile(source, "rw").use { file ->
            file.setLength(0)
            // Structurally valid tiny multilingual Whisper header with a bounded synthetic body.
            listOf(0x67676d6c, 51_865, 1500, 384, 6, 4, 448, 384, 6, 4, 80, 1009).forEach {
                file.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(it).array())
            }
            file.setLength(1024L * 1024)
        }
        checksum = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @After fun cleanup() {
        source.delete()
        File(context.filesDir, "models").deleteRecursively()
    }

    @Test fun verifiedInstallPersistsAndReusesCopyWithoutReadingAssetAgain() {
        val installed = models.installVerifiedBundledModel("Included speech", source.length(), checksum) { source.inputStream() }
        assertEquals(checksum, installed.sha256)
        assertEquals("true", installed.metadata["multilingual"])
        assertEquals("true", installed.metadata["bundled"])
        val afterRestart = ModelManager(context).installVerifiedBundledModel("Included speech", source.length(), checksum) {
            error("A valid private copy must be reused")
        }
        assertEquals(installed.id, afterRestart.id)
        assertEquals(1, models.list().size)
    }

    @Test fun checksumMismatchLeavesNoRegisteredModelOrPartialFile() {
        try {
            models.installVerifiedBundledModel("Included speech", source.length(), "0".repeat(64)) { source.inputStream() }
            fail("Unverified model must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("checksum"))
        }
        assertTrue(models.list().isEmpty())
        assertTrue(File(context.filesDir, "models").listFiles().orEmpty().isEmpty())
    }

    @Test fun interruptedOrDamagedPrivateCopyIsRepairedWithStableId() {
        val installed = models.installVerifiedBundledModel("Included speech", source.length(), checksum) { source.inputStream() }
        File(installed.path).writeBytes(byteArrayOf(1, 2, 3))
        val repaired = ModelManager(context).installVerifiedBundledModel("Included speech", source.length(), checksum) { source.inputStream() }
        assertEquals(installed.id, repaired.id)
        assertEquals(source.length(), File(repaired.path).length())
        assertEquals(1, models.list().size)
        assertFalse(File(context.filesDir, "models").listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test fun simultaneousManagersShareOneVerifiedInstallation() {
        val start = CountDownLatch(1)
        val reads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map {
                pool.submit<InstalledModel> {
                    start.await()
                    ModelManager(context).installVerifiedBundledModel("Included speech", source.length(), checksum) {
                        reads.incrementAndGet()
                        source.inputStream()
                    }
                }
            }
            start.countDown()
            val installed = tasks.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(installed[0].id, installed[1].id)
            assertEquals(1, reads.get())
            assertEquals(1, models.list().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun interruptedRegistryCommitDoesNotAccumulateOrphanModels() {
        val first = models.installVerifiedBundledModel("Included speech", source.length(), checksum) { source.inputStream() }
        // Model rename completed, but process ended before the registry commit became visible.
        assertTrue(File(context.filesDir, "models/registry.json").delete())
        val restarted = ModelManager(context).installVerifiedBundledModel("Included speech", source.length(), checksum) { source.inputStream() }
        assertEquals(first.id, restarted.id)
        assertEquals(1, File(context.filesDir, "models").listFiles().orEmpty().count { it.extension == "bin" })
        assertEquals(1, models.list().size)
    }

    @Test fun customFirstKeepsItsIdentityAndChoiceWhenIdenticalBundledBytesAreInstalled() {
        val custom = models.importModel(Uri.fromFile(source), ModelKind.WHISPER)
        val settings = AppSettings(context).apply { selectedWhisperId = custom.id }
        val bundled = models.installVerifiedBundledModel("Included speech", source.length(), checksum) { source.inputStream() }

        assertNotEquals(custom.id, bundled.id)
        assertNotEquals(custom.path, bundled.path)
        assertFalse(custom.metadata["bundled"] == "true")
        assertEquals("true", bundled.metadata["bundled"])
        assertEquals(custom.sha256, bundled.sha256)
        assertEquals(2, models.list().size)
        val persisted = ModelManager(context).list().associateBy { it.id }
        assertFalse(persisted.getValue(custom.id).metadata["bundled"] == "true")
        assertEquals("true", persisted.getValue(bundled.id).metadata["bundled"])
        assertEquals(custom.id, AppSettings(context).selectedWhisperId)
        assertArrayEquals(source.readBytes(), models.fileFor(custom).readBytes())
        assertEquals(bundled.id, ModelManager(context).installVerifiedBundledModel("Included speech", source.length(), checksum) {
            error("The existing bundled identity should be reused without reading the asset")
        }.id)
        assertThrows(IllegalArgumentException::class.java) { models.importModel(Uri.fromFile(source), ModelKind.WHISPER) }
        assertEquals(custom.id, settings.selectedWhisperId)
        assertEquals(2, models.list().size)
    }

    @Test fun bundledFirstAllowsIdenticalCustomIdentityAndOnlyBundledRemovalMarksItRemoved() {
        val bundled = models.installVerifiedBundledModel("Included speech", source.length(), checksum) { source.inputStream() }
        val custom = models.importModel(Uri.fromFile(source), ModelKind.WHISPER)

        assertNotEquals(bundled.id, custom.id)
        assertEquals("true", bundled.metadata["bundled"])
        assertFalse(custom.metadata["bundled"] == "true")
        assertEquals(bundled.sha256, custom.sha256)
        assertArrayEquals(source.readBytes(), models.fileFor(bundled).readBytes())
        assertArrayEquals(source.readBytes(), models.fileFor(custom).readBytes())
        assertEquals(2, models.list().size)
        assertEquals(bundled.id, models.installVerifiedBundledModel("Included speech", source.length(), checksum) {
            error("Custom import must not replace the reusable bundled entry")
        }.id)

        // Removal reads registry identity, not model tensors. Give the bounded fixtures the
        // real included checksum to exercise its removal marker without large test weights.
        val registry = File(context.filesDir, "models/registry.json")
        val entries = JSONArray(registry.readText())
        for (index in 0 until entries.length()) entries.getJSONObject(index).put("sha256", BundledSpeechModel.SHA256)
        registry.writeText(entries.toString())
        val preferences = context.getSharedPreferences("pocket_bundled_speech", Context.MODE_PRIVATE)

        models.remove(custom.id)
        assertNull(preferences.getString("removed_sha256", null))
        assertEquals(bundled.id, models.list().single().id)
        assertArrayEquals(source.readBytes(), models.fileFor(bundled).readBytes())
        assertFalse(File(custom.path).exists())

        models.remove(bundled.id)
        assertEquals(BundledSpeechModel.SHA256, preferences.getString("removed_sha256", null))
        assertTrue(models.list().isEmpty())
    }
}
