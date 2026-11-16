package com.pocketai.models

import android.content.Context
import android.net.Uri
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ModelManagerTest {
    private lateinit var context: Context
    private lateinit var source: File
    private lateinit var models: ModelManager

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "models").deleteRecursively()
        source = File(context.cacheDir, "test-model.gguf")
        models = ModelManager(context)
        // Robolectric defaults storage to zero; supply an actual device-like capacity.
        ShadowStatFs.registerStats(File(context.filesDir, "models").absolutePath, 1_000_000, 900_000, 800_000)
        RandomAccessFile(source, "rw").use { f ->
            f.setLength(0)
            fun int(value: Int) = f.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            fun long(value: Long) = f.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
            fun string(value: String) { val data = value.toByteArray(); long(data.size.toLong()); f.write(data) }
            int(0x46554747); int(3); long(1); long(1)
            string("general.architecture"); int(8); string("llama")
            string("test.weight"); int(1); long(4); int(0); long(0)
            f.setLength(((f.filePointer + 31) / 32) * 32 + 16)
        }
    }

    @After fun cleanup() {
        source.delete()
        File(context.filesDir, "models").deleteRecursively()
    }

    @Test fun importsPersistsAndRemovesPrivateModel() {
        val imported = models.importModel(Uri.fromFile(source), ModelKind.LLM)
        assertEquals("llama", imported.architecture)
        assertEquals(64, imported.sha256.length)
        assertEquals(source.length(), imported.sizeBytes)
        assertTrue(models.fileFor(imported).isFile)
        val afterRestart = ModelManager(context).list()
        assertEquals(imported.id, afterRestart.single().id)
        models.remove(imported.id)
        assertTrue(models.list().isEmpty())
        assertFalse(File(imported.path).exists())
    }

    @Test fun duplicateImportLeavesOriginalModelIntact() {
        val first = models.importModel(Uri.fromFile(source), ModelKind.LLM)
        try { models.importModel(Uri.fromFile(source), ModelKind.LLM); fail("Duplicate must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(first.id, models.list().single().id)
        assertTrue(models.fileFor(first).exists())
        assertFalse(File(context.filesDir, "models").listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test fun failedValidationDoesNotInstallAnything() {
        source.writeBytes(ByteArray(200))
        try { models.importModel(Uri.fromFile(source), ModelKind.LLM); fail("Invalid model must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertTrue(models.list().isEmpty())
        assertTrue(File(context.filesDir, "models").listFiles().orEmpty().isEmpty())
    }

    @Test fun refusesArbitraryFilePaths() {
        val imported = models.importModel(Uri.fromFile(source), ModelKind.LLM)
        try { models.fileFor(imported.copy(path = source.absolutePath)); fail("External path must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun reservesStorageBeforeStartingAnImport() {
        ShadowStatFs.registerStats(File(context.filesDir, "models").absolutePath, 1_000_000, 10_000, 10_000)
        try { models.importModel(Uri.fromFile(source), ModelKind.LLM); fail("Storage reserve must be enforced") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("128 MB")) }
        assertTrue(models.list().isEmpty())
    }
}
