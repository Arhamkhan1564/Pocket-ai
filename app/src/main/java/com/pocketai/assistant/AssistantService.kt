package com.pocketai.assistant

import com.pocketai.AppSettings
import com.pocketai.data.KnowledgeRecord
import com.pocketai.data.PocketDatabase
import com.pocketai.data.SearchText
import com.pocketai.engine.InferenceEngine
import com.pocketai.engine.LocalAiEngine
import com.pocketai.models.ModelKind
import com.pocketai.models.ModelManager
import com.pocketai.rag.OfflineRagServices
import com.pocketai.retrieval.RagSearchResult
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException

data class AssistantAnswer(val text: String, val sources: String, val engine: String, val sourceIds: List<Long> = emptyList()) {
    val storedSources: String get() = if (sources.startsWith("@engine=")) sources else "@engine=$engine\n$sources"
}

/** Only verified retrieved records are evidence. Conversation and model parameters are never sources. */
class AssistantService private constructor(
    private val db: PocketDatabase,
    private val models: ModelManager,
    private val engine: InferenceEngine,
    private val settings: AppSettings,
    private val offlineRag: RagBackend?,
) {
    constructor(db: PocketDatabase, models: ModelManager, engine: InferenceEngine, settings: AppSettings, offlineRag: OfflineRagServices? = null) :
        this(db, models, engine, settings, offlineRag?.let(::ServiceRagBackend))

    suspend fun answer(question: String): AssistantAnswer {
        val language = settings.responseLanguage
        val tag = if (language == "pa") "pa-${settings.punjabiScript}" else language
        val command = question.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        if (command in setOf("repeat", "repeat your previous answer", "repeat that", "دوبارہ بتائیں")) {
            val previous = db.conversations(40).lastOrNull { it.role == "assistant" }
            return AssistantAnswer(previous?.content ?: "There isn’t a previous answer yet.", previous?.sources ?: "", "Command", previous?.sourceIds.orEmpty())
        }
        if (command in setOf("speak more slowly", "speak slowly", "slower")) {
            settings.speechRate = (settings.speechRate - .15f).coerceAtLeast(.5f)
            return AssistantAnswer("Speech speed is now ${"%.2f".format(settings.speechRate)}×.", "", "Command")
        }
        LanguageTools.configured(settings.thirdLanguage).firstOrNull {
            command in setOf("change the response language to ${it.name.lowercase()}", "speak ${it.name.lowercase()}", "respond in ${it.name.lowercase()}")
        }?.let {
            settings.responseLanguage = it.tag
            return AssistantAnswer("Response language set to ${it.name}. Saved text keeps its original language; translation requires a capable local model.", "", "Command")
        }

        val recent = db.conversations(12).let { history ->
            if (history.lastOrNull()?.let { it.role == "user" && it.content == question } == true) history.dropLast(1) else history
        }
        val priorAssistant = recent.lastOrNull { it.role == "assistant" }
        var assessment = MedicalGuidance.assess(question)
        if (assessment.intent == MedicalGuidance.Intent.URGENT) return AssistantAnswer(MedicalGuidance.urgentMessage(tag), "", "Urgent safety guidance")
        if (priorAssistant?.sources?.startsWith("@engine=Clinical clarification") == true && MedicalGuidance.isPatientContextReply(question)) {
            assessment = MedicalGuidance.Assessment(MedicalGuidance.Intent.PERSONAL_TREATMENT)
        }
        var records = db.retrieve(question, language, 3).filter { it.verified }
        var excerpts: Map<Long, String> = emptyMap()
        var retrievalNotice: String? = null
        if (offlineRag != null) {
            try {
                val result = offlineRag.retrieve(question, language, 3)
                retrievalNotice = result.notice?.takeIf { it.isNotBlank() }
                records = result.records.filter { it.verified }.distinctBy { it.id }.take(3)
                // Excerpts must remain verbatim spans of their authoritative saved record.
                excerpts = result.excerpts.filter { (id, text) ->
                    text.isNotBlank() && records.any { it.id == id && it.content.contains(text) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                retrievalNotice = "Offline semantic search could not run. Keyword search remains available."
            }
        }
        if (assessment.intent == MedicalGuidance.Intent.PERSONAL_TREATMENT) {
            if (records.isEmpty() && priorAssistant?.sources?.startsWith("@engine=Clinical clarification") == true) {
                records = priorAssistant.sourceIds.asSequence().mapNotNull { id ->
                    db.getKnowledge(id)?.takeIf { it.verified }
                }.distinctBy { it.id }.take(3).toList()
            }
            val sources = GroundedEvidence.sources(records)
            // Never select a drug or surface a dosing passage as a personalized recommendation.
            val references = if (sources.isNotBlank()) "\n\n$sources" else ""
            return AssistantAnswer(MedicalGuidance.treatmentMessage(tag, records.isNotEmpty()) + references, sources, "Clinical clarification", records.map { it.id })
        }

        val file = selectedModelFile() ?: offlineRag?.defaultGeneratorFile()
        if (file != null && offlineRag?.searchReady != true && (records.isEmpty() || question.any { it in '\u0600'..'\u06FF' || it in '\u0A00'..'\u0A7F' })) {
            try {
                val rewritten = LocalAiEngine.stripReasoning(engine.rewriteQueryToEnglish(file, question, settings.contextSize)).trim()
                if (validSearchRewrite(question, rewritten)) {
                    records = (records + db.retrieve(rewritten, "en", 3)).distinctBy { it.id }.filter { it.verified }.take(3)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Curated lexical retrieval remains available. */ }
        }
        val medical = assessment.intent == MedicalGuidance.Intent.MEDICINE_INFORMATION || records.any {
            MedicalGuidance.isMedicalReference("${it.category} ${it.sourceReference} ${it.content}")
        }
        if (records.isEmpty() && retrievalNotice != null) return AssistantAnswer(
            unavailableSearchLabel(tag) + "\n\n" + retrievalNotice, "", "Offline search unavailable",
        )
        if (records.isEmpty()) return AssistantAnswer(
            if (medical) MedicalGuidance.noSourcesMessage(tag) else LanguageTools.unknown(language, settings.punjabiScript),
            "", if (medical) "No matching medical reference" else "No matching knowledge"
        )
        val sources = GroundedEvidence.sources(records)
        if (offlineRag != null) {
            if (!medical && settings.ragAnswerStyle == "synthesis" && file != null &&
                records.none { containsDocumentCommands("${it.sourceReference}\n${it.question}\n${it.content}") }) {
                try {
                    val nonLatinQuestion = question.any { Character.isLetter(it) && it.code > 0x024F }
                    val passages = records.map { selectedPassage(it, question, excerpts) }
                    require(nonLatinQuestion || coversEnglishQuestionAnchors(question, passages.joinToString("\n"))) {
                        "The saved excerpts do not cover the question's requested fields."
                    }
                    val prompt = if (nonLatinQuestion) "Summarize only the relevant facts in these retrieved English source passages. Preserve their exact numbers and identifiers. This is a source summary." else question
                    val contexts = records.mapIndexed { index, record -> "${GroundedEvidence.reference(record, index + 1)}\n${passages[index]}" }
                    val text = LocalAiEngine.stripReasoning(engine.generate(file, prompt, contexts, "en", emptyList(), settings.contextSize))
                    validateGenerated(text, records, "en")
                    val label = if (nonLatinQuestion) "Summary of retrieved references" else "Explanation based on retrieved references"
                    return translatedExplanation(text, label, records, tag, sources)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Cited passages remain the dependable baseline. */ }
            }
            return offlineReferences(question, records, excerpts, tag, sources, medical)
        }
        if (medical) return medicineReference(question, records, file, tag, sources)

        if (file != null && records.none { containsDocumentCommands("${it.sourceReference}\n${it.question}\n${it.content}") }) {
            try {
                val contexts = records.mapIndexed { i, record ->
                    "${GroundedEvidence.reference(record, i + 1)}\n${GroundedEvidence.passage(record, question)}"
                }
                val text = LocalAiEngine.stripReasoning(engine.generate(file, question, contexts, tag, emptyList(), settings.contextSize))
                validateGenerated(text, records, tag)
                return AssistantAnswer(text, sources, "Grounded local model · check sources", records.map { it.id })
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Exact source fallback, never an invented answer. */ }
        }
        return original(records, tag, sources)
    }

    private fun selectedPassage(record: KnowledgeRecord, question: String, excerpts: Map<Long, String>): String =
        excerpts[record.id] ?: GroundedEvidence.passage(record, question)

    /** The small English generator must not guess a missing field from an unrelated retrieved paragraph. */
    private fun coversEnglishQuestionAnchors(question: String, evidence: String): Boolean {
        val generic = setOf("what", "which", "when", "where", "how", "why", "please", "tell", "explain", "describe", "give", "show", "does", "said", "says", "say",
            "document", "documents", "reference", "references", "saved", "text", "passage", "passages", "information", "according", "about")
        val anchors = SearchText.keywordQuery(question).literalTerms.filter { it !in generic && it.matches(Regex("[a-z][a-z0-9_-]{2,}")) }
        if (anchors.isEmpty()) return false
        val normalized = SearchText.normalize(evidence)
        return anchors.all { SearchText.containsTerm(normalized, it) }
    }

    private suspend fun offlineReferences(question: String, records: List<KnowledgeRecord>, excerpts: Map<Long, String>, tag: String, sources: String, medical: Boolean): AssistantAnswer {
        val passages = records.map { selectedPassage(it, question, excerpts) }
        val cited = records.mapIndexed { index, record -> "${GroundedEvidence.reference(record, index + 1)}\n${passages[index]}" }.joinToString("\n\n")
        val ids = records.map { it.id }
        if (!needsTranslation(records, tag)) return AssistantAnswer(cited, sources,
            if (medical) "Medical reference · cited excerpts" else "Saved references · cited excerpts", ids)
        val original = "${if (medical) MedicalGuidance.originalLabel(tag) else originalReferenceLabel(tag)}\n\n${GroundedEvidence.exact(records)}"
        try {
            val translated = passages.mapIndexed { index, source ->
                val result = offlineRag!!.translateText(source, settings.responseLanguage, settings.punjabiScript)
                require(result.sourceText == source && GroundedEvidence.safeTranslation(source, result.text, tag, medical)) {
                    "The translation changed source quantities, identifiers, warning language or script."
                }
                "[${index + 1}] ${result.text}"
            }.joinToString("\n\n")
            return AssistantAnswer("${excerptTranslationLabel(tag)}\n\n$translated\n\n$original", sources,
                if (medical) "Medical reference · offline excerpt translation" else "Saved references · offline excerpt translation", ids)
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            return AssistantAnswer("${MedicalGuidance.translationUnavailable(tag)}\n\n$original", sources,
                "Translation unavailable · original text", ids)
        }
    }

    private suspend fun translatedExplanation(text: String, label: String, records: List<KnowledgeRecord>, tag: String, sources: String): AssistantAnswer {
        val ids = records.map { it.id }
        if (tag == "en") return AssistantAnswer("$label\n\n$text", sources, "Grounded English explanation · check sources", ids)
        val result = offlineRag!!.translateText(text, settings.responseLanguage, settings.punjabiScript)
        val citations = Regex("\\[\\d+\\]")
        require(result.sourceText == text && citations.findAll(text).map { it.value }.toList() == citations.findAll(result.text).map { it.value }.toList()) {
            "The translation changed a source citation."
        }
        require(GroundedEvidence.safeTranslation(citations.replace(text, ""), citations.replace(result.text, ""), tag, medical = false)) {
            "The translated explanation changed source numbers, identifiers or script."
        }
        return AssistantAnswer("${explanationTranslationLabel(tag)}\n\n${result.text}\n\nOriginal English explanation\n$text", sources,
            "Grounded explanation · offline translation · check sources", ids)
    }

    private fun validateGenerated(text: String, records: List<KnowledgeRecord>, tag: String) {
        require(text.isNotBlank() && MedicalGuidance.matchesRequestedScript(text, tag)) { "The model did not answer in the requested script." }
        require(!MedicalGuidance.containsPersonalPrescription(text)) { "The model added patient-directed recommendations." }
        require(GroundedEvidence.validCitations(text, records.size)) { "The model returned an invalid source index." }
        val evidence = records.joinToString("\n") { it.content }
        require(!GroundedEvidence.hasUnsupportedNumbers(text, evidence)) { "The model added a number not present in saved evidence." }
        require(!GroundedEvidence.hasUnsupportedIdentifiers(text, evidence)) { "The model changed a source identifier." }
    }

    private fun excerptTranslationLabel(tag: String): String = when {
        tag.startsWith("ur") -> "منتخب حوالہ جاتی اقتباسات کا آف لائن ترجمہ؛ مکمل اصل عبارت نیچے ہے۔"
        tag.startsWith("pa") && tag.contains("Guru", true) -> "ਚੁਣੇ ਹਵਾਲਾ ਅੰਸ਼ਾਂ ਦਾ ਆਫਲਾਈਨ ਅਨੁਵਾਦ; ਪੂਰਾ ਮੂਲ ਪਾਠ ਹੇਠਾਂ ਹੈ।"
        tag.startsWith("pa") -> "چُنے حوالہ جاتی حصیاں دا آف لائن ترجمہ؛ پورا اصل متن تھلّے اے۔ شاہ مکھی رسم الخط خودکار بدلیا گیا اے۔"
        else -> "Offline translation of selected reference excerpts; complete original passages follow."
    }

    private fun originalReferenceLabel(tag: String): String = when {
        tag.startsWith("ur") -> "محفوظ حوالے — اصل متن:"
        tag.startsWith("pa") && tag.contains("Guru", true) -> "ਸੰਭਾਲੇ ਹਵਾਲੇ — ਮੂਲ ਪਾਠ:"
        tag.startsWith("pa") -> "محفوظ حوالے — اصل متن:"
        else -> "Saved references — original text:"
    }

    private fun unavailableSearchLabel(tag: String): String = when {
        tag.startsWith("ur") -> "آف لائن معنوی تلاش مکمل نہیں ہو سکی؛ اس لیے یہ طے نہیں ہوا کہ محفوظ حوالوں میں جواب موجود ہے یا نہیں۔ دوبارہ کوشش کریں یا اصل دستاویز کا لفظ تلاش کریں۔"
        tag.startsWith("pa") && tag.contains("Guru", true) -> "ਆਫਲਾਈਨ ਅਰਥ ਅਧਾਰਿਤ ਖੋਜ ਪੂਰੀ ਨਹੀਂ ਹੋ ਸਕੀ; ਇਸ ਲਈ ਇਹ ਪਤਾ ਨਹੀਂ ਲੱਗਿਆ ਕਿ ਸੰਭਾਲੇ ਹਵਾਲਿਆਂ ਵਿੱਚ ਜਵਾਬ ਹੈ ਜਾਂ ਨਹੀਂ। ਮੁੜ ਕੋਸ਼ਿਸ਼ ਕਰੋ ਜਾਂ ਮੂਲ ਦਸਤਾਵੇਜ਼ ਦਾ ਕੋਈ ਸ਼ਬਦ ਲੱਭੋ।"
        tag.startsWith("pa") -> "آف لائن معنوی تلاش پوری نہیں ہو سکی؛ ایس لئی ایہہ پتہ نہیں لگیا کہ محفوظ حوالیاں وچ جواب موجود اے یا نہیں۔ فیر کوشش کرو یا اصل دستاویز دا کوئی لفظ لبھو۔"
        else -> "Offline semantic search could not finish, so I could not determine whether the saved references contain the answer. Try again or search using a word from the original document."
    }

    private fun explanationTranslationLabel(tag: String): String = when {
        tag.startsWith("ur") -> "انگریزی وضاحت کا آف لائن ترجمہ؛ اصل وضاحت نیچے ہے۔ حوالوں سے جانچیں۔"
        tag.startsWith("pa") && tag.contains("Guru", true) -> "ਅੰਗਰੇਜ਼ੀ ਵਿਆਖਿਆ ਦਾ ਆਫਲਾਈਨ ਅਨੁਵਾਦ; ਮੂਲ ਵਿਆਖਿਆ ਹੇਠਾਂ ਹੈ। ਹਵਾਲਿਆਂ ਨਾਲ ਜਾਂਚੋ।"
        tag.startsWith("pa") -> "انگریزی وضاحت دا آف لائن ترجمہ؛ اصل وضاحت تھلّے اے۔ حوالیاں نال جانچو۔ شاہ مکھی رسم الخط خودکار بدلیا گیا اے۔"
        else -> "Offline translation of the English explanation; original explanation follows. Check its sources."
    }

    private suspend fun medicineReference(question: String, records: List<KnowledgeRecord>, file: File?, tag: String, sources: String): AssistantAnswer {
        val original = "${MedicalGuidance.originalLabel(tag)}\n\n${GroundedEvidence.exact(records)}"
        if (!needsTranslation(records, tag)) return AssistantAnswer(original, sources, "Medical reference · original text", records.map { it.id })
        if (file != null && records.none { containsDocumentCommands("${it.sourceReference}\n${it.question}\n${it.content}") }) {
            try {
                val passage = GroundedEvidence.passage(records.first(), question, 360)
                val translated = LocalAiEngine.stripReasoning(engine.translateEvidence(file, passage, tag, settings.contextSize))
                require(GroundedEvidence.safeTranslation(passage, translated, tag, medical = true)) { "The translation changed a quantity, unit, script or added prescribing language." }
                return AssistantAnswer("${MedicalGuidance.translationLabel(tag)}\n[1] $translated\n\n$original", sources, "Medical reference · local translation", records.map { it.id })
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Show intact evidence and an honest capability message. */ }
        }
        return AssistantAnswer("${MedicalGuidance.translationUnavailable(tag)}\n\n$original", sources, "Translation unavailable · medical original", records.map { it.id })
    }

    private fun original(records: List<KnowledgeRecord>, tag: String, sources: String): AssistantAnswer {
        val unavailable = needsTranslation(records, tag)
        val prefix = if (unavailable) MedicalGuidance.translationUnavailable(tag) + "\n\n" else ""
        return AssistantAnswer(prefix + GroundedEvidence.exact(records), sources,
            if (unavailable) "Translation unavailable · original text" else "Saved knowledge · original text", records.map { it.id })
    }

    private fun selectedModelFile(): File? = runCatching {
        models.list().firstOrNull { it.id == settings.selectedLlmId && it.kind == ModelKind.LLM }?.let(models::fileFor)
    }.getOrNull()

    private fun needsTranslation(records: List<KnowledgeRecord>, tag: String): Boolean = records.any {
        it.language.substringBefore('-') != tag.substringBefore('-') ||
            (tag.startsWith("pa") && it.script != if (tag.contains("Guru", true)) "Guru" else "Arab")
    }

    private fun validSearchRewrite(original: String, text: String): Boolean {
        if (text.length !in 2..320 || !Regex("[A-Za-z]{2,}").containsMatchIn(text) || containsDocumentCommands(text) ||
            Regex("\\b(?:you should|take \\d|i recommend|i cannot|no sources)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
        if (!GroundedEvidence.preservesIdentifiers(original, text)) return false
        val normalized = SearchText.normalize(text)
        val plan = SearchText.keywordQuery(original)
        if (!plan.concepts.filterNot { it.generic }.all { concept -> concept.terms.any { SearchText.containsTerm(normalized, it) } }) return false
        val generic = setOf("used", "uses", "use", "usage", "using", "work", "works", "does", "mean", "means", "meaning", "say", "says",
            "document", "documents", "book", "books", "leaflet", "leaflets", "reference", "references", "saved", "show", "give",
            "information", "info", "question", "please", "write", "read", "dose", "dosage", "drug", "drugs", "medicine", "medicines",
            "medication", "medications", "side", "effects", "effect", "treat", "treatment", "help", "helps", "symptom", "symptoms", "find", "lookup")
        val anchors = plan.literalTerms.filter { it !in generic && it.matches(Regex("[a-z][a-z0-9_-]{2,}")) }
        return anchors.all { SearchText.containsTerm(normalized, it) }
    }

    private fun containsDocumentCommands(text: String): Boolean = Regex(
        "ignore\\s+(?:all\\s+)?(?:previous|earlier|system|safety)\\s+(?:instructions?|rules?|prompts?)|" +
            "disregard\\s+(?:the\\s+)?(?:instructions?|rules?)|you are now|<\\|im_start\\|>|reveal (?:the )?(?:system prompt|secret)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)

    internal data class TranslatedEvidence(val text: String, val sourceText: String)
    internal interface RagBackend {
        val searchReady: Boolean
        suspend fun retrieve(question: String, language: String, limit: Int): RagSearchResult
        suspend fun translateText(source: String, targetLanguage: String, script: String): TranslatedEvidence
        fun defaultGeneratorFile(): File?
    }
    private class ServiceRagBackend(private val service: OfflineRagServices) : RagBackend {
        override val searchReady: Boolean get() = service.state.value.searchReady
        override suspend fun retrieve(question: String, language: String, limit: Int) = service.retrieve(question, language, limit)
        override suspend fun translateText(source: String, targetLanguage: String, script: String): TranslatedEvidence =
            service.translateText(source, targetLanguage, script).let { TranslatedEvidence(it.text, it.sourceText) }
        override fun defaultGeneratorFile() = service.defaultGeneratorFile()
    }
    companion object {
        internal fun withBackend(db: PocketDatabase, models: ModelManager, engine: InferenceEngine, settings: AppSettings, backend: RagBackend): AssistantService =
            AssistantService(db, models, engine, settings, backend)
    }
}
