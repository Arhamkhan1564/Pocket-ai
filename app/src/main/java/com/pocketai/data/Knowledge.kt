package com.pocketai.data

import java.text.Normalizer
import java.text.BreakIterator
import java.util.Locale

data class KnowledgeDraft(
    val content: String,
    val question: String = "",
    val language: String = "en",
    val script: String = "Latn",
    val category: String = "General",
    val source: String = "text",
    val sourceReference: String = "",
    val confidence: Double? = null,
)

data class KnowledgeRecord(
    val id: Long,
    val content: String,
    val question: String,
    val language: String,
    val script: String,
    val category: String,
    val source: String,
    val sourceReference: String,
    val confidence: Double?,
    val createdAt: Long,
    val updatedAt: Long,
    val verified: Boolean,
) {
    fun toDraft() = KnowledgeDraft(content, question, language, script, category, source, sourceReference, confidence)
}

data class ConversationRecord(val id: Long, val role: String, val content: String, val language: String, val sources: String, val createdAt: Long, val sourceIds: List<Long> = emptyList())
data class TrainingEvent(val id: Long, val knowledgeId: Long?, val action: String, val details: String, val createdAt: Long)
data class DocumentSummary(val id: Long, val name: String, val language: String, val importedAt: Long, val chunkCount: Int, val characterCount: Long)
data class KnowledgeVectorPart(val part: Int, val startOffset: Int, val endOffset: Int, val vector: FloatArray)
data class KnowledgeVectorHit(val knowledgeId: Long, val score: Double, val startOffset: Int, val endOffset: Int)

