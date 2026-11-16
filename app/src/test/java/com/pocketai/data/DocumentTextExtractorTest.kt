package com.pocketai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentTextExtractorTest {
    private lateinit var extractor: DocumentTextExtractor
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        extractor = DocumentTextExtractor(context)
    }

    @Test fun utf8TxtPreservesOriginalEnglishAndUnicode() {
        val document = extractor.extract(ByteArrayInputStream("\uFEFFClinical reference\nاردو ਪੰਜਾਬੀ".toByteArray()), "reference.txt")
        assertEquals("Clinical reference\nاردو ਪੰਜਾਬੀ", document.text)
        assertEquals("TXT", document.format)
        assertEquals("reference.txt", document.fileName)
    }

    @Test fun rejectsInvalidUtf8AndBinaryTxtRatherThanReplacingBytes() {
        val malformed = assertThrows(IllegalArgumentException::class.java) {
            extractor.extract(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)), "bad.txt")
        }
        assertTrue(malformed.message!!.contains("UTF-8"))
        assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream("Text\u0000binary".toByteArray()), "bad.txt") }
    }

    @Test fun legacyDocHasExplicitConversionInstructions() {
        val failure = assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream(byteArrayOf(1)), "old.doc", "application/msword") }
        assertEquals("Convert this legacy DOC file to DOCX or UTF-8 TXT first.", failure.message)
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream(ole), "misnamed.txt") }
    }

    @Test fun txtInputAndExtractedOutputHaveIndependentBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            extractor.extract(ByteArrayInputStream(ByteArray(DocumentTextExtractor.MAX_INPUT_BYTES + 1) { 65 }), "too-large.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            extractor.extract(ByteArrayInputStream(ByteArray(DocumentTextExtractor.MAX_TEXT_CHARACTERS + 1) { 65 }), "too-much-text.txt")
        }
    }

    @Test fun extractsDocxParagraphsTabsAndTableTextAndFootnotes() {
        val xml = wordDocument("""<w:p><w:r><w:t>Reference &amp; evidence</w:t><w:tab/><w:t>Table value</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Leaflet</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Source details</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""")
        val footnotes = """<w:footnotes xmlns:w="$WORD_NAMESPACE"><w:footnote w:id="1"><w:p><w:r><w:t>Original publication note.</w:t></w:r></w:p></w:footnote></w:footnotes>"""
        val result = extractor.extract(ByteArrayInputStream(docx(xml, mapOf("word/footnotes.xml" to footnotes.toByteArray()))), "reference.docx")
        assertEquals("DOCX", result.format)
        assertTrue(result.text.contains("Reference & evidence\tTable value"))
        assertTrue(result.text.contains("Leaflet"))
        assertTrue(result.text.contains("Source details"))
        assertTrue(result.text.contains("Footnotes:\nOriginal publication note."))
        assertTrue(result.warnings.isNotEmpty())
        assertNoTemporaryDocuments()
    }

    @Test fun rejectsDocxExternalEntitiesBeforeParsingAndCleansTemporaryFiles() {
        val xml = """<?xml version="1.0"?><!DOCTYPE document [<!ENTITY xxe SYSTEM "file:///should-never-be-read">]><w:document xmlns:w="$WORD_NAMESPACE"><w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body></w:document>"""
        val failure = assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream(docx(xml)), "unsafe.docx") }
        assertTrue(failure.message!!.contains("entity"))
        assertNoTemporaryDocuments()
    }

    @Test fun rejectsZipBombCompressionAndExcessiveEntries() {
        val bomb = docx(wordDocument("<w:p><w:r><w:t>" + "A".repeat(200_000) + "</w:t></w:r></w:p>"))
        val failure = assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream(bomb), "compressed.docx") }
        assertTrue(failure.message!!.contains("compression"))
        val extras = (1..513).associate { "unused/$it.xml" to "x".toByteArray() }
        assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream(docx(wordDocument(""), extras)), "entries.docx") }
        assertNoTemporaryDocuments()
    }

    @Test fun rejectsUnsafeZipPathsAndZipFilesThatAreNotDocx() {
        assertThrows(IllegalArgumentException::class.java) {
            extractor.extract(ByteArrayInputStream(docx(wordDocument("<w:p/>"), mapOf("../outside" to byteArrayOf(1)))), "bad.docx")
        }
        val arbitrary = zip(mapOf("notes.txt" to "Unrelated ZIP".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream(arbitrary), "not-a-document.docx") }
    }

    @Test fun textBasedPdfUsesRealExtractionAndReportsPageCount() {
        val result = extractor.extract(ByteArrayInputStream(pdf("An original English document reference.")), "reference.pdf", "application/pdf")
        assertEquals("PDF", result.format)
        assertEquals(1, result.pages)
        assertTrue(result.text.contains("An original English document reference."))
        assertNoTemporaryDocuments()
    }

    @Test fun imageOnlyOrEmptyPdfHasClearOcrMessage() {
        val failure = assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream(pdf(null)), "scan.pdf") }
        assertTrue(failure.message!!.contains("OCR"))
        assertNoTemporaryDocuments()
    }

    @Test fun oversizedPdfPageCountIsRejectedBeforeTextProcessing() {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document -> repeat(101) { document.addPage(PDPage()) }; document.save(bytes) }
        val failure = assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream(bytes.toByteArray()), "too-many-pages.pdf") }
        assertTrue(failure.message!!.contains("1–100 pages"))
        assertNoTemporaryDocuments()
    }

    @Test fun unsupportedAndRenamedBinaryFilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream("text".toByteArray()), "file.xls") }
        assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream(pdf(null)), "renamed.txt") }
        assertThrows(IllegalArgumentException::class.java) { extractor.extract(ByteArrayInputStream("not a PDF".toByteArray()), "renamed.pdf") }
    }

    private fun wordDocument(body: String) = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$WORD_NAMESPACE"><w:body>$body</w:body></w:document>"""

    private fun docx(xml: String, extra: Map<String, ByteArray> = emptyMap()): ByteArray = zip(linkedMapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray(),
        "word/document.xml" to xml.toByteArray(),
    ) + extra)

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() } }
        return bytes.toByteArray()
    }

    private fun pdf(text: String?): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            if (text != null) PDPageContentStream(document, page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.newLineAtOffset(50f, 700f); stream.showText(text); stream.endText()
            }
            document.save(output)
        }
        return output.toByteArray()
    }

    private fun assertNoTemporaryDocuments() {
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("pocket-document-") })
    }

    companion object { private const val WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main" }
}
