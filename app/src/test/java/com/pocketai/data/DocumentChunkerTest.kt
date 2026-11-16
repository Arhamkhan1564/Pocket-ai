package com.pocketai.data

import org.junit.Assert.*
import org.junit.Test

class DocumentChunkerTest {
    @Test fun longParagraphKeepsEveryWordAndSharesBoundedContextBetweenChunks() {
        val sentences = (0 until 150).map { index -> "Sentence $index contains unique$index reference$index evidence$index and original wording." }
        val text = sentences.joinToString(" ")
        val chunks = DocumentChunker.chunk(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= DocumentChunker.MAX_CHUNK_CHARACTERS })
        sentences.forEachIndexed { index, _ -> assertTrue("Lost source marker $index", chunks.any { "unique$index " in it }) }
        chunks.zipWithNext().forEach { (left, right) ->
            val leftTokens = left.split(Regex("\\s+")).filter { it.startsWith("unique") }.toSet()
            val rightTokens = right.split(Regex("\\s+")).filter { it.startsWith("unique") }.toSet()
            assertTrue("Adjacent chunks lost overlapping source context", leftTokens.intersect(rightTokens).isNotEmpty())
        }
    }

    @Test fun shortOriginalParagraphsAndUnicodeRemainEditableWithoutRewriting() {
        assertEquals(listOf("Original English.", "اردو ਪੰਜਾਬੀ"), DocumentChunker.chunk("Original English.\r\n\r\nاردو ਪੰਜਾਬੀ"))
        val long = (0..400).joinToString(" ") { "Reference$it 🧪" }
        val chunks = DocumentChunker.chunk(long)
        assertTrue(chunks.all { !Character.isLowSurrogate(it.first()) && !Character.isHighSurrogate(it.last()) })
    }

    @Test fun pathologicalLongWordsAndDocumentLimitsRemainBounded() {
        val chunks = DocumentChunker.chunk("A".repeat(20_000))
        assertTrue(chunks.size < 30)
        assertTrue(chunks.all { it.length <= DocumentChunker.MAX_CHUNK_CHARACTERS })
        assertThrows(IllegalArgumentException::class.java) { DocumentChunker.chunk(" ") }
        assertThrows(IllegalArgumentException::class.java) { DocumentChunker.chunk("A".repeat(DocumentChunker.MAX_DOCUMENT_CHARACTERS + 1)) }
        assertThrows(IllegalArgumentException::class.java) { DocumentChunker.chunk((0..DocumentChunker.MAX_CHUNKS).joinToString("\n\n") { "Paragraph $it" }) }
    }
}
