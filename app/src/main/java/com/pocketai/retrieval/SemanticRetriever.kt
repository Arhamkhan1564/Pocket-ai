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
    private val fields = listOf(
        Field(Regex("\\b(?:pin|passcode)\\b|پن کوڈ|ਪਿੰਨ", RegexOption.IGNORE_CASE), Regex("\\b(?:pin|passcode|unlock code)\\b|پن کوڈ|ਪਿੰਨ", RegexOption.IGNORE_CASE)),
        Field(Regex("password|passphrase|پاس ورڈ|ਪਾਸਵਰਡ", RegexOption.IGNORE_CASE), Regex("password|passphrase|access phrase|پاس ورڈ|ਪਾਸਵਰਡ", RegexOption.IGNORE_CASE)),
        Field(Regex("\\b(?:wifi|wi-fi|ssid)\\b|وائی فائی|ਵਾਈਫਾਈ", RegexOption.IGNORE_CASE), Regex("\\b(?:wifi|wi-fi|wireless|ssid)\\b|وائی فائی|ਵਾਈਫਾਈ", RegexOption.IGNORE_CASE)),
        Field(Regex("\\b(?:dose|dosage|how much|how many tablets)\\b|کتنی خوراک|خوراک|ਖੁਰਾਕ", RegexOption.IGNORE_CASE), Regex("\\b(?:dose|dosage|take|daily|every|maximum)\\b|خوراک|ਖੁਰਾਕ", RegexOption.IGNORE_CASE)),
        Field(Regex("pregnan|breastfeed|حمل|حاملہ|ਗਰਭ", RegexOption.IGNORE_CASE), Regex("pregnan|breastfeed|حمل|حاملہ|ਗਰਭ", RegexOption.IGNORE_CASE)),
        Field(Regex("\\b(?:age|birthday|date of birth)\\b|تاریخ پیدائش|عمر|ਉਮਰ", RegexOption.IGNORE_CASE), Regex("\\b(?:age|years old|birthday|date of birth|born)\\b|تاریخ پیدائش|عمر|ਉਮਰ", RegexOption.IGNORE_CASE)),
    )

    fun allows(question: String, record: KnowledgeRecord): Boolean {
        val body = SearchText.normalize(record.content)
        val all = SearchText.normalize("${record.question} ${record.sourceReference} ${record.content}")
        val concepts = SearchText.keywordQuery(question).concepts.filterNot { it.generic }
        if (concepts.any { concept -> concept.aliases.none { SearchText.containsTerm(all, it) } }) return false
        val namedUse = Regex("\\b(?:what is|about|use of|side effects of)\\s+([a-z][a-z-]{2,})\\s+(?:used|for|safe|side effects)", RegexOption.IGNORE_CASE)
            .findAll(question).map { SearchText.normalize(it.groupValues[1]) }.filterNot { it in setOf("the", "this", "that", "medicine", "drug", "tablet") }
        if (namedUse.any { name -> !SearchText.containsTerm(all, name) && concepts.none { name in it.terms } }) return false
        val identifiers = Regex("(?<![\\p{L}\\p{Nd}])\\p{L}[\\p{L}\\p{Nd}_-]*\\p{Nd}[\\p{L}\\p{Nd}_-]*")
            .findAll(question).map { it.value }.toList()
        if (identifiers.any { !record.content.contains(it) }) return false
        val requested = fields.filter { it.query.containsMatchIn(question) }
        return requested.isEmpty() || requested.any { it.source.containsMatchIn(body) }
    }

    fun covers(question: String, records: List<KnowledgeRecord>): Boolean {
        if (records.isEmpty()) return false
        return fields.filter { it.query.containsMatchIn(question) }.all { field -> records.any { field.source.containsMatchIn(it.content) } }
    }
}

class SemanticRetriever(private val db: PocketDatabase, private val embeddings: OfflineEmbeddingService) {
    suspend fun retrieve(question: String, language: String, limit: Int = 3, semanticReady: Boolean = true): RagSearchResult = withContext(Dispatchers.IO) {
        require(limit in 1..10)
        val lexical = db.retrieve(question, language, limit).filter { EvidenceCoverage.allows(question, it) }
        var semantic = false
        val total = db.countKnowledge()
        val pending = db.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256)
        var notice: String? = when {
            total > 0 && !semanticReady -> "Prepare offline search in Offline tools to match references across languages. Original passages and keyword search remain available."
            pending > 0 -> "Offline reference indexing is incomplete (${total - pending} of $total passages indexed). Wait for indexing or resume it in Offline tools, then ask again. Original passages and keyword search remain available."
            else -> null
        }
        val excerpts = LinkedHashMap<Long, String>()
        val candidates = mutableListOf<KnowledgeRecord>()
        if (semanticReady && pending < total) {
            try {
                val vector = embeddings.query(question)
                val hits = db.nearestKnowledgeVectors(vector, OnnxEmbeddingKernel.MODEL_SHA256, 20)
                val first = hits.firstOrNull()
                val gap = if (hits.size > 1) first!!.score - hits[1].score else 1.0
                if (first != null && first.score >= .82 && (gap >= .012 || lexical.isNotEmpty())) {
                    hits.filter { it.score >= .82 && it.score >= first.score - .05 }.forEach { hit ->
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
        val records = (lexical + candidates).distinctBy { it.id }.take(limit)
        if (!EvidenceCoverage.covers(question, records)) RagSearchResult(emptyList(), notice = notice)
        else RagSearchResult(records, excerpts.filterKeys { id -> records.any { it.id == id } }, semantic, notice)
    }
}
