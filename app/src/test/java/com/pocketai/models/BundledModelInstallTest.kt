package com.pocketai.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
