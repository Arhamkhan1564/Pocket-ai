package com.pocketai.retrieval

import com.pocketai.data.KnowledgeRecord
import com.pocketai.data.PocketDatabase
import com.pocketai.data.SearchText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RagSearchResult(val records: List<KnowledgeRecord>, val excerpts: Map<Long, String> = emptyMap(), val semantic: Boolean = false, val notice: String? = null)
data class SemanticIndexState(
    val running: Boolean = false, val paused: Boolean = false, val indexedRecords: Int = 0,
    val totalRecords: Int = 0, val message: String = "No documents indexed yet.", val error: String? = null,
)

/** Input anchors constrain a semantic match; a score is relevance evidence, not factual verification. */
object EvidenceCoverage {
    private data class Field(val query: Regex, val source: Regex)
    private val codeSource = Regex("\\b(?:codes?|access phrase|passphrase)\\b|کوڈ|ਕੋਡ", RegexOption.IGNORE_CASE)
    private val fields = listOf(
        Field(Regex("\\b(?:pin|passcode)\\b|پن\\s*کوڈ|ਪਿੰਨ|ਪਿਨ", RegexOption.IGNORE_CASE), Regex("\\b(?:pin|passcode|unlock code)\\b|پن\\s*کوڈ|ਪਿੰਨ|ਪਿਨ", RegexOption.IGNORE_CASE)),
        Field(Regex("password|passphrase|پاس ورڈ|ਪਾਸਵਰਡ", RegexOption.IGNORE_CASE), Regex("password|passphrase|access phrase|پاس ورڈ|ਪਾਸਵਰਡ", RegexOption.IGNORE_CASE)),
        Field(Regex("\\b(?:wifi|wi-fi|ssid)\\b|وائی فائی|ਵਾਈਫਾਈ", RegexOption.IGNORE_CASE), Regex("\\b(?:wifi|wi-fi|wireless|ssid)\\b|وائی فائی|ਵਾਈਫਾਈ", RegexOption.IGNORE_CASE)),
        Field(Regex("\\b(?:dose|dosage|how much|how many tablets)\\b|کتنی خوراک|خوراک|ਖੁਰਾਕ", RegexOption.IGNORE_CASE), Regex("\\b(?:dose|dosage|take|daily|every|maximum)\\b|خوراک|ਖੁਰਾਕ", RegexOption.IGNORE_CASE)),
        Field(Regex("pregnan|breastfeed|حمل|حاملہ|ਗਰਭ", RegexOption.IGNORE_CASE), Regex("pregnan|breastfeed|حمل|حاملہ|ਗਰਭ", RegexOption.IGNORE_CASE)),
        Field(Regex("\\b(?:age|birthday|birth date|birthdate|date of birth)\\b|تاریخ پیدائش|عمر|ਉਮਰ", RegexOption.IGNORE_CASE), Regex("\\b(?:age|years old|birthday|birth date|birthdate|date of birth|born)\\b|تاریخ پیدائش|عمر|ਉਮਰ", RegexOption.IGNORE_CASE)),
    )

    fun allows(question: String, record: KnowledgeRecord): Boolean {
        val body = SearchText.normalize(fieldEvidence(record))
        if (QuestionAnchors.isCodeQuestion(question) && !codeSource.containsMatchIn(body)) return false
        val all = SearchText.normalize("${record.question} ${record.sourceReference} ${record.content}")
        val concepts = SearchText.keywordQuery(question).concepts.filterNot { it.generic }
        if (concepts.any { concept -> concept.aliases.none { SearchText.containsTerm(all, it) } }) return false
        val namedUse = Regex("\\b(?:what is|about|use of|side effects of)\\s+([a-z][a-z-]{2,})\\s+(?:used|for|safe|side effects)", RegexOption.IGNORE_CASE)
            .findAll(question).map { SearchText.normalize(it.groupValues[1]) }.filterNot { it in setOf("the", "this", "that", "medicine", "drug", "tablet") }
        if (namedUse.any { name -> !SearchText.containsTerm(all, name) && concepts.none { name in it.terms } }) return false
        val explicitSubjects = Regex("\\babout\\s+(?:the\\s+)?([a-z][a-z0-9-]{2,})", RegexOption.IGNORE_CASE)
            .findAll(question).map { SearchText.normalize(it.groupValues[1]) }.filterNot { it in subjectIntroductions }
        if (explicitSubjects.any { name -> !SearchText.containsTerm(all, name) && concepts.none { name in it.terms || name in it.aliases } }) return false
        val identifiers = Regex("(?<![\\p{L}\\p{Nd}])\\p{L}[\\p{L}\\p{Nd}_-]*\\p{Nd}[\\p{L}\\p{Nd}_-]*")
            .findAll(question).map { it.value }.toList()
        if (identifiers.any { !record.content.contains(it) }) return false
        val requested = fields.filter { it.query.containsMatchIn(question) }
        return requested.isEmpty() || requested.any { it.source.containsMatchIn(body) }
    }

