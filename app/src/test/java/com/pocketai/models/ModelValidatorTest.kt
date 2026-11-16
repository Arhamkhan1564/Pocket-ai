package com.pocketai.models

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelValidatorTest {
    private fun temporary(block: (File) -> Unit) {
        val file = File.createTempFile("pocket-model-test-", ".bin")
        try { block(file) } finally { file.delete() }
    }

    @Test fun readsValidGgufArchitectureAndParameterCount() = temporary { file ->
        writeGguf(file)
        val header = ModelValidator.validateHeader(file, ModelKind.LLM)
        assertEquals("llama", header.architecture)
        assertEquals("4", header.metadata["parameter_count"])
        assertEquals("GGUF v3", header.format)
    }

    @Test fun rejectsTruncatedTensorPayload() = temporary { file ->
        writeGguf(file)
        RandomAccessFile(file, "rw").use { it.setLength(24) }
        assertFails { ModelValidator.validateHeader(file, ModelKind.LLM) }
    }

    @Test fun rejectsForeignFileBeforeModelLoading() = temporary { file ->
        file.writeBytes(ByteArray(100) { 1 })
        assertFails { ModelValidator.validateHeader(file, ModelKind.LLM) }
        assertFails { ModelValidator.validateHeader(file, ModelKind.WHISPER) }
    }

    @Test fun rejectsHugeMetadataCountWithoutAllocating() = temporary { file ->
        RandomAccessFile(file, "rw").use { f ->
            f.write(le32(0x46554747)); f.write(le32(3)); f.write(le64(1)); f.write(le64(Long.MAX_VALUE)); f.setLength(100)
        }
        assertFails { ModelValidator.validateHeader(file, ModelKind.LLM) }
    }

    @Test fun distinguishesEnglishOnlyAndMultilingualWhisper() = temporary { file ->
        writeWhisper(file, 51_864)
        assertEquals("false", ModelValidator.validateHeader(file, ModelKind.WHISPER).metadata["multilingual"])
        writeWhisper(file, 51_865)
        val header = ModelValidator.validateHeader(file, ModelKind.WHISPER)
        assertEquals("true", header.metadata["multilingual"])
        assertEquals("tiny", header.metadata["variant"])
    }

    @Test fun rejectsIncompleteWhisperFile() = temporary { file ->
        writeWhisper(file, 51_865)
        RandomAccessFile(file, "rw").use { it.setLength(500) }
        assertFails { ModelValidator.validateHeader(file, ModelKind.WHISPER) }
    }

    private fun writeGguf(file: File) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            f.write(le32(0x46554747)); f.write(le32(3)); f.write(le64(1)); f.write(le64(1))
            writeString(f, "general.architecture"); f.write(le32(8)); writeString(f, "llama")
            writeString(f, "test.weight"); f.write(le32(1)); f.write(le64(4)); f.write(le32(0)); f.write(le64(0))
            val dataOffset = ((f.filePointer + 31) / 32) * 32
            f.setLength(dataOffset + 16)
        }
    }

    private fun writeWhisper(file: File, vocabulary: Int) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            listOf(0x67676d6c, vocabulary, 1500, 384, 6, 4, 448, 384, 6, 4, 80, 1).forEach { f.write(le32(it)) }
            f.setLength(1024 * 1024L)
        }
    }

    private fun writeString(f: RandomAccessFile, value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        f.write(le64(data.size.toLong())); f.write(data)
    }
    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun le64(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    private fun assertFails(block: () -> Unit) {
        try { block(); fail("Malformed model must be rejected") } catch (_: IllegalArgumentException) { } catch (_: java.io.EOFException) { }
    }
}
