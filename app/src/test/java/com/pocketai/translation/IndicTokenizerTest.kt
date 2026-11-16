package com.pocketai.translation

import org.junit.Assert.*
import org.junit.Test

class IndicTokenizerTest {
    private val source = mapOf("eng_Latn" to 4, "urd_Arab" to 68, "pan_Guru" to 46, "\u2581The" to 18, "\u2581tablet" to 7275)

    @Test fun modelIdsComeFromPieceStringsAndLanguageTagsAreNotEncoded() {
        var encoded = ""
        val codec = IndicTokenizer.forTests(source, Array(6) { "" }, { text -> encoded = text; listOf("\u2581The", "\u2581tablet", "missing") })
        codec.use {
            assertArrayEquals(longArrayOf(4, 68, 18, 7275, 3, 2), it.encode("The tablet.", "ur-PK"))
            assertEquals("The tablet .", encoded)
            assertFalse(encoded.contains("eng_Latn"))
            assertArrayEquals(longArrayOf(4, 46, 18, 7275, 3, 2), it.encode("The tablet.", "pa-Guru-IN"))
        }
    }

    @Test fun inverseTargetDictionaryJoinRendersUnifiedPunjabiAsGurmukhi() {
        val target = arrayOf("<s>", "<pad>", "</s>", "<unk>", "\u2581दवा", "\u2581।")
        IndicTokenizer.forTests(source, target, { emptyList() }).use { codec ->
            assertEquals("ਦਵਾ।", codec.decode(longArrayOf(0, 1, 3, 4, 5, 2), "pa-Guru"))
        }
    }

    @Test fun arabicOutputRemainsArabicAndAttachesUrduPunctuation() {
        val target = arrayOf("<s>", "<pad>", "</s>", "<unk>", "\u2581دوا", "\u2581۔")
        IndicTokenizer.forTests(source, target, { emptyList() }).use { codec ->
            assertEquals("دوا۔", codec.decode(longArrayOf(4, 5, 2), "ur"))
        }
    }

    @Test fun unsupportedShahmukhiTargetIsNotSilentlyTreatedAsGurmukhi() {
        assertThrows(IllegalArgumentException::class.java) { IndicTokenizer.targetTag("pa-Arab") }
        assertThrows(IllegalArgumentException::class.java) { IndicTokenizer.targetTag("pa") }
        assertThrows(IllegalArgumentException::class.java) { IndicTokenizer.targetTag("hi") }
    }

    @Test fun sourceLimitIncludesTwoLanguageTagsAndEosAndNeverTruncates() {
        IndicTokenizer.forTests(source, Array(4) { "" }, { List(254) { "\u2581The" } }).use { codec ->
            assertThrows(IllegalArgumentException::class.java) { codec.encode("The tablet.", "ur") }
        }
        IndicTokenizer.forTests(source, Array(4) { "" }, { List(253) { "\u2581The" } }).use { codec ->
            assertEquals(256, codec.encode("The tablet.", "ur").size)
        }
    }

    @Test fun requestEntityMapsAreConsumedAndNeverSharedWithNextRequest() {
        val target = arrayOf("<s>", "<pad>", "</s>", "<unk>", "\u2581<ID1>")
        IndicTokenizer.forTests(source, target, { emptyList() }).use { codec ->
            codec.encode("Write to first@example.org.", "ur")
            assertEquals("first@example.org", codec.decode(longArrayOf(4, 2), "ur"))
            codec.encode("The tablet.", "ur")
            assertNotEquals("first@example.org", codec.decode(longArrayOf(4, 2), "ur"))
        }
    }

    @Test fun mismatchedTargetAndInvalidOutputIdsAreRejected() {
        IndicTokenizer.forTests(source, Array(6) { "" }, { emptyList() }).use { codec ->
            codec.encode("The tablet.", "ur")
            assertThrows(IllegalArgumentException::class.java) { codec.decode(longArrayOf(2), "pa-Guru") }
            assertThrows(IllegalArgumentException::class.java) { codec.decode(longArrayOf(-1), "ur") }
            assertThrows(IllegalArgumentException::class.java) { codec.decode(longArrayOf(6), "ur") }
            assertThrows(IllegalArgumentException::class.java) { codec.decode(LongArray(257) { 2 }, "ur") }
        }
    }

    @Test fun closeReleasesNativeProcessorExactlyOnceAndRejectsReuse() {
        var releases = 0
        val codec = IndicTokenizer.forTests(source, Array(4) { "" }, { emptyList() }, { releases++ })
        codec.close()
        codec.close()
        assertEquals(1, releases)
        assertThrows(IllegalStateException::class.java) { codec.encode("The tablet.", "ur") }
        assertThrows(IllegalStateException::class.java) { codec.decode(longArrayOf(2), "ur") }
    }
}