    fun covers(question: String, records: List<KnowledgeRecord>): Boolean {
        if (records.isEmpty()) return false
        if (QuestionAnchors.isCodeQuestion(question) && records.none { codeSource.containsMatchIn(SearchText.normalize(fieldEvidence(it))) }) return false
        return fields.filter { it.query.containsMatchIn(question) }.all { field -> records.any { field.source.containsMatchIn(fieldEvidence(it)) } }
    }

    // A verified Q&A's explicit question supplies context for a short answer.
    // Imported document titles/filenames do not establish fields absent from their text.
    fun fieldEvidence(record: KnowledgeRecord): String = if (record.source != "document" && record.question.isNotBlank())
        "${record.question}\n${record.content}" else record.content

    private val subjectIntroductions = setOf("a", "an", "the", "this", "that", "these", "those", "my", "our", "your", "its", "their", "using", "taking", "keeping", "storing", "how", "whether", "what", "medicine", "medicines", "drug", "drugs", "side", "effects", "symptoms", "saved", "document", "documents", "reference", "references")
}

class SemanticRetriever(
    private val db: PocketDatabase,
    private val embeddings: OfflineEmbeddingService,
    private val verifyCodeTopic: (suspend (String, String, KnowledgeRecord, String) -> Boolean)? = null,
) {
    suspend fun retrieve(question: String, language: String, limit: Int = 3, semanticReady: Boolean = true): RagSearchResult = withContext(Dispatchers.IO) {
        require(limit in 1..10)
        val lexical = db.retrieve(question, language, limit).filter { EvidenceCoverage.allows(question, it) }
        var semantic = false
        val total = db.countKnowledge()
        val pending = db.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256)
        var notice: String? = when {
            total > 0 && !semanticReady -> "Prepare offline search in Offline tools to match references across languages. Original passages and keyword search remain available."
            pending > 0 -> "Offline reference indexing is incomplete (${total - pending} of $total passages indexed). Check Offline tools for progress or passages that need splitting, then ask again. Original passages and keyword search remain available."
            else -> null
        }
        val excerpts = LinkedHashMap<Long, String>()
        val candidates = mutableListOf<KnowledgeRecord>()
        val codeQuestion = QuestionAnchors.isCodeQuestion(question)
        val canVerifyCode = codeQuestion && QuestionAnchors.topicWords(question).isNotEmpty() && verifyCodeTopic != null
        if (semanticReady && pending < total) {
            try {
                val vector = embeddings.query(question)
                val hits = db.nearestKnowledgeVectors(vector, OnnxEmbeddingKernel.MODEL_SHA256, 20)
                val first = hits.firstOrNull()
                val gap = if (hits.size > 1) first!!.score - hits[1].score else 1.0
                val regularMatch = first != null && first.score >= .82 && (gap >= .012 || lexical.isNotEmpty())
                // A weaker cross-language code match is only a candidate until its subject
                // words are verified against the original or actual local translation below.
                val codeCandidate = canVerifyCode && first != null && first.score >= .80 && gap >= .025
                if (first != null && (regularMatch || codeCandidate)) {
                    val floor = if (regularMatch) .82 else .80
                    hits.filter { it.score >= floor && it.score >= first.score - .05 }.forEach { hit ->
                        val record = db.getKnowledge(hit.knowledgeId)
                        if (record != null && record.verified && EvidenceCoverage.allows(question, record)) {
                            candidates += record
                            if (hit.startOffset >= 0 && hit.endOffset in (hit.startOffset + 1)..record.content.length)
                                excerpts[record.id] = record.content.substring(hit.startOffset, hit.endOffset)
                        }
                    }
                    semantic = candidates.isNotEmpty()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "Semantic search could not run; keyword search remains available." }
        }
        val records = ArrayList<KnowledgeRecord>()
        for (record in (lexical + candidates).distinctBy { it.id }) {
            if (codeQuestion) {
                val passage = excerpts[record.id] ?: record.content
                val evidence = if (record.source != "document" && record.question.isNotBlank()) "${record.question}\n$passage" else passage
                val verified = try {
                    verifyCodeTopic?.invoke(question, language, record, evidence) ?: QuestionAnchors.matches(question, evidence)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    notice = failure.message ?: "This cross-language code reference could not be verified. Read the original source."
                    false
                }
                if (!verified) continue
            }
            records += record
            if (records.size == limit) break
        }
        if (!EvidenceCoverage.covers(question, records)) RagSearchResult(emptyList(), notice = notice)
        else RagSearchResult(records, excerpts.filterKeys { id -> records.any { it.id == id } },
            semantic && records.any { record -> candidates.any { it.id == record.id } }, notice)
    }
}