/** Search normalization only. The administrator's original script and text are never overwritten. */
object SearchText {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]"), "")
        .replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک')
        .replace("ـ", "").replace('\u200C', ' ').replace("\u200D", "")
        .replace(Regex("[\\p{P}\\p{S}\\p{Z}\\s]+"), " ").trim()

    fun terms(value: String): List<String> = normalize(value).split(' ').filter { it.isNotBlank() }.distinct().take(20)

    fun detectedLanguage(text: String, fallback: String = "en"): String = when {
        text.any { it in '\u0A00'..'\u0A7F' } -> "pa"
        text.any { it in '\u0600'..'\u06FF' } -> if (fallback in setOf("pa", "ur", "ar", "fa")) fallback else "ur"
        else -> fallback
    }

    data class KeywordQuery(
        val normalized: String,
        val literalTerms: List<String>,
        val terms: List<String>,
        val primaryTerms: List<String>,
        val concepts: List<KeywordConcept>,
    )

    data class KeywordConcept(val terms: Set<String>, val aliases: Set<String>, val generic: Boolean = false)

    /** A small search vocabulary, not translation, embeddings, diagnosis, or drug recommendations. */
    fun keywordQuery(value: String): KeywordQuery {
        val normalized = normalize(value.take(2000))
        val literal = normalized.split(' ').filter { it.isNotBlank() && it !in stopWords }.distinct().take(24)
        val storageContext = storageWords.containsMatchIn(normalized) && !containsTerm(normalized, "common cold")
        val found = keywordConcepts.filter { concept -> concept.aliases.any { containsTerm(normalized, it) } }
            .filterNot { storageContext && "cold" in it.terms }.take(12)
        val expanded = (literal + found.flatMap { it.terms }).distinct().take(64)
        val specific = found.filterNot { it.generic }
        val fallbackPain = if (specific.isEmpty()) found.filter { "pain" in it.terms }.flatMap { it.terms } else emptyList()
        val primary = (literal.filterNot { it in genericTerms } + specific.flatMap { it.terms } + fallbackPain)
            .distinct().take(48).ifEmpty { expanded }
        return KeywordQuery(normalized, literal, expanded, primary, found)
    }

    fun containsTerm(normalizedText: String, term: String): Boolean {
        if (term.isBlank()) return false
        var position = normalizedText.indexOf(term)
        while (position >= 0) {
            val end = position + term.length
            if ((position == 0 || normalizedText[position - 1] == ' ') &&
                (end == normalizedText.length || normalizedText[end] == ' ')) return true
            position = normalizedText.indexOf(term, position + 1)
        }
        return false
    }

    private fun concept(terms: String, aliases: String, generic: Boolean = false): KeywordConcept {
        val english = terms.split('|').map(::normalize).filter { it.isNotBlank() }.toSet()
        return KeywordConcept(english, (aliases.split('|').map(::normalize) + english).filter { it.isNotBlank() }.toSet(), generic)
    }

    private val keywordConcepts = listOf(
        concept("headache|headaches", "سر درد|سردرد|سر میں درد|سر وچ درد|سر چ درد|سر دا درد|sar dard|sir dard|sar mein dard|sir mein dard|sar me dard|sir me dard|sir vich dard|sir wich dard|ਸਿਰ ਦਰਦ|ਸਿਰਦਰਦ|ਸਿਰ ਵਿੱਚ ਦਰਦ|ਸਿਰ ਵਿਚ ਦਰਦ|سر پیڑ"),
        concept("fever|fevers", "بخار|bukhar|bukhaar|ਬੁਖਾਰ"),
        concept("cough|coughing", "کھانسی|کھنگ|khansi|khaansi|khang|ਖੰਘ|ਖੰਘਣਾ"),
        concept("cold|common cold", "زکام|نزلہ|zukam|zukaam|nazla|ਜ਼ੁਕਾਮ|ਜ਼ੁਕਾਮ"),
        concept("sore throat|throat", "گلے میں درد|گلے کا درد|گلا خراب|gala kharab|gale mein dard|گلے دی تکلیف|ਗਲਾ ਖਰਾਬ|ਗਲੇ ਵਿੱਚ ਦਰਦ"),
        concept("abdominal pain|stomach|stomachache", "پیٹ درد|پیٹ میں درد|پیٹ وچ درد|pait dard|pet dard|pait mein dard|pet mein dard|ਢਿੱਡ ਦਰਦ|ਪੇਟ ਦਰਦ|ਪੇਟ ਵਿੱਚ ਦਰਦ"),
        concept("toothache|tooth|dental", "دانت درد|دانت میں درد|دند دا درد|daant dard|dant dard|ਦੰਦ ਦਰਦ|ਦੰਦ ਵਿੱਚ ਦਰਦ"),
        concept("back pain|backache", "کمر درد|کمر میں درد|کمر وچ درد|kamar dard|kamar mein dard|ਕਮਰ ਦਰਦ"),
        concept("joint pain|joints", "جوڑوں میں درد|جوڑ درد|joron mein dard|جوڑاں دا درد|ਜੋੜਾਂ ਦਾ ਦਰਦ"),
        concept("pain|pains", "درد|پیڑ|dard|peer|ਦਰਦ|ਪੀੜ", true),
        concept("nausea", "متلی|matli|ਮਤਲੀ"),
        concept("vomiting|vomit", "قے|الٹی|الٹیاں|ulti|qay|ਉਲਟੀ|ਉਲਟੀਆਂ"),
        concept("diarrhea|diarrhoea", "اسہال|دست|dast|دست لگنا|ਦਸਤ"),
        concept("constipation", "قبض|qabz|ਕਬਜ਼|ਕਬਜ"),
        concept("allergy|allergies|allergic", "الرجی|allarji|alarji|ਐਲਰਜੀ"),
        concept("itching|itch", "خارش|kharish|ਖਾਰਸ਼|ਖੁਜਲੀ"),
        concept("rash|rashes", "جلد پر دانے|جلدی دانے|ਰੈਸ਼|ਧੱਫੜ"),
        concept("breathlessness|shortness of breath|breathing", "سانس میں تکلیف|سانس پھولنا|saans phoolna|saans ki takleef|ساہ چڑھنا|ਸਾਹ ਚੜ੍ਹਨਾ|ਸਾਹ ਲੈਣ ਵਿੱਚ ਤਕਲੀਫ"),
        concept("chest pain|chest", "سینے میں درد|چھاتی میں درد|seene mein dard|seene me dard|ਛਾਤੀ ਵਿੱਚ ਦਰਦ|ਛਾਤੀ ਦਰਦ"),
        concept("dizziness|dizzy", "چکر|chakkar|chakar|ਚੱਕਰ"),
        concept("fatigue|tiredness", "تھکاوٹ|تھکن|thakan|ਥਕਾਵਟ"),
        concept("swelling|swollen", "سوجن|soojan|sujan|ਸੋਜ|ਸੋਜਸ਼"),
        concept("blood pressure|pressure", "بلڈ پریشر|فشار خون|ਬਲੱਡ ਪ੍ਰੈਸ਼ਰ|ਬਲੱਡ ਪ੍ਰੈਸ਼ਰ"),
        concept("diabetes|diabetic", "ذیابیطس|شوگر|شوگر کی بیماری|ਸ਼ੂਗਰ|ਮਧੁਮੇਹ"),
        concept("infection|infections", "انفیکشن|ਲਾਗ|ਸੰਕਰਮਣ"),
        concept("pregnancy|pregnant", "حمل|حاملہ|hamal|ਗਰਭ|ਗਰਭਵਤੀ|ਗਰਭਅਵਸਥਾ"),
        concept("children|child|pediatric|paediatric", "بچے|بچوں|بچہ|bachay|bacha|ਬੱਚੇ|ਬੱਚਾ", true),
        concept("adult|adults", "بالغ|baaligh|ਬਾਲਗ", true),
        concept("dose|dosage|doses", "خوراک|مقدار|khurak|khoraak|ਖੁਰਾਕ|ਡੋਜ਼|ਡੋਜ਼", true),
        concept("side effects|adverse effects|adverse", "ضمنی اثرات|مضر اثرات|سائیڈ ایفیکٹس|نقصانات|sideeffects|ਮਾੜੇ ਪ੍ਰਭਾਵ|ਬੁਰੇ ਪ੍ਰਭਾਵ|ਸਾਈਡ ਇਫੈਕਟ", true),
        concept("contraindications|contraindication|warnings", "ممانعت|پرہیز|احتیاط|کن لوگوں کو نہیں|ਮਨਾਹੀ|ਸਾਵਧਾਨੀਆਂ", true),
        concept("medicine|medicines|medication|medications|drug|drugs", "دوا|دوائی|دوائیں|دواؤں|دواوں|ادویات|dawa|dawai|dawaai|dawaiyan|ਦਵਾਈ|ਦਵਾਈਆਂ|ਦਵਾ", true),
        concept("tablet|tablets", "گولی|گولیاں|goli|goliyan|ਗੋਲੀ|ਗੋਲੀਆਂ", true),
        concept("syrup", "شربت|sharbat|ਸ਼ਰਬਤ|ਸ਼ਰਬਤ", true),
        concept("paracetamol|acetaminophen", "پیراسیٹامول|پیرا سیٹامول|پیراسٹامول|ਪੈਰਾਸੀਟਾਮੋਲ"),
        concept("ibuprofen", "آئبوپروفین|آئیبوپروفین|ਆਈਬੂਪ੍ਰੋਫ਼ੈਨ|ਆਈਬੂਪ੍ਰੋਫੈਨ"),
        concept("aspirin", "اسپرین|ਐਸਪਿਰਿਨ"),
        concept("amoxicillin|amoxycillin", "اموکسیسلن|اموکسی سیلن|ਅਮੌਕਸੀਸਿਲਿਨ"),
        concept("cetirizine", "سیٹیریزین|سیٹریزین|ਸੇਟੀਰਿਜ਼ੀਨ"),
        concept("loratadine", "لوراٹاڈین|ਲੋਰੈਟਾਡੀਨ"),
        concept("metformin", "میٹفارمن|ਮੈਟਫਾਰਮਿਨ"),
        concept("omeprazole", "اومیپرازول|اومیپریذول|ਓਮੇਪ੍ਰਾਜ਼ੋਲ"),
        concept("antibiotic|antibiotics", "اینٹی بائیوٹک|ਐਂਟੀਬਾਇਓਟਿਕ"),
        concept("antihistamine|antihistamines", "اینٹی ہسٹامین|ਐਂਟੀਹਿਸਟਾਮੀਨ"),
        concept("panadol", "پیناڈول|پناڈول|ਪੈਨਾਡੋਲ"),
        concept("brufen", "بروفین|ਬਰੂਫੈਨ"),
    )

    private val genericTerms = keywordConcepts.filter { it.generic }.flatMap { it.terms + it.aliases }.filterNot { it.contains(' ') }.toSet()

    private val storageWords = Regex("\\b(?:keep|keeping|kept|store|stored|storing|storage|refrigerat[a-z]*|freez[a-z]*|temperature)\\b")
    private val stopWords = setOf(
        "does", "document", "documents", "reference", "references", "saved", "say", "says", "said", "according", "text", "passage", "passages", "information",
        "what", "who", "when", "where", "why", "how", "is", "are", "was", "were", "a", "an", "the", "of", "about", "me", "tell", "do", "you", "know", "did", "i", "teach", "taught", "my", "please", "explain", "in", "on", "to", "and", "it", "for", "which", "can", "should", "take", "taking", "have", "has", "give", "suggest", "recommend", "help", "with",
        "ہے", "ہیں", "ہوں", "کیا", "کے", "کی", "کا", "کو", "میں", "سے", "یہ", "وہ", "مجھے", "بتائیں", "میرے", "میری", "میرا", "اور", "کونسی", "کونسا", "کون", "سی", "سا", "دیں", "لوں", "لینا", "لیے", "لئے", "ہو", "رہا", "رہی", "مینوں", "توں", "وچ", "نوں", "دا", "دی", "دے", "کیڑی", "اے",
        "ਬਾਰੇ", "ਕੀ", "ਹੈ", "ਨੂੰ", "ਦੱਸੋ", "ਮੈਨੂੰ", "ਮੇਰੇ", "ਮੇਰੀ", "ਮੈਂ", "ਹੋ", "ਰਿਹਾ", "ਰਹੀ", "ਵਿੱਚ", "ਨਾਲ", "ਲਈ", "ਕਿਹੜੀ", "ਕਿਹੜਾ", "ਲਵਾਂ", "ਦਿਓ",
        "mujhe", "mujhay", "mujh", "meri", "mere", "mera", "mein", "main", "ko", "hai", "hain", "ka", "ki", "ke", "kya", "konsa", "konsi", "kon", "si", "batao", "bataen", "bataein", "liye", "lo", "lun", "loon", "hon", "ho", "raha", "rahi", "hun", "menu", "mainu", "mennu", "nu", "wich", "vich", "layi", "lai", "di", "da", "de", "aa", "ae", "kedi", "kehri", "karo", "das", "daso"
    )
}

