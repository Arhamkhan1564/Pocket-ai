package com.pocketai.assistant

import com.pocketai.AppSettings
import com.pocketai.data.KnowledgeRecord
import com.pocketai.data.PocketDatabase
import com.pocketai.data.SearchText
import com.pocketai.engine.InferenceEngine
import com.pocketai.engine.LocalAiEngine
import com.pocketai.models.ModelKind
import com.pocketai.models.ModelManager
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException

data class AssistantAnswer(val text: String, val sources: String, val engine: String) {
    val storedSources: String get() = if (sources.startsWith("@engine=")) sources else "@engine=$engine\n$sources"
}

/** Only verified retrieved records are evidence. Conversation and model parameters are never sources. */
class AssistantService(
    private val db: PocketDatabase,
    private val models: ModelManager,
    private val engine: InferenceEngine,
    private val settings: AppSettings
) {
    suspend fun answer(question: String): AssistantAnswer {
        val language = settings.responseLanguage
        val tag = if (language == "pa") "pa-${settings.punjabiScript}" else language
        val command = question.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        if (command in setOf("repeat", "repeat your previous answer", "repeat that", "دوبارہ بتائیں")) {
            val previous = db.conversations(40).lastOrNull { it.role == "assistant" }
            return AssistantAnswer(previous?.content ?: "There isn’t a previous answer yet.", previous?.sources ?: "", "Command")
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
        if (assessment.intent == MedicalGuidance.Intent.PERSONAL_TREATMENT) {
            if (records.isEmpty() && priorAssistant?.sources?.startsWith("@engine=Clinical clarification") == true) {
                records = Regex("#(\\d+)").findAll(priorAssistant.sources).mapNotNull { match ->
                    match.groupValues[1].toLongOrNull()?.let(db::getKnowledge)?.takeIf { it.verified }
                }.distinctBy { it.id }.take(3).toList()
            }
            val sources = GroundedEvidence.sources(records)
            // Never select a drug or surface a dosing passage as a personalized recommendation.
            val references = if (sources.isNotBlank()) "\n\n$sources" else ""
            return AssistantAnswer(MedicalGuidance.treatmentMessage(tag, records.isNotEmpty()) + references, sources, "Clinical clarification")
        }

        val file = selectedModelFile()
        if (file != null && (records.isEmpty() || question.any { it in '\u0600'..'\u06FF' || it in '\u0A00'..'\u0A7F' })) {
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
        if (records.isEmpty()) return AssistantAnswer(
            if (medical) MedicalGuidance.noSourcesMessage(tag) else LanguageTools.unknown(language, settings.punjabiScript),
            "", if (medical) "No matching medical reference" else "No matching knowledge"
        )
        val sources = GroundedEvidence.sources(records)
        if (medical) return medicineReference(question, records, file, tag, sources)

        if (file != null && records.none { containsDocumentCommands("${it.sourceReference}\n${it.question}\n${it.content}") }) {
            try {
                val contexts = records.mapIndexed { i, record ->
                    "${GroundedEvidence.reference(record, i + 1)}\n${GroundedEvidence.passage(record, question)}"
                }
                val text = LocalAiEngine.stripReasoning(engine.generate(file, question, contexts, tag, emptyList(), settings.contextSize))
                require(text.isNotBlank() && MedicalGuidance.matchesRequestedScript(text, tag)) { "The model did not answer in the requested script." }
                require(!MedicalGuidance.containsPersonalPrescription(text)) { "The model added patient-directed recommendations." }
                require(GroundedEvidence.validCitations(text, records.size)) { "The model returned an invalid source index." }
                require(!GroundedEvidence.hasUnsupportedNumbers(text, records.joinToString("\n") { it.content })) { "The model added a number not present in saved evidence." }
                require(!GroundedEvidence.hasUnsupportedIdentifiers(text, records.joinToString("\n") { it.content })) { "The model changed a source identifier." }
                return AssistantAnswer(text, sources, "Grounded local model · check sources")
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Exact source fallback, never an invented answer. */ }
        }
        return original(records, tag, sources)
    }

    private suspend fun medicineReference(question: String, records: List<KnowledgeRecord>, file: File?, tag: String, sources: String): AssistantAnswer {
        val original = "${MedicalGuidance.originalLabel(tag)}\n\n${GroundedEvidence.exact(records)}"
        if (!needsTranslation(records, tag)) return AssistantAnswer(original, sources, "Medical reference · original text")
        if (file != null && records.none { containsDocumentCommands("${it.sourceReference}\n${it.question}\n${it.content}") }) {
            try {
                val passage = GroundedEvidence.passage(records.first(), question, 360)
                val translated = LocalAiEngine.stripReasoning(engine.translateEvidence(file, passage, tag, settings.contextSize))
                require(GroundedEvidence.safeTranslation(passage, translated, tag, medical = true)) { "The translation changed a quantity, unit, script or added prescribing language." }
                return AssistantAnswer("${MedicalGuidance.translationLabel(tag)}\n[1] $translated\n\n$original", sources, "Medical reference · local translation")
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Show intact evidence and an honest capability message. */ }
        }
        return AssistantAnswer("${MedicalGuidance.translationUnavailable(tag)}\n\n$original", sources, "Translation unavailable · medical original")
    }

    private fun original(records: List<KnowledgeRecord>, tag: String, sources: String): AssistantAnswer {
        val unavailable = needsTranslation(records, tag)
        val prefix = if (unavailable) MedicalGuidance.translationUnavailable(tag) + "\n\n" else ""
        return AssistantAnswer(prefix + GroundedEvidence.exact(records), sources,
            if (unavailable) "Translation unavailable · original text" else "Saved knowledge · original text")
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
}
