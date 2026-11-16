package com.pocketai.data

import android.content.Context
import android.util.Xml
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipFile

data class ExtractedDocument(
    val fileName: String,
    val text: String,
    val format: String,
    val pages: Int? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * Reads text only. It never executes document fields, macros, links, or scripts, and performs no OCR.
 * Run on Dispatchers.IO. The caller owns [InputStream]; all temporary copies are deleted before return.
 */
class DocumentTextExtractor(context: Context) {
    private val cacheDirectory = context.applicationContext.cacheDir

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    fun extract(input: InputStream, fileName: String, mimeType: String? = null): ExtractedDocument {
        val name = cleanName(fileName)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension == "doc") throw IllegalArgumentException(LEGACY_DOC_MESSAGE)
        val format = when (extension) {
            "txt" -> "TXT"
            "docx" -> "DOCX"
            "pdf" -> "PDF"
            else -> when (mimeType?.substringBefore(';')?.lowercase(Locale.ROOT)) {
                "text/plain" -> "TXT"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
                "application/pdf" -> "PDF"
                "application/msword" -> throw IllegalArgumentException(LEGACY_DOC_MESSAGE)
                else -> throw IllegalArgumentException("Choose a UTF-8 TXT, DOCX, or text-based PDF file. Legacy DOC files must be converted to DOCX first.")
            }
        }
        val bytes = readBounded(input, MAX_INPUT_BYTES, "Document must be smaller than 8 MiB.")
        require(bytes.isNotEmpty()) { "This document is empty." }
        if (bytes.startsWith(OLE_MAGIC)) throw IllegalArgumentException(LEGACY_DOC_MESSAGE)
        return when (format) {
            "TXT" -> {
                require(!bytes.startsWith(PDF_MAGIC) && !bytes.startsWith(ZIP_MAGIC)) { "This is a binary document. Select its original DOCX or PDF file instead of renaming it TXT." }
                val text = strictUtf8(bytes, "Text files must use UTF-8 encoding. Export this document as UTF-8 TXT and try again.")
                require(text.none { it == '\u0000' || (it.code in 1..31 && it !in "\t\r\n\u000c") || it == '\u007f' }) {
                    "This file contains binary data. Choose a readable UTF-8 TXT, DOCX, or PDF document."
                }
                ExtractedDocument(name, readableText(text), format)
            }
            "DOCX" -> {
                require(bytes.startsWith(ZIP_MAGIC)) { "This is not a valid DOCX document. Export it as DOCX or UTF-8 TXT first." }
                ExtractedDocument(name, extractDocx(bytes), format, warnings = listOf("Only readable document text and footnotes are imported. Images, embedded files, and macros are not imported; fields are not executed."))
            }
            else -> {
                require(bytes.startsWith(PDF_MAGIC)) { "This is not a valid PDF file. Export a text-based PDF or UTF-8 TXT copy first." }
                extractPdf(bytes, name)
            }
        }
    }

    private fun extractDocx(bytes: ByteArray): String = withTemporaryFile(bytes, ".docx") { file ->
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            val names = HashSet<String>()
            var count = 0
            var declaredTotal = 0L
            while (entries.hasMoreElements()) {
                checkInterrupted()
                val entry = entries.nextElement()
                require(++count <= MAX_ZIP_ENTRIES) { "This DOCX has too many ZIP entries. Export a smaller document." }
                require(entry.name.length <= 512 && !entry.name.startsWith('/') && '\\' !in entry.name && entry.name.split('/').none { it == ".." }) { "This DOCX contains an unsafe ZIP entry." }
                require(names.add(entry.name)) { "This DOCX contains duplicate ZIP entries." }
                require(entry.size in 0..MAX_ZIP_ENTRY_BYTES && entry.compressedSize in 0..bytes.size.toLong()) { "A DOCX entry is too large or has invalid size metadata." }
                declaredTotal += entry.size
                require(declaredTotal <= MAX_ZIP_TOTAL_BYTES) { "This DOCX expands beyond the 32 MiB import limit. Export a smaller document." }
                require(entry.size <= 64 * 1024 || entry.size <= entry.compressedSize.coerceAtLeast(1) * MAX_COMPRESSION_RATIO) { "This DOCX exceeds the safe ZIP compression limit. Export a smaller, simpler document." }
            }
            require("[Content_Types].xml" in names && "word/document.xml" in names) { "This ZIP is not a DOCX word-processing document." }
            val output = BoundedTextWriter(MAX_TEXT_CHARACTERS)
            for (part in listOf("word/document.xml", "word/footnotes.xml", "word/endnotes.xml")) {
                val entry = zip.getEntry(part) ?: continue
                require(entry.size <= MAX_XML_BYTES) { "The DOCX text XML is too large. Split the document into smaller files." }
                val xml = zip.getInputStream(entry).use { readBounded(it, MAX_XML_BYTES, "The DOCX text XML exceeds 4 MiB.") }
                val decoded = strictUtf8(xml, "This DOCX uses an unsupported XML encoding. Re-save it as a modern DOCX document.")
                if (part != "word/document.xml") output.append("\n\n${if (part.endsWith("endnotes.xml")) "Endnotes" else "Footnotes"}:\n")
                extractWordXml(decoded, output)
            }
            readableText(output.toString())
        }
    }

    private fun extractWordXml(xml: String, output: BoundedTextWriter) {
        // A pull parser does not resolve external entities. Reject declarations before it sees them,
        // including declarations in otherwise unused portions of the Word document.
        require(!FORBIDDEN_XML_DECLARATION.containsMatchIn(xml)) { "DOCX files containing XML DTD or entity declarations cannot be imported." }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        var events = 0
        var textDepth = -1
        while (event != XmlPullParser.END_DOCUMENT) {
            checkInterrupted()
            require(++events <= MAX_XML_EVENTS && parser.depth <= 128) { "This DOCX XML is too complex to import safely." }
            val isWord = parser.namespace in WORD_NAMESPACES
            when (event) {
                XmlPullParser.DOCDECL -> throw IllegalArgumentException("DOCX XML declarations are not allowed.")
                XmlPullParser.START_TAG -> if (isWord) when (parser.name) {
                    "t" -> textDepth = parser.depth
                    "tab" -> output.append("\t")
                    "br", "cr" -> output.append("\n")
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (textDepth >= 0) output.append(parser.text)
                XmlPullParser.END_TAG -> if (isWord) when (parser.name) {
                    "t" -> textDepth = -1
                    "p", "tr" -> output.append("\n")
                    "tc" -> output.append("\t")
                }
            }
            event = parser.next()
        }
    }

    private fun extractPdf(bytes: ByteArray, name: String): ExtractedDocument = withTemporaryFile(bytes, ".pdf") { file ->
        try {
            val memory = MemoryUsageSetting.setupMixed(4L * 1024 * 1024, 32L * 1024 * 1024).setTempDir(cacheDirectory)
            PDDocument.load(file, memory).use { document ->
                require(!document.isEncrypted) { "Encrypted PDFs are not imported. Remove its password or export an unencrypted PDF or UTF-8 TXT copy first." }
                require(document.numberOfPages in 1..MAX_PDF_PAGES) { "Import a PDF containing 1–100 pages. Split larger references into smaller files." }
                checkInterrupted()
                val output = BoundedTextWriter(MAX_TEXT_CHARACTERS)
                object : PDFTextStripper() {
                    private var pagePositions = 0
                    private var totalPositions = 0
                    override fun startPage(page: PDPage) {
                        checkInterrupted()
                        pagePositions = 0
                        super.startPage(page)
                    }
                    override fun processTextPosition(text: TextPosition) {
                        checkInterrupted()
                        require(++pagePositions <= 100_000 && ++totalPositions <= MAX_TEXT_CHARACTERS) {
                            "This PDF page has too much text to process safely. Split or simplify the PDF first."
                        }
                        super.processTextPosition(text)
                    }
                }.apply {
                    sortByPosition = true
                    startPage = 1
                    endPage = document.numberOfPages
                }.writeText(document, output)
                val text = output.toString().trim()
                require(text.any { it.isLetterOrDigit() }) { "This PDF has no readable selectable text. Apply OCR first, then import a searchable PDF or UTF-8 TXT copy." }
                ExtractedDocument(name, readableText(text), "PDF", pages = document.numberOfPages,
                    warnings = listOf("Only selectable PDF text is imported. Images, scans, and embedded files are not read; table order may need review."))
            }
        } catch (failure: InvalidPasswordException) {
            throw IllegalArgumentException("This PDF is password protected. Remove its password or export an unencrypted PDF or UTF-8 TXT copy first.", failure)
        }
    }

    private fun readableText(value: String): String {
        require(value.length <= MAX_TEXT_CHARACTERS) { "Extracted text exceeds 1,000,000 characters. Split this document into smaller files." }
        val text = value.removePrefix("\uFEFF").trim()
        require(text.isNotBlank()) { "This document contains no readable text." }
        return text
    }

    private fun strictUtf8(bytes: ByteArray, message: String): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (failure: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException(message, failure)
    }

    private fun <T> withTemporaryFile(bytes: ByteArray, suffix: String, block: (File) -> T): T {
        val file = File.createTempFile("pocket-document-", suffix, cacheDirectory)
        return try { file.outputStream().use { it.write(bytes) }; block(file) } finally { file.delete() }
    }

    private fun readBounded(input: InputStream, limit: Int, message: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkInterrupted()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(output.size().toLong() + count <= limit) { message }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class BoundedTextWriter(private val limit: Int) : Writer() {
        private val text = StringBuilder()
        override fun write(buffer: CharArray, offset: Int, count: Int) {
            require(text.length.toLong() + count <= limit) { "Extracted text exceeds 1,000,000 characters. Split this document into smaller files." }
            text.append(buffer, offset, count)
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = text.toString()
    }

    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Document import cancelled.") }
    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    private fun cleanName(value: String) = value.substringAfterLast('/').substringAfterLast('\\').filterNot { Character.isISOControl(it) }.take(200).ifBlank { "Imported document" }

    companion object {
        const val MAX_INPUT_BYTES = 8 * 1024 * 1024
        const val MAX_TEXT_CHARACTERS = 1_000_000
        private const val MAX_ZIP_ENTRIES = 512
        private const val MAX_ZIP_ENTRY_BYTES = 16L * 1024 * 1024
        private const val MAX_ZIP_TOTAL_BYTES = 32L * 1024 * 1024
        private const val MAX_COMPRESSION_RATIO = 100L
        private const val MAX_XML_BYTES = 4 * 1024 * 1024
        private const val MAX_XML_EVENTS = 500_000
        private const val MAX_PDF_PAGES = 100
        private const val LEGACY_DOC_MESSAGE = "Convert this legacy DOC file to DOCX or UTF-8 TXT first."
        private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val FORBIDDEN_XML_DECLARATION = Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
        private val WORD_NAMESPACES = setOf("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "http://purl.oclc.org/ooxml/wordprocessingml/main")
    }
}