/** Sentence/word boundaries and a short overlap keep adjacent context together without loading models. */
object DocumentChunker {
    const val MAX_DOCUMENT_CHARACTERS = 1_000_000
    const val MAX_CHUNKS = 1000
    const val MAX_CHUNK_CHARACTERS = 2400
    private const val TARGET = 1800
    private const val OVERLAP = 220

    fun chunk(text: String): List<String> {
        require(text.isNotBlank() && text.length <= MAX_DOCUMENT_CHARACTERS) { "Import readable text with no more than 1,000,000 characters." }
        val chunks = ArrayList<String>()
        text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n")).forEach { raw ->
            val paragraph = raw.trim()
            if (paragraph.isNotEmpty()) splitParagraph(paragraph, chunks)
            require(chunks.size <= MAX_CHUNKS) { "The document produces more than 1,000 chunks. Split it into smaller files." }
        }
        require(chunks.isNotEmpty()) { "This document contains no readable text." }
        return chunks
    }

    private fun splitParagraph(paragraph: String, chunks: MutableList<String>) {
        if (paragraph.length <= MAX_CHUNK_CHARACTERS) { chunks += paragraph; return }
        val sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH).apply { setText(paragraph) }
        var start = 0
        while (start < paragraph.length) {
            val remaining = paragraph.length - start
            if (remaining <= MAX_CHUNK_CHARACTERS) { chunks += paragraph.substring(start).trim(); break }
            val target = start + TARGET
            val hardEnd = (start + MAX_CHUNK_CHARACTERS).coerceAtMost(paragraph.length)
            var end = sentences.preceding(target + 1)
            if (end < start + TARGET / 2) {
                val after = sentences.following(target)
                end = if (after != BreakIterator.DONE && after <= hardEnd) after else wordBoundary(paragraph, target, start + TARGET / 2)
            }
            if (end <= start) end = hardEnd
            if (end < paragraph.length && end > 0 && Character.isHighSurrogate(paragraph[end - 1]) && Character.isLowSurrogate(paragraph[end])) end--
            chunks += paragraph.substring(start, end).trim()
            require(chunks.size <= MAX_CHUNKS) { "The document produces more than 1,000 chunks. Split it into smaller files." }
            var next = sentences.following((end - OVERLAP).coerceAtLeast(start))
            if (next == BreakIterator.DONE || next >= end || next < end - OVERLAP) next = wordBoundary(paragraph, end - OVERLAP, (end - OVERLAP - 80).coerceAtLeast(start + 1))
            if (next > start && next < paragraph.length && Character.isLowSurrogate(paragraph[next]) && Character.isHighSurrogate(paragraph[next - 1])) next--
            start = next.coerceIn(start + 1, end)
            while (start < paragraph.length && paragraph[start].isWhitespace()) start++
        }
    }

    private fun wordBoundary(text: String, preferred: Int, minimum: Int): Int {
        var position = preferred.coerceIn(minimum, text.length)
        while (position > minimum && !text[position - 1].isWhitespace()) position--
        return if (position > minimum) position else preferred.coerceIn(minimum, text.length)
    }
}
