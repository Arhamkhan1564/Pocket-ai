package com.pocketai.rag

import org.junit.Assert.*
import org.junit.Test

class LiteralProtectionTest {
    @Test fun sourceIdentifiersRoundTripWithoutCollidingWithAnExistingNumber() {
        val source = "Reference cedar-47 uses B12. Existing number 9876543210; quantity 250 mg."
        val protected = LiteralProtection.protect(source)
        assertFalse(protected.literals.containsKey("9876543210"))
        assertEquals(source, protected.restore(protected.text))
        assertEquals(setOf("cedar-47", "B12"), protected.literals.values.toSet())
    }

    @Test fun droppedOrDuplicatedIdentifierNeverProducesAUsableTranslation() {
        val protected = LiteralProtection.protect("The code is cedar-47.")
        val marker = protected.literals.keys.single()
        assertThrows(IllegalArgumentException::class.java) { protected.restore("The code was omitted.") }
        assertThrows(IllegalArgumentException::class.java) { protected.restore("$marker $marker") }
    }

    @Test fun equivalentUrduDigitsRestoreTheExactLatinSourceCode() {
        val protected = LiteralProtection.protect("The code is cedar-47.")
        val marker = protected.literals.keys.single()
        val eastern = marker.map { ('\u06F0'.code + (it - '0')).toChar() }.joinToString("")
        assertEquals("کوڈ cedar-47 ہے۔", protected.restore("کوڈ $eastern ہے۔"))
    }
}
